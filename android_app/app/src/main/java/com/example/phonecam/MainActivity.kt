package com.example.phonecam

import android.Manifest
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.phonecam.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    private var isConnected = false

    private val eglBase: EglBase by lazy { EglBase.create() }
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private lateinit var videoSource: VideoSource
    private lateinit var localVideoTrack: VideoTrack

    private var webSocket: WebSocket? = null

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 101
        private const val TAG = "PhoneCam"
        private const val VIDEO_RESOLUTION_WIDTH = 1920
        private const val VIDEO_RESOLUTION_HEIGHT = 1080
        private const val VIDEO_FPS = 30
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkCameraPermission()

        binding.startStopButton.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                connect()
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
        } else {
            initializeWebRTC()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeWebRTC()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initializeWebRTC() {
        // 1. Initialize PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        // 2. Setup local video preview
        binding.localVideoView.init(eglBase.eglBaseContext, null)
        binding.localVideoView.setMirror(true)
        binding.localVideoView.setEnableHardwareScaler(true)

        // 3. Create video capturer
        videoCapturer = createCameraCapturer()
        videoSource = peerConnectionFactory.createVideoSource(videoCapturer!!.isScreencast)

        // 4. Create local video track
        localVideoTrack = peerConnectionFactory.createVideoTrack("local_video_track", videoSource)
        videoCapturer?.initialize(SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext), this, videoSource.capturerObserver)
        videoCapturer?.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, VIDEO_FPS)
        localVideoTrack.addSink(binding.localVideoView)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(this)
        val deviceNames = enumerator.deviceNames

        // Try to find back facing camera
        for (deviceName in deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        // If no back camera, try front
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        // If no cameras, return null
        return null
    }

    private fun connect() {
        val serverUrl = binding.serverUrlInput.text.toString()
        val roomId = binding.roomIdInput.text.toString()

        if (serverUrl.isEmpty() || roomId.isEmpty()) {
            Toast.makeText(this, "Server URL and Room ID cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        binding.startStopButton.text = "Stop"
        binding.startStopButton.isEnabled = false
        isConnected = true

        // Initialize WebSocket
        val client = OkHttpClient()
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                // Join room
                val joinMsg = JSONObject().apply {
                    put("type", "join")
                    put("room", roomId)
                }
                webSocket.send(joinMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignalingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                disconnect()
            }
        })

        // Initialize PeerConnection
        setupPeerConnection()
    }

    private fun setupPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) { Log.d(TAG, "onSignalingChange: $newState") }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) { Log.d(TAG, "onIceConnectionChange: $newState") }
            override fun onIceConnectionReceivingChange(receiving: Boolean) { Log.d(TAG, "onIceConnectionReceivingChange: $receiving") }
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) { Log.d(TAG, "onIceGatheringChange: $newState") }
            override fun onAddStream(stream: MediaStream?) { Log.d(TAG, "onAddStream") }
            override fun onRemoveStream(stream: MediaStream?) { Log.d(TAG, "onRemoveStream") }
            override fun onDataChannel(dataChannel: DataChannel?) { Log.d(TAG, "onDataChannel") }
            override fun onRenegotiationNeeded() { Log.d(TAG, "onRenegotiationNeeded") }
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) { Log.d(TAG, "onAddTrack") }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) { Log.d(TAG, "onIceCandidatesRemoved") }

            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val candidateMsg = JSONObject().apply {
                        put("type", "candidate")
                        put("room", binding.roomIdInput.text.toString())
                        put("payload", JSONObject().apply {
                            put("candidate", it.sdp)
                            put("sdpMid", it.sdpMid)
                            put("sdpMLineIndex", it.sdpMLineIndex)
                        })
                    }
                    webSocket?.send(candidateMsg.toString())
                    Log.d(TAG, "Sent ICE candidate")
                }
            }
        })

        // Add local video track to the peer connection
        peerConnection?.addTrack(localVideoTrack)

        runOnUiThread {
            binding.startStopButton.isEnabled = true
        }
    }

    private fun handleSignalingMessage(message: String) {
        val json = JSONObject(message)
        when (json.getString("type")) {
            "offer" -> {
                Log.d(TAG, "Received offer")
                val payload = json.getJSONObject("payload")
                val offerSdp = payload.getString("sdp")
                peerConnection?.setRemoteDescription(
                    object : SdpObserver {
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onSetSuccess() {
                            Log.d(TAG, "Remote description set successfully")
                            createAnswer()
                        }
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) { Log.e(TAG, "Failed to set remote description: $p0") }
                    },
                    SessionDescription(SessionDescription.Type.OFFER, offerSdp)
                )
            }
            "candidate" -> {
                Log.d(TAG, "Received ICE candidate")
                val payload = json.getJSONObject("payload")
                val candidate = IceCandidate(
                    payload.getString("sdpMid"),
                    payload.getInt("sdpMLineIndex"),
                    payload.getString("candidate")
                )
                peerConnection?.addIceCandidate(candidate)
            }
        }
    }

    private fun createAnswer() {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                Log.d(TAG, "Answer created successfully")
                var sdp = sessionDescription.description
                sdp = preferH264(sdp) // Force H.264
                val answer = SessionDescription(sessionDescription.type, sdp)
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set successfully")
                        // After setting local description, send the answer to the peer
                        val answerMsg = JSONObject().apply {
                            put("type", "answer")
                            put("room", binding.roomIdInput.text.toString())
                            put("payload", JSONObject().apply {
                                put("type", "answer")
                                put("sdp", answer.description)
                            })
                        }
                        webSocket?.send(answerMsg.toString())
                        Log.d(TAG, "Sent answer")
                    }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) { Log.e(TAG, "Failed to set local description: $p0") }
                }, answer)
            }
            override fun onCreateFailure(p0: String?) { Log.e(TAG, "Failed to create answer: $p0") }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    private fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n").toMutableList()
        var mLineIndex = -1
        // Find video media line
        for (i in lines.indices) {
            if (lines[i].startsWith("m=video")) {
                mLineIndex = i
                break
            }
        }
        if (mLineIndex == -1) {
            Log.d(TAG, "No m=video line found in SDP")
            return sdp
        }

        // Find H264 payload type
        var h264PayloadType = ""
        val pattern = Regex("a=rtpmap:(\\d+) H264/90000")
        for (line in lines) {
            val match = pattern.find(line)
            if (match != null) {
                h264PayloadType = match.groupValues[1]
                break
            }
        }
        if (h264PayloadType.isEmpty()) {
            Log.d(TAG, "No H264 payload type found in SDP")
            return sdp
        }

        Log.d(TAG, "Found H264 payload type: $h264PayloadType")

        // Reorder codecs
        val mLineParts = lines[mLineIndex].split(" ").toMutableList()
        // m=video 9 UDP/TLS/RTP/SAVPF 100 101 102 96
        if (mLineParts.size <= 3) return sdp // Not enough parts to reorder

        val codecPayloads = mLineParts.subList(3, mLineParts.size)
        if (!codecPayloads.contains(h264PayloadType)) {
            Log.d(TAG, "H264 payload type not in m-line, cannot reorder.")
            return sdp
        }

        codecPayloads.removeAll { it == h264PayloadType }
        codecPayloads.add(0, h264PayloadType)

        Log.d(TAG, "Reordered codecs: $codecPayloads")

        lines[mLineIndex] = mLineParts.joinToString(" ")
        return lines.joinToString("\r\n")
    }

    private fun disconnect() {
        coroutineScope.launch {
            webSocket?.close(1000, "Client disconnect")
            peerConnection?.close()
            peerConnection = null
            webSocket = null

            withContext(Dispatchers.Main) {
                isConnected = false
                binding.startStopButton.text = "Start"
                binding.startStopButton.isEnabled = true
                Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localVideoTrack.removeSink(binding.localVideoView)
        binding.localVideoView.release()
        videoSource.dispose()
        peerConnectionFactory.dispose()
        eglBase.release()
        coroutineScope.cancel()
    }
}
