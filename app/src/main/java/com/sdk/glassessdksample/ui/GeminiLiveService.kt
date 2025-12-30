package com.sdk.glassessdksample.ui

import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.content.Context
import android.os.Build
import android.util.Base64
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import java.util.concurrent.CountDownLatch
import android.util.Log
import com.google.gson.Gson
import com.sdk.glassessdksample.BuildConfig
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
        
        // Audio configuration constants
        private const val INPUT_SAMPLE_RATE = 16000 // 16kHz for input
        private const val OUTPUT_SAMPLE_RATE = 24000 // 24kHz for output
        private val CHANNEL_CONFIG_IN = android.media.AudioFormat.CHANNEL_IN_MONO
        private val CHANNEL_CONFIG_OUT = android.media.AudioFormat.CHANNEL_OUT_MONO
        private val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4 // Larger buffers to reduce underruns
        
        // Loudness settings
        // Increased gain to make TTS playback louder. Reduce if you hear distortion.
        private const val SOFTWARE_GAIN = 3.0f // Amplify TTS output (1.0 = normal, 3.0 = 3x louder)
        
        // WebSocket configuration
        private const val MODEL = "gemini-2.0-flash-exp"
        private const val VOICE_NAME = "Kore" // Clear, articulate voice (options: "Aoede", "Charon", "Fenrir", "Kore", "Puck")
        
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
    
    /**
     * Start the live conversation session
     */
    fun startLiveConversation(systemInstruction: String = "You are a helpful AI assistant.") {
        if (webSocket != null) {
            Log.w(TAG, "Live conversation already started")
            callbacks.onError("Conversation already in progress")
            return
        }

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
            delay(200)
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
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
                    delay(20) // 20ms
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
                Log.e(TAG, "❌ Response: ${response?.body?.string()}")
                callbacks.onError("Connection failed: ${t.message}")
                callbacks.onConnectionStatusChanged(false)
                cleanup()
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

        // Enhanced system instruction with tool awareness and strict brevity rules
        val enhancedInstruction = """$systemInstruction

    You are Imi Glass, the glasses assistant. Keep replies extremely short (1-2 sentences). If an action is requested, call the matching function and confirm once. Reply in the user's language."""

        val setupMessage = mapOf(
            "setup" to mapOf(
                "model" to "models/$MODEL",
                "generationConfig" to mapOf(
                    "responseModalities" to listOf("AUDIO"),
                    "temperature" to 0.0,
                    "topP" to 0.3,
                    "maxOutputTokens" to 60,
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

                // Use smaller buffer for lower latency (about 40ms chunks at 16kHz)
                val bufferSize = 640 // 40ms at 16kHz mono = 640 samples (faster response)
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

    /**
     * Start audio playback coroutine
     */
    private fun startAudioPlayback() {
        scope.launch {
            try {
                audioTrack?.play()
                isPlaying.set(true)
                Log.d(TAG, "🔊 Audio playback started")

                while (isPlaying.get()) {
                    val audioData = synchronized(audioQueueLock) {
                        if (audioQueue.isNotEmpty()) {
                            audioQueue.removeAt(0)
                        } else {
                            null
                        }
                    }

                    if (audioData != null) {
                        callbacks.onAudioPlaybackStart()
                        playAudioChunk(audioData)
                        
                        // Check if queue is empty after playing
                        val queueEmpty = synchronized(audioQueueLock) {
                            audioQueue.isEmpty()
                        }
                        if (queueEmpty) {
                            isAIPlaying.set(false) // AI stopped speaking, resume mic capture
                            callbacks.onAudioPlaybackEnd()
                        } else {
                            isAIPlaying.set(true) // AI is speaking, pause mic capture
                        }
                    } else {
                        delay(10) // Wait briefly for audio data (lower latency)
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
     * Play a single audio chunk with software gain applied
     */
    private fun playAudioChunk(audioData: ByteArray) {
        try {
            // Convert byte array to short array for AudioTrack
            val shortBuffer = byteArrayToShortArray(audioData)
            
            // Apply software gain for louder playback
            applyGainToPcm16(shortBuffer, SOFTWARE_GAIN)
            
            audioTrack?.write(shortBuffer, 0, shortBuffer.size)
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
            }
        }

        // Handle turn complete
        val turnComplete = serverContent["turnComplete"] as? Boolean
        if (turnComplete == true) {
            // If we received audio but no transcription, generate a placeholder
            if (receivedAudioInCurrentTurn && !hasTranscriptionForCurrentTurn) {
                currentOutputTranscription.append("[AI speaking...]")
                Log.d(TAG, "📝 Generated placeholder transcription (no text from API)")
            }
            
            val fullInput = currentInputTranscription.toString()
            val fullOutput = currentOutputTranscription.toString()
            
            // Send final update with isFinal=true
            callbacks.onTranscriptionUpdate(fullInput, fullOutput, true)
            
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
        isRecording.set(false)
        isPlaying.set(false)
        isAIPlaying.set(false)
        
        // Enhanced SCO cleanup using helper
        scoHelper?.disconnectSco()
        scoHelper?.cleanup()
        scoHelper = null

        // Release audio effects
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        automaticGainControl?.release()
        automaticGainControl = null

        // Stop and release audio components
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioTrack?.stop()
        audioTrack?.flush()
        audioTrack?.release()
        audioTrack = null

        // Close WebSocket
        webSocket?.close(1000, "Session ended")
        webSocket = null

        // Clear audio queue
        synchronized(audioQueueLock) {
            audioQueue.clear()
        }

        // Reset transcriptions
        currentInputTranscription.clear()
        currentOutputTranscription.clear()

        Log.d(TAG, "🧹 Enhanced cleanup complete - Glass headset disconnected")
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
