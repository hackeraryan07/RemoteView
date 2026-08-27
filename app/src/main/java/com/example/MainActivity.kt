package com.example

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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

import android.Manifest
import android.os.Build

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

    private var lastCrashMessage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            if (com.google.firebase.FirebaseApp.getApps(this).isEmpty()) {
                val options = com.google.firebase.FirebaseOptions.Builder()
                    .setProjectId("remoteview-d1adf")
                    .setApplicationId("1:943698175804:android:4fbc38a7b28c4d3f7f6039")
                    .setApiKey("AIzaSyBOAaq5tUqcQAwBjdsWUNqGPy2e6-2JTIA")
                    .setDatabaseUrl("https://remoteview-d1adf-default-rtdb.firebaseio.com")
                    .setStorageBucket("remoteview-d1adf.firebasestorage.app")
                    .build()
                com.google.firebase.FirebaseApp.initializeApp(this, options)
            }
        } catch (e: Exception) {
            android.util.Log.e("FirebaseInit", "Error initializing Firebase", e)
        }

        val sharedPrefs = getSharedPreferences("CrashLogs", Context.MODE_PRIVATE)
        val lastCrash = sharedPrefs.getString("last_crash", null)
        if (lastCrash != null) {
            lastCrashMessage.value = lastCrash
            sharedPrefs.edit().remove("last_crash").apply()
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            sharedPrefs.edit().putString("last_crash", throwable.stackTraceToString()).commit()
            defaultHandler?.uncaughtException(thread, throwable)
        }

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                    
                    lastCrashMessage.value?.let { crash ->
                        AlertDialog(
                            onDismissRequest = { lastCrashMessage.value = null },
                            title = { Text("App Crashed Last Time") },
                            text = { 
                                androidx.compose.foundation.lazy.LazyColumn {
                                    item { Text(crash, style = MaterialTheme.typography.bodySmall) }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { lastCrashMessage.value = null }) { Text("OK") }
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun MainScreen(modifier: Modifier = Modifier) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { _ -> }

        LaunchedEffect(Unit) {
            permissionLauncher.launch(permissions.toTypedArray())
        }

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
                        webRTCClient?.getEglBaseContext()?.let { eglContext ->
                            init(eglContext, null)
                        }
                        webRTCClient?.setRemoteRenderer(this)
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
        try {
            isSharing.value = true
            val mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            isSharing.value = false
            android.widget.Toast.makeText(this, "requestMediaProjection Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun startScreenCapture(mediaProjectionIntent: Intent) {
        try {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            ScreenCaptureService.onServiceStarted = {
                // Delay to ensure the OS has fully registered the foreground service
                // before we attempt to start MediaProjection, avoiding a SecurityException race condition.
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        setupSignalingAndWebRTC(mediaProjectionIntent)
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(this, "setupSignalingAndWebRTC Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }, 500)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "startScreenCapture Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun startViewing() {
        try {
            setupSignalingAndWebRTC(null)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "startViewing Error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
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
