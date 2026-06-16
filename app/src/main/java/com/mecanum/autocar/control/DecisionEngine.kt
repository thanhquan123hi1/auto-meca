package com.mecanum.autocar.control

import com.mecanum.autocar.ai.Detection
import com.mecanum.autocar.ai.GoalMarker
import kotlin.math.abs

class DecisionEngine(
    private val obstacleMinAreaRatio: Float = 0.06f,
    private val obstacleCloseAreaRatio: Float = 0.18f,
    private val obstacleCenterLeftBound: Float = 0.35f,
    private val obstacleCenterRightBound: Float = 0.65f,
    private val avoidCommandHoldMs: Long = 300L,
    private val turnDeadzone: Float = 0.16f,
    private val goalReachedAreaRatio: Float = 0.22f,
    private val goalStopDistanceCm: Float = 30f,
    private val goalSlowDistanceCm: Float = 45f,
    private val goalSlowForwardPulseMs: Long = 120L,
    private val goalSlowPauseMs: Long = 200L,
    private val goalLostGraceMs: Long = 450L,
    private val goalReachedHoldMs: Long = 1500L,
    private val rotatePulseMs: Long = 120L,
    private val rotatePauseMs: Long = 100L,
    private val searchPulseMs: Long = 180L,
    private val searchPauseMs: Long = 900L,
    private val defaultSearchCommand: Char = 'Q',
) {
    private var lastMarkerSeenAt = 0L
    private var lastMarkerOffset = 0f
    private var lastVisibleCommand = 'S'
    private var rotatePhaseCommand = 'S'
    private var rotatePhaseStartedAt = 0L
    private var goalReached = false
    private var goalReachedAt = 0L
    private var lastAvoidCommand = 'S'
    private var lastAvoidStartedAt = 0L

    @Volatile var lastReason: String = "Đang chờ"
        private set

    fun decide(
        frameWidth: Int,
        frameHeight: Int,
        detections: List<Detection>,
        marker: GoalMarker?,
        autonomous: Boolean,
    ): Char {
        if (!autonomous || frameWidth <= 0 || frameHeight <= 0) {
            resetTransientState()
            lastReason = "Tắt Auto"
            return 'S'
        }

        val now = System.currentTimeMillis()

        if (goalReached) {
            lastReason = if (now - goalReachedAt <= goalReachedHoldMs) {
                "Đã tới marker"
            } else {
                "Đã tới marker, đang giữ vị trí"
            }
            return 'S'
        }

        decideObstacle(frameWidth, frameHeight, detections, now)?.let { return it }
        decideMarker(frameWidth, frameHeight, marker, now)?.let { return it }

        if (now - lastMarkerSeenAt <= goalLostGraceMs) {
            rotatePhaseCommand = 'S'
            lastReason = "Vừa mất marker, chờ ổn định"
            return 'S'
        }

        val command = searchForMarker(now)
        lastReason = "Đang tìm marker"
        return command
    }

    private fun decideObstacle(
        frameWidth: Int,
        frameHeight: Int,
        detections: List<Detection>,
        now: Long,
    ): Char? {
        val obstacle = detections.maxByOrNull { it.area } ?: return null
        val frameArea = (frameWidth * frameHeight).coerceAtLeast(1).toFloat()
        val ratio = obstacle.area / frameArea
        if (ratio < obstacleMinAreaRatio) return null

        if (lastAvoidCommand != 'S' && now - lastAvoidStartedAt <= avoidCommandHoldMs) {
            lastReason = when (lastAvoidCommand) {
                'B' -> "Vật cản quá gần"
                'Q' -> "Đang giữ hướng né sang trái"
                'E' -> "Đang giữ hướng né sang phải"
                else -> "Đang né vật cản"
            }
            return lastAvoidCommand
        }

        val region = obstacle.centerX / frameWidth.toFloat()
        val command = when {
            ratio >= obstacleCloseAreaRatio -> 'B'
            region < obstacleCenterLeftBound -> 'E'
            region > obstacleCenterRightBound -> 'Q'
            lastMarkerOffset < -0.05f -> 'Q'
            lastMarkerOffset > 0.05f -> 'E'
            lastVisibleCommand == 'E' -> 'E'
            else -> 'Q'
        }

        lastAvoidCommand = command
        lastAvoidStartedAt = now
        rotatePhaseCommand = 'S'
        lastReason = when (command) {
            'B' -> "Vật cản quá gần"
            'Q' -> if (region > obstacleCenterRightBound) "Né vật cản bên phải" else "Né vật cản phía trước sang trái"
            'E' -> if (region < obstacleCenterLeftBound) "Né vật cản bên trái" else "Né vật cản phía trước sang phải"
            else -> "Đang né vật cản"
        }
        return command
    }

    private fun decideMarker(
        frameWidth: Int,
        frameHeight: Int,
        marker: GoalMarker?,
        now: Long,
    ): Char? {
        marker ?: return null
        lastAvoidCommand = 'S'
        lastMarkerSeenAt = now

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
            lastReason = "Đang căn marker sang trái"
            return command
        }
        if (offset > turnDeadzone) {
            lastVisibleCommand = 'E'
            val command = rotateToward('E', offset, now)
            lastReason = "Đang căn marker sang phải"
            return command
        }

        rotatePhaseCommand = 'S'
        lastVisibleCommand = 'F'
        val command = approachGoal(marker, now)
        lastReason = if (command == 'F') {
            if (marker.distanceCm != null && marker.distanceCm <= goalSlowDistanceCm) {
                "Đang tiến chậm tới marker"
            } else {
                "Đang tiến tới marker"
            }
        } else {
            "Đang tiến chậm tới marker"
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
        if (distance <= goalStopDistanceCm) {
            return 'S'
        }
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
        val excess = ((abs(offset) - deadzone) / deadzone).coerceAtLeast(0f)
        val pulse = (rotatePulseMs * (1f + excess).coerceIn(1f, 2f)).toLong()
        val period = pulse + rotatePauseMs
        if (period <= 0L) return command

        if (command != rotatePhaseCommand) {
            rotatePhaseCommand = command
            rotatePhaseStartedAt = now
            return command
        }

        val phase = (now - rotatePhaseStartedAt) % period
        return if (phase < pulse) command else 'S'
    }

    private fun searchForMarker(now: Long): Char {
        val command = when {
            lastMarkerOffset < -0.05f -> 'Q'
            lastMarkerOffset > 0.05f -> 'E'
            lastVisibleCommand == 'E' -> 'E'
            lastVisibleCommand == 'Q' -> 'Q'
            else -> defaultSearchCommand.uppercaseChar().takeIf { it == 'Q' || it == 'E' } ?: 'Q'
        }

        val pulse = searchPulseMs.coerceAtLeast(0L)
        val pause = searchPauseMs.coerceAtLeast(0L)
        if (pulse <= 0L) return 'S'
        if (pause <= 0L) return command
        val phase = now % (pulse + pause)
        return if (phase < pulse) command else 'S'
    }

    private fun resetTransientState() {
        rotatePhaseCommand = 'S'
        lastAvoidCommand = 'S'
    }
}