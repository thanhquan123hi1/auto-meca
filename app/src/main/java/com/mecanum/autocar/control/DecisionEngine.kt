package com.mecanum.autocar.control

import com.mecanum.autocar.ai.Detection
import com.mecanum.autocar.ai.GoalMarker
import kotlin.math.abs

class DecisionEngine(
    private val obstacleMinAreaRatio: Float = 0.06f,
    private val obstacleCloseAreaRatio: Float = 0.18f,
    private val turnDeadzone: Float = 0.16f,
    private val goalReachedAreaRatio: Float = 0.22f,
    private val goalStopDistanceCm: Float = 30f,
    private val goalLostGraceMs: Long = 450L,
    private val rotatePulseMs: Long = 120L,
    private val rotatePauseMs: Long = 100L,
) {
    private var lastMarkerSeenAt = 0L
    private var lastMarkerOffset = 0f
    private var rotatePhaseCommand = 'S'
    private var rotatePhaseStartedAt = 0L
    @Volatile var lastReason: String = "idle"
        private set

    fun decide(
        frameWidth: Int,
        frameHeight: Int,
        detections: List<Detection>,
        marker: GoalMarker?,
        autonomous: Boolean,
    ): Char {
        if (!autonomous || frameWidth <= 0 || frameHeight <= 0) {
            lastReason = "disarmed"
            return 'S'
        }

        val now = System.currentTimeMillis()
        val frameArea = (frameWidth * frameHeight).coerceAtLeast(1).toFloat()

        val obstacle = detections.maxByOrNull { it.area }
        if (obstacle != null) {
            val ratio = obstacle.area / frameArea
            if (ratio >= obstacleCloseAreaRatio) {
                lastReason = "obstacle close area=${"%.2f".format(ratio)}"
                return 'B'
            }
            if (ratio >= obstacleMinAreaRatio) {
                val command = if (obstacle.centerX >= frameWidth / 2f) 'Q' else 'E'
                lastReason = "avoid obstacle ${if (command == 'Q') "right" else "left"} area=${"%.2f".format(ratio)}"
                return command
            }
        }

        if (marker != null) {
            lastMarkerSeenAt = now
            val markerRatio = marker.area / frameArea
            if (isGoalReached(marker, markerRatio)) {
                rotatePhaseCommand = 'S'
                lastReason = "goal reached area=${"%.2f".format(markerRatio)}"
                return 'S'
            }

            val offset = (marker.centerX - frameWidth / 2f) / (frameWidth / 2f)
            lastMarkerOffset = offset
            if (offset < -turnDeadzone) {
                val command = rotateToward('Q', offset, now)
                lastReason = "marker left offset=${"%.2f".format(offset)}"
                return command
            }
            if (offset > turnDeadzone) {
                val command = rotateToward('E', offset, now)
                lastReason = "marker right offset=${"%.2f".format(offset)}"
                return command
            }

            rotatePhaseCommand = 'S'
            lastReason = "marker centered"
            return 'F'
        }

        if (now - lastMarkerSeenAt <= goalLostGraceMs) {
            lastReason = "marker grace stop"
            return 'S'
        }
        val command = searchCommand(now)
        lastReason = "search marker"
        return command
    }

    private fun isGoalReached(marker: GoalMarker, markerRatio: Float): Boolean {
        val distance = marker.distanceCm
        if (distance != null) return distance <= goalStopDistanceCm
        return markerRatio >= goalReachedAreaRatio
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

    private fun searchCommand(now: Long): Char {
        val command = if (lastMarkerOffset < 0f) 'Q' else 'E'
        val pulse = 180L
        val pause = 900L
        val phase = now % (pulse + pause)
        return if (phase < pulse) command else 'S'
    }
}
