package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private var webRTCClient: WebRTCClient? = null
    private var signalingClient: SignalingClient? = null
    private var isHost = false

    // State for UI
    private var isSharing = mutableStateOf(false)
    private var isViewing = mutableStateOf(false)
    private var roomCode = mutableStateOf("")

    private var viewerRenderer: SurfaceViewRenderer? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startScreenCapture(result.data!!)
        } else {
            isSharing.value = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    @Composable
    fun MainScreen(modifier: Modifier = Modifier) {
        if (isViewing.value) {
            ViewerScreen(modifier)
        } else {
            LobbyScreen(modifier)
        }
    }

    @Composable
    fun LobbyScreen(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Screen Share",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(48.dp))

            if (isSharing.value) {
                Text(
                    text = "You are sharing your screen",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Room Code:",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = roomCode.value,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.height(32.dp))
                Button(
                    onClick = { stopSharing() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop Sharing")
                }
            } else {
                Button(
                    onClick = {
                        isHost = true
                        roomCode.value = Random.nextInt(1000, 9999).toString()
                        requestMediaProjection()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Start Sharing")
                }
                Spacer(modifier = Modifier.height(32.dp))
                Text(text = "OR", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(32.dp))

                OutlinedTextField(
                    value = roomCode.value,
                    onValueChange = { roomCode.value = it },
                    label = { Text("Enter Room Code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (roomCode.value.isNotBlank()) {
                            isHost = false
                            isViewing.value = true
                            startViewing()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Join as Viewer")
                }
            }
        }
    }

    @Composable
    fun ViewerScreen(modifier: Modifier = Modifier) {
        Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { context ->
                    SurfaceViewRenderer(context).apply {
                        viewerRenderer = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Button(
                onClick = { stopViewing() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Leave")
            }
        }
    }

    private fun requestMediaProjection() {
        isSharing.value = true
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startScreenCapture(mediaProjectionIntent: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setupSignalingAndWebRTC(mediaProjectionIntent)
    }

    private fun startViewing() {
        setupSignalingAndWebRTC(null)
    }

    private fun setupSignalingAndWebRTC(mediaProjectionIntent: Intent?) {
        val listener = object : SignalingClient.SignalingListener {
            override fun onOfferReceived(sessionDescription: SessionDescription) {
                if (!isHost) {
                    webRTCClient?.handleOffer(sessionDescription)
                }
            }
            override fun onAnswerReceived(sessionDescription: SessionDescription) {
                if (isHost) {
                    webRTCClient?.handleAnswer(sessionDescription)
                }
            }
            override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
                webRTCClient?.handleIceCandidate(iceCandidate)
            }
        }

        signalingClient = SignalingClient(roomCode.value, isHost, listener)
        webRTCClient = WebRTCClient(
            context = this,
            signalingClient = signalingClient!!,
            isHost = isHost,
            mediaProjectionIntent = mediaProjectionIntent,
            viewerRenderer = viewerRenderer
        )
        
        viewerRenderer?.init(webRTCClient?.getEglBaseContext(), null)
    }

    private fun stopSharing() {
        isSharing.value = false
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        stopService(serviceIntent)
        cleanup()
    }

    private fun stopViewing() {
        isViewing.value = false
        viewerRenderer?.release()
        viewerRenderer = null
        cleanup()
    }

    private fun cleanup() {
        webRTCClient?.onDestroy()
        webRTCClient = null
        signalingClient?.cleanUp()
        signalingClient = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSharing()
        stopViewing()
    }
}
