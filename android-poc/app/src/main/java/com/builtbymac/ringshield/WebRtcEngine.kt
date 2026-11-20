package com.builtbymac.ringshield

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

data class PrewarmedOffer(
    val peerConnection: PeerConnection,
    val sdpOffer: String,
    val createdAtMonoMs: Long,
    val videoTrackDeferred: CompletableDeferred<VideoTrack>,
    val iceConnectedDeferred: CompletableDeferred<Long>,
)

/**
 * Manages a single shared EglBase + PeerConnectionFactory and fast WHEP PeerConnections.
 *
 * Audio playout is explicitly disabled/muted so the overlay never interrupts foreground
 * media playback on the Nvidia Shield Pro.
 */
class WebRtcEngine(
    private val context: Context,
    private val softwareDecoder: Boolean,
    private val minPlayoutDelayMs: Int = 350,
) {
    val eglBase: EglBase = EglBase.create()
    private val audioDeviceModule = JavaAudioDeviceModule.builder(context)
        .setUseHardwareAcousticEchoCanceler(false)
        .setUseHardwareNoiseSuppressor(false)
        .createAudioDeviceModule()
        .apply {
            setSpeakerMute(true)
            setMicrophoneMute(true)
        }

    private val factory: PeerConnectionFactory

    init {
        val fieldTrials = if (minPlayoutDelayMs > 0) {
            val maxMs = maxOf(minPlayoutDelayMs + 500, 1000)
            "WebRTC-ForcePlayoutDelay/min_ms:$minPlayoutDelayMs,max_ms:$maxMs/"
        } else {
            ""
        }
        Log.i(TAG, "Initializing PeerConnectionFactory (fieldTrials='$fieldTrials')")
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials(fieldTrials)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val decoderFactory = if (softwareDecoder) {
            Log.i(TAG, "Using SoftwareVideoDecoderFactory (fallback mode)")
            SoftwareVideoDecoderFactory()
        } else {
            Log.i(TAG, "Using DefaultVideoDecoderFactory (Tegra X1+ HW MediaCodec preferred)")
            DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        }
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    /**
     * Creates a PeerConnection, generates an SDP offer, and performs bounded fast ICE
     * gathering (resolves early once a server-reflexive `srflx` candidate is gathered,
     * or at `iceGatherBudgetMs`, rather than waiting 3 seconds for ICE completion).
     */
    suspend fun prepareOffer(cfg: PocConfig): PrewarmedOffer {
        val videoTrackDeferred = CompletableDeferred<VideoTrack>()
        val iceConnectedDeferred = CompletableDeferred<Long>()
        val srflxOrComplete = CompletableDeferred<Unit>()
        val expectedSrflx = if (cfg.includeAudioTransceiver) 2 else 1
        val srflxCount = java.util.concurrent.atomic.AtomicInteger(0)

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.kinesisvideo.us-east-1.amazonaws.com:443").createIceServer(),
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Match Amazon's official ring-api-helloworld (BALANCED, not MAXBUNDLE)
            bundlePolicy = PeerConnection.BundlePolicy.BALANCED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        val pc = factory.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "Gathered ICE candidate: ${candidate.sdp}")
                    if (candidate.sdp.contains(" typ srflx ")) {
                        if (srflxCount.incrementAndGet() >= expectedSrflx) {
                            srflxOrComplete.complete(Unit)
                        }
                    }
                }

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Log.d(TAG, "ICE gathering -> $state")
                    if (state == PeerConnection.IceGatheringState.COMPLETE) {
                        srflxOrComplete.complete(Unit)
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.i(TAG, "ICE connection -> $state")
                    if (state == PeerConnection.IceConnectionState.CONNECTED ||
                        state == PeerConnection.IceConnectionState.COMPLETED
                    ) {
                        iceConnectedDeferred.complete(SystemClock.elapsedRealtime())
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    val track = transceiver.receiver.track()
                    Log.i(TAG, "onTrack: kind=${track?.kind()} id=${track?.id()} enabled=${track?.enabled()}")
                    when (track) {
                        is VideoTrack -> {
                            track.setEnabled(true)
                            videoTrackDeferred.complete(track)
                        }
                        is AudioTrack -> {
                            // Never render audio to avoid interrupting YouTube / streaming playback
                            track.setEnabled(false)
                        }
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(channel: DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
            }
        ) ?: error("PeerConnectionFactory.createPeerConnection returned null")

        if (cfg.includeAudioTransceiver) {
            // Match official ring-api-helloworld: audio sendrecv (mic & speaker muted at ADM)
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            )
        }
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )

        val offer = createOfferSuspend(pc)
        setLocalDescriptionSuspend(pc, offer)

        // Wait for ICE gathering to finish (bounded by iceGatherBudgetMs) so all host + srflx
        // candidates are embedded in the single WHEP POST offer.
        withTimeoutOrNull(cfg.iceGatherBudgetMs) { srflxOrComplete.await() }

        val gatheredSdp = pc.localDescription?.description ?: offer.description
        val candCount = gatheredSdp.lineSequence().count { it.startsWith("a=candidate:") }
        Log.i(TAG, "Offer ready with $candCount ICE candidate(s)")
        val finalSdp = if (cfg.stripTwcc) stripTwccFromSdp(gatheredSdp) else gatheredSdp
        return PrewarmedOffer(
            peerConnection = pc,
            sdpOffer = finalSdp,
            createdAtMonoMs = SystemClock.elapsedRealtime(),
            videoTrackDeferred = videoTrackDeferred,
            iceConnectedDeferred = iceConnectedDeferred,
        )
    }

    suspend fun applyAnswer(offer: PrewarmedOffer, sdpAnswer: String, stripTwcc: Boolean) {
        val cleaned = if (stripTwcc) stripTwccFromSdp(sdpAnswer) else sdpAnswer
        setRemoteDescriptionSuspend(
            offer.peerConnection,
            SessionDescription(SessionDescription.Type.ANSWER, cleaned)
        )
    }

    fun attachVideoSink(track: VideoTrack, sink: VideoSink) {
        track.addSink(sink)
    }

    fun closeOffer(offer: PrewarmedOffer?) {
        if (offer == null) return
        runCatching { offer.peerConnection.close() }
        runCatching { offer.peerConnection.dispose() }
    }

    fun release() {
        runCatching { factory.dispose() }
        runCatching { audioDeviceModule.release() }
        runCatching { eglBase.release() }
    }

    private suspend fun createOfferSuspend(pc: PeerConnection): SessionDescription {
        val deferred = CompletableDeferred<SessionDescription>()
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                deferred.complete(desc)
            }
            override fun onCreateFailure(err: String?) {
                deferred.completeExceptionally(IllegalStateException("createOffer failed: $err"))
            }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(err: String?) = Unit
        }, MediaConstraints())
        return deferred.await()
    }

    private suspend fun setLocalDescriptionSuspend(pc: PeerConnection, desc: SessionDescription) {
        val deferred = CompletableDeferred<Unit>()
        pc.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() { deferred.complete(Unit) }
            override fun onSetFailure(err: String?) {
                deferred.completeExceptionally(IllegalStateException("setLocalDescription failed: $err"))
            }
            override fun onCreateSuccess(d: SessionDescription) = Unit
            override fun onCreateFailure(err: String?) = Unit
        }, desc)
        deferred.await()
    }

    private suspend fun setRemoteDescriptionSuspend(pc: PeerConnection, desc: SessionDescription) {
        val deferred = CompletableDeferred<Unit>()
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() { deferred.complete(Unit) }
            override fun onSetFailure(err: String?) {
                deferred.completeExceptionally(IllegalStateException("setRemoteDescription failed: $err"))
            }
            override fun onCreateSuccess(d: SessionDescription) = Unit
            override fun onCreateFailure(err: String?) = Unit
        }, desc)
        deferred.await()
    }

    companion object {
        private const val TAG = "WebRtcEngine"

        /**
         * Strips `transport-cc` RTCP feedback & header extension lines so Ring's send-side
         * bandwidth estimator does not probe resolutions up/down on stream startup.
         */
        fun stripTwccFromSdp(sdp: String): String =
            sdp.lineSequence()
                .map { it.trimEnd('\r') }
                .filter { it.isNotEmpty() }
                .filterNot { line -> line.contains("rtcp-fb:") && line.contains("transport-cc") }
                .joinToString("\r\n") + "\r\n"
    }
}
