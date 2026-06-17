package com.mecanum.autocar.control

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException

class UdpTelemetryReceiver(
    private val port: Int,
    private val onTelemetry: (distanceCm: Float?, guardActive: Boolean) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = Thread(::runLoop, "udp-telemetry-rx").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        socket?.close()
        worker?.interrupt()
        worker = null
        socket = null
    }

    private fun runLoop() {
        try {
            DatagramSocket(port).use { datagramSocket ->
                socket = datagramSocket
                datagramSocket.soTimeout = 1000
                val buffer = ByteArray(128)
                while (running) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        datagramSocket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8).trim()
                        parseTelemetry(text)?.let { (distance, guard) -> onTelemetry(distance, guard) }
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (error: SocketException) {
                        if (running) Log.w(TAG, "telemetry socket error", error)
                    } catch (error: Exception) {
                        Log.w(TAG, "telemetry parse error", error)
                    }
                }
            }
        } catch (error: Exception) {
            if (running) Log.w(TAG, "telemetry receiver failed", error)
        } finally {
            socket = null
        }
    }

    private fun parseTelemetry(text: String): Pair<Float?, Boolean>? {
        val parts = text.split(',')
        if (parts.size < 3 || parts[0] != "T") return null
        val distance = parts[1].toFloatOrNull()
        val guard = parts[2] == "1"
        return (distance?.takeIf { it >= 0f }) to guard
    }

    companion object {
        private const val TAG = "UdpTelemetryReceiver"
    }
}
