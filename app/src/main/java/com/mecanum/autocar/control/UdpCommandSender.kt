package com.mecanum.autocar.control

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class UdpCommandSender(
    private val host: String = "192.168.4.1",
    private val port: Int = 4210,
    private val intervalMs: Long = 100L,
) {
    @Volatile var active: Boolean = false
        private set
    @Volatile var lastCommand: Char = 'S'
        private set
    @Volatile var lastError: String = ""
        private set

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var task: ScheduledFuture<*>? = null

    fun start() {
        active = true
        if (task == null || task?.isCancelled == true || task?.isDone == true) {
            task = executor.scheduleAtFixedRate(::tick, 0L, intervalMs, TimeUnit.MILLISECONDS)
        }
    }

    fun setCommand(command: Char) {
        lastCommand = normalize(command)
    }

    fun stopAutonomous() {
        lastCommand = 'S'
        active = false
        repeat(3) { sendNow('S') }
    }

    fun shutdown() {
        stopAutonomous()
        task?.cancel(true)
        task = null
        executor.shutdownNow()
        socket?.close()
        socket = null
    }

    private fun tick() {
        if (!active) return
        sendNow(lastCommand)
    }

    private fun sendNow(command: Char) {
        try {
            val targetAddress = address ?: InetAddress.getByName(host).also { address = it }
            val datagramSocket = socket ?: DatagramSocket().also {
                it.broadcast = false
                socket = it
            }
            val payload = byteArrayOf(normalize(command).code.toByte())
            val packet = DatagramPacket(payload, payload.size, targetAddress, port)
            datagramSocket.send(packet)
            lastError = ""
        } catch (error: Exception) {
            lastError = error.message ?: error.javaClass.simpleName
            Log.w(TAG, "UDP send failed", error)
        }
    }

    private fun normalize(command: Char): Char {
        val normalized = command.uppercaseChar()
        return if (normalized in VALID_COMMANDS) normalized else 'S'
    }

    companion object {
        private const val TAG = "UdpCommandSender"
        private val VALID_COMMANDS = setOf('F', 'B', 'L', 'R', 'Q', 'E', 'G', 'H', 'J', 'K', 'S')
    }
}
