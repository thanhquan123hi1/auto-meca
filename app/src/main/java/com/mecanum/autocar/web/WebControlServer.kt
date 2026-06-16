package com.mecanum.autocar.web

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mecanum.autocar.ai.VisionResult
import com.mecanum.autocar.control.StationController
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Trang thai gui ra dashboard. Tat ca cac truong duoc serialize sang JSON UTF-8.
 */
data class StationStatus(
    val autonomous: Boolean,
    val command: Char,
    val reason: String,
    val modelId: String,
    val modelName: String,
    val modelOptions: List<Pair<String, String>>,
    val aiFps: Float,
    val udpError: String,
    val markerDistanceCm: Float?,
    val markerId: Int?,
    val detectionCount: Int,
    val connectedWifiSsid: String,
    val yoloMs: Long,
    val arucoMs: Long,
    val pipelineMs: Long,
    val skippedFrames: Int,
    val markerSeenRatio: Float,
    val avgConfidence: Float,
    val skipRatio: Float,
    val commandChanges: Int,
)

/**
 * May chu tich hop trong app: phuc vu dashboard HTML, REST dieu khien va WebSocket /ws.
 * - HTTP: GET /, GET /status.json, POST /api/...
 * - WebSocket /ws: nhan lenh tay (giu/nha), gui status realtime va khung JPEG.
 *
 * Uu tien tai nguyen cho AI/dieu khien: chi sao chep va ma hoa JPEG khi co client xem.
 */
class WebControlServer(
    private val context: Context,
    private val port: Int = 8088,
    private val controller: StationController,
    private val statusProvider: () -> StationStatus,
    private val onAutoOn: () -> Unit,
    private val onAutoOff: () -> Unit,
    private val onStopVehicle: () -> Unit,
    private val onModelSelect: (String) -> Unit,
    private val targetStreamFps: Int = 8,
    private val jpegQuality: Int = 58,
    private val maxClients: Int = 4,
) : NanoWSD(port) {

    private val clients = CopyOnWriteArraySet<DashboardSocket>()
    private val clientIds = AtomicInteger(0)

    // Khung moi nhat (chi giu khi co client). Truy cap duoi khoa frameLock.
    private val frameLock = Any()
    private var latestRaw: Bitmap? = null
    private var latestResult: VisionResult? = null
    private var frameDirty = false
    private var cachedStatusJson: String = "{}"

    @Volatile private var streaming = false
    private var streamThread: Thread? = null
    private var statusThread: Thread? = null

    val url: String
        get() = "http://${localIpv4Address() ?: "0.0.0.0"}:$port"

    fun startServer() {
        if (streaming) return
        streaming = true
        cachedStatusJson = buildStatusJson(statusProvider())
        start(SOCKET_READ_TIMEOUT, false)
        streamThread = Thread(::streamLoop, "web-stream").also { it.isDaemon = true; it.start() }
        statusThread = Thread(::statusLoop, "web-status").also { it.isDaemon = true; it.start() }
    }

    fun stopServer() {
        streaming = false
        try {
            stop()
        } catch (_: Exception) {
        }
        clients.clear()
        synchronized(frameLock) {
            latestRaw?.recycle()
            latestRaw = null
            latestResult = null
            frameDirty = false
        }
    }

    /** Co client nao dang xem stream khong. */
    private fun hasViewers(): Boolean = clients.isNotEmpty()

    /**
     * MainActivity goi moi khung sau khi xu ly AI. Chi sao chep khi co nguoi xem
     * de khong lam cham pipeline AI. Bitmap nguon van thuoc ve nguoi goi.
     */
    fun submitFrame(bitmap: Bitmap, result: VisionResult) {
        if (!streaming || !hasViewers()) return
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        synchronized(frameLock) {
            latestRaw?.recycle()
            latestRaw = Bitmap.createScaledBitmap(copy, 480, 360, true).also {
                if (it !== copy) copy.recycle()
            }
            latestResult = result
            frameDirty = true
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return DashboardSocket(handshake)
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveDashboard()
                uri == "/status.json" -> jsonResponse(buildStatusJson(statusProvider()))
                uri.startsWith("/api/auto/on") -> { onAutoOn(); okResponse() }
                uri.startsWith("/api/auto/off") -> { onAutoOff(); okResponse() }
                uri.startsWith("/api/stop") -> { onStopVehicle(); okResponse() }
                uri.startsWith("/api/model") -> {
                    val id = session.parameters["id"]?.firstOrNull().orEmpty()
                    if (id.isNotBlank()) onModelSelect(id)
                    okResponse()
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404")
            }
        } catch (error: Exception) {
            Log.w(TAG, "serveHttp error", error)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error")
        }
    }

    private fun serveDashboard(): Response {
        val html = context.assets.open("dashboard.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        return response
    }

    private fun okResponse(): Response =
        jsonResponse("{\"ok\":true}")

    private fun jsonResponse(json: String): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun streamLoop() {
        val frameIntervalMs = (1000L / targetStreamFps.coerceIn(1, 30)).coerceAtLeast(1L)
        while (streaming) {
            val start = System.currentTimeMillis()
            try {
                broadcastFrame()
            } catch (error: Exception) {
                Log.w(TAG, "stream error", error)
            }
            val elapsed = System.currentTimeMillis() - start
            val sleep = frameIntervalMs - elapsed
            if (sleep > 0) Thread.sleep(sleep)
        }
    }

    private fun broadcastFrame() {
        if (!hasViewers()) return
        val raw: Bitmap
        val result: VisionResult?
        synchronized(frameLock) {
            if (!frameDirty || latestRaw == null) return
            raw = latestRaw ?: return
            result = latestResult
            latestRaw = null
            latestResult = null
            frameDirty = false
        }

        // Nhom client theo che do overlay de ma hoa nhieu nhat 2 lan moi khung.
        val wantOverlay = clients.any { it.overlayEnabled }
        val wantPlain = clients.any { !it.overlayEnabled }

        var overlayJpeg: ByteArray? = null
        var plainJpeg: ByteArray? = null
        if (wantOverlay) {
            val rendered = FrameOverlayRenderer.render(raw, result)
            overlayJpeg = encodeJpeg(rendered)
            rendered.recycle()
        }
        if (wantPlain) {
            plainJpeg = encodeJpeg(raw)
        }
        raw.recycle()

        for (client in clients) {
            val payload = if (client.overlayEnabled) overlayJpeg else plainJpeg
            if (payload != null) client.sendFrame(payload)
        }
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
        return out.toByteArray()
    }

    private fun statusLoop() {
        while (streaming) {
            try {
                if (hasViewers()) {
                    cachedStatusJson = buildStatusJson(statusProvider())
                    val json = "{\"type\":\"status\",\"payload\":" + cachedStatusJson + "}"
                    for (client in clients) client.sendText(json)
                }
            } catch (error: Exception) {
                Log.w(TAG, "status error", error)
            }
            Thread.sleep(200L)
        }
    }

    private fun buildStatusJson(s: StationStatus): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"autonomous\":").append(s.autonomous).append(",")
        sb.append("\"command\":\"").append(s.command).append("\",")
        sb.append("\"reason\":\"").append(jsonEscape(s.reason)).append("\",")
        sb.append("\"modelId\":\"").append(jsonEscape(s.modelId)).append("\",")
        sb.append("\"modelName\":\"").append(jsonEscape(s.modelName)).append("\",")
        sb.append("\"aiFps\":").append(String.format(Locale.US, "%.1f", s.aiFps)).append(",")
        sb.append("\"udpError\":\"").append(jsonEscape(s.udpError)).append("\",")
        sb.append("\"markerId\":").append(s.markerId?.toString() ?: "null").append(",")
        sb.append("\"markerDistanceCm\":").append(s.markerDistanceCm?.let { String.format(Locale.US, "%.1f", it) } ?: "null").append(",")
        sb.append("\"detectionCount\":").append(s.detectionCount).append(",")
        sb.append("\"connectedWifiSsid\":\"").append(jsonEscape(s.connectedWifiSsid)).append("\",")
        sb.append("\"yoloMs\":").append(s.yoloMs).append(",")
        sb.append("\"arucoMs\":").append(s.arucoMs).append(",")
        sb.append("\"pipelineMs\":").append(s.pipelineMs).append(",")
        sb.append("\"skippedFrames\":").append(s.skippedFrames).append(",")
        sb.append("\"markerSeenRatio\":").append(String.format(Locale.US, "%.3f", s.markerSeenRatio)).append(",")
        sb.append("\"avgConfidence\":").append(String.format(Locale.US, "%.3f", s.avgConfidence)).append(",")
        sb.append("\"skipRatio\":").append(String.format(Locale.US, "%.3f", s.skipRatio)).append(",")
        sb.append("\"commandChanges\":").append(s.commandChanges).append(",")
        sb.append("\"modelOptions\":[")
        s.modelOptions.forEachIndexed { index, (id, name) ->
            if (index > 0) sb.append(",")
            sb.append("[\"").append(jsonEscape(id)).append("\",\"").append(jsonEscape(name)).append("\"]")
        }
        sb.append("]}")
        return sb.toString()
    }

    inner class DashboardSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        val id: String = "client-${clientIds.incrementAndGet()}"
        @Volatile var overlayEnabled: Boolean = false

        override fun onOpen() {
            if (clients.size >= maxClients) {
                try {
                    close(WebSocketFrame.CloseCode.PolicyViolation, "Qua nhieu client", false)
                } catch (_: IOException) {
                }
                return
            }
            clients.add(this)
            try {
                cachedStatusJson = buildStatusJson(statusProvider())
                sendText("{\"type\":\"status\",\"payload\":" + cachedStatusJson + "}")
            } catch (_: Exception) {
            }
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            clients.remove(this)
            controller.releaseManual(id)
        }

        override fun onMessage(message: WebSocketFrame) {
            val text = message.textPayload ?: return
            handleClientMessage(this, text)
        }

        override fun onPong(pong: WebSocketFrame) {}

        override fun onException(exception: IOException) {
            Log.w(TAG, "ws exception", exception)
        }

        fun sendFrame(bytes: ByteArray) {
            try {
                send(bytes)
            } catch (_: Exception) {
                clients.remove(this)
            }
        }

        fun sendText(text: String) {
            try {
                send(text)
            } catch (_: Exception) {
                clients.remove(this)
            }
        }
    }

    private fun handleClientMessage(socket: DashboardSocket, text: String) {
        val type = extractJsonString(text, "type") ?: return
        when (type) {
            "manual_press" -> {
                val command = extractJsonString(text, "command")?.firstOrNull() ?: return
                controller.pressManual(command, socket.id, System.currentTimeMillis())
                cachedStatusJson = buildStatusJson(statusProvider())
            }
            "manual_release" -> {
                controller.releaseManual(socket.id)
                cachedStatusJson = buildStatusJson(statusProvider())
            }
            "overlay" -> {
                socket.overlayEnabled = text.contains("\"enabled\":true")
            }
        }
    }

    private fun extractJsonString(json: String, key: String): String? {
        val marker = "\"$key\""
        val keyIndex = json.indexOf(marker)
        if (keyIndex < 0) return null
        val colon = json.indexOf(':', keyIndex + marker.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '"') return null
        val end = json.indexOf('"', i + 1)
        if (end < 0) return null
        return json.substring(i + 1, end)
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    companion object {
        private const val TAG = "WebControlServer"
        private const val SOCKET_READ_TIMEOUT = 10000

        fun localIpv4Address(): String? {
            return NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }
    }
}