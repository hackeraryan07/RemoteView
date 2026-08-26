package com.example

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*
import org.webrtc.PeerConnection.IceServer
import org.webrtc.SessionDescription

class WebRTCClient(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val isHost: Boolean,
    private val mediaProjectionIntent: Intent?,
    private val viewerRenderer: SurfaceViewRenderer?
) {
    private val eglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory
    private val peerConnection: PeerConnection?
    private var videoCapturer: VideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    init {
        // Initialize WebRTC
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    Log.d("WebRTCClient", "Sending ICE candidate")
                    signalingClient.sendIceCandidate(it)
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

            override fun onAddStream(stream: MediaStream?) {
                Log.d("WebRTCClient", "Stream added: ${stream?.videoTracks?.size}")
                if (!isHost && stream?.videoTracks?.isNotEmpty() == true) {
                    val track = stream.videoTracks[0]
                    viewerRenderer?.let {
                        track.addSink(it)
                    }
                }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        })

        if (isHost && mediaProjectionIntent != null) {
            setupScreenCapturer(mediaProjectionIntent)
            peerConnection?.let { createOffer(it) }
        }
    }

    private fun setupScreenCapturer(intent: Intent) {
        videoCapturer = ScreenCapturerAndroid(intent, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.e("WebRTCClient", "User revoked screen capture permission")
            }
        })
        
        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.eglBaseContext)
        localVideoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)
        videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)
        
        // Start capture. Width, height, fps
        val displayMetrics = context.resources.displayMetrics
        var width = displayMetrics.widthPixels
        var height = displayMetrics.heightPixels
        
        // WebRTC hardware video encoders often crash if dimensions are not even numbers
        if (width % 2 != 0) width -= 1
        if (height % 2 != 0) height -= 1
        
        videoCapturer?.startCapture(width, height, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("100", localVideoSource)
        val stream = peerConnectionFactory.createLocalMediaStream("102")
        stream.addTrack(localVideoTrack)
        peerConnection?.addStream(stream)
    }

    private fun createOffer(pc: PeerConnection) {
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    pc.setLocalDescription(this, it)
                    signalingClient.sendOffer(it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    fun handleOffer(offer: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                createAnswer()
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, offer)
    }

    private fun createAnswer() {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    peerConnection.setLocalDescription(this, it)
                    signalingClient.sendAnswer(it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    fun handleAnswer(answer: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, answer)
    }

    fun handleIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun getEglBaseContext(): EglBase.Context = eglBase.eglBaseContext

    fun onDestroy() {
        try {
            videoCapturer?.stopCapture()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        videoCapturer?.dispose()
        localVideoSource?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnection?.close()
        peerConnectionFactory.dispose()
        eglBase.release()
    }
}
