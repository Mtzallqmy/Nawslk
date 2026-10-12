package com.nawslk.app.calls

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Audio-only DTLS-SRTP transport. All operations/callbacks are serialized on main. */
class WebRtcEngine(
    context: Context,
    servers: List<PeerConnection.IceServer>,
    private val onIce: (IceCandidate) -> Unit,
    private val onState: (PeerConnection.PeerConnectionState) -> Unit
) : AutoCloseable {
    private val executor = ContextCompat.getMainExecutor(context)
    private val module: JavaAudioDeviceModule
    private val factory: PeerConnectionFactory
    private val source: AudioSource
    private val track: AudioTrack
    private val peer: PeerConnection
    private var closed = false
    private var remoteReady = false
    private val pendingIce = mutableListOf<IceCandidate>()

    init {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        require(servers.isNotEmpty()) { "TURN configuration required" }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
        module = JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
        source = factory.createAudioSource(MediaConstraints())
        track = factory.createAudioTrack("nawslk-audio", source)
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        peer = checkNotNull(factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) { executor.execute { if (!closed) onIce(candidate) } }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) { executor.execute { if (!closed) onState(state) } }
        }))
        peer.addTrack(track, listOf("nawslk"))
    }
    private suspend fun sdpOperation(operation: (SdpObserver) -> Unit): SessionDescription? =
        suspendCancellableCoroutine { continuation ->
            operation(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) { executor.execute { if (continuation.isActive) continuation.resume(sdp) } }
                override fun onSetSuccess() { executor.execute { if (continuation.isActive) continuation.resume(null) } }
                override fun onCreateFailure(message: String) { executor.execute { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("SDP creation failed")) } }
                override fun onSetFailure(message: String) { executor.execute { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("SDP negotiation failed")) } }
            })
        }
    suspend fun offer(): SessionDescription {
        val sdp = checkNotNull(sdpOperation { peer.createOffer(it, MediaConstraints()) })
        sdpOperation { peer.setLocalDescription(it, sdp) }
        return sdp
    }
    suspend fun answer(offer: String): SessionDescription {
        remote(SessionDescription.Type.OFFER, offer)
        val sdp = checkNotNull(sdpOperation { peer.createAnswer(it, MediaConstraints()) })
        sdpOperation { peer.setLocalDescription(it, sdp) }
        return sdp
    }
    suspend fun remote(type: SessionDescription.Type, sdp: String) {
        sdpOperation { peer.setRemoteDescription(it, SessionDescription(type, sdp)) }
        remoteReady = true
        pendingIce.forEach { check(peer.addIceCandidate(it)) }
        pendingIce.clear()
    }
    fun ice(candidate: IceCandidate) {
        check(!closed)
        if (remoteReady) check(peer.addIceCandidate(candidate)) else {
            check(pendingIce.size < 512)
            pendingIce.add(candidate)
        }
    }
    fun mute(value: Boolean) { if (!closed) track.setEnabled(!value) }
    override fun close() {
        if (closed) return
        closed = true
        runCatching { track.setEnabled(false) }
        runCatching { peer.close() }
        runCatching { peer.dispose() }
        runCatching { track.dispose() }
        runCatching { source.dispose() }
        runCatching { factory.dispose() }
        runCatching { module.release() }
        pendingIce.clear()
    }
}
