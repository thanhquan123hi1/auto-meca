package com.mecanum.autocar.control

import com.mecanum.autocar.ai.Detection
import com.mecanum.autocar.ai.GoalMarker
import kotlin.math.abs
import kotlin.math.max

class DecisionEngine(
    private val obstacleMinAreaRatio: Float = 0.022f,
    private val obstacleStrongAreaRatio: Float = 0.095f,
    private val obstacleCenterLeftBound: Float = 0.35f,
    private val obstacleCenterRightBound: Float = 0.65f,
    private val obstacleAvoidDistanceCm: Float = 30f,
    private val obstacleEmergencyDistanceCm: Float = 12f,
    private val markerOcclusionGraceMs: Long = 3200L,
    private val avoidEmergencyReverseMs: Long = 300L,
    private val avoidTurnMs: Long = 340L,
    private val avoidForwardMs: Long = 430L,
    private val avoidForwardRetryMs: Long = 220L,
    private val avoidReacquireMs: Long = 1350L,
    private val avoidMaxTurnRetries: Int = 2,
    private val turnDeadzone: Float = 0.22f,
    private val goalReachedAreaRatio: Float = 0.22f,
    private val goalStopDistanceCm: Float = 30f,
    private val goalSlowDistanceCm: Float = 45f,
    private val goalSlowForwardPulseMs: Long = 190L,
    private val goalSlowPauseMs: Long = 150L,
    private val goalLostGraceMs: Long = 950L,
    private val markerMemorySearchMs: Long = 5200L,
    private val goalReachedHoldMs: Long = 1500L,
    private val rotatePulseMs: Long = 160L,
    private val rotatePauseMs: Long = 140L,
    private val rotatePulseScaleMax: Float = 1.30f,
    private val searchPulseMs: Long = 190L,
    private val searchPauseMs: Long = 150L,
    private val searchSweepLimit: Int = 5,
    private val defaultSearchCommand: Char = 'Q',
    private val fieldSmoothingAlpha: Float = 0.36f,
    private val forwardBaseThreshold: Float = 0.78f,
    private val centerBlockedThreshold: Float = 1.08f,
    private val sideBlockedThreshold: Float = 1.15f,
    private val directionSwitchPenalty: Float = 0.30f,
    private val switchPenaltyHoldMs: Long = 900L,
    private val directionFlipMargin: Float = 0.32f,
) {
    private enum class AvoidState {
        NONE,
        EMERGENCY_REVERSE,
        TURN_AWAY,
        CLEAR_FORWARD,
        REACQUIRE_MARKER,
    }

    private data class ObstacleInfo(
        val ratio: Float,
        val region: Float,
        val leftRisk: Float,
        val centerRisk: Float,
        val rightRisk: Float,
        val sectorRisks: FloatArray,
        val dangerScore: Float,
        val centerBlocked: Boolean,
        val preferredEscape: Char,
        val strongestLabel: String?,
    )

    private data class CandidateAction(
        val command: Char,
        val score: Float,
    )

    private var lastMarkerSeenAt = 0L
    private var lastMarkerOffset = 0f
    private var lastVisibleCommand = 'S'
    private var rotatePhaseCommand = 'S'
    private var rotatePhaseStartedAt = 0L
    private var goalReached = false
    private var goalReachedAt = 0L

    private var avoidState = AvoidState.NONE
    private var avoidStateStartedAt = 0L
    private var avoidTurnCommand = 'Q'
    private var avoidForwardCommand = 'F'
    private var avoidReacquireCommand = 'E'
    private var avoidTurnRetries = 0

    private var searchDirection = defaultSearchCommand.uppercaseChar().takeIf { it == 'Q' || it == 'E' } ?: 'Q'
    private var searchPeriodCount = 0
    private var lastSearchPeriodIndex = -1L
    private var smoothedSectorRisks = FloatArray(SECTOR_COUNT)
    private var lastAvoidDirection: Char? = null
    private var lastDirectionSwitchAt = 0L

    @Volatile var lastReason: String = "?ang ch?"
        private set

    fun decide(
        frameWidth: Int,
        frameHeight: Int,
        detections: List<Detection>,
        marker: GoalMarker?,
        autonomous: Boolean,
        frontDistanceCm: Float?,
    ): Char {
        if (!autonomous || frameWidth <= 0 || frameHeight <= 0) {
            resetTransientState()
            lastReason = "T?t Auto"
            return 'S'
        }

        val now = System.currentTimeMillis()
        val obstacleInfo = evaluateObstacleField(frameWidth, frameHeight, detections, frontDistanceCm)

        marker?.let { rememberMarker(it, frameWidth, frameHeight, now) }

        if (goalReached) {
            lastReason = if (now - goalReachedAt <= goalReachedHoldMs) {
                "?? t?i marker"
            } else {
                "?? t?i marker, ?ang gi? v? tr?"
            }
            return 'S'
        }

        continueAvoidanceIfNeeded(marker, now, frontDistanceCm, obstacleInfo)?.let { return it }
        obstacleInfo?.let { decideObstacle(it, marker, now, frontDistanceCm) }?.let { return it }
        decideMarker(frameWidth, frameHeight, marker, now)?.let { return it }

        val markerAgeMs = now - lastMarkerSeenAt
        if (markerAgeMs <= goalLostGraceMs) {
            return recallLastMarker(now, shortDropout = true)
        }
        if (markerAgeMs <= markerMemorySearchMs) {
            val command = searchForMarker(now, allowWideSweep = false)
            lastReason = if (command == 'S') {
                "M?t marker, gi? h??ng c? v? ch? khung ti?p"
            } else {
                "M?t marker, qu?t theo h??ng marker c?"
            }
            return command
        }

        val command = searchForMarker(now, allowWideSweep = true)
        lastReason = "?ang qu?t t?m marker"
        return command
    }

    private fun evaluateObstacleField(
        frameWidth: Int,
        frameHeight: Int,
        detections: List<Detection>,
        frontDistanceCm: Float?,
    ): ObstacleInfo? {
        val frameArea = (frameWidth * frameHeight).coerceAtLeast(1).toFloat()
        val rawRisks = FloatArray(SECTOR_COUNT)
        var bestRatio = 0f
        var bestRegion = 0.5f
        var strongestLabel: String? = null

        detections.forEach { detection ->
            val weight = obstacleWeight(detection, frameWidth, frameHeight, frameArea)
            if (weight <= 0f) return@forEach
            val ratio = detection.area / frameArea
            if (ratio > bestRatio) {
                bestRatio = ratio
                bestRegion = (detection.centerX / frameWidth.toFloat()).coerceIn(0f, 1f)
                strongestLabel = detection.label
            }

            for (sector in 0 until SECTOR_COUNT) {
                val overlap = overlapFraction(detection, frameWidth, sector)
                if (overlap > 0f) rawRisks[sector] += weight * overlap
            }
        }

        fuseFrontDistance(rawRisks, frontDistanceCm)
        val hasRisk = rawRisks.any { it >= 0.05f } || (frontDistanceCm != null && frontDistanceCm <= obstacleAvoidDistanceCm)
        if (!hasRisk) {
            smoothRisks(FloatArray(SECTOR_COUNT))
            return null
        }

        val smoothed = smoothRisks(rawRisks)
        val leftRisk = smoothed[0] * 1.15f + smoothed[1]
        val centerRisk = smoothed[2]
        val rightRisk = smoothed[4] * 1.15f + smoothed[3]
        val centerBlocked = centerRisk >= centerBlockedThreshold ||
            (frontDistanceCm != null && frontDistanceCm <= obstacleAvoidDistanceCm)
        val dangerScore = max(max(leftRisk, centerRisk), rightRisk)
        val preferredEscape = choosePreferredEscape(leftRisk, rightRisk)

        return ObstacleInfo(
            ratio = bestRatio,
            region = bestRegion,
            leftRisk = leftRisk,
            centerRisk = centerRisk,
            rightRisk = rightRisk,
            sectorRisks = smoothed.copyOf(),
            dangerScore = dangerScore,
            centerBlocked = centerBlocked,
            preferredEscape = preferredEscape,
            strongestLabel = strongestLabel,
        )
    }

    private fun obstacleWeight(
        detection: Detection,
        frameWidth: Int,
        frameHeight: Int,
        frameArea: Float,
    ): Float {
        val ratio = detection.area / frameArea
        if (ratio < obstacleMinAreaRatio * 0.4f) return 0f

        val confidence = detection.confidence.coerceIn(0f, 1f)
        val centerXNorm = (detection.centerX / frameWidth.toFloat()).coerceIn(0f, 1f)
        val centerYNorm = (detection.box.centerY() / frameHeight.toFloat()).coerceIn(0f, 1f)
        val bottomNorm = (detection.box.bottom / frameHeight.toFloat()).coerceIn(0f, 1f)
        val laneBias = (1f - (abs(centerXNorm - 0.5f) / 0.5f)).coerceIn(0f, 1f)
        val bottomWeight = ((bottomNorm - 0.28f) / 0.72f).coerceIn(0f, 1.35f)
        val roiWeight = when {
            centerYNorm > 0.58f -> 1f
            centerYNorm > 0.45f -> 0.7f
            else -> 0.32f
        }
        val classWeight = softClassWeight(detection.label)
        val sizeWeight = (ratio / obstacleStrongAreaRatio.coerceAtLeast(0.001f)).coerceIn(0.2f, 1.45f)
        val weight = confidence * (0.45f + 0.55f * laneBias) * (0.35f + 0.65f * bottomWeight) * roiWeight * classWeight * sizeWeight
        return if (weight >= 0.08f) weight else 0f
    }

    private fun softClassWeight(label: String): Float {
        val normalized = label.trim().lowercase()
        return when {
            normalized.contains("person") || normalized.contains("car") || normalized.contains("truck") ||
                normalized.contains("bus") || normalized.contains("bicycle") || normalized.contains("motor") -> 1.15f
            normalized.startsWith("class_") -> 1.0f
            normalized.contains("chair") || normalized.contains("bottle") || normalized.contains("cup") -> 0.82f
            else -> 0.92f
        }
    }

    private fun overlapFraction(detection: Detection, frameWidth: Int, sector: Int): Float {
        val sectorWidth = frameWidth.toFloat() / SECTOR_COUNT
        val sectorLeft = sector * sectorWidth
        val sectorRight = sectorLeft + sectorWidth
        val overlap = (minOf(detection.box.right, sectorRight) - maxOf(detection.box.left, sectorLeft)).coerceAtLeast(0f)
        val width = detection.box.width().coerceAtLeast(1f)
        return (overlap / width).coerceIn(0f, 1f)
    }

    private fun fuseFrontDistance(rawRisks: FloatArray, frontDistanceCm: Float?) {
        val distance = frontDistanceCm ?: return
        when {
            distance <= obstacleEmergencyDistanceCm -> {
                rawRisks[2] += 2.0f
                rawRisks[1] += 0.65f
                rawRisks[3] += 0.65f
            }
            distance <= obstacleAvoidDistanceCm -> {
                val severity = ((obstacleAvoidDistanceCm - distance) /
                    (obstacleAvoidDistanceCm - obstacleEmergencyDistanceCm).coerceAtLeast(1f)).coerceIn(0f, 1f)
                rawRisks[2] += 0.9f + severity * 0.85f
                rawRisks[1] += 0.18f + severity * 0.28f
                rawRisks[3] += 0.18f + severity * 0.28f
            }
        }
    }

    private fun smoothRisks(raw: FloatArray): FloatArray {
        val alpha = fieldSmoothingAlpha.coerceIn(0.05f, 1f)
        for (i in raw.indices) {
            smoothedSectorRisks[i] = smoothedSectorRisks[i] * (1f - alpha) + raw[i] * alpha
        }
        return smoothedSectorRisks
    }

    private fun choosePreferredEscape(leftRisk: Float, rightRisk: Float): Char {
        val preferred = if (leftRisk + directionFlipMargin < rightRisk) 'Q' else if (rightRisk + directionFlipMargin < leftRisk) 'E' else null
        if (preferred == null) return lastAvoidDirection ?: if (leftRisk <= rightRisk) 'Q' else 'E'
        val previous = lastAvoidDirection
        if (previous != null && previous != preferred) {
            val recentSwitch = System.currentTimeMillis() - lastDirectionSwitchAt < switchPenaltyHoldMs
            if (recentSwitch) return previous
        }
        return preferred
    }

    private fun continueAvoidanceIfNeeded(
        marker: GoalMarker?,
        now: Long,
        frontDistanceCm: Float?,
        obstacleInfo: ObstacleInfo?,
    ): Char? {
        if (avoidState == AvoidState.NONE) return null

        val elapsed = now - avoidStateStartedAt
        when (avoidState) {
            AvoidState.EMERGENCY_REVERSE -> {
                if (elapsed < avoidEmergencyReverseMs) {
                    lastReason = "B??c 1/4: l?i kh?n c?p ?? t?o kho?ng tr?ng"
                    return 'B'
                }
                startAvoidState(AvoidState.TURN_AWAY, now)
                lastReason = reasonForTurnCommand(avoidTurnCommand, markerOccluded = marker == null)
                return avoidTurnCommand
            }

            AvoidState.TURN_AWAY -> {
                if (elapsed < avoidTurnMs) {
                    lastReason = reasonForTurnCommand(avoidTurnCommand, markerOccluded = marker == null)
                    return avoidTurnCommand
                }
                startAvoidState(AvoidState.CLEAR_FORWARD, now)
            }

            AvoidState.CLEAR_FORWARD -> {
                val distance = frontDistanceCm
                if (distance != null && distance <= obstacleEmergencyDistanceCm) {
                    startAvoidState(AvoidState.EMERGENCY_REVERSE, now)
                    lastReason = "V?n qu? g?n, l?i th?m ?? tr?nh k?t ??u xe"
                    return 'B'
                }

                val stillBlocked = when {
                    distance != null -> distance <= obstacleAvoidDistanceCm
                    obstacleInfo != null -> obstacleInfo.centerBlocked || obstacleInfo.dangerScore >= sideBlockedThreshold
                    else -> false
                }

                if (stillBlocked && avoidTurnRetries < avoidMaxTurnRetries.coerceAtLeast(0)) {
                    avoidTurnRetries += 1
                    startAvoidState(AvoidState.TURN_AWAY, now)
                    lastReason = "Ch?a ?? tho?ng, xoay th?m m?t nh?p ng?n"
                    return avoidTurnCommand
                }

                if (stillBlocked) {
                    startAvoidState(AvoidState.REACQUIRE_MARKER, now)
                    lastReason = "V?t c?n c?n g?n, quay b?t l?i marker"
                    return avoidReacquireCommand
                }

                val forwardBudget = avoidForwardMs + avoidTurnRetries * avoidForwardRetryMs
                if (elapsed < forwardBudget) {
                    lastReason = "B??c 3/4: ti?n ch?o ng?n ?? v??t v?ng b? che"
                    return avoidForwardCommand
                }
                startAvoidState(AvoidState.REACQUIRE_MARKER, now)
            }

            AvoidState.REACQUIRE_MARKER -> {
                if (marker != null) {
                    avoidState = AvoidState.NONE
                    searchPeriodCount = 0
                    lastReason = "?? b?t l?i marker"
                    return null
                }
                if (elapsed < avoidReacquireMs) {
                    lastReason = "B??c 4/4: xoay ng?n ?? b?t l?i marker"
                    return avoidReacquireCommand
                }
                avoidState = AvoidState.NONE
                rotatePhaseCommand = 'S'
                flipSearchDirection()
                lastReason = "Ch?a th?y marker, chuy?n sang qu?t r?ng"
                return null
            }

            AvoidState.NONE -> return null
        }

        return continueAvoidanceIfNeeded(marker, now, frontDistanceCm, obstacleInfo)
    }

    private fun decideObstacle(
        obstacleInfo: ObstacleInfo,
        marker: GoalMarker?,
        now: Long,
        frontDistanceCm: Float?,
    ): Char? {
        val markerRecentlySeen = now - lastMarkerSeenAt <= markerOcclusionGraceMs
        val shouldAvoid = when {
            frontDistanceCm != null -> frontDistanceCm <= obstacleAvoidDistanceCm
            obstacleInfo.centerBlocked -> true
            obstacleInfo.dangerScore >= forwardBaseThreshold -> true
            marker == null && markerRecentlySeen && obstacleInfo.dangerScore >= 0.55f -> true
            else -> false
        }

        if (!shouldAvoid) {
            lastReason = if (frontDistanceCm == null) {
                "C? v?t th? nh?ng ch?a ch?n h?nh lang di chuy?n"
            } else {
                "V?t ? tr??c nh?ng c?n xa"
            }
            return null
        }

        prepareAvoidancePlan(obstacleInfo, marker, now)
        rotatePhaseCommand = 'S'

        if (frontDistanceCm != null && frontDistanceCm <= obstacleEmergencyDistanceCm) {
            startAvoidState(AvoidState.EMERGENCY_REVERSE, now)
            lastReason = if (marker == null && markerRecentlySeen) {
                "B??c 1/4: marker b? che, l?i kh?n c?p tr??c"
            } else {
                "B??c 1/4: ??u xe qu? g?n v?t c?n, ?u ti?n l?i"
            }
            return 'B'
        }

        startAvoidState(AvoidState.TURN_AWAY, now)
        lastReason = reasonForTurnCommand(avoidTurnCommand, markerOccluded = marker == null)
        return avoidTurnCommand
    }

    private fun prepareAvoidancePlan(obstacleInfo: ObstacleInfo, marker: GoalMarker?, now: Long) {
        avoidTurnRetries = 0
        val bestTurn = chooseBestAvoidanceTurn(obstacleInfo, marker, now)
        if (lastAvoidDirection != null && lastAvoidDirection != bestTurn) {
            lastDirectionSwitchAt = now
        }
        lastAvoidDirection = bestTurn
        avoidTurnCommand = bestTurn
        avoidForwardCommand = when (avoidTurnCommand) {
            'Q' -> 'G'
            'E' -> 'H'
            else -> 'F'
        }
        avoidReacquireCommand = oppositeTurn(avoidTurnCommand)
    }

    private fun chooseBestAvoidanceTurn(obstacleInfo: ObstacleInfo, marker: GoalMarker?, now: Long): Char {
        val goalBias = marker?.let {
            ((it.centerX - it.box.centerX()) + 0f)
        }
        val markerBias = marker?.let {
            val frameCenterOffset = lastMarkerOffset.coerceIn(-1f, 1f)
            frameCenterOffset
        } ?: lastMarkerOffset.coerceIn(-1f, 1f)

        val leftScore = scoreTurnCandidate('Q', obstacleInfo, markerBias, now)
        val rightScore = scoreTurnCandidate('E', obstacleInfo, markerBias, now)
        return if (rightScore > leftScore) 'E' else 'Q'
    }

    private fun scoreTurnCandidate(
        command: Char,
        obstacleInfo: ObstacleInfo,
        markerBias: Float,
        now: Long,
    ): Float {
        val sideRisk = if (command == 'Q') obstacleInfo.leftRisk else obstacleInfo.rightRisk
        val oppositeRisk = if (command == 'Q') obstacleInfo.rightRisk else obstacleInfo.leftRisk
        val goalBonus = when {
            command == 'Q' && markerBias < -0.08f -> 0.26f
            command == 'E' && markerBias > 0.08f -> 0.26f
            abs(markerBias) <= 0.08f -> 0.08f
            else -> 0f
        }
        val preferredBonus = if (command == obstacleInfo.preferredEscape) 0.14f else 0f
        val switchPenalty = if (lastAvoidDirection != null && lastAvoidDirection != command && now - lastDirectionSwitchAt < switchPenaltyHoldMs) {
            directionSwitchPenalty
        } else {
            0f
        }
        return oppositeRisk * 0.18f - sideRisk * 0.92f - obstacleInfo.centerRisk * 0.38f + goalBonus + preferredBonus - switchPenalty
    }

    private fun reasonForTurnCommand(command: Char, markerOccluded: Boolean): String {
        val prefix = if (markerOccluded) {
            "B??c 2/4: marker ?ang b? che"
        } else {
            "B??c 2/4: tr?nh v?t c?n ph?a tr??c"
        }
        return when (command) {
            'Q' -> "$prefix, xoay tr?i m?t nh?p"
            'E' -> "$prefix, xoay ph?i m?t nh?p"
            else -> "$prefix, ?ang ??i h??ng n?"
        }
    }

    private fun oppositeTurn(command: Char): Char = when (command) {
        'Q' -> 'E'
        'E' -> 'Q'
        else -> 'Q'
    }

    private fun flipSearchDirection() {
        searchDirection = oppositeTurn(searchDirection)
    }

    private fun startAvoidState(state: AvoidState, now: Long) {
        avoidState = state
        avoidStateStartedAt = now
    }

    private fun rememberMarker(marker: GoalMarker, frameWidth: Int, frameHeight: Int, now: Long) {
        lastMarkerSeenAt = now
        val safeWidth = frameWidth.coerceAtLeast(1)
        lastMarkerOffset = ((marker.centerX - safeWidth / 2f) / (safeWidth / 2f)).coerceIn(-1f, 1f)
        searchDirection = when {
            lastMarkerOffset < -0.10f -> 'Q'
            lastMarkerOffset > 0.10f -> 'E'
            lastVisibleCommand == 'Q' || lastVisibleCommand == 'E' -> lastVisibleCommand
            else -> searchDirection
        }
        searchPeriodCount = 0
        lastSearchPeriodIndex = -1L

        if (frameHeight > 0) {
            val frameArea = (frameWidth * frameHeight).coerceAtLeast(1).toFloat()
            val markerRatio = marker.area / frameArea
            if (isGoalReached(marker, markerRatio)) {
                goalReached = true
                goalReachedAt = now
                rotatePhaseCommand = 'S'
                lastVisibleCommand = 'S'
            }
        }
    }

    private fun decideMarker(
        frameWidth: Int,
        frameHeight: Int,
        marker: GoalMarker?,
        now: Long,
    ): Char? {
        marker ?: return null
        avoidState = AvoidState.NONE

        val frameArea = (frameWidth * frameHeight).coerceAtLeast(1).toFloat()
        val markerRatio = marker.area / frameArea
        if (isGoalReached(marker, markerRatio)) {
            goalReached = true
            goalReachedAt = now
            rotatePhaseCommand = 'S'
            lastVisibleCommand = 'S'
            lastReason = "?? t?i marker"
            return 'S'
        }

        val offset = (marker.centerX - frameWidth / 2f) / (frameWidth / 2f)
        lastMarkerOffset = offset
        if (offset < -turnDeadzone) {
            lastVisibleCommand = 'Q'
            val command = rotateToward('Q', offset, now)
            lastReason = if (command == 'Q') {
                "Canh tr?i ng?n ?? b?m marker"
            } else {
                "H?m xoay tr?i ?? tr?nh qu? tr?n"
            }
            return command
        }
        if (offset > turnDeadzone) {
            lastVisibleCommand = 'E'
            val command = rotateToward('E', offset, now)
            lastReason = if (command == 'E') {
                "Canh ph?i ng?n ?? b?m marker"
            } else {
                "H?m xoay ph?i ?? tr?nh qu? tr?n"
            }
            return command
        }

        rotatePhaseCommand = 'S'
        lastVisibleCommand = 'F'
        val command = approachGoal(marker, now)
        lastReason = if (command == 'F') {
            if (marker.distanceCm != null && marker.distanceCm <= goalSlowDistanceCm) {
                "Ti?n ch?m ?n ??nh t?i marker"
            } else {
                "Ti?n ?n ??nh t?i marker"
            }
        } else {
            "T?m d?ng ng?n ?? camera k?p x? l?"
        }
        return command
    }

    private fun isGoalReached(marker: GoalMarker, markerRatio: Float): Boolean {
        val distance = marker.distanceCm
        if (distance != null) return distance <= goalStopDistanceCm
        return markerRatio >= goalReachedAreaRatio
    }

    private fun approachGoal(marker: GoalMarker, now: Long): Char {
        val distance = marker.distanceCm
        if (distance == null || distance > goalSlowDistanceCm || goalSlowDistanceCm <= goalStopDistanceCm) {
            return 'F'
        }
        if (distance <= goalStopDistanceCm) return 'S'

        val pulse = goalSlowForwardPulseMs.coerceAtLeast(0L)
        val pause = goalSlowPauseMs.coerceAtLeast(0L)
        if (pulse <= 0L) return 'S'
        if (pause <= 0L) return 'F'
        val phase = now % (pulse + pause)
        return if (phase < pulse) 'F' else 'S'
    }

    private fun rotateToward(command: Char, offset: Float, now: Long): Char {
        if (rotatePulseMs <= 0L) return command

        val deadzone = turnDeadzone.coerceAtLeast(0.001f)
        val excess = ((abs(offset) - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        val pulseScale = 1f + excess * (rotatePulseScaleMax - 1f)
        val pulse = (rotatePulseMs * pulseScale).toLong().coerceAtLeast(rotatePulseMs)
        val pause = rotatePauseMs.coerceAtLeast(0L)
        val period = pulse + pause
        if (period <= 0L) return command

        if (command != rotatePhaseCommand) {
            rotatePhaseCommand = command
            rotatePhaseStartedAt = now
            return command
        }

        val phase = (now - rotatePhaseStartedAt) % period
        return if (phase < pulse) command else 'S'
    }

    private fun recallLastMarker(now: Long, shortDropout: Boolean): Char {
        val command = preferredSearchCommand()
        if (abs(lastMarkerOffset) <= turnDeadzone * 1.15f) {
            rotatePhaseCommand = 'S'
            lastReason = if (shortDropout) {
                "V?a m?t marker, gi? h??ng c? v? ch? ?n ??nh"
            } else {
                "M?t marker g?n gi?a, ch? camera b?t l?i"
            }
            return 'S'
        }

        val rememberedOffset = when (command) {
            'Q' -> lastMarkerOffset.takeIf { it < -turnDeadzone } ?: -1f
            'E' -> lastMarkerOffset.takeIf { it > turnDeadzone } ?: 1f
            else -> lastMarkerOffset
        }
        val pulseCommand = rotateToward(command, rememberedOffset, now)
        lastReason = if (pulseCommand == 'S') {
            "Nh? h??ng marker c?, h?m nh?p ?? camera b?t l?i"
        } else if (command == 'Q') {
            "Nh? marker c? b?n tr?i, xoay tr?i t?m l?i"
        } else {
            "Nh? marker c? b?n ph?i, xoay ph?i t?m l?i"
        }
        return pulseCommand
    }

    private fun searchForMarker(now: Long, allowWideSweep: Boolean): Char {
        val pulse = searchPulseMs.coerceAtLeast(0L)
        val pause = searchPauseMs.coerceAtLeast(0L)
        if (pulse <= 0L) return 'S'
        if (pause <= 0L) return preferredSearchCommand()

        val period = pulse + pause
        if (period <= 0L) return preferredSearchCommand()

        val periodIndex = now / period
        if (lastSearchPeriodIndex < 0L) {
            lastSearchPeriodIndex = periodIndex
        } else if (periodIndex != lastSearchPeriodIndex) {
            val advanced = (periodIndex - lastSearchPeriodIndex).coerceAtLeast(1L).coerceAtMost(3L)
            lastSearchPeriodIndex = periodIndex
            if (allowWideSweep) {
                searchPeriodCount += advanced.toInt()
            } else {
                searchPeriodCount = 0
            }
            if (allowWideSweep && searchPeriodCount >= searchSweepLimit.coerceAtLeast(1)) {
                flipSearchDirection()
                searchPeriodCount = 0
                lastMarkerOffset = 0f
            }
        }

        val phase = now % period
        return if (phase < pulse) preferredSearchCommand() else 'S'
    }

    private fun preferredSearchCommand(): Char = when {
        lastMarkerOffset < -0.12f -> 'Q'
        lastMarkerOffset > 0.12f -> 'E'
        lastVisibleCommand == 'Q' || lastVisibleCommand == 'E' -> lastVisibleCommand
        else -> searchDirection
    }

    private fun resetTransientState() {
        rotatePhaseCommand = 'S'
        rotatePhaseStartedAt = 0L
        lastMarkerSeenAt = 0L
        lastMarkerOffset = 0f
        lastVisibleCommand = 'S'
        goalReached = false
        goalReachedAt = 0L
        avoidState = AvoidState.NONE
        avoidStateStartedAt = 0L
        avoidTurnCommand = 'Q'
        avoidForwardCommand = 'F'
        avoidReacquireCommand = 'E'
        avoidTurnRetries = 0
        searchDirection = defaultSearchCommand.uppercaseChar().takeIf { it == 'Q' || it == 'E' } ?: 'Q'
        searchPeriodCount = 0
        lastSearchPeriodIndex = -1L
        smoothedSectorRisks = FloatArray(SECTOR_COUNT)
        lastAvoidDirection = null
        lastDirectionSwitchAt = 0L
    }

    companion object {
        private const val SECTOR_COUNT = 5
    }
}
