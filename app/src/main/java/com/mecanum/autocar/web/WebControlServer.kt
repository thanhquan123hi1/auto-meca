package com.mecanum.autocar.web

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.Executors

data class StationStatus(
    val autonomous: Boolean,
    val command: Char,
    val reason: String,
    val modelId: String,
    val modelName: String,
    val modelOptions: List<Pair<String, String>>,
    val aiFps: Float,
    val udpError: String,
)

class WebControlServer(
    private val port: Int = 8088,
    private val statusProvider: () -> StationStatus,
    private val onStart: () -> Unit,
    private val onStop: () -> Unit,
    private val onModelSelect: (String) -> Unit,
) {
    @Volatile private var running = false
    private val executor = Executors.newSingleThreadExecutor()
    private var serverSocket: ServerSocket? = null

    val url: String
        get() = "http://${localIpv4Address() ?: "0.0.0.0"}:$port"

    fun start() {
        if (running) return
        running = true
        executor.execute(::serveLoop)
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        executor.shutdownNow()
    }

    private fun serveLoop() {
        try {
            ServerSocket(port).use { server ->
                serverSocket = server
                while (running) {
                    val client = server.accept()
                    handleClient(client)
                }
            }
        } catch (error: Exception) {
            if (running) Log.w(TAG, "Web server failed", error)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            when {
                path.startsWith("/start") -> {
                    onStart()
                    respondRedirect(client)
                }
                path.startsWith("/stop") -> {
                    onStop()
                    respondRedirect(client)
                }
                path.startsWith("/model") -> {
                    val modelId = path.substringAfter("id=", "").substringBefore("&")
                    if (modelId.isNotBlank()) onModelSelect(modelId)
                    respondRedirect(client)
                }
                path.startsWith("/status.json") -> respondJson(client)
                else -> respondHtml(client)
            }
        }
    }

    private fun respondRedirect(socket: Socket) {
        PrintWriter(socket.getOutputStream()).use { out ->
            out.print("HTTP/1.1 303 See Other\r\n")
            out.print("Location: /\r\n")
            out.print("Connection: close\r\n\r\n")
        }
    }

    private fun respondJson(socket: Socket) {
        val status = statusProvider()
        val body = """
            {"autonomous":${status.autonomous},"command":"${status.command}","reason":"${jsonEscape(status.reason)}","model":"${jsonEscape(status.modelId)}","aiFps":${String.format(Locale.US, "%.1f", status.aiFps)},"udpError":"${jsonEscape(status.udpError)}"}
        """.trimIndent()
        respond(socket, "application/json; charset=utf-8", body)
    }

    private fun respondHtml(socket: Socket) {
        val status = statusProvider()
        val state = if (status.autonomous) "ĐANG CHẠY" else "ĐANG DỪNG"
        val body = """
            <!doctype html>
            <html lang="vi">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta http-equiv="refresh" content="1">
              <title>Mecanum Vision Station</title>
              <style>
                body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;margin:0;background:#0b1014;color:#edf7f4}
                main{max-width:720px;margin:40px auto;padding:0 20px}
                .panel{background:#151d23;border:1px solid #29343c;border-radius:8px;padding:20px}
                h1{font-size:24px;margin:0 0 16px}
                .grid{display:grid;grid-template-columns:160px 1fr;gap:8px 16px;margin:18px 0}
                a{display:inline-block;text-decoration:none;color:#06100d;background:#57d99a;padding:12px 18px;border-radius:6px;font-weight:700;margin-right:10px}
                a.stop{background:#ff6b6b;color:#170606}
                a.model{background:#26323a;color:#edf7f4;border:1px solid #40515c;margin-top:8px}
                a.model.active{background:#8ee6bd;color:#06100d;border-color:#8ee6bd}
                code{background:#0b1014;padding:2px 6px;border-radius:4px}
              </style>
            </head>
            <body>
              <main>
                <section class="panel">
                  <h1>Mecanum Vision Station</h1>
                  <div class="grid">
                    <div>Trạng thái</div><strong>$state</strong>
                    <div>Model</div><code>${htmlEscape(status.modelName)}</code>
                    <div>Lệnh hiện tại</div><code>${status.command}</code>
                    <div>Lý do</div><code>${htmlEscape(status.reason)}</code>
                    <div>AI FPS</div><code>${String.format(Locale.US, "%.1f", status.aiFps)}</code>
                    <div>UDP lỗi</div><code>${htmlEscape(status.udpError).ifBlank { "không" }}</code>
                  </div>
                  <a href="/start">Start Autonomous</a>
                  <a class="stop" href="/stop">Stop</a>
                  <h2 style="font-size:18px;margin:24px 0 8px">Model</h2>
                  ${modelButtons(status)}
                </section>
              </main>
            </body>
            </html>
        """.trimIndent()
        respond(socket, "text/html; charset=utf-8", body)
    }

    private fun modelButtons(status: StationStatus): String =
        status.modelOptions.joinToString(" ") { (id, name) ->
            val active = if (id == status.modelId) " active" else ""
            """<a class="model$active" href="/model?id=${htmlEscape(id)}">${htmlEscape(name)}</a>"""
        }

    private fun respond(socket: Socket, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        PrintWriter(socket.getOutputStream()).use { out ->
            out.print("HTTP/1.1 200 OK\r\n")
            out.print("Content-Type: $contentType\r\n")
            out.print("Content-Length: ${bytes.size}\r\n")
            out.print("Connection: close\r\n\r\n")
            out.flush()
            socket.getOutputStream().write(bytes)
        }
    }

    private fun htmlEscape(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

    companion object {
        private const val TAG = "WebControlServer"

        fun localIpv4Address(): String? {
            return NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }
    }
}
