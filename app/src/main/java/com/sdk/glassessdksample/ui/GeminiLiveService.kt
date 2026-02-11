package com.sdk.glassessdksample.ui

import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.CountDownLatch
import android.util.Log
import com.google.gson.Gson
import com.sdk.glassessdksample.BuildConfig
import com.sdk.glassessdksample.R
import kotlinx.coroutines.*
import okhttp3.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.sdk.glassessdksample.ui.ScoConnectionHelper


/**
 * GeminiLiveService - Android implementation of real-time bidirectional audio streaming
 * with Google's Gemini 2.0 Flash Live API.
 * 
 * This service handles:
 * - Real-time audio input capture (16kHz PCM)
 * - Real-time audio output playback (24kHz PCM)
 * - WebSocket-based bidirectional communication
 * - Live transcription (input and output)
 * - Turn-based conversation management
 */
class GeminiLiveService(
    private val context: Context,
    private val callbacks: GeminiLiveCallbacks
) {
    companion object {
        private const val TAG = "GeminiLiveService"
        
        // 🆕 Singleton instance for cross-activity access
        @Volatile
        private var instance: GeminiLiveService? = null
        
        /**
         * Get the current active instance (if any)
         */
        fun getInstance(): GeminiLiveService? = instance
        
        /**
         * Check if Gemini Live is currently active
         */
        fun isActive(): Boolean = instance?.webSocket != null
        
        // Audio configuration constants
        private const val INPUT_SAMPLE_RATE = 16000 // 16kHz for input
        private const val OUTPUT_SAMPLE_RATE = 24000 // 24kHz for output
        private val CHANNEL_CONFIG_IN = android.media.AudioFormat.CHANNEL_IN_MONO
        private val CHANNEL_CONFIG_OUT = android.media.AudioFormat.CHANNEL_OUT_MONO
        private val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 8 // INCREASED for smoother audio without crackling
        
        // Pre-buffering: Wait for this many audio chunks before starting playback
        private const val PRE_BUFFER_COUNT = 3 // ⚡ REDUCED: 8 -> 3 for faster start (lower latency)
        
        // Audio timeout: How long to wait for more audio before declaring end of speech
        // 🔥 TUNED: 700ms - Fast enough for snappy replies, slow enough to catch pauses
        private const val AUDIO_END_TIMEOUT_MS = 700L
        
        // Loudness settings
        // Balanced gain to prevent audio distortion/clipping
        private const val SOFTWARE_GAIN = 0.85f // REDUCED slightly to prevent any clipping
        
        // WebSocket configuration
        private const val MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        private const val VOICE_NAME = "Kore" // Gentle, soft voice (requested by user)
        
        // Echo cancellation and noise suppression
        private const val ENERGY_THRESHOLD = 200.0 // RMS threshold - lowered for better speech detection
    }

    // Callbacks interface for communication with UI
    interface GeminiLiveCallbacks {
        fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean)
        fun onTurnComplete(fullInput: String, fullOutput: String)
        
        // Tool call callback - called when Gemini wants to execute a function
        fun onToolCall(toolName: String, args: Map<String, Any>): String
        fun onAudioPlaybackStart()
        fun onAudioPlaybackEnd()
        fun onError(error: String)
        fun onConnectionStatusChanged(isConnected: Boolean)
    }
    
    /**
     * 🆕 Secondary listener for VisionChatActivity
     * VisionChat can register to receive user transcription and intercept vision commands
     */
    interface VisionTranscriptionListener {
        fun onUserTranscription(text: String, isFinal: Boolean)
    }
    
    // 🆕 Secondary vision listener (VisionChatActivity)
    @Volatile
    private var visionTranscriptionListener: VisionTranscriptionListener? = null
    
    /**
     * 🆕 Register a secondary vision listener (for VisionChatActivity)
     * This allows VisionChat to intercept voice commands while Gemini Live is active
     */
    fun setVisionTranscriptionListener(listener: VisionTranscriptionListener?) {
        visionTranscriptionListener = listener
        Log.d(TAG, "👁️ Vision transcription listener ${if (listener != null) "registered" else "removed"}")
    }

    private val gson = com.google.gson.Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // WebSocket components
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Audio components
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var scoHelper: ScoConnectionHelper? = null
    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val isSetupComplete = AtomicBoolean(false)
    
    // Echo cancellation and noise suppression
    private val isAIPlaying = AtomicBoolean(false) // Half-duplex flag: true when AI is speaking
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    
    // Transcription state
    private var currentInputTranscription = StringBuilder()
    private var currentOutputTranscription = StringBuilder()
    private var receivedAudioInCurrentTurn = false
    private var hasTranscriptionForCurrentTurn = false
    
    // Audio playback queue
    private val audioQueue = mutableListOf<ByteArray>()
    private val audioQueueLock = Any()
    private var isPreBuffering = true // Wait for buffer to fill before playing
    
    // 🆕 Mute functionality for vision chat integration
    private val isMuted = AtomicBoolean(false) // When true, blocks audio output (but keeps listening)
    
    // 🎵 Thinking sound - plays during delay between user question and AI reply
    private var thinkingPlayer: MediaPlayer? = null
    private val isThinkingSoundPlaying = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper()) // Must use main thread for MediaPlayer
    
    /**
     * 🎵 Start the thinking/processing sound (loops until AI starts speaking)
     * Runs on main thread because MediaPlayer requires it
     */
    private fun startThinkingSound() {
        if (isThinkingSoundPlaying.get()) return // Already playing
        mainHandler.post {
            try {
                // Release previous player if any
                thinkingPlayer?.let { p ->
                    try { if (p.isPlaying) p.stop() } catch (_: Exception) {}
                    try { p.release() } catch (_: Exception) {}
                }
                thinkingPlayer = null
                
                thinkingPlayer = MediaPlayer.create(context, R.raw.swar_chakra_thinking)?.apply {
                    isLooping = true
                    setVolume(0.35f, 0.35f) // 35% volume - subtle but audible
                    start()
                }
                if (thinkingPlayer != null) {
                    isThinkingSoundPlaying.set(true)
                    Log.d(TAG, "🎵 Thinking sound STARTED on main thread")
                } else {
                    Log.e(TAG, "🎵 MediaPlayer.create returned null! Check res/raw/swar_chakra_thinking.mp3")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🎵 Error starting thinking sound: ${e.message}", e)
            }
        }
    }
    
    /**
     * 🎵 Stop the thinking/processing sound (called when AI reply audio arrives)
     * Runs on main thread for safety
     */
    private fun stopThinkingSound() {
        if (!isThinkingSoundPlaying.get() && thinkingPlayer == null) return
        isThinkingSoundPlaying.set(false) // Set immediately to prevent race conditions
        mainHandler.post {
            try {
                thinkingPlayer?.let { player ->
                    try { if (player.isPlaying) player.stop() } catch (_: Exception) {}
                    try { player.release() } catch (_: Exception) {}
                }
                thinkingPlayer = null
                Log.d(TAG, "🎵 Thinking sound STOPPED on main thread")
            } catch (e: Exception) {
                Log.e(TAG, "🎵 Error stopping thinking sound: ${e.message}")
                thinkingPlayer = null
            }
        }
    }
    
    /**
     * Start the live conversation session
     */
    fun startLiveConversation(systemInstruction: String = "You are a helpful AI assistant.") {
        if (webSocket != null) {
            Log.w(TAG, "Live conversation already started")
            callbacks.onError("Conversation already in progress")
            return
        }
        
        // 🆕 Set singleton instance
        instance = this
        Log.d(TAG, "🌐 GeminiLiveService instance set for cross-activity access")

        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty()) {
            callbacks.onError("API Key is not configured")
            return
        }

        scope.launch {
            try {
                // Initialize audio components
                initializeAudioComponents()
                
                // Connect to WebSocket
                connectWebSocket(apiKey, systemInstruction)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start live conversation", e)
                callbacks.onError("Failed to start: ${e.message}")
                cleanup()
            }
        }
    }

    /**
     * Stop the live conversation session
     */
    fun stopLiveConversation() {
        scope.launch {
            cleanup()
            callbacks.onConnectionStatusChanged(false)
        }
    }
    
    /**
     * 🆕 Inject text for Gemini to speak naturally
     * This sends a user message asking Gemini to speak the provided text
     * Gemini will respond with its natural streaming voice
     * 
     * @param textToSpeak The text content for Gemini to speak
     * @param speakDirectly If true, instructs Gemini to speak this text directly
     */
    fun speakText(textToSpeak: String, speakDirectly: Boolean = true) {
        val ws = webSocket
        if (ws == null) {
            Log.e(TAG, "❌ Cannot speak text - WebSocket not connected")
            callbacks.onError("Gemini Live not connected")
            return
        }
        
        try {
            // Speak the text naturally and COMPLETELY without stopping
            val promptText = if (speakDirectly) {
                "Read this COMPLETELY in one go, do not pause or stop in the middle: $textToSpeak"
            } else {
                textToSpeak
            }
            
            val message = mapOf(
                "clientContent" to mapOf(
                    "turns" to listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(
                                mapOf("text" to promptText)
                            )
                        )
                    ),
                    "turnComplete" to true
                )
            )
            
            val jsonMessage = gson.toJson(message)
            val sent = ws.send(jsonMessage)
            Log.d(TAG, "🔊 Injected text for Gemini to speak: ${textToSpeak.take(100)}... sent=$sent")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to inject speak text: ${e.message}", e)
            callbacks.onError("Failed to speak: ${e.message}")
        }
    }
    
    /**
     * 🆕 Inject vision context into Gemini Live's conversation memory
     * This adds the vision analysis to the conversation history so Gemini
     * remembers it for follow-up questions
     */
    fun injectVisionContext(visionDescription: String) {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot inject vision context - WebSocket not connected")
            return
        }
        
        try {
            // Inject as a system/context message that Gemini will remember
            val contextMessage = "VISION CONTEXT: $visionDescription"
            
            val message = mapOf(
                "clientContent" to mapOf(
                    "turns" to listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(
                                mapOf("text" to contextMessage)
                            )
                        )
                    ),
                    "turnComplete" to true
                )
            )
            
            val jsonMessage = gson.toJson(message)
            val sent = ws.send(jsonMessage)
            Log.d(TAG, "📝 Injected vision context into conversation memory: sent=$sent")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to inject vision context: ${e.message}", e)
        }
    }
    
    /**
     * 🆕 Mute Gemini Live output (stops audio playback)
     * Used during vision chat processing - Gemini will be silent
     * Input audio recording continues (user can still speak)
     */
    fun muteOutput() {
        isMuted.set(true)
        Log.d(TAG, "🔇 Gemini Live OUTPUT MUTED (input still active)")
        // Clear any queued audio
        synchronized(audioQueueLock) {
            audioQueue.clear()
            isPreBuffering = true
        }
        // Stop current playback
        audioTrack?.pause()
    }
    
    /**
     * 🆕 Unmute Gemini Live output (resumes audio playback)
     * Used when vision description is ready to be spoken
     */
    fun unmuteOutput() {
        isMuted.set(false)
        Log.d(TAG, "🔊 Gemini Live OUTPUT UNMUTED (ready to speak)")
        // Resume playback if needed
        if (isPlaying.get()) {
            audioTrack?.play()
        }
    }
    
    /**
     * 🆕 Check if Gemini Live is currently muted
     */
    fun isOutputMuted(): Boolean = isMuted.get()
    
    /**
     * 🆕 Play thinking sound while waiting for vision analysis
     * Gemini will say "hmm hmm hmm" naturally to indicate processing
     * CONTINUOUS: Keeps saying until interrupted by actual response
     */
    fun playThinkingSound() {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot play thinking sound - WebSocket not connected")
            return
        }
        
        try {
            val message = mapOf(
                "clientContent" to mapOf(
                    "turns" to listOf(
                        mapOf(
                            "role" to "user",
                            "parts" to listOf(
                                mapOf("text" to "Say naturally like you're thinking: 'hmm... hmm... let me see... hmm... looking at this... hmm hmm...' Keep going for about 5-10 seconds, like you're carefully examining something. Sound natural and thoughtful.")
                            )
                        )
                    ),
                    "turnComplete" to true
                )
            )
            
            val jsonMessage = gson.toJson(message)
            ws.send(jsonMessage)
            Log.d(TAG, "🎵 Playing CONTINUOUS thinking sound through Gemini Live")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to play thinking sound: ${e.message}", e)
        }
    }

    private suspend fun initializeAudioComponents() {
    Log.d(TAG, "🎧 Initializing DUAL CONNECTION audio (BLE Data + System Audio)")
    
    audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    
    // ========== OPTION A: Use System Bluetooth for Audio ==========
    // BLE handles data (photos, commands) - System Bluetooth handles audio
    
    // 1. Set audio mode to IN_CALL for automatic Bluetooth routing
    audioManager?.mode = AudioManager.MODE_IN_CALL
    Log.d(TAG, "📞 Audio mode: IN_CALL (enables automatic Bluetooth routing)")
    
    // 2. Check if Bluetooth audio is available
    val isBluetoothAvailable = audioManager?.isBluetoothScoAvailableOffCall == true
    val isBluetoothOn = audioManager?.isBluetoothScoOn == true
    
    Log.d(TAG, "🎧 System Bluetooth Audio Status:")
    Log.d(TAG, "   - Available: $isBluetoothAvailable")
    Log.d(TAG, "   - Active: $isBluetoothOn")
    
    // 3. Start Bluetooth SCO if available (but don't wait - Android handles it)
    if (isBluetoothAvailable && !isBluetoothOn) {
        try {
            audioManager?.startBluetoothSco()
            audioManager?.isBluetoothScoOn = true
            Log.d(TAG, "📡 Bluetooth SCO start requested")
            
            // Give it a short moment for connection (non-blocking)
            delay(50) // Ultra-fast startup - 50ms only
        } catch (e: Exception) {
            Log.w(TAG, "SCO start attempt: ${e.message}")
        }
    }
    
    // 4. Calculate buffer sizes
    val inputBufferSize = AudioRecord.getMinBufferSize(
        INPUT_SAMPLE_RATE,
        CHANNEL_CONFIG_IN,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_MULTIPLIER

    val outputBufferSize = AudioTrack.getMinBufferSize(
        OUTPUT_SAMPLE_RATE,
        CHANNEL_CONFIG_OUT,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_MULTIPLIER

    // 5. Create AudioRecord with VOICE_COMMUNICATION (auto-routes to Bluetooth)
    try {
        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(INPUT_SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG_IN)
                    .build()
            )
            .setBufferSizeInBytes(inputBufferSize)
            .build()
        
        // 6. Try to set preferred device to Bluetooth SCO (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: arrayOf()
                
                Log.d(TAG, "🎙️ Available input devices:")
                devices.forEach { dev ->
                    val typeStr = when (dev.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO (Glass)"
                        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone Mic"
                        else -> "Other (${dev.type})"
                    }
                    Log.d(TAG, "   - ${dev.productName}: $typeStr")
                }
                
                // Find and prefer Bluetooth SCO device
                val bluetoothDevice = devices.firstOrNull { 
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO 
                }
                
                if (bluetoothDevice != null) {
                    val success = audioRecord?.setPreferredDevice(bluetoothDevice)
                    Log.d(TAG, "🎯 Set preferred device to: ${bluetoothDevice.productName}, success=$success")
                } else {
                    Log.d(TAG, "ℹ️ No Bluetooth SCO device found - will use default routing")
                    Log.d(TAG, "   (Android will auto-route to Bluetooth when available)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set preferred device: ${e.message}")
            }
        }
        
        // 7. Enable audio processing (echo cancellation, noise suppression)
        audioRecord?.audioSessionId?.let { sessionId ->
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)
                acousticEchoCanceler?.enabled = true
                Log.d(TAG, "✅ AcousticEchoCanceler enabled")
            }
            
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.d(TAG, "✅ NoiseSuppressor enabled")
            }
            
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(sessionId)
                automaticGainControl?.enabled = true
                Log.d(TAG, "✅ AutomaticGainControl enabled")
            }
        }

        // 8. Create AudioTrack for speaker output (auto-routes to Bluetooth)
        // Using USAGE_MEDIA for crystal clear audio quality like Gemini Chat
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)  // Better quality than VOICE_COMMUNICATION
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .setEncoding(AUDIO_FORMAT)
                    .build()
            )
            .setBufferSizeInBytes(outputBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // 9. Check final audio routing
        val finalDevice = audioRecord?.routedDevice
        val deviceName = finalDevice?.productName ?: "default"
        val deviceType = when (finalDevice?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "✅ Bluetooth SCO (Glass Mic)"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "📱 Phone Mic"
            else -> "Other (${finalDevice?.type})"
        }
        
        Log.d(TAG, "🎧 ===== AUDIO INITIALIZATION COMPLETE =====")
        Log.d(TAG, "   📡 BLE Connection: Active (for data/commands)")
        Log.d(TAG, "   🎤 Audio Input: $deviceName ($deviceType)")
        Log.d(TAG, "   🔊 Audio Output: ${OUTPUT_SAMPLE_RATE}Hz")
        Log.d(TAG, "   🎧 Mode: Dual Connection (BLE + Audio)")
        Log.d(TAG, "==========================================")
        
    } catch (e: Exception) {
        Log.e(TAG, "❌ Failed to initialize audio: ${e.message}", e)
        throw e
    }
}

    /**
     * Connect to Gemini Live WebSocket
     */
    private fun connectWebSocket(apiKey: String, systemInstruction: String) {
        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        
        val request = Request.Builder()
            .url(url)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WebSocket connected")
                this@GeminiLiveService.webSocket = webSocket
                callbacks.onConnectionStatusChanged(true)

                // Send initial setup configuration
                sendSetupMessage(webSocket, systemInstruction)

                // Start audio playback (ready to receive)
                startAudioPlayback()
                
                // Start audio capture immediately (don't wait for setupComplete)
                // The API should work even without explicit setupComplete message
                scope.launch {
                    // Give the server a very short moment to respond
                    delay(10) // 10ms - faster startup
                    if (!isSetupComplete.get()) {
                        Log.d(TAG, "⚡ Starting audio capture (fallback - no setupComplete received)")
                        
                        // Send a test text message first to verify connection
                        val testMessage = mapOf(
                            "clientContent" to mapOf(
                                "turns" to listOf(
                                    mapOf(
                                        "role" to "user",
                                        "parts" to listOf(
                                            mapOf("text" to "Hello, please respond with a short greeting.")
                                        )
                                    )
                                ),
                                "turnComplete" to true
                            )
                        )
                        val testJson = gson.toJson(testMessage)
                        Log.d(TAG, "📤 Sending test text message to verify connection")
                        webSocket.send(testJson)
                        
                        // Then start audio capture immediately
                        startAudioCapture(webSocket)
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📩 WebSocket onMessage (text): ${text.take(200)}...")
                handleWebSocketMessage(text)
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                Log.d(TAG, "📩 WebSocket onMessage (bytes): ${bytes.size} bytes")
                // Convert bytes to string and handle
                handleWebSocketMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WebSocket failure: ${t.message}", t)
                try {
                    Log.e(TAG, "❌ Response: ${response?.body?.string()}")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Could not read response body")
                }
                
                // Handle network disconnection gracefully - don't crash
                val errorMessage = when {
                    t.message?.contains("network", ignoreCase = true) == true -> "Network disconnected"
                    t.message?.contains("internet", ignoreCase = true) == true -> "No internet connection"
                    t.message?.contains("connection", ignoreCase = true) == true -> "Connection lost"
                    t.message?.contains("timeout", ignoreCase = true) == true -> "Connection timeout"
                    else -> "Connection failed: ${t.message}"
                }
                
                try {
                    callbacks.onError(errorMessage)
                    callbacks.onConnectionStatusChanged(false)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in callbacks: ${e.message}")
                }
                
                // Cleanup safely
                try {
                    cleanup()
                } catch (e: Exception) {
                    Log.e(TAG, "Error during cleanup: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "⚠️ WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔒 WebSocket closed: $code - $reason")
                callbacks.onConnectionStatusChanged(false)
                cleanup()
            }
        }

        client.newWebSocket(request, listener)
    }

    /**
     * Send initial setup message to configure the session with tools
     */
    private fun sendSetupMessage(webSocket: WebSocket, systemInstruction: String) {
        // Define tools/functions that Gemini can call
        val tools = listOf(
            mapOf(
                "functionDeclarations" to listOf(
                    mapOf(
                        "name" to "make_phone_call",
                        "description" to "Make a phone call to a contact by name",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "contact_name" to mapOf(
                                    "type" to "string",
                                    "description" to "Name of the contact to call"
                                )
                            ),
                            "required" to listOf("contact_name")
                        )
                    ),
                    mapOf(
                        "name" to "play_music",
                        "description" to "Play music or a specific song on Spotify",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "song_or_artist" to mapOf(
                                    "type" to "string",
                                    "description" to "Song name, artist name, or music genre to play"
                                )
                            ),
                            "required" to listOf("song_or_artist")
                        )
                    ),
                    mapOf(
                        "name" to "play_youtube",
                        "description" to "Play a video on YouTube",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "search_query" to mapOf(
                                    "type" to "string",
                                    "description" to "Video or topic to search on YouTube"
                                )
                            ),
                            "required" to listOf("search_query")
                        )
                    ),
                    mapOf(
                        "name" to "open_camera",
                        "description" to "Open the camera to take a photo or record video",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "mode" to mapOf(
                                    "type" to "string",
                                    "description" to "Camera mode: 'photo', 'video', or 'view'",
                                    "enum" to listOf("photo", "video", "view")
                                )
                            ),
                            "required" to listOf("mode")
                        )
                    ),
                    mapOf(
                        "name" to "take_photo",
                        "description" to "Capture a photo with the camera",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf<String, Any>()
                        )
                    ),
                    mapOf(
                        "name" to "record_video",
                        "description" to "Start or stop video recording",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "action" to mapOf(
                                    "type" to "string",
                                    "description" to "Action: 'start' to begin recording, 'stop' to end recording",
                                    "enum" to listOf("start", "stop")
                                )
                            ),
                            "required" to listOf("action")
                        )
                    ),
                    mapOf(
                        "name" to "analyze_view",
                        "description" to "Analyze what is in front of the user using the camera (what is this, what is in front of me, identify object)",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "question" to mapOf(
                                    "type" to "string",
                                    "description" to "The question about what the user wants to know about their view"
                                )
                            )
                        )
                    ),
                    mapOf(
                        "name" to "capture_new_frame",
                        "description" to "Capture a new photo/frame from glasses camera when user wants to see something new (click another, new photo, take another picture, naya photo, dusra click karo, agla frame)",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf<String, Any>()
                        )
                    ),
                    mapOf(
                        "name" to "open_maps",
                        "description" to "Open Google Maps for navigation or location search",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "destination" to mapOf(
                                    "type" to "string",
                                    "description" to "Destination or place to navigate to or search for"
                                ),
                                "mode" to mapOf(
                                    "type" to "string",
                                    "description" to "Navigation mode: 'driving', 'walking', 'transit'",
                                    "enum" to listOf("driving", "walking", "transit")
                                )
                            )
                        )
                    ),
                    mapOf(
                        "name" to "send_message",
                        "description" to "Send a WhatsApp or SMS message to a contact",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "contact_name" to mapOf(
                                    "type" to "string",
                                    "description" to "Name of the contact to message"
                                ),
                                "message" to mapOf(
                                    "type" to "string",
                                    "description" to "Message content to send"
                                ),
                                "app" to mapOf(
                                    "type" to "string",
                                    "description" to "Messaging app to use: 'whatsapp' or 'sms'",
                                    "enum" to listOf("whatsapp", "sms")
                                )
                            ),
                            "required" to listOf("contact_name", "message")
                        )
                    ),
                    mapOf(
                        "name" to "set_reminder",
                        "description" to "Set a reminder or alarm",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "reminder_text" to mapOf(
                                    "type" to "string",
                                    "description" to "What to remind about"
                                ),
                                "time" to mapOf(
                                    "type" to "string",
                                    "description" to "When to remind (e.g., 'in 10 minutes', '3 PM', 'tomorrow 9 AM')"
                                )
                            ),
                            "required" to listOf("reminder_text")
                        )
                    ),
                    mapOf(
                        "name" to "get_weather",
                        "description" to "Get current weather information",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "location" to mapOf(
                                    "type" to "string",
                                    "description" to "City or location to get weather for (optional, uses current location if not specified)"
                                )
                            )
                        )
                    ),
                    mapOf(
                        "name" to "web_search",
                        "description" to "Perform a web search and return a concise summary",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "query" to mapOf(
                                    "type" to "string",
                                    "description" to "Search query to look up on the web"
                                )
                            ),
                            "required" to listOf("query")
                        )
                    ),
                    mapOf(
                        "name" to "get_news",
                        "description" to "Fetch top news headlines for a topic or location",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "query" to mapOf(
                                    "type" to "string",
                                    "description" to "Topic or location for news (optional)"
                                )
                            )
                        )
                    ),
                    mapOf(
                        "name" to "play_shayari",
                        "description" to "Play or return a short shayari/poem",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "mood" to mapOf(
                                    "type" to "string",
                                    "description" to "Optional mood: romantic, sad, funny"
                                )
                            )
                        )
                    ),
                    mapOf(
                        "name" to "control_volume",
                        "description" to "Control the device volume",
                        "parameters" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "action" to mapOf(
                                    "type" to "string",
                                    "description" to "Volume action: 'up', 'down', 'mute', 'unmute', or a number 0-100",
                                    "enum" to listOf("up", "down", "mute", "unmute", "max")
                                )
                            ),
                            "required" to listOf("action")
                        )
                    )
                )
            ),
            // Enable Google Search tool so Gemini can call it when asked
            mapOf(
                "googleSearch" to emptyMap<String, Any>()
            )
        )

        // Natural conversation with intelligent voice detection
        val enhancedInstruction = """$systemInstruction

You are Imi Glass, a smart glasses assistant.
IMPORTANT LANGUAGE RULE: ALWAYS Reply in the EXACT SAME LANGUAGE the user speaks.
- If user speaks English -> Reply in English.
- If user speaks Hindi -> Reply in Hindi.
- If user speaks Hinglish -> Reply in Hinglish.

CRITICAL: You have access to REAL-TIME data via 'googleSearch' tool.
- If user asks about current events, news, or live info, USE 'googleSearch' IMMEDIATELY.
- Do NOT say "I cannot browse the web" or "My knowledge is limited". USE THE TOOL.
- Reply FAST and CONCISELY. No filler words. Match the user's vibe."""

        val setupMessage = mapOf(
            "setup" to mapOf(
                "model" to "models/$MODEL",
                "generationConfig" to mapOf(
                    "responseModalities" to listOf("AUDIO"),
                    "temperature" to 0.6, // 🔥 Optimized: 0.6 for faster, sharper responses
                    "topP" to 0.9,
                    "topK" to 40,
                    "maxOutputTokens" to 512, // 🔥 INCREASED: 512 tokens for more detailed responses (user request)
                    "speechConfig" to mapOf(
                        "voiceConfig" to mapOf(
                            "prebuiltVoiceConfig" to mapOf(
                                "voiceName" to VOICE_NAME
                            )
                        )
                    )
                ),
                "systemInstruction" to mapOf(
                    "parts" to listOf(
                        mapOf("text" to enhancedInstruction)
                    )
                ),
                "tools" to tools
            )
        )

        val json = gson.toJson(setupMessage)
        Log.d(TAG, "📤 Sending setup with tools: $json")
        webSocket.send(json)
    }

    /**
     * Start capturing audio from microphone and streaming to WebSocket
     */
    private fun startAudioCapture(webSocket: WebSocket) {
        scope.launch {
            try {
                audioRecord?.startRecording()
                isRecording.set(true)
                Log.d(TAG, "🎤 Audio capture started")

                // Use smaller buffer for lower latency (about 30ms chunks at 16kHz)
                val bufferSize = 480 // 30ms at 16kHz mono = 480 samples (ultra-fast response)
                val buffer = ShortArray(bufferSize)
                var chunkCount = 0
                var totalBytes = 0
                var halfDuplexSkips = 0

                while (isRecording.get()) {
                    val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    
                    if (readSize > 0) {
                        // Half-duplex: Skip sending audio when AI is speaking (prevents echo)
                        if (isAIPlaying.get()) {
                            halfDuplexSkips++
                            continue
                        }
                        
                        // VAD DISABLED - Send all audio, let Gemini handle speech detection
                        // AEC + NoiseSuppressor will handle echo/noise at hardware level
                        
                        // Convert PCM16 to base64
                        val pcmData = shortArrayToByteArray(buffer, readSize)
                        val base64Data = Base64.encodeToString(pcmData, Base64.NO_WRAP)
                        totalBytes += pcmData.size

                        // Send realtime input with correct format for Gemini Live API
                        val realtimeInput = mapOf(
                            "realtimeInput" to mapOf(
                                "mediaChunks" to listOf(
                                    mapOf(
                                        "mimeType" to "audio/pcm;rate=16000",
                                        "data" to base64Data
                                    )
                                )
                            )
                        )

                        val json = gson.toJson(realtimeInput)
                        val sent = webSocket.send(json)
                        
                        chunkCount++
                        
                        // Log first chunk for debugging
                        if (chunkCount == 1) {
                            Log.d(TAG, "📤 First audio chunk: ${pcmData.size} bytes, base64 length: ${base64Data.length}")
                            if (!sent) {
                                Log.e(TAG, "❌ Failed to send first audio chunk!")
                            }
                        }
                        
                        if (chunkCount % 100 == 0) {
                            Log.d(TAG, "📤 Sent $chunkCount audio chunks (${totalBytes/1024} KB total) | Half-duplex skips: $halfDuplexSkips")
                        }
                    }

                    // Removed delay for fastest possible streaming
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture: ${e.message}", e)
                callbacks.onError("Audio capture error: ${e.message}")
            }
        }
    }

        // Send image frames over the active WebSocket for Gemini Live (chunked, camelCase)
        fun sendRealtimeImage(imageBytes: ByteArray) {
            if (webSocket == null) {
                Log.e(TAG, "❌ WebSocket not connected. Cannot send image.")
                return
            }

            try {
                // Split large images into manageable Base64 chunks to avoid huge single messages
                val maxChunkBytes = 160 * 1024 // 160 KB per chunk (tune if needed)
                val chunks = mutableListOf<Map<String, Any>>()
                var offset = 0
                while (offset < imageBytes.size) {
                    val len = minOf(maxChunkBytes, imageBytes.size - offset)
                    val part = imageBytes.copyOfRange(offset, offset + len)
                    val base64Part = Base64.encodeToString(part, Base64.NO_WRAP)
                    chunks.add(mapOf("mimeType" to "image/jpeg", "data" to base64Part))
                    offset += len
                }

                // Build message using camelCase keys to match audio path
                val message = mapOf(
                    "realtimeInput" to mapOf(
                        "mediaChunks" to chunks
                    )
                )

                val jsonMessage = gson.toJson(message)
                val sent = webSocket?.send(jsonMessage) ?: false
                Log.d(TAG, "� Sent Live Image Frame to Gemini (${imageBytes.size} bytes) in ${chunks.size} chunk(s). sent=$sent")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send realtime image: ${e.message}", e)
                callbacks.onError("Failed to send image: ${e.message}")
            }
        }

    /**
     * Start audio playback coroutine with pre-buffering for smooth playback
     */
    private fun startAudioPlayback() {
        scope.launch {
            try {
                audioTrack?.play()
                isPlaying.set(true)
                isPreBuffering = true // Start in pre-buffering mode
                var lastAudioTime = 0L // Track when we last played audio
                Log.d(TAG, "🔊 Audio playback started (pre-buffering enabled, PRE_BUFFER_COUNT=$PRE_BUFFER_COUNT)")

                while (isPlaying.get()) {
                    // Pre-buffering: Wait until we have enough chunks for smooth playback
                    val queueSize = synchronized(audioQueueLock) { audioQueue.size }
                    
                    if (isPreBuffering && queueSize < PRE_BUFFER_COUNT) {
                        delay(1) // Ultra-fast polling for instant start
                        continue
                    }
                    
                    if (isPreBuffering && queueSize >= PRE_BUFFER_COUNT) {
                        isPreBuffering = false
                        isAIPlaying.set(true) // AI is speaking, pause mic capture EARLY
                        callbacks.onAudioPlaybackStart()
                        lastAudioTime = System.currentTimeMillis()
                        Log.d(TAG, "✅ Pre-buffer filled ($queueSize chunks), starting smooth playback")
                    }
                    
                    // Play ALL available chunks in one go for smooth continuous audio
                    val chunksToPlay = synchronized(audioQueueLock) {
                        if (audioQueue.isNotEmpty()) {
                            val chunks = audioQueue.toList()
                            audioQueue.clear()
                            chunks
                        } else {
                            emptyList()
                        }
                    }

                    if (chunksToPlay.isNotEmpty()) {
                        lastAudioTime = System.currentTimeMillis()
                        
                        // Play all chunks continuously without interruption
                        for (chunk in chunksToPlay) {
                            playAudioChunk(chunk)
                        }
                    } else if (!isPreBuffering) {
                        // Queue is empty but we were playing - check if more audio is coming
                        val timeSinceLastAudio = System.currentTimeMillis() - lastAudioTime
                        
                        if (timeSinceLastAudio > AUDIO_END_TIMEOUT_MS) {
                            // No new audio for a while, AI likely finished speaking
                            isAIPlaying.set(false) // Resume mic capture
                            isPreBuffering = true // Reset for next turn
                            callbacks.onAudioPlaybackEnd()
                            Log.d(TAG, "🔇 Audio playback ended (no new audio for ${AUDIO_END_TIMEOUT_MS}ms)")
                        }
                        delay(5) // Quick check for new audio
                    } else {
                        delay(1) // Ultra-fast polling when pre-buffering
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio playback", e)
                callbacks.onError("Audio playback error: ${e.message}")
            }
        }
    }

    /**
     * Apply software gain to PCM16 samples with clipping protection
     */
    private fun applyGainToPcm16(pcm: ShortArray, gain: Float) {
        for (i in pcm.indices) {
            val amplified = (pcm[i] * gain).toInt()
            pcm[i] = when {
                amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> amplified.toShort()
            }
        }
    }
    
    /**
     * Play a single audio chunk with software gain applied using blocking write
     */
    private fun playAudioChunk(audioData: ByteArray) {
        try {
            // 🔇 Check if output is muted (for vision chat processing)
            if (isMuted.get()) {
                Log.v(TAG, "🔇 Audio muted - skipping playback")
                return // Don't play audio while muted
            }
            
            // Convert byte array to short array for AudioTrack
            val shortBuffer = byteArrayToShortArray(audioData)
            
            // Apply software gain for louder playback
            applyGainToPcm16(shortBuffer, SOFTWARE_GAIN)
            
            // Use WRITE_BLOCKING to ensure all audio is written without dropping
            audioTrack?.write(shortBuffer, 0, shortBuffer.size, AudioTrack.WRITE_BLOCKING)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio chunk", e)
        }
    }

    /**
     * Handle incoming WebSocket messages
     */
    private fun handleWebSocketMessage(text: String) {
        try {
            // Log the raw message (truncated for large audio data)
            val logText = if (text.length > 500) text.substring(0, 500) + "...(truncated)" else text
            Log.d(TAG, "📩 Raw message: $logText")
            
            val messageMap = gson.fromJson(text, Map::class.java) as Map<*, *>
            Log.d(TAG, "📨 Received message keys: ${messageMap.keys}")

            // Handle setup complete
            if (messageMap.containsKey("setupComplete")) {
                Log.d(TAG, "✅ Setup complete received from server!")
                isSetupComplete.set(true)
                webSocket?.let { startAudioCapture(it) }
                return
            }

            // Handle server content
            val serverContent = messageMap["serverContent"] as? Map<*, *>
            if (serverContent != null) {
                Log.d(TAG, "📦 ServerContent keys: ${serverContent.keys}")
                handleServerContent(serverContent)
            }

            // Handle top-level toolCall (function calls from Gemini)
            val toolCall = messageMap["toolCall"] as? Map<*, *>
            if (toolCall != null) {
                Log.d(TAG, "🔧 Top-level toolCall received")
                handleToolCall(toolCall)
            }

            // Handle errors
            val error = messageMap["error"] as? Map<*, *>
            if (error != null) {
                val errorMsg = error["message"] as? String ?: "Unknown error"
                Log.e(TAG, "❌ Server error: $errorMsg")
                callbacks.onError(errorMsg)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}", e)
        }
    }

    /**
     * Handle server content (transcriptions, audio, turn complete, tool calls, etc.)
     */
    private fun handleServerContent(serverContent: Map<*, *>) {
        // Handle model turn (audio response and tool calls)
        val modelTurn = serverContent["modelTurn"] as? Map<*, *>
        if (modelTurn != null) {
            val parts = modelTurn["parts"] as? List<*>
            parts?.forEach { part ->
                val partMap = part as? Map<*, *>
                
                // Extract audio data
                val inlineData = partMap?.get("inlineData") as? Map<*, *>
                if (inlineData != null) {
                    val base64Audio = inlineData["data"] as? String
                    if (base64Audio != null) {
                        // 🎵 First AI audio chunk arrived - stop thinking sound immediately
                        if (!receivedAudioInCurrentTurn) {
                            stopThinkingSound()
                        }
                        
                        val audioData = Base64.decode(base64Audio, Base64.DEFAULT)
                        synchronized(audioQueueLock) {
                            audioQueue.add(audioData)
                        }
                        receivedAudioInCurrentTurn = true
                        Log.d(TAG, "🎵 Received audio chunk: ${audioData.size} bytes")
                    }
                }
                
                // Handle function calls
                val functionCall = partMap?.get("functionCall") as? Map<*, *>
                if (functionCall != null) {
                    handleFunctionCall(functionCall)
                }
            }
        }
        
        // Handle tool call response (alternative format)
        val toolCall = serverContent["toolCall"] as? Map<*, *>
        if (toolCall != null) {
            handleFunctionCall(toolCall)
        }

        // Handle output transcription (model's speech-to-text)
        val outputTranscription = serverContent["modelTurnTranscription"] as? Map<*, *>
        if (outputTranscription != null) {
            val text = outputTranscription["text"] as? String
            if (!text.isNullOrEmpty()) {
                currentOutputTranscription.append(text)
                hasTranscriptionForCurrentTurn = true
                callbacks.onTranscriptionUpdate(
                    currentInputTranscription.toString(),
                    currentOutputTranscription.toString(),
                    false
                )
                Log.d(TAG, "🤖 Model transcription: $text")
            }
        }

        // Handle input transcription (user's speech-to-text)
        val inputTranscription = serverContent["userInputTranscription"] as? Map<*, *>
        if (inputTranscription != null) {
            val text = inputTranscription["text"] as? String
            if (!text.isNullOrEmpty()) {
                currentInputTranscription.append(text)
                callbacks.onTranscriptionUpdate(
                    currentInputTranscription.toString(),
                    currentOutputTranscription.toString(),
                    false
                )
                Log.d(TAG, "👤 User transcription: $text")
                
                // User has spoken - start thinking sound immediately
                startThinkingSound()

                // Notify vision listener
                visionTranscriptionListener?.onUserTranscription(text, false)
            }
        }

        // Handle turn complete
        val turnComplete = serverContent["turnComplete"] as? Boolean
        if (turnComplete == true) {
            // 🎵 Safety: Stop thinking sound if still playing
            stopThinkingSound()
            
            // If we received audio but no transcription, generate a placeholder
            if (receivedAudioInCurrentTurn && !hasTranscriptionForCurrentTurn) {
                currentOutputTranscription.append("[AI speaking...]")
                Log.d(TAG, "📝 Generated placeholder transcription (no text from API)")
            }
            
            val fullInput = currentInputTranscription.toString()
            val fullOutput = currentOutputTranscription.toString()
            
            // Send final update with isFinal=true
            callbacks.onTranscriptionUpdate(fullInput, fullOutput, true)
            
            // 🆕 Notify vision listener with FINAL user input (for command detection)
            if (fullInput.isNotEmpty()) {
                visionTranscriptionListener?.onUserTranscription(fullInput, true)
            }
            
            Log.d(TAG, "✅ Turn complete - Input: '$fullInput', Output: '$fullOutput'")
            callbacks.onTurnComplete(fullInput, fullOutput)
            
            // Reset transcriptions and flags
            currentInputTranscription.clear()
            currentOutputTranscription.clear()
            receivedAudioInCurrentTurn = false
            hasTranscriptionForCurrentTurn = false
        }

        // Handle interruption
        val interrupted = serverContent["interrupted"] as? Boolean
        if (interrupted == true) {
            Log.d(TAG, "⚠️ Turn interrupted - clearing audio queue")
            stopThinkingSound() // 🎵 Stop thinking sound on interruption
            synchronized(audioQueueLock) {
                audioQueue.clear()
            }
            audioTrack?.flush()
            callbacks.onAudioPlaybackEnd()
        }
    }

    /**
     * Handle top-level toolCall from Gemini (contains functionCalls array)
     */
    private fun handleToolCall(toolCall: Map<*, *>) {
        val functionCalls = toolCall["functionCalls"] as? List<*>
        if (functionCalls == null) {
            Log.w(TAG, "⚠️ toolCall has no functionCalls")
            return
        }
        
        functionCalls.forEach { call ->
            val callMap = call as? Map<*, *> ?: return@forEach
            handleFunctionCall(callMap)
        }
    }

    /**
     * Handle function/tool calls from Gemini
     */
    private fun handleFunctionCall(functionCall: Map<*, *>) {
        val name = functionCall["name"] as? String ?: return
        val argsRaw = functionCall["args"] as? Map<*, *> ?: emptyMap<String, Any>()
        val args = argsRaw.mapKeys { it.key.toString() }.mapValues { it.value as Any }
        val callId = functionCall["id"] as? String
        
        Log.d(TAG, "🔧 Function call received: $name with args: $args")
        
        // Execute the function via callback
        scope.launch {
            try {
                val result = callbacks.onToolCall(name, args)
                Log.d(TAG, "✅ Function $name result: $result")
                
                // Send function response back to Gemini
                if (callId != null) {
                    sendFunctionResponse(callId, name, result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error executing function $name: ${e.message}", e)
                if (callId != null) {
                    sendFunctionResponse(callId, name, "Error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Send function response back to Gemini
     */
    private fun sendFunctionResponse(callId: String, name: String, result: String) {
        val response = mapOf(
            "toolResponse" to mapOf(
                "functionResponses" to listOf(
                    mapOf(
                        "id" to callId,
                        "name" to name,
                        "response" to mapOf(
                            "result" to result
                        )
                    )
                )
            )
        )
        
        val json = gson.toJson(response)
        Log.d(TAG, "📤 Sending function response: $json")
        webSocket?.send(json)
    }

    private fun cleanup() {
        try {
            isRecording.set(false)
            isPlaying.set(false)
            isAIPlaying.set(false)
            
            // 🆕 Clear singleton instance
            instance = null
            
            // Enhanced SCO cleanup using helper
            try {
                scoHelper?.disconnectSco()
                scoHelper?.cleanup()
                scoHelper = null
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up SCO: ${e.message}")
            }

            // Release audio effects
            try {
                acousticEchoCanceler?.release()
                acousticEchoCanceler = null
                noiseSuppressor?.release()
                noiseSuppressor = null
                automaticGainControl?.release()
                automaticGainControl = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing audio effects: ${e.message}")
            }

            // Stop and release audio components
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing audioRecord: ${e.message}")
            }

            try {
                audioTrack?.stop()
                audioTrack?.flush()
                audioTrack?.release()
                audioTrack = null
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing audioTrack: ${e.message}")
            }

            // Close WebSocket
            try {
                webSocket?.close(1000, "Session ended")
                webSocket = null
            } catch (e: Exception) {
                Log.w(TAG, "Error closing websocket: ${e.message}")
            }

            // Clear audio queue
            synchronized(audioQueueLock) {
                audioQueue.clear()
            }

            // Reset transcriptions
            currentInputTranscription.clear()
            currentOutputTranscription.clear()

            Log.d(TAG, "🧹 Enhanced cleanup complete - Glass headset disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup: ${e.message}")
        }
    }

    /**
     * Helper function to convert ShortArray to ByteArray (PCM16)
     */
    private fun shortArrayToByteArray(shortArray: ShortArray, size: Int): ByteArray {
        val byteBuffer = ByteBuffer.allocate(size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until size) {
            byteBuffer.putShort(shortArray[i])
        }
        return byteBuffer.array()
    }

    /**
     * Helper function to convert ByteArray to ShortArray
     */
    private fun byteArrayToShortArray(byteArray: ByteArray): ShortArray {
        val shortBuffer = ShortArray(byteArray.size / 2)
        ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)
        return shortBuffer
    }
    
    /**
     * Calculate RMS (Root Mean Square) energy of audio samples
     * Used for Voice Activity Detection to filter out quiet/noise frames
     */
    private fun calculateRMS(buffer: ShortArray, size: Int): Double {
        var sum = 0.0
        for (i in 0 until size) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / size)
    }

    /**
     * Release all resources when service is destroyed
     */
    fun destroy() {
        stopThinkingSound() // Clean up thinking sound
        scope.cancel()
        cleanup()
    }

    /**
     * Interrupt the current AI response immediately: clear audio queue, flush track and resume mic.
     */
    fun interruptCurrentResponse() {
        Log.d(TAG, "🛑 interruptCurrentResponse called - clearing audio queue and stopping AI playback")
        synchronized(audioQueueLock) {
            audioQueue.clear()
        }
        try {
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing audioTrack during interrupt: ${e.message}")
        }
        isAIPlaying.set(false)
        callbacks.onAudioPlaybackEnd()
    }
}
