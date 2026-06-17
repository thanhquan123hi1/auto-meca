package com.mecanum.autocar.control

import com.mecanum.autocar.ai.GoalMarker

/**
 * Trang thai dung chung cho ca UI dien thoai va dashboard web.
 * Moi thay doi Auto / dieu khien tay deu di qua day de app va web luon dong bo.
 *
 * (Ghi chu khong dau de tranh loi font trong source; van ban hien thi tieng Viet
 *  duoc dat trong strings.xml va assets/dashboard.html.)
 */
class StationController(
    private val manualTimeoutMs: Long = 900L,
    private val telemetryFreshMs: Long = 500L,
    private val onAutonomousChanged: (Boolean) -> Unit = {},
    private val onActivityChanged: () -> Unit = {},
    private val onManualCommand: (Char) -> Unit = {},
) {
    @Volatile var autonomous: Boolean = false
        private set

    @Volatile private var manualCommand: Char? = null
    @Volatile private var manualDeadlineMs: Long = 0L
    @Volatile private var manualOwner: String? = null

    // Thong tin trang thai moi nhat de hien thi (app + web).
    @Volatile var currentCommand: Char = 'S'
    @Volatile var reason: String = "idle"
    @Volatile var aiFps: Float = 0f
    @Volatile var udpError: String = ""
    @Volatile var markerDistanceCm: Float? = null
    @Volatile var markerId: Int? = null
    @Volatile var detectionCount: Int = 0
    @Volatile var modelId: String = ""
    @Volatile var modelName: String = ""
    @Volatile var yoloError: String = ""
    @Volatile var arucoError: String = ""
    @Volatile var frontDistanceCm: Float? = null
        private set
    @Volatile var frontDistanceUpdatedAt: Long = 0L
        private set
    @Volatile var guardActive: Boolean = false
        private set

    val manualActive: Boolean get() = manualCommand != null

    /** Bat / tat che do tu hanh. Bat Auto se xoa moi lenh tay dang giu. */
    fun setAutonomous(value: Boolean) {
        if (value) clearManual()
        if (autonomous == value) return
        autonomous = value
        onAutonomousChanged(value)
        onActivityChanged()
    }

    /** Dung xe ngay: tat Auto va xoa moi lenh tay bat ke chu so huu. */
    fun stopAll() {
        val changed = autonomous || manualCommand != null || currentCommand != 'S'
        clearManual()
        if (autonomous) {
            autonomous = false
            onAutonomousChanged(false)
        }
        currentCommand = 'S'
        onManualCommand('S')
        if (changed) onActivityChanged()
    }

    /** Web bat dau giu mot lenh tay. Lenh tay luon tat Auto. */
    fun pressManual(command: Char, owner: String, now: Long) {
        val turnedOffAuto = autonomous
        if (autonomous) autonomous = false
        manualOwner = owner
        manualCommand = command
        manualDeadlineMs = now + manualTimeoutMs
        currentCommand = command
        onManualCommand(command)
        if (turnedOffAuto) onAutonomousChanged(false)
        onActivityChanged()
    }

    /** Nha lenh tay (chi chu so huu lenh moi duoc nha). */
    fun releaseManual(owner: String) {
        if (manualOwner == null || manualOwner == owner) {
            clearManual()
            currentCommand = 'S'
            onManualCommand('S')
            onActivityChanged()
        }
    }

    private fun clearManual() {
        manualCommand = null
        manualOwner = null
    }

    /**
     * Lenh tay con hieu luc hay khong. Qua han deadman thi tu xoa
     * (an toan khi mat ket noi / tha nut khong kip bao).
     */
    fun activeManualCommand(now: Long): Char? {
        val cmd = manualCommand ?: return null
        if (now > manualDeadlineMs) {
            clearManual()
            currentCommand = 'S'
            onManualCommand('S')
            onActivityChanged()
            return null
        }
        return cmd
    }

    /** Co can gui goi UDP hay khong (dang Auto hoac dang giu lenh tay). */
    val shouldDrive: Boolean get() = autonomous || manualActive

    fun applyMarker(marker: GoalMarker?) {
        markerId = marker?.id
        markerDistanceCm = marker?.distanceCm
    }

    fun applyFrontTelemetry(distanceCm: Float?, guard: Boolean, now: Long) {
        frontDistanceCm = distanceCm
        frontDistanceUpdatedAt = now
        guardActive = guard
        onActivityChanged()
    }

    fun freshFrontDistance(now: Long): Float? {
        val distance = frontDistanceCm ?: return null
        if (frontDistanceUpdatedAt <= 0L) return null
        return if (now - frontDistanceUpdatedAt <= telemetryFreshMs) distance else null
    }

    fun isFrontTelemetryFresh(now: Long): Boolean = freshFrontDistance(now) != null
}
