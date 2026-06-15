package com.mecanum.autocar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import com.mecanum.autocar.control.UdpCommandSender
import com.mecanum.autocar.web.StationStatus
import com.mecanum.autocar.web.WebControlServer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var webText: TextView

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val decisionEngine = DecisionEngine()
    private val udpSender = UdpCommandSender()
    private val detectorLock = Any()

    @Volatile private var autonomous = false
    @Volatile private var currentCommand = 'S'
    @Volatile private var aiFps = 0f
    @Volatile private var lastResult: VisionResult? = null
    @Volatile private var yoloLoadError = ""
    @Volatile private var arucoLoadError = ""
    @Volatile private var currentModel = DetectorModel.OPTIONS.first()

    private var yoloDetector: YoloTfliteDetector? = null
    private var arucoDetector: ArucoGoalDetector? = null
    private var webServer: WebControlServer? = null
    private var lastInferenceAt = 0L

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

        initDetectors()
        startWebServer()
        ensureCameraPermission()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        autonomous = false
        udpSender.shutdown()
        webServer?.stop()
        yoloDetector?.close()
        arucoDetector?.close()
        cameraExecutor.shutdownNow()
    }

    private fun initDetectors() {
        loadYoloModel(currentModel)

        try {
            arucoDetector = ArucoGoalDetector(targetId = 0)
        } catch (error: Exception) {
            arucoLoadError = error.message ?: error.javaClass.simpleName
        }
    }

    private fun startWebServer() {
        webServer = WebControlServer(
            statusProvider = {
                StationStatus(
                    autonomous = autonomous,
                    command = currentCommand,
                    reason = decisionEngine.lastReason,
                    modelId = currentModel.id,
                    modelName = currentModel.displayName,
                    modelOptions = DetectorModel.OPTIONS.map { it.id to it.displayName },
                    aiFps = aiFps,
                    udpError = udpSender.lastError,
                )
            },
            onStart = {
                autonomous = true
                udpSender.start()
                updateStatus()
            },
            onStop = {
                autonomous = false
                currentCommand = 'S'
                udpSender.stopAutonomous()
                updateStatus()
            },
            onModelSelect = { modelId ->
                autonomous = false
                currentCommand = 'S'
                udpSender.stopAutonomous()
                loadYoloModel(DetectorModel.byId(modelId))
                updateStatus()
            },
        ).also { it.start() }
    }

    private fun loadYoloModel(model: DetectorModel) {
        synchronized(detectorLock) {
            currentModel = model
            yoloLoadError = ""
            yoloDetector?.close()
            yoloDetector = null
            aiFps = 0f
            try {
                yoloDetector = YoloTfliteDetector(this, model.assetName)
            } catch (error: Exception) {
                yoloLoadError = error.message ?: error.javaClass.simpleName
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
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { useCase ->
                    useCase.setAnalyzer(cameraExecutor, ::analyzeFrame)
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: androidx.camera.core.ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastInferenceAt < MIN_INFERENCE_INTERVAL_MS || !processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        lastInferenceAt = now

        try {
            val bitmap = image.toBitmapRotated()
            val detections: List<Detection>
            var elapsedMs = 0L
            synchronized(detectorLock) {
                val yolo = yoloDetector
                if (yolo != null) {
                    val result = yolo.detect(bitmap)
                    detections = result.first
                    elapsedMs = result.second
                } else {
                    detections = emptyList()
                }
            }
            val marker: GoalMarker? = arucoDetector?.detect(bitmap)
            if (elapsedMs > 0L) {
                val instantFps = 1000f / elapsedMs.coerceAtLeast(1L)
                aiFps = if (aiFps <= 0f) instantFps else aiFps * 0.85f + instantFps * 0.15f
            }

            val command = decisionEngine.decide(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                detections = detections,
                marker = marker,
                autonomous = autonomous,
            )
            currentCommand = command
            udpSender.setCommand(command)

            val visionResult = VisionResult(
                frameWidth = bitmap.width,
                frameHeight = bitmap.height,
                detections = detections,
                marker = marker,
                aiFps = aiFps,
                command = command,
                reason = decisionEngine.lastReason,
                autonomous = autonomous,
            )
            lastResult = visionResult
            bitmap.recycle()

            runOnUiThread {
                overlayView.update(visionResult)
                updateStatus()
            }
        } catch (error: Exception) {
            yoloLoadError = yoloLoadError.ifBlank { error.message ?: error.javaClass.simpleName }
            runOnUiThread { updateStatus() }
        } finally {
            processing.set(false)
            image.close()
        }
    }

    private fun updateStatus() {
        val result = lastResult
        val yoloState = if (yoloDetector == null) "YOLO lỗi: ${yoloLoadError.ifBlank { "missing ${currentModel.assetName}" }}" else "YOLO OK"
        val arucoState = if (arucoDetector == null) "ArUco lỗi: $arucoLoadError" else "ArUco OK"
        val udpState = udpSender.lastError.ifBlank { "OK" }
        statusText.text = buildString {
            appendLine(if (autonomous) "AUTO: ON" else "AUTO: OFF")
            appendLine("Model: ${currentModel.displayName}")
            appendLine("CMD: $currentCommand  FPS: ${String.format(Locale.US, "%.1f", aiFps)}")
            appendLine("Reason: ${result?.reason ?: decisionEngine.lastReason}")
            appendLine("Objects: ${result?.detections?.size ?: 0}  Marker: ${result?.marker?.id ?: "-"}")
            appendLine("UDP: $udpState")
            appendLine(yoloState)
            append(arucoState)
        }
        val url = webServer?.url ?: "starting..."
        webText.text = "Kết nối WiFi Mecanum-Car rồi mở trên laptop:\n$url"
    }

    companion object {
        private const val MIN_INFERENCE_INTERVAL_MS = 16L
    }
}
