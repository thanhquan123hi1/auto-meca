package com.mecanum.autocar

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.net.Uri
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.mecanum.autocar.ai.ArucoGoalDetector
import com.mecanum.autocar.ai.DetectorModel
import com.mecanum.autocar.ai.Detection
import com.mecanum.autocar.ai.GoalMarker
import com.mecanum.autocar.ai.VisionResult
import com.mecanum.autocar.ai.YoloTfliteDetector
import com.mecanum.autocar.ai.toBitmapRotated
import com.mecanum.autocar.control.DecisionEngine
import com.mecanum.autocar.control.StationController
import com.mecanum.autocar.control.UdpCommandSender
import com.mecanum.autocar.web.StationStatus
import com.mecanum.autocar.web.WebControlServer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var webText: TextView
    private lateinit var copyWebButton: Button
    private lateinit var autoToggleButton: androidx.appcompat.widget.AppCompatButton

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val decisionEngine = DecisionEngine()
    private val udpSender = UdpCommandSender()
    private val detectorLock = Any()

    private val controller = StationController(
        onAutonomousChanged = { enabled ->
            if (enabled) udpSender.start() else udpSender.stopAutonomous()
            runOnUiThread { syncAutoSwitch(enabled) }
        },
        onActivityChanged = { runOnUiThread { updateStatus() } },
        onManualCommand = { command ->
            if (command == 'S') {
                udpSender.stopAutonomous()
            } else {
                udpSender.start()
                udpSender.setCommand(command)
            }
        },
    )

    @Volatile private var lastResult: VisionResult? = null
    @Volatile private var yoloLoadError = ""
    @Volatile private var arucoLoadError = ""
    @Volatile private var currentModel = DetectorModel.OPTIONS.first()
    @Volatile private var cachedWifiSsid = ""
    @Volatile private var lastWifiRefreshAt = 0L
    @Volatile private var lastYoloMs = 0L
    @Volatile private var lastArucoMs = 0L
    @Volatile private var lastPipelineMs = 0L
    @Volatile private var lastCommandChanges = 0
    @Volatile private var lastMarkerSeenRatio = 0f
    @Volatile private var lastAvgConfidence = 0f
    @Volatile private var lastSkipRatio = 0f
    @Volatile private var lastMarker: GoalMarker? = null

    private var yoloDetector: YoloTfliteDetector? = null
    private var arucoDetector: ArucoGoalDetector? = null
    private var webServer: WebControlServer? = null
    private var lastInferenceAt = 0L
    private var frameCounter = 0
    private var skippedFrames = 0
    private val markerSeenWindow = ArrayDeque<Boolean>()
    private val confidenceWindow = ArrayDeque<Float>()
    private val commandWindow = ArrayDeque<Char>()
    private val frameOutcomeWindow = ArrayDeque<Boolean>()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else updateStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)
        webText = findViewById(R.id.webText)
        copyWebButton = findViewById(R.id.copyWebButton)
        autoToggleButton = findViewById(R.id.autoToggleButton)

        autoToggleButton.setOnClickListener { controller.setAutonomous(!controller.autonomous) }
        syncAutoSwitch(false)
        copyWebButton.setOnClickListener { copyWebUrlToClipboard() }

        initDetectors()
        startWebServer()
        ensureCameraPermission()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.setAutonomous(false)
        udpSender.shutdown()
        webServer?.stopServer()
        yoloDetector?.close()
        arucoDetector?.close()
        cameraExecutor.shutdownNow()
    }

    private fun initDetectors() {
        loadYoloModel(currentModel)
        try {
            arucoDetector = ArucoGoalDetector(targetId = 0, markerSizeCm = 10f, horizontalFovDeg = 60f)
        } catch (error: Exception) {
            arucoLoadError = error.message ?: error.javaClass.simpleName
            controller.arucoError = arucoLoadError
        }
    }

    private fun startWebServer() {
        webServer = WebControlServer(
            context = applicationContext,
            controller = controller,
            statusProvider = { currentStatus() },
            onAutoOn = { controller.setAutonomous(true) },
            onAutoOff = { controller.setAutonomous(false) },
            onStopVehicle = {
                controller.stopAll()
                udpSender.stopAutonomous()
                runOnUiThread { updateStatus() }
            },
            onModelSelect = { modelId ->
                controller.setAutonomous(false)
                controller.currentCommand = 'S'
                loadYoloModel(DetectorModel.byId(modelId))
                runOnUiThread { updateStatus() }
            },
        ).also { it.startServer() }
    }

    private fun currentStatus(): StationStatus {
        return StationStatus(
            autonomous = controller.autonomous,
            command = controller.currentCommand,
            reason = controller.reason,
            modelId = currentModel.id,
            modelName = currentModel.displayName,
            modelOptions = DetectorModel.OPTIONS.map { it.id to it.displayName },
            aiFps = controller.aiFps,
            udpError = controller.udpError,
            markerDistanceCm = controller.markerDistanceCm,
            markerId = controller.markerId,
            detectionCount = controller.detectionCount,
            connectedWifiSsid = currentWifiSsid(),
            yoloMs = lastYoloMs,
            arucoMs = lastArucoMs,
            pipelineMs = lastPipelineMs,
            skippedFrames = skippedFrames,
            markerSeenRatio = lastMarkerSeenRatio,
            avgConfidence = lastAvgConfidence,
            skipRatio = lastSkipRatio,
            commandChanges = lastCommandChanges,
        )
    }

    private fun loadYoloModel(model: DetectorModel) {
        synchronized(detectorLock) {
            currentModel = model
            controller.modelId = model.id
            controller.modelName = model.displayName
            yoloLoadError = ""
            controller.yoloError = ""
            yoloDetector?.close()
            yoloDetector = null
            controller.aiFps = 0f
            lastYoloMs = 0L
            lastArucoMs = 0L
            lastPipelineMs = 0L
            try {
                yoloDetector = YoloTfliteDetector(this, model.assetName)
            } catch (error: Exception) {
                yoloLoadError = error.message ?: error.javaClass.simpleName
                controller.yoloError = yoloLoadError
            }
        }
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { useCase -> useCase.setAnalyzer(cameraExecutor, ::analyzeFrame) }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: androidx.camera.core.ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInferenceAt < MIN_INFERENCE_INTERVAL_MS || !processing.compareAndSet(false, true)) {
            skippedFrames++
            recordFrameOutcome(true)
            image.close()
            return
        }
        lastInferenceAt = now

        try {
            val pipelineStartedAt = SystemClock.elapsedRealtimeNanos()
            val bitmap = image.toBitmapRotated()
            val detections: List<Detection>
            var yoloMs = 0L
            synchronized(detectorLock) {
                val yolo = yoloDetector
                if (yolo != null) {
                    val result = yolo.detect(bitmap)
                    detections = result.detections
                    yoloMs = result.inferenceMs + result.preprocessMs
                } else {
                    detections = emptyList()
                }
            }

            frameCounter += 1
            val shouldRunAruco = frameCounter % ARUCO_EVERY_N_FRAMES == 0
            val markerStartedAt = SystemClock.elapsedRealtimeNanos()
            val detectedMarker: GoalMarker? = if (shouldRunAruco) arucoDetector?.detect(bitmap) else lastMarker
            val arucoMs = if (shouldRunAruco) (SystemClock.elapsedRealtimeNanos() - markerStartedAt) / 1_000_000 else 0L
            lastMarker = detectedMarker

            if (yoloMs > 0L) {
                val instantFps = 1000f / yoloMs.coerceAtLeast(1L)
                controller.aiFps = if (controller.aiFps <= 0f) instantFps else controller.aiFps * 0.85f + instantFps * 0.15f
            }

            val manualCommand = controller.activeManualCommand(System.currentTimeMillis())
            val command = manualCommand ?: decisionEngine.decide(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                detections = detections,
                marker = detectedMarker,
                autonomous = controller.autonomous,
            )
            controller.currentCommand = command
            controller.reason = if (manualCommand != null) "Điều khiển tay từ web" else decisionEngine.lastReason
            controller.detectionCount = detections.size
            controller.applyMarker(detectedMarker)
            controller.udpError = udpSender.lastError
            lastYoloMs = yoloMs
            lastArucoMs = arucoMs
            lastPipelineMs = (SystemClock.elapsedRealtimeNanos() - pipelineStartedAt) / 1_000_000
            updateRollingMetrics(detectedMarker != null, detections, command)
            recordFrameOutcome(false)

            if (controller.shouldDrive) {
                udpSender.start()
                udpSender.setCommand(command)
            } else {
                udpSender.setCommand('S')
            }

            val visionResult = VisionResult(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                detections = detections,
                marker = detectedMarker,
                aiFps = controller.aiFps,
                command = command,
                reason = controller.reason,
                autonomous = controller.autonomous,
            )
            lastResult = visionResult
            overlayView.update(visionResult)
            webServer?.submitFrame(bitmap, visionResult)
            bitmap.recycle()

            runOnUiThread { updateStatus() }
        } catch (error: Exception) {
            yoloLoadError = yoloLoadError.ifBlank { error.message ?: error.javaClass.simpleName }
            controller.yoloError = yoloLoadError
            runOnUiThread { updateStatus() }
        } finally {
            processing.set(false)
            image.close()
        }
    }

    private fun syncAutoSwitch(enabled: Boolean) {
        autoToggleButton.text = getString(if (enabled) R.string.auto_switch_on else R.string.auto_switch_off)
        autoToggleButton.backgroundTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor(if (enabled) "#57D99A" else "#26323A")
        )
        autoToggleButton.setTextColor(android.graphics.Color.parseColor(if (enabled) "#06100D" else "#F2F7F5"))
    }

    private fun updateStatus() {
        val result = lastResult
        val yoloState = if (yoloDetector == null) {
            val fallback = yoloLoadError.ifBlank { getString(R.string.status_yolo_missing, currentModel.assetName) }
            getString(R.string.status_yolo_error, fallback)
        } else {
            getString(R.string.status_yolo_ok)
        }
        val arucoState = if (arucoDetector == null) {
            getString(R.string.status_aruco_error, arucoLoadError)
        } else {
            getString(R.string.status_aruco_ok)
        }
        val udpState = udpSender.lastError.ifBlank { getString(R.string.udp_ok) }
        val markerText = result?.marker?.id?.toString() ?: getString(R.string.status_unknown_marker)
        val fpsText = String.format(Locale.US, "%.1f", controller.aiFps)
        val distanceText = controller.markerDistanceCm?.let { String.format(Locale.US, "%.1f cm", it) }
            ?: getString(R.string.status_distance_unknown)

        statusText.text = buildString {
            appendLine(if (controller.autonomous) getString(R.string.status_auto_on) else getString(R.string.status_auto_off))
            appendLine(getString(R.string.status_model, currentModel.displayName))
            appendLine(getString(R.string.status_cmd_fps, controller.currentCommand.toString(), fpsText))
            appendLine(getString(R.string.status_reason, controller.reason))
            appendLine(getString(R.string.status_objects_marker, result?.detections?.size ?: 0, markerText))
            appendLine(getString(R.string.status_marker_distance, distanceText))
            appendLine(getString(R.string.status_udp, udpState))
            appendLine(yoloState)
            append(arucoState)
        }

        val url = webServer?.url ?: getString(R.string.web_starting)
        val wifi = currentWifiSsid().ifBlank { getString(R.string.wifi_unknown) }
        webText.text = buildWebHintText(url, wifi)
        webText.movementMethod = LinkMovementMethod.getInstance()
        syncAutoSwitch(controller.autonomous)
    }

    private fun buildWebHintText(url: String, wifi: String): SpannableString {
        val title = getString(R.string.web_hint, url)
        val wifiText = getString(R.string.wifi_hint, wifi)
        val full = "$title\n$wifiText"
        val span = SpannableString(full)
        val start = full.indexOf(url)
        if (start >= 0 && url.startsWith("http")) {
            span.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                        copyWebUrlToClipboard()
                    }
                }
            }, start, start + url.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return span
    }

    private fun copyWebUrlToClipboard() {
        val url = webServer?.url ?: return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("web-link", url))
        Toast.makeText(this, getString(R.string.copy_web_link_done), Toast.LENGTH_SHORT).show()
    }

    private fun updateRollingMetrics(markerSeen: Boolean, detections: List<Detection>, command: Char) {
        pushWindow(markerSeenWindow, markerSeen, 30)
        val frameAvgConfidence = if (detections.isEmpty()) 0f else detections.map { it.confidence }.average().toFloat()
        pushWindow(confidenceWindow, frameAvgConfidence, 30)
        pushWindow(commandWindow, command, 30)
        lastMarkerSeenRatio = markerSeenWindow.count { it }.toFloat() / markerSeenWindow.size.coerceAtLeast(1)
        lastAvgConfidence = confidenceWindow.average().toFloat()
        lastCommandChanges = commandWindow.zipWithNext().count { it.first != it.second }
    }

    private fun <T> pushWindow(window: ArrayDeque<T>, value: T, maxSize: Int) {
        window.addLast(value)
        while (window.size > maxSize) window.removeFirst()
    }

    /** Ghi nhan ket qua moi khung camera (skip hay xu ly) de tinh ti le skip gan day. */
    private fun recordFrameOutcome(skipped: Boolean) {
        pushWindow(frameOutcomeWindow, skipped, FRAME_OUTCOME_WINDOW)
        val total = frameOutcomeWindow.size.coerceAtLeast(1)
        lastSkipRatio = frameOutcomeWindow.count { it }.toFloat() / total
    }

    @Suppress("DEPRECATION")
    private fun currentWifiSsid(): String {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWifiRefreshAt < WIFI_REFRESH_INTERVAL_MS && cachedWifiSsid.isNotBlank()) return cachedWifiSsid
        cachedWifiSsid = try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
            val raw = wifiManager?.connectionInfo?.ssid.orEmpty()
            raw.removePrefix("\"").removeSuffix("\"")
                .takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: ""
        } catch (_: Exception) {
            ""
        }
        lastWifiRefreshAt = now
        return cachedWifiSsid
    }

    companion object {
        private const val MIN_INFERENCE_INTERVAL_MS = 90L
        private const val ARUCO_EVERY_N_FRAMES = 2
        private const val WIFI_REFRESH_INTERVAL_MS = 2_000L
        private const val FRAME_OUTCOME_WINDOW = 60
    }
}
