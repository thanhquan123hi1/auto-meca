package com.mecanum.autocar.control

import com.mecanum.autocar.ai.Detection
import com.mecanum.autocar.ai.GoalMarker
import kotlin.math.abs

class DecisionEngine(
    private val obstacleMinAreaRatio: Float = 0.06f,
    private val obstacleStrongAreaRatio: Float = 0.12f,
    private val obstacleCenterLeftBound: Float = 0.35f,
    private val obstacleCenterRightBound: Float = 0.65f,
    private val obstacleAvoidDistanceCm: Float = 30f,
    private val obstacleEmergencyDistanceCm: Float = 12f,
    private val markerOcclusionGraceMs: Long = 2600L,
    private val avoidEmergencyReverseMs: Long = 300L,
    private val avoidTurnMs: Long = 280L,
    private val avoidForwardMs: Long = 300L,
    private val avoidForwardRetryMs: Long = 160L,
    private val avoidReacquireMs: Long = 1100L,
    private val avoidMaxTurnRetries: Int = 2,
    private val turnDeadzone: Float = 0.18f,
    private val goalReachedAreaRatio: Float = 0.22f,
    private val goalStopDistanceCm: Float = 30f,
    private val goalSlowDistanceCm: Float = 45f,
    private val goalSlowForwardPulseMs: Long = 190L,
    private val goalSlowPauseMs: Long = 150L,
    private val goalLostGraceMs: Long = 650L,
    private val markerMemorySearchMs: Long = 4200L,
    private val goalReachedHoldMs: Long = 1500L,
    private val rotatePulseMs: Long = 145L,
    private val rotatePauseMs: Long = 125L,
    private val rotatePulseScaleMax: Float = 1.45f,
    private val searchPulseMs: Long = 190L,
    private val searchPauseMs: Long = 150L,
    private val searchSweepLimit: Int = 5,
    private val defaultSearchCommand: Char = 'Q',
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

    @Volatile var lastReason: String = "Đang chờ"
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
            lastReason = "Tắt Auto"
            return 'S'
        }

        val now = System.currentTimeMillis()
        val obstacleInfo = detectCenteredObstacle(frameWidth, frameHeight, detections)

        marker?.let { rememberMarker(it, frameWidth, frameHeight, now) }

        if (goalReached) {
            lastReason = if (now - goalReachedAt <= goalReachedHoldMs) {
                "Đã tới marker"
            } else {
                "Đã tới marker, đang giữ vị trí"
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
                "Mất marker, giữ hướng cũ và chờ khung tiếp"
            } else {
                "Mất marker, quét theo hướng marker cũ"
            }
            return command
        }

        val command = searchForMarker(now, allowWideSweep = true)
        lastReason = "Đang quét tìm marker"
        return command
    }

    private fun detectCenteredObstacle(
        frameWidth: Int,
        frameHeight: Int,
        detections: List<Detection>,
    ): ObstacleInfo? {
        val frameArea = (frameWidth * frameHeight).coerceAtLeast(1).toFloat()
        return detections
            .asSequence()
            .map { detection ->
                val ratio = detection.area / frameArea
                val region = detection.centerX / frameWidth.toFloat()
                ObstacleInfo(ratio = ratio, region = region)
            }
            .filter { it.ratio >= obstacleMinAreaRatio }
            .filter { it.region in obstacleCenterLeftBound..obstacleCenterRightBound }
            .maxByOrNull { it.ratio }
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
                    lastReason = "Bước 1/4: lùi khẩn cấp để tạo khoảng trống"
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
                    lastReason = "Vẫn quá gần, lùi thêm để tránh kẹt đầu xe"
                    return 'B'
                }

                val stillBlocked = when {
                    distance != null -> distance <= obstacleAvoidDistanceCm
                    obstacleInfo != null -> obstacleInfo.ratio >= obstacleStrongAreaRatio
                    else -> false
                }

                if (stillBlocked && avoidTurnRetries < avoidMaxTurnRetries.coerceAtLeast(0)) {
                    avoidTurnRetries += 1
                    startAvoidState(AvoidState.TURN_AWAY, now)
                    lastReason = "Chưa đủ thoáng, xoay thêm một nhịp ngắn"
                    return avoidTurnCommand
                }

                if (stillBlocked) {
                    startAvoidState(AvoidState.REACQUIRE_MARKER, now)
                    lastReason = "Vật cản còn gần, quay bắt lại marker"
                    return avoidReacquireCommand
                }

                val forwardBudget = avoidForwardMs + avoidTurnRetries * avoidForwardRetryMs
                if (elapsed < forwardBudget) {
                    lastReason = "Bước 3/4: tiến chéo ngắn để vượt vùng bị che"
                    return avoidForwardCommand
                }
                startAvoidState(AvoidState.REACQUIRE_MARKER, now)
            }

            AvoidState.REACQUIRE_MARKER -> {
                if (marker != null) {
                    avoidState = AvoidState.NONE
                    searchPeriodCount = 0
                    lastReason = "Đã bắt lại marker"
                    return null
                }
                if (elapsed < avoidReacquireMs) {
                    lastReason = "Bước 4/4: xoay ngắn để bắt lại marker"
                    return avoidReacquireCommand
                }
                avoidState = AvoidState.NONE
                rotatePhaseCommand = 'S'
                flipSearchDirection()
                lastReason = "Chưa thấy marker, chuyển sang quét rộng"
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
            obstacleInfo.ratio >= obstacleStrongAreaRatio -> true
            marker == null && markerRecentlySeen -> true
            else -> false
        }

        if (!shouldAvoid) {
            lastReason = if (frontDistanceCm == null) {
                "Thấy vật cản nhỏ, chờ dữ liệu HC-SR04"
            } else {
                "Vật ở giữa nhưng còn xa"
            }
            return null
        }

        prepareAvoidancePlan(obstacleInfo)
        rotatePhaseCommand = 'S'

        if (frontDistanceCm != null && frontDistanceCm <= obstacleEmergencyDistanceCm) {
            startAvoidState(AvoidState.EMERGENCY_REVERSE, now)
            lastReason = if (marker == null && markerRecentlySeen) {
                "Bước 1/4: marker bị che, lùi khẩn cấp trước"
            } else {
                "Bước 1/4: đầu xe quá gần vật cản, ưu tiên lùi"
            }
            return 'B'
        }

        startAvoidState(AvoidState.TURN_AWAY, now)
        lastReason = reasonForTurnCommand(avoidTurnCommand, markerOccluded = marker == null)
        return avoidTurnCommand
    }

    private fun prepareAvoidancePlan(obstacleInfo: ObstacleInfo) {
        avoidTurnRetries = 0

        avoidTurnCommand = when {
            lastMarkerOffset < -0.06f -> 'Q'
            lastMarkerOffset > 0.06f -> 'E'
            obstacleInfo.region < 0.48f -> 'E'
            obstacleInfo.region > 0.52f -> 'Q'
            lastVisibleCommand == 'Q' || lastVisibleCommand == 'E' -> lastVisibleCommand
            else -> searchDirection
        }

        avoidForwardCommand = when (avoidTurnCommand) {
            'Q' -> 'G'
            'E' -> 'H'
            else -> 'F'
        }
        avoidReacquireCommand = oppositeTurn(avoidTurnCommand)
    }

    private fun reasonForTurnCommand(command: Char, markerOccluded: Boolean): String {
        val prefix = if (markerOccluded) {
            "Bước 2/4: marker đang bị che"
        } else {
            "Bước 2/4: HC-SR04 xác nhận vật cản gần"
        }
        return when (command) {
            'Q' -> "$prefix, xoay trái một nhịp"
            'E' -> "$prefix, xoay phải một nhịp"
            else -> "$prefix, đang đổi hướng né"
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
            lastMarkerOffset < -0.03f -> 'Q'
            lastMarkerOffset > 0.03f -> 'E'
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
            lastReason = "Đã tới marker"
            return 'S'
        }

        val offset = (marker.centerX - frameWidth / 2f) / (frameWidth / 2f)
        lastMarkerOffset = offset
        if (offset < -turnDeadzone) {
            lastVisibleCommand = 'Q'
            val command = rotateToward('Q', offset, now)
            lastReason = if (command == 'Q') {
                "Canh trái ngắn để bám marker"
            } else {
                "Hãm xoay trái để tránh quá trớn"
            }
            return command
        }
        if (offset > turnDeadzone) {
            lastVisibleCommand = 'E'
            val command = rotateToward('E', offset, now)
            lastReason = if (command == 'E') {
                "Canh phải ngắn để bám marker"
            } else {
                "Hãm xoay phải để tránh quá trớn"
            }
            return command
        }

        rotatePhaseCommand = 'S'
        lastVisibleCommand = 'F'
        val command = approachGoal(marker, now)
        lastReason = if (command == 'F') {
            if (marker.distanceCm != null && marker.distanceCm <= goalSlowDistanceCm) {
                "Tiến chậm ổn định tới marker"
            } else {
                "Tiến ổn định tới marker"
            }
        } else {
            "Tạm dừng ngắn để camera kịp xử lý"
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
        if (lastVisibleCommand != 'Q' && lastVisibleCommand != 'E' && abs(lastMarkerOffset) <= turnDeadzone) {
            rotatePhaseCommand = 'S'
            lastReason = if (shortDropout) {
                "Vừa mất marker, giữ hướng cũ và chờ ổn định"
            } else {
                "Mất marker gần giữa, chờ camera bắt lại"
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
            "Nhớ hướng marker cũ, hãm nhịp để camera bắt lại"
        } else if (command == 'Q') {
            "Nhớ marker cũ bên trái, xoay trái tìm lại"
        } else {
            "Nhớ marker cũ bên phải, xoay phải tìm lại"
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
        lastMarkerOffset < -0.08f -> 'Q'
        lastMarkerOffset > 0.08f -> 'E'
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
    }
}
