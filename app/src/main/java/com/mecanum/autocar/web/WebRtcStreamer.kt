package com.mecanum.autocar.web

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.YuvHelper
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class WebRtcStreamer(
    private val context: Context,
    private val server: WebControlServer,
    private val streamWidth: Int = 480,
    private val streamHeight: Int = 360,
    private val targetFps: Int = 12,
) {
    private val eglBase = EglBase.create()
    private val latestBitmap = AtomicBitmapRef()
    private val frameCounter = AtomicLong(0)
    private val initialized = AtomicBoolean(false)
    private val peers = ConcurrentHashMap<String, PeerState>()

    @Volatile private var peerConnectionFactory: PeerConnectionFactory? = null
    @Volatile private var videoSource: VideoSource? = null
    @Volatile private var videoTrack: VideoTrack? = null
    @Volatile private var audioSource: AudioSource? = null
    @Volatile private var capturerObserver: org.webrtc.CapturerObserver? = null
    @Volatile private var framePusherThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        initWebRtc()
        startFrameLoop()
    }

    fun stop() {
        running = false
        framePusherThread?.interrupt()
        framePusherThread = null
        peers.values.forEach { it.close() }
        peers.clear()
        try {
            videoTrack?.dispose()
            videoSource?.dispose()
            audioSource?.dispose()
            peerConnectionFactory?.dispose()
            eglBase.release()
        } catch (error: Throwable) {
            Log.w(TAG, "stop dispose failed", error)
        } finally {
            videoTrack = null
            videoSource = null
            audioSource = null
            peerConnectionFactory = null
            capturerObserver = null
        }
    }

    fun pushFrame(bitmap: Bitmap) {
        if (!running) return
        if (peers.isEmpty()) return
        latestBitmap.set(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        frameCounter.incrementAndGet()
    }

    fun handleSignal(clientId: String, raw: String) {
        val json = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            return
        }
        when (json.optString("type")) {
            "webrtc_offer" -> handleOffer(clientId, json.optString("sdp"))
            "webrtc_ice" -> handleRemoteIce(clientId, json)
            "webrtc_disconnect" -> removePeer(clientId)
        }
    }

    private fun initWebRtc() {
        if (!initialized.compareAndSet(false, true)) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val options = PeerConnectionFactory.Options()
        val encoderFactory = try {
            DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        } catch (_: Throwable) {
            SoftwareVideoEncoderFactory()
        }
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        videoSource = peerConnectionFactory?.createVideoSource(false)
        capturerObserver = videoSource?.capturerObserver
        videoTrack = peerConnectionFactory?.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        audioSource = peerConnectionFactory?.createAudioSource(MediaConstraints())
        Log.i(TAG, "WebRTC initialized")
    }

    private fun handleOffer(clientId: String, sdp: String?) {
        val factory = peerConnectionFactory ?: return
        val track = videoTrack ?: return
        val offerSdp = sdp?.takeIf { it.isNotBlank() } ?: return
        val peer = peers.computeIfAbsent(clientId) { createPeerState(clientId, factory, track) }
        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peer.connection.setRemoteDescription(SimpleSdpObserver(
            onSetSuccess = {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                }
                peer.connection.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription?) {
                        if (answer == null) return
                        peer.connection.setLocalDescription(SimpleSdpObserver(
                            onSetSuccess = {
                                val payload = JSONObject()
                                    .put("type", "webrtc_answer")
                                    .put("sdp", answer.description)
                                server.sendWebRtcSignal(clientId, payload.toString())
                            },
                            onFailure = { error ->
                                Log.e(TAG, "setLocalDescription failed for $clientId: $error")
                            }
                        ), answer)
                    }

                    override fun onCreateFailure(error: String?) {
                        Log.e(TAG, "createAnswer failed for $clientId: $error")
                    }
                }, constraints)
            },
            onFailure = { error ->
                Log.e(TAG, "setRemoteDescription failed for $clientId: $error")
            }
        ), offer)
    }

    private fun handleRemoteIce(clientId: String, json: JSONObject) {
        val candidate = json.optString("candidate")
        val sdpMid = json.optString("sdpMid")
        val sdpMLineIndex = json.optInt("sdpMLineIndex", -1)
        if (candidate.isBlank() || sdpMid.isBlank() || sdpMLineIndex < 0) return
        peers[clientId]?.connection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    private fun createPeerState(
        clientId: String,
        factory: PeerConnectionFactory,
        track: VideoTrack,
    ): PeerState {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                sendRtcState(clientId, newState?.name ?: "unknown")
                if (newState == PeerConnection.IceConnectionState.FAILED ||
                    newState == PeerConnection.IceConnectionState.CLOSED ||
                    newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    removePeer(clientId)
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val payload = JSONObject()
                    .put("type", "webrtc_ice")
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                server.sendWebRtcSignal(clientId, payload.toString())
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {}
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                sendRtcState(clientId, newState?.name ?: "unknown")
            }
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {}
            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
        }
        val connection = factory.createPeerConnection(rtcConfig, observer)
            ?: error("Cannot create PeerConnection")
        val sender = connection.addTrack(track, listOf(STREAM_ID))
        requireNotNull(sender) { "Cannot add video track" }
        return PeerState(clientId, connection)
    }

    private fun startFrameLoop() {
        framePusherThread = Thread({
            var lastFrameId = -1L
            val minFrameDeltaMs = (1000L / targetFps.coerceAtLeast(1))
            var lastSentAt = 0L
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    val currentFrameId = frameCounter.get()
                    val now = SystemClock.elapsedRealtime()
                    if (currentFrameId != lastFrameId && peers.isNotEmpty() && now - lastSentAt >= minFrameDeltaMs) {
                        val bitmap = latestBitmap.getAndClear()
                        if (bitmap != null) {
                            pushBitmapToVideoSource(bitmap, now)
                            bitmap.recycle()
                            lastFrameId = currentFrameId
                            lastSentAt = now
                        }
                    }
                    Thread.sleep(5L)
                } catch (_: InterruptedException) {
                    break
                } catch (error: Throwable) {
                    Log.w(TAG, "frame loop error", error)
                    try { Thread.sleep(30L) } catch (_: InterruptedException) { break }
                }
            }
        }, "webrtc-frame-pusher").also { it.isDaemon = true; it.start() }
    }

    private fun pushBitmapToVideoSource(bitmap: Bitmap, elapsedRealtimeMs: Long) {
        val observer = capturerObserver ?: return
        val scaled = if (bitmap.width != streamWidth || bitmap.height != streamHeight) {
            Bitmap.createScaledBitmap(bitmap, streamWidth, streamHeight, true)
        } else {
            bitmap
        }
        val capacity = scaled.width * scaled.height * 4
        val argb = ByteBuffer.allocateDirect(capacity)
        scaled.copyPixelsToBuffer(argb)
        argb.rewind()
        val i420 = JavaI420Buffer.allocate(scaled.width, scaled.height)
        YuvHelper.ABGRToI420(
            argb,
            scaled.width * 4,
            i420.dataY,
            i420.strideY,
            i420.dataU,
            i420.strideU,
            i420.dataV,
            i420.strideV,
            scaled.width,
            scaled.height,
        )
        val frame = VideoFrame(i420, 0, elapsedRealtimeMs * 1_000_000L)
        try {
            observer.onFrameCaptured(frame)
        } finally {
            frame.release()
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun removePeer(clientId: String) {
        peers.remove(clientId)?.close()
    }

    private fun sendRtcState(clientId: String, value: String) {
        server.sendWebRtcSignal(clientId, JSONObject().put("type", "rtc_state").put("value", value).toString())
    }

    private class PeerState(
        val clientId: String,
        val connection: PeerConnection,
    ) {
        fun close() {
            try {
                connection.dispose()
            } catch (_: Throwable) {
            }
        }
    }

    private open class SimpleSdpObserver(
        private val onSetSuccess: (() -> Unit)? = null,
        private val onFailure: ((String?) -> Unit)? = null,
    ) : org.webrtc.SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
        override fun onSetSuccess() { onSetSuccess?.invoke() }
        override fun onCreateFailure(error: String?) { onFailure?.invoke(error) }
        override fun onSetFailure(error: String?) { onFailure?.invoke(error) }
    }

    private class AtomicBitmapRef {
        @Volatile private var bitmap: Bitmap? = null

        fun set(next: Bitmap) {
            val old = bitmap
            bitmap = next
            old?.recycle()
        }

        fun getAndClear(): Bitmap? {
            val current = bitmap
            bitmap = null
            return current
        }
    }

    companion object {
        private const val TAG = "WebRtcStreamer"
        private const val VIDEO_TRACK_ID = "ARDAMSv0"
        private const val STREAM_ID = "ARDAMS"
    }
}
