package com.mecanum.autocar.web

import android.content.Context
import android.util.Log
import com.mecanum.autocar.control.StationController
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Trang thai gui ra dashboard. Tat ca truong hien thi duoc serialize UTF-8.
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
    val frontDistanceCm: Float?,
    val frontTelemetryFresh: Boolean,
    val guardActive: Boolean,
)

class WebControlServer(
    private val context: Context,
    private val port: Int = 8088,
    private val controller: StationController,
    private val statusProvider: () -> StationStatus,
    private val onAutoOn: () -> Unit,
    private val onAutoOff: () -> Unit,
    private val onStopVehicle: () -> Unit,
    private val onModelSelect: (String) -> Unit,
    private val onWebRtcSignal: (clientId: String, message: String) -> Unit = { _, _ -> },
    private val maxClients: Int = 4,
) : NanoWSD(port) {

    private val clients = CopyOnWriteArraySet<DashboardSocket>()
    @Volatile private var latestMjpegFrameProvider: (() -> ByteArray?)? = null
    @Volatile private var mjpegVersionProvider: (() -> Long)? = null
    private val clientIds = AtomicInteger(0)
    private var cachedStatusJson: String = "{}"

    @Volatile private var running = false
    private var statusThread: Thread? = null

    val url: String
        get() = "http://${localIpv4Address() ?: "0.0.0.0"}:$port"

    fun startServer() {
        if (running) return
        running = true
        cachedStatusJson = buildStatusJson(statusProvider())
        start(SOCKET_READ_TIMEOUT, false)
        statusThread = Thread(::statusLoop, "web-status").also { it.isDaemon = true; it.start() }
    }

    fun stopServer() {
        running = false
        statusThread?.interrupt()
        statusThread = null
        try { stop() } catch (_: Exception) {}
        clients.clear()
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = DashboardSocket(handshake)

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return try {
            when {
                uri == "/" || uri == "/index.html" -> serveDashboard()
                uri.startsWith("/stream.mjpeg") -> serveMjpeg(session)
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


    fun setMjpegProvider(latestFrameProvider: () -> ByteArray?, versionProvider: () -> Long) {
        latestMjpegFrameProvider = latestFrameProvider
        mjpegVersionProvider = versionProvider
    }

    fun sendWebRtcSignal(clientId: String, message: String) {
        clients.firstOrNull { it.id == clientId }?.sendText(message)
    }


    private fun serveMjpeg(session: IHTTPSession): Response {
        val provider = latestMjpegFrameProvider
        if (provider == null) {
            return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "stream unavailable")
        }
        val boundary = "frame"
        val stream = object : java.io.PipedInputStream(256 * 1024) {}
        val output = java.io.PipedOutputStream(stream)
        Thread({
            var lastVersion = -1L
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val version = mjpegVersionProvider?.invoke() ?: 0L
                    val frame = provider.invoke()
                    if (frame != null && version != lastVersion) {
                        output.write(("--" + boundary + "\r\n").toByteArray(Charsets.US_ASCII))
                        output.write("Content-Type: image/jpeg\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(("Content-Length: " + frame.size + "\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.write(frame)
                        output.write("\r\n".toByteArray(Charsets.US_ASCII))
                        output.flush()
                        lastVersion = version
                    }
                    Thread.sleep(80L)
                }
            } catch (_: Exception) {
            } finally {
                try { output.close() } catch (_: Exception) {}
            }
        }, "mjpeg-stream").also { it.isDaemon = true; it.start() }

        return NanoHTTPD.newChunkedResponse(
            Response.Status.OK,
            "multipart/x-mixed-replace; boundary=" + boundary,
            stream,
        ).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Pragma", "no-cache")
            addHeader("Connection", "close")
        }
    }

    private fun serveDashboard(): Response {
        val html = context.assets.open("dashboard.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun okResponse(): Response = jsonResponse("{\"ok\":true}")

    private fun jsonResponse(json: String): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun statusLoop() {
        while (running) {
            try {
                if (clients.isNotEmpty()) {
                    cachedStatusJson = buildStatusJson(statusProvider())
                    val json = "{\"type\":\"status\",\"payload\":" + cachedStatusJson + "}"
                    for (client in clients) client.sendText(json)
                }
            } catch (error: Exception) {
                Log.w(TAG, "status error", error)
            }
            try {
                Thread.sleep(200L)
            } catch (_: InterruptedException) {
                break
            }
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
        sb.append("\"frontDistanceCm\":").append(s.frontDistanceCm?.let { String.format(Locale.US, "%.1f", it) } ?: "null").append(",")
        sb.append("\"frontTelemetryFresh\":").append(s.frontTelemetryFresh).append(",")
        sb.append("\"guardActive\":").append(s.guardActive).append(",")
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

        override fun onOpen() {
            if (clients.size >= maxClients) {
                try { close(WebSocketFrame.CloseCode.PolicyViolation, "Qua nhieu client", false) } catch (_: IOException) {}
                return
            }
            clients.add(this)
            sendText("{\"type\":\"hello\",\"clientId\":\"$id\"}")
            cachedStatusJson = buildStatusJson(statusProvider())
            sendText("{\"type\":\"status\",\"payload\":" + cachedStatusJson + "}")
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            clients.remove(this)
            controller.releaseManual(id)
            onWebRtcSignal(id, "{\"type\":\"webrtc_disconnect\"}")
        }

        override fun onMessage(message: WebSocketFrame) {
            val text = message.textPayload ?: return
            handleClientMessage(this, text)
        }

        override fun onPong(pong: WebSocketFrame) {}
        override fun onException(exception: IOException) { Log.w(TAG, "ws exception", exception) }

        fun sendText(text: String) {
            try { send(text) } catch (_: Exception) { clients.remove(this) }
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
            "webrtc_offer", "webrtc_answer", "webrtc_ice" -> onWebRtcSignal(socket.id, text)
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
            val addresses = NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress }
                .filterNotNull()
                .toList()
            return addresses.firstOrNull { it.startsWith("192.168.4.") }
                ?: addresses.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
                ?: addresses.firstOrNull()
        }
    }
}
