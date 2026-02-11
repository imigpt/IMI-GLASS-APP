package com.sdk.glassessdksample

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
// removed ToneGenerator import (no beep fallback)
import android.media.MediaPlayer
import android.media.MediaScannerConnection
import android.media.ToneGenerator
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.ContactsContract
import android.provider.CalendarContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.android.gms.location.LocationServices
import okhttp3.OkHttpClient
import okhttp3.Request
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.wifi.GlassesControl
import com.oudmon.wifi.bean.GlassAlbumEntity
import com.sdk.glassessdksample.databinding.ActivityMainBinding
import com.sdk.glassessdksample.ui.*
import com.sdk.glassessdksample.ui.wifi.WifiTransferManager
import com.sdk.glassessdksample.ui.wifi.WifiP2PLiveCamera
import com.sdk.glassessdksample.ui.wifi.GlassMediaTransfer
import com.sdk.glassessdksample.ui.gallery.GlassMediaGalleryActivity
import com.sdk.glassessdksample.utils.SafeBleCommandHelper
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val TAG = "SmartGlassAI"
    private lateinit var binding: ActivityMainBinding
    private var audioManager: AudioManager? = null

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var customVoiceDetector: CustomVoiceDetector? = null
    private var geminiClient: GeminiLiveApiClient? = null
    private var visionClient: GeminiAIClient? = null // For image analysis
    private var geminiLiveService: GeminiLiveService? = null // Gemini Live API for bidirectional audio

    private var isListening = false
    private var isInConversationMode = false
    private var isGeminiLiveMode = false // Track if using Gemini Live bidirectional audio
    private var aiIsPlaying = false // true when AI audio playback is active; used to prevent interrupts
    private var isInterruptEnabled = false // Interrupt mode: when enabled, user voice stops AI immediately
    private var lastCapturedPhoto: ByteArray? = null // Store last photo for object detection
    private var lastSavedPhotoFile: File? = null
    private var glassBatteryLevel: Int? = null // Store glass battery percentage
    private var isWaitingForVisionPhoto = false // Flag to auto-analyze next photo
    private var lastVisionRequestType: String? = null // "person" | "general"
    // Interactive Vision Mode flags (step-by-step photo capture)
    private var isInteractiveVisionMode = false // Control step-by-step mode
    private var isProcessingVisionRequest = false // Prevent double clicks
    private var currentTtsJob: Job? = null
    private var noMatchRetryCount = 0
    // Speech recognizer error/backoff tracking
    private var lastSpeechErrorTime: Long = 0L
    private var speechErrorCount: Int = 0
    private val speechErrorWindowMs: Long = 5000L
    private val speechErrorThreshold = 6
    private var speechBackoffUntil: Long = 0L
    private var useGeminiAck: Boolean = true
    private lateinit var prefs: android.content.SharedPreferences
    
    // Timing configurations (tuned for instant replies)
    private var wakeToListenDelayMs: Long = 100L
    private var scoPollTimeoutMs: Long = 1200L
    private var scoPollIntervalMs: Long = 80L
    private val sttSafetyDelayMs = 100L
    
    private var glassStreamingAvailable = false
    private var waitingForManualStream = false
    
    private var scoConnectionReceiver: BroadcastReceiver? = null
    private var pendingScoSpeechText: String? = null
    private var pendingScoUtteranceId: String? = null
    private var scoStateDeferred: CompletableDeferred<Int>? = null
    private var scoHelper: ScoConnectionHelper? = null
    
    // Broadcast receiver for resuming Gemini Live after VisionChat completes
    private var geminiLiveResumeReceiver: BroadcastReceiver? = null

    // Live Camera Streaming - Direct Bluetooth photo capture + Gemini AI
    // NO WiFi/Hotspot needed - works via Bluetooth directly
    private var liveCameraJob: Job? = null
    private var isLiveCameraActive = false
    private val CAMERA_CAPTURE_INTERVAL_MS = 2000L // Capture every 2 seconds
    private var lastLiveCameraAnalysis = ""
    private var lastLiveCameraSpeakTime = 0L

    private val aiHistory = mutableListOf<Pair<String, String>>()
    
    // Pending action for multi-step interactions (Spotify, YouTube, calling, etc.)
    private var pendingAction: ((String) -> Unit)? = null
    private var pendingDistanceOrigin: String? = null
    private var pendingDistanceDestination: String? = null
    
    // WiFi Transfer Manager for downloading media from glasses
    private var wifiTransferManager: WifiTransferManager? = null
    
    // WiFi P2P Live Camera for fast image streaming via WiFi + Mobile Data for AI
    private var wifiP2PLiveCamera: WifiP2PLiveCamera? = null
    private var isWifiP2PLiveMode = false
    
    // 🆕 Thinking tune player for Gemini Live delays
    private var geminiThinkingPlayer: MediaPlayer? = null
    private var thinkingTuneStartTime: Long = 0
    private var lastUserInputTime: Long = 0
    private val THINKING_TUNE_DELAY_MS = 800L // Start tuning if no AI response within 0.8s (faster feedback)
    
    // Image Upload Service for transferring photos to server
    private var imageUploadService: ImageUploadService? = null
    // Weather service (real data)
    private var weatherService: WeatherService? = null
    
    // News API Service for news fetching
    private var newsApiService: NewsApiService? = null
    
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val REQUEST_RECORD_AUDIO_CODE = 201
    private val REQUEST_READ_CONTACTS = 302
    private val REQUEST_CALL_PHONE = 303
    private val REQUEST_BLUETOOTH_CONNECT = 401
    private val REQUEST_POST_NOTIFICATIONS = 501
    private val REQUEST_BACKGROUND_LISTENING = 502
    private var voiceCommandEnabled = false
    private var backgroundListeningEnabled = true // Allow background listening by default
    
    // Double-click detection for physical button on glasses
    private var lastButtonPressTime: Long = 0L
    private var lastButtonCode: Int = -1
    private val DOUBLE_CLICK_INTERVAL_MS = 500L // Max time between clicks to count as double-click
    
    // Chat adapter and messages
    private lateinit var chatAdapter: ChatAdapter
    private val chatMessages = mutableListOf<ChatMessage>()
    
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { /* no-op */ }
    private val deviceNotifyListener by lazy { MyDeviceNotifyListener() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        initSpeechListener()
        
        // Initialize SharedPreferences first (required by other services)
        prefs = getSharedPreferences("imi_prefs", MODE_PRIVATE)
        useGeminiAck = prefs.getBoolean("useGeminiAck", true)
        // If there is no News API key set, write the provided key into prefs
        try {
            val currentNewsKey = prefs.getString("news_api_key", "YOUR_API_KEY_HERE")
            if (currentNewsKey == null || currentNewsKey == "YOUR_API_KEY_HERE" || currentNewsKey.isBlank()) {
                // Provided key requested to be stored by user
                prefs.edit().putString("news_api_key", "bc0f2136-d476-49b7-afc0-3814a9b0d362").apply()
                Log.d(TAG, "🔐 News API key written to prefs")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write News API key to prefs: ${e.message}")
        }
        
        customVoiceDetector = CustomVoiceDetector(this)
        geminiClient = GeminiLiveApiClient()
        visionClient = GeminiAIClient() // Initialize vision client
        wifiTransferManager = WifiTransferManager(this) // Initialize WiFi transfer
        
        // Initialize WiFi P2P Live Camera for fast image streaming
        initWifiP2PLiveCamera()
        
        // Image upload feature temporarily disabled (removed per request)
        val uploadUrl = prefs.getString("image_upload_url", "http://10.0.2.2:8080/upload") ?: "http://10.0.2.2:8080/upload"
        imageUploadService = null
        
        // Initialize News API Service (get free key from newsapi.org)
        val newsApiKey = prefs.getString("news_api_key", "YOUR_API_KEY_HERE") ?: "YOUR_API_KEY_HERE"
        newsApiService = NewsApiService(newsApiKey)

        // Initialize Weather Service (OpenWeatherMap). Get free key from openweathermap.org
        val weatherApiKey = prefs.getString("weather_api_key", "YOUR_API_KEY_HERE") ?: "YOUR_API_KEY_HERE"
        weatherService = WeatherService(weatherApiKey)
        
        // Initialize Gemini Live Service for bidirectional audio streaming
        initializeGeminiLive()
        
        // Load conversation history from cache
        loadConversationHistory()
        
        setupScoConnectionListener()
        // Prepare SCO helper for routing microphone to glasses when available
        scoHelper = ScoConnectionHelper(this)
        initView()
        // Wire photo preview download button
        try {
            binding.btnSavePhoto.setOnClickListener {
                try {
                    saveLastPhotoToDownloads()
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving last photo to downloads: ${e.message}", e)
                    runOnUiThread { Toast.makeText(this, "Failed to save photo: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        } catch (e: Exception) {
            // If view binding missing (older layout), skip
        }
        initGlassWifiListener()
 
        LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
        
        // Request all runtime permissions at first launch
        requestAllPermissionsAtOnce()
    }
    
    private fun requestAllPermissionsAtOnce() {
        // Check if this is first launch
        val isFirstLaunch = prefs.getBoolean("first_launch", true)
        
        val permissionsNeeded = mutableListOf<String>()
        
        // Essential permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_CONTACTS)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CALL_PHONE)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        // Storage permissions (Android 13+ uses READ_MEDIA_*)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            // POST_NOTIFICATIONS required on Android 13+ for foreground service notification
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        // Bluetooth permissions (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
        
        if (permissionsNeeded.isNotEmpty()) {
            Log.d(TAG, "🔒 Requesting ${permissionsNeeded.size} permissions: ${permissionsNeeded.joinToString()}")
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), REQUEST_BACKGROUND_LISTENING)
            
            // Mark that we've requested permissions (not first launch anymore)
            if (isFirstLaunch) {
                prefs.edit().putBoolean("first_launch", false).apply()
            }
        } else {
            Log.d(TAG, "✅ All permissions already granted")
            // Start HotHelper if permissions available
            try {
                Log.d(TAG, "🎙️ Starting HotHelper for wake word detection")
                HotHelper.getInstance(this).start()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start HotHelper: ${e.message}", e)
            }
        }
        
        // Register broadcast receiver to resume Gemini Live after VisionChat TTS finishes
        registerGeminiLiveResumeReceiver()
    }
    
    /**
     * Register broadcast receiver for resuming Gemini Live after VisionChat completes
     */
    private fun registerGeminiLiveResumeReceiver() {
        geminiLiveResumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == VisionChatActivity.ACTION_RESUME_GEMINI_LIVE) {
                    // 🆕 Get vision text from broadcast (if available)
                    val visionText = intent.getStringExtra(VisionChatActivity.EXTRA_VISION_TEXT)
                    Log.d(TAG, "📡 Received broadcast to resume Gemini Live. Vision text: ${visionText?.take(50)}...")
                    
                    // Small delay to ensure VisionChatActivity has fully finished
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (visionText != null) {
                            // 🆕 Start Gemini Live with instruction to speak the vision result
                            Log.d(TAG, "🎙️ Starting Gemini Live to speak vision result...")
                            startGeminiLiveWithVisionResult(visionText)
                        } else {
                            // Regular resume without vision text
                            Log.d(TAG, "🎙️ Auto-resuming Gemini Live conversation...")
                            startGeminiLiveConversation()
                        }
                    }, 500)
                }
            }
        }
        
        val filter = IntentFilter(VisionChatActivity.ACTION_RESUME_GEMINI_LIVE)
        LocalBroadcastManager.getInstance(this).registerReceiver(geminiLiveResumeReceiver!!, filter)
        Log.d(TAG, "📡 Registered Gemini Live resume receiver")
    }
    
    /**
     * 🆕 Start Gemini Live with a vision result to speak immediately
     * The AI will naturally speak the vision analysis in its own voice
     * 🆕 NOW: Just unmutes Gemini Live (kept active) and injects vision text
     */
    private fun startGeminiLiveWithVisionResult(visionText: String) {
        try {
            // 🔊 UNMUTE Gemini Live - it was kept active but muted during vision processing
            geminiLiveService?.unmuteOutput()
            Log.d(TAG, "🔊 Gemini Live unmuted - ready to speak vision result")
            
            // Inject the vision text for Gemini to speak naturally
            geminiLiveService?.speakText(visionText, speakDirectly = true)
            Log.i(TAG, "🎙️ Vision result sent to Gemini Live for speaking")
            
            updateConversation("System", "🎧 Speaking vision result...")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to speak vision result: ${e.message}", e)
            Toast.makeText(this@MainActivity, "Failed to speak: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun onStop() {
        super.onStop()
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        
        // Start background listening service when app goes to background
        if (backgroundListeningEnabled && (isInConversationMode || isGeminiLiveMode)) {
            try {
                Log.d(TAG, "📱 App backgrounded - starting ListeningService")
                val svc = Intent(this, ListeningService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start ListeningService: ${e.message}", e)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Resume listening if conversation mode was active
        // BUT: Don't resume if Gemini Live mode is active (it handles its own audio)
        if (isInConversationMode && !isListening && !isGeminiLiveMode) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isInConversationMode && !isListening && !isGeminiLiveMode) {
                    Log.d(TAG, "🔄 Resuming listening after app resume (Traditional mode)")
                    try {
                        startListening()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resuming listening: ${e.message}")
                        // Retry once more
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (isInConversationMode && !isListening && !isGeminiLiveMode) {
                                try { startListening() } catch (ex: Exception) {}
                            }
                        }, 500)
                    }
                }
            }, 300)
        } else if (isGeminiLiveMode) {
            Log.d(TAG, "🎙️ App resumed - Gemini Live already active, no action needed")
        }
    }

    override fun onDestroy() {
        // OPTIMIZATION: Clean up BEFORE super.onDestroy to prevent leaks
        try {
            scoConnectionReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver: ${e.message}")
        }
        
        // Unregister Gemini Live resume receiver
        try {
            geminiLiveResumeReceiver?.let {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
                Log.d(TAG, "📡 Unregistered Gemini Live resume receiver")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering Gemini Live resume receiver: ${e.message}")
        }
        
        // Clean up glass headset if active
        try {
            if (isGeminiLiveMode) {
                disableGlassHeadset()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error disabling glass headset on destroy: ${e.message}")
        }

        // Clean up Gemini Live Service
        try {
            geminiLiveService?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying Gemini Live: ${e.message}")
        }
        
        tts?.stop()
        tts?.shutdown()
        
        speechRecognizer?.destroy()
        speechRecognizer = null
        
        customVoiceDetector?.release()
        mainScope.cancel()
        LargeDataHandler.getInstance().removeOutDeviceListener(100)
        
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_RECORD_AUDIO_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    try {
                        customVoiceDetector?.startDetection()
                        Toast.makeText(this, "Hey Imi is ready!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting detector: ${e.message}")
                    }
                } else {
                    Toast.makeText(this, "Microphone denied. Wake word won't work.", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_READ_CONTACTS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Contacts access granted", Toast.LENGTH_SHORT).show()
                } else {
                    speakOut("Contacts permission denied. Can't access phonebook.", "ERROR")
                }
            }
            REQUEST_CALL_PHONE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Call permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    speakOut("Call permission denied", "ERROR")
                }
            }
            REQUEST_BLUETOOTH_CONNECT -> {
                // If any of the requested permissions were granted, try to enable glass mic
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    enableGlassMic()
                } else {
                    Toast.makeText(this, "Permissions denied. Using phone mic.", Toast.LENGTH_SHORT).show()
                    // Glass mic switch removed from UI; ensure we don't attempt to toggle it here
                }
            }
            REQUEST_BACKGROUND_LISTENING, REQUEST_POST_NOTIFICATIONS -> {
                val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    Log.d(TAG, "✅ Background listening permissions granted")
                    Toast.makeText(this, "Background listening enabled", Toast.LENGTH_SHORT).show()
                    // Start HotHelper now that we have permissions
                    try {
                        Log.d(TAG, "🎙️ Starting HotHelper after permission grant")
                        HotHelper.getInstance(this).start()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to start HotHelper: ${e.message}", e)
                    }
                } else {
                    Log.w(TAG, "⚠️ Background listening permissions denied")
                    Toast.makeText(this, "Microphone/notification permission denied. Background listening won't work.", Toast.LENGTH_LONG).show()
                    backgroundListeningEnabled = false
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Configure high-quality voice first
            setupHighQualityVoice()
            
            // Optimize speech parameters for natural, pleasant conversation
            tts?.setSpeechRate(0.95f)  // Slightly slower for clarity and warmth
            tts?.setPitch(1.08f)  // Slightly higher for pleasant, engaging female tone
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started: $utteranceId")
                }
                
                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "✅ TTS finished: $utteranceId, conversationMode=$isInConversationMode")
                    
                    // Clean audio ONCE at the end - not multiple times
                    mainScope.launch {
                        try {
                            delay(100) // Brief delay before cleanup
                            audioManager?.let { am ->
                                if (am.isBluetoothScoOn) {
                                    am.stopBluetoothSco()
                                    Log.d(TAG, "🔇 Bluetooth SCO stopped")
                                }
                                if (am.mode != AudioManager.MODE_NORMAL) {
                                    am.mode = AudioManager.MODE_NORMAL
                                }
                            }
                            audioManager?.abandonAudioFocus(audioFocusListener)
                        } catch (ex: Exception) {
                            Log.w(TAG, "Audio cleanup error: ${ex.message}")
                        }
                    }
                    
                    // Continue conversation after ANY AI response
                    if (isInConversationMode) {
                        Log.d(TAG, "🔄 Continuing conversation - starting listener after TTS")
                            mainScope.launch {
                                delay(150) // Shorter pause for snappier interactions
                                startListening()
                            }
                    } else if (utteranceId?.contains("GOODBYE") != true) {
                        // Restart wake word detection if not in conversation
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                HotHelper.getInstance(this@MainActivity).start()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error restarting Porcupine: ${e.message}")
                            }
                        }, 500)
                    }
                }

                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error: $utteranceId")
                    isListening = false
                }
            })
        }
    }

    /**
     * Setup high-quality TTS voice for English and Hindi with premium natural voices
     */
    private fun setupHighQualityVoice() {
        tts?.let { textToSpeech ->
            val currentLocale = Locale.getDefault()
            val isHindi = currentLocale.language == "hi" || currentLocale.language == "hin"
            
            // Set language based on system locale
            val targetLocale = if (isHindi) Locale("hi", "IN") else Locale.US
            
            val result = textToSpeech.setLanguage(targetLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Language not supported, falling back to default")
                textToSpeech.language = Locale.getDefault()
            }
            
            // Try to select the highest quality voice available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val voices = textToSpeech.voices
                
                // Log all available voices for debugging
                Log.d(TAG, "📢 Available TTS voices:")
                voices?.forEach { voice ->
                    Log.d(TAG, "  ${voice.name} - Lang: ${voice.locale.language}, Quality: ${voice.quality}, Network: ${voice.isNetworkConnectionRequired}")
                }
                
                val preferredVoice = voices?.filter { voice ->
                    voice.locale.language == targetLocale.language &&
                    voice.quality >= 300 && // Accept good quality and above (lowered from 400)
                    voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == false
                }?.maxByOrNull { voice ->
                    // Prefer enhanced/premium voices with natural characteristics
                    var score = voice.quality * 2 // Prioritize quality heavily
                    
                    // Boost scores for premium voice names
                    if (voice.name.contains("enhanced", ignoreCase = true)) score += 200
                    if (voice.name.contains("premium", ignoreCase = true)) score += 200
                    if (voice.name.contains("wavenet", ignoreCase = true)) score += 250 // Google WaveNet
                    if (voice.name.contains("neural", ignoreCase = true)) score += 250 // Neural TTS
                    if (voice.name.contains("natural", ignoreCase = true)) score += 150
                    if (voice.name.contains("expressive", ignoreCase = true)) score += 150
                    
                    // Prefer female voices for warmer, pleasant tone
                    if (voice.name.contains("female", ignoreCase = true)) score += 100
                    if (voice.name.contains("woman", ignoreCase = true)) score += 100
                    
                    // Boost specific high-quality Google voices
                    when {
                        voice.name.contains("en-in", ignoreCase = true) -> score += 80 // Indian English
                        voice.name.contains("hi-in", ignoreCase = true) -> score += 80 // Indian Hindi
                        voice.name.contains("en-us", ignoreCase = true) -> score += 60 // US English
                    }
                    
                    // Slightly prefer offline voices for reliability (small boost)
                    if (!voice.isNetworkConnectionRequired) score += 20
                    
                    Log.d(TAG, "  Voice ${voice.name} scored: $score")
                    score
                }
                
                preferredVoice?.let {
                    textToSpeech.voice = it
                    Log.d(TAG, "✨ Selected premium voice: ${it.name} (quality: ${it.quality}, network: ${it.isNetworkConnectionRequired})")
                } ?: run {
                    Log.w(TAG, "⚠️ No premium voice found, using default")
                    // Log default voice being used
                    Log.d(TAG, "Default voice: ${textToSpeech.voice?.name}")
                }
            }
        }
    }
    /**
     * Speak text using Enhanced Local TTS
     * Uses best available device voice with optimized settings
     */
    private fun speakOut(text: String?, utteranceId: String = "DEFAULT") {
        // ✅ ENABLE for specific cases like Vision Chat trigger
        if (text == null || text.isEmpty()) {
            Log.w(TAG, "speakOut: empty text")
            return
        }
        
        // Allow Vision Chat and important notifications
        if (utteranceId == "VISION" || utteranceId == "ERROR") {
            Log.d(TAG, "🔊 Speaking: $text (ID: $utteranceId)")
            speakWithLocalTTS(text, utteranceId)
        } else {
            // Other cases: Use Gemini Live audio
            Log.d(TAG, "speakOut disabled (Live API mode): $text")
        }
    }
    
    /**
     * Speak using local Android TTS
     */
    private fun speakWithLocalTTS(text: String, utteranceId: String) {
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        
        // Add audio quality parameters for clearer output
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            params.putInt(TextToSpeech.Engine.KEY_PARAM_VOLUME, 100) // Full volume
        }
        try {
            audioManager?.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        } catch (e: Exception) {}
        
        // Use high-quality synthesis with proper audio routing
        this@MainActivity.tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun speakOnGlass(text: String, utteranceId: String = "DEFAULT_GLASS") {
        // BLE glasses don't have speaker - use phone speaker with glasses text display
        Log.d(TAG, "speakOnGlass: '$text' - speaking on phone, showing on glass")
        
        // Show text on glasses display (0x03 = text display, if supported)
        try {
            val displayCmd = ByteArray(text.length + 2)
            displayCmd[0] = 0x03
            displayCmd[1] = text.length.toByte()
            System.arraycopy(text.toByteArray(Charsets.UTF_8), 0, displayCmd, 2, minOf(text.length, 250))
            LargeDataHandler.getInstance().glassesControl(displayCmd) { code, _ ->
                Log.d(TAG, "Glass display: ${if (code == 0) "✅" else "code=$code"}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Glass display error: ${e.message}")
        }
        
        // Speak on phone speaker (with proper utterance ID for callback)
        speakOut(text, utteranceId)
    }

    /**
     * Play a short, low-latency chime routed over Bluetooth SCO if available.
     * Uses ToneGenerator for minimal overhead and warms SCO briefly before playing.
     */
    fun playImmediateChime() {
        try {
            audioManager?.let { am ->
                try {
                    // Request transient focus on voice call stream
                    am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                } catch (_: Exception) {}

                // Try to enable SCO routing quickly
                try { if (!am.isBluetoothScoOn) { am.setBluetoothScoOn(true) } } catch (_: Exception) {}
                try { am.startBluetoothSco() } catch (_: Exception) {}
                try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
            }

            // Play tone after a tiny settle to let routing take effect
            mainScope.launch {
                var waited = 0
                while (waited < 700 && (audioManager?.isBluetoothScoOn != true)) {
                    delay(80)
                    waited += 80
                }

                try {
                    val tg = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 100)
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
                    delay(220)
                    tg.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Immediate chime failed: ${'$'}{e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in playImmediateChime: ${'$'}{e.message}", e)
        }
    }

    private fun routeTtsToBluetoothAndSpeak(text: String, utteranceId: String) {
        try {
            audioManager?.let { am ->
                try {
                    if (!am.isBluetoothScoOn) {
                        // Prepare SCO and request focus for voice call stream
                        pendingScoSpeechText = text
                        pendingScoUtteranceId = utteranceId
                        try { am.setBluetoothScoOn(true) } catch (_: Exception) {}
                        try { am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) } catch (_: Exception) {}
                        try { am.startBluetoothSco() } catch (_: Exception) {}

                        mainScope.launch {
                            val connected = tryStartScoWithRetries(5000L)
                            if (connected) {
                                try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
                                try { am.setBluetoothScoOn(true) } catch (_: Exception) {}
                                speakViaSco(text, utteranceId)
                            } else {
                                // Fallback to PCM Streaming
                                val pcmSent = try {
                                    streamPcmAudioToGlass(text, utteranceId)
                                } catch (e: Exception) { false }

                                if (!pcmSent) {
                                    speakOut(text, utteranceId)
                                }
                            }
                            pendingScoSpeechText = null
                            pendingScoUtteranceId = null
                        }
                    } else {
                        try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
                        try { am.setBluetoothScoOn(true) } catch (_: Exception) {}
                        speakViaSco(text, utteranceId)
                    }
                } catch (ex: Exception) {
                    speakOut(text, utteranceId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback TTS failed: ${e.message}")
        }
    }
    
    private fun setupScoConnectionListener() {
        // Old SCO connection listener removed — using automatic SCO routing via MODE_IN_CALL and startBluetoothSco()
        // Keep this method as a no-op placeholder to avoid changing call sites.
        Log.d(TAG, "setupScoConnectionListener: no-op (automatic SCO routing enabled)")
    }

    /**
     * Attempt to start SCO (glass mic) before launching Gemini Live conversation.
     * If SCO connects within timeout, use glass mic; otherwise fall back to phone mic.
     */
    private fun startGeminiAfterSco(timeoutMs: Long = 1500L) {
        mainScope.launch {
            val helper = scoHelper ?: ScoConnectionHelper(this@MainActivity).also { scoHelper = it }
            Log.d(TAG, "🔔 Trying SCO before starting Gemini Live (timeout=${timeoutMs}ms)")

            val connected = withTimeoutOrNull(timeoutMs) {
                helper.connectScoWithRetries()
            } ?: false

            if (connected) {
                Log.d(TAG, "✅ SCO connected — proceeding with glass mic")
                // allow mic to stabilize briefly
                delay(120)
            } else {
                Log.w(TAG, "⚠️ SCO not connected in time — falling back to phone mic")
            }

            try {
                startGeminiLiveConversation()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Gemini Live after SCO attempt: ${e.message}")
            }
        }
    }

    // ========== OPTION A: DUAL CONNECTION SETUP ==========

    /**
     * Check both BLE and Audio connections before starting Gemini Live
     */
    private fun checkDualConnection(): Boolean {
        val bleConnected = checkBLEConnection()
        val audioConnected = checkAudioConnection()

        Log.d(TAG, "🔍 Dual Connection Status:")
        Log.d(TAG, "   📡 BLE (Data): ${if (bleConnected) "✅" else "❌"}")
        Log.d(TAG, "   🎧 Audio (Mic): ${if (audioConnected) "✅" else "❌"}")

        return bleConnected && audioConnected
    }

    /**
     * Check if BLE connection is active (for data/commands)
     */
    private fun checkBLEConnection(): Boolean {
        return try {
            com.oudmon.ble.base.bluetooth.BleOperateManager.getInstance()?.isConnected ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking BLE: ${e.message}")
            false
        }
    }

    /**
     * Check if System Bluetooth audio connection is active (for microphone)
     */
    private fun checkAudioConnection(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return audioManager?.isBluetoothScoAvailableOffCall == true
    }

    /**
     * Show setup guide if connections are missing
     */
    private fun promptMissingConnections() {
        val bleConnected = checkBLEConnection()
        val audioConnected = checkAudioConnection()

        when {
            !bleConnected && !audioConnected -> {
                // Both missing - show full setup guide
                showFullSetupGuide()
            }
            !bleConnected -> {
                // Only BLE missing
                showBLESetupGuide()
            }
            !audioConnected -> {
                // Only audio missing - most common case
                showAudioSetupGuide()
            }
        }
    }

    /**
     * Guide for setting up audio connection (most common)
     */
    private fun showAudioSetupGuide() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("🎤 Setup Glass Microphone")
            .setMessage(
                "Your glasses are connected for data ✅\n\n" +
                "To enable the glass microphone:\n\n" +
                "1️⃣ Open Bluetooth Settings\n" +
                "2️⃣ Find your glasses in paired devices\n" +
                "3️⃣ Tap the ⚙️ settings icon\n" +
                "4️⃣ Enable 'Phone Audio' or 'Call Audio'\n\n" +
                "This is separate from the data connection.\n" +
                "Skip if you want to use phone mic."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                openBluetoothSettings()
            }
            .setNegativeButton("Use Phone Mic") { dialog, _ ->
                Toast.makeText(this, "Using phone microphone", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNeutralButton("Help") { _, _ ->
                showDetailedAudioSetupHelp()
            }
            .setCancelable(false)
            .create()

        dialog.show()
    }

    /**
     * Guide for setting up BLE connection
     */
    private fun showBLESetupGuide() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("📡 Connect Glasses (Data)")
            .setMessage(
                "Please connect your glasses for data first:\n\n" +
                "1️⃣ Click the 'BT' button in the app\n" +
                "2️⃣ Select your glasses from the list\n" +
                "3️⃣ Wait for connection\n\n" +
                "After data connection, we'll setup audio."
            )
            .setPositiveButton("Open BT Scanner") { _, _ ->
                startKtxActivity<DeviceBindActivity>()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        dialog.show()
    }

    /**
     * Full setup guide (both connections missing)
     */
    private fun showFullSetupGuide() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("🔌 Setup Smart Glasses")
            .setMessage(
                "Your glasses need TWO connections:\n\n" +
                "📡 Data Connection (BLE):\n" +
                "   • Use the 'BT' button in app\n" +
                "   • For photos, commands, text\n\n" +
                "🎧 Audio Connection (System BT):\n" +
                "   • Go to Bluetooth Settings\n" +
                "   • Enable 'Phone Audio'\n" +
                "   • For microphone & speaker\n\n" +
                "Let's start with data connection:"
            )
            .setPositiveButton("Setup Data") { _, _ ->
                startKtxActivity<DeviceBindActivity>()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        dialog.show()
    }

    /**
     * Detailed help with screenshots/instructions
     */
    private fun showDetailedAudioSetupHelp() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("📖 Glass Mic Setup Guide")
            .setMessage(
                "Step-by-step instructions:\n\n" +
                "1️⃣ Open Android Settings\n" +
                "2️⃣ Tap 'Connected Devices' or 'Bluetooth'\n" +
                "3️⃣ Find your glasses in the list\n" +
                "4️⃣ Tap the ⚙️ icon next to the name\n" +
                "5️⃣ You'll see these options:\n" +
                "   ✅ Phone Audio (enable this!)\n" +
                "   ✅ Media Audio (optional)\n" +
                "   ✅ Contact Sharing (optional)\n" +
                "6️⃣ Return to this app\n" +
                "7️⃣ Say 'Hey Imi' to test\n\n" +
                "Note: Your glasses stay connected for data.\n" +
                "This just adds audio routing."
            )
            .setPositiveButton("Got It") { dialog, _ ->
                openBluetoothSettings()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        dialog.show()
    }

    /**
     * Open Android Bluetooth settings
     */
    private fun openBluetoothSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            startActivity(intent)

            // Show reminder toast after 1 second
            Handler(Looper.getMainLooper()).postDelayed({
                Toast.makeText(
                    this,
                    "Find your glasses and enable 'Phone Audio'",
                    Toast.LENGTH_LONG
                ).show()
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Bluetooth settings: ${e.message}")
            Toast.makeText(this, "Please open Bluetooth settings manually", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * REPLACE your existing startGeminiLiveConversation()
     */
    private fun startGeminiLiveConversation() {
        try {
            // Check dual connection before starting
            val hasFullConnection = checkDualConnection()

            if (!hasFullConnection) {
                Log.w(TAG, "⚠️ Missing required connections")
                promptMissingConnections()

                // Ask if user wants to continue with phone mic
                Handler(Looper.getMainLooper()).postDelayed({
                    val audioConnected = checkAudioConnection()
                    if (!audioConnected) {
                        val confirmDialog = android.app.AlertDialog.Builder(this)
                            .setTitle("Continue with Phone Mic?")
                            .setMessage(
                                "Glass microphone is not connected.\n\n" +
                                "Start conversation with phone mic instead?"
                            )
                            .setPositiveButton("Yes") { _, _ ->
                                proceedWithGeminiLive()
                            }
                            .setNegativeButton("Setup First") { _, _ ->
                                showAudioSetupGuide()
                            }
                            .create()

                        confirmDialog.show()
                    }
                }, 500)

                return
            }

            // Both connections OK - proceed
            proceedWithGeminiLive()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Gemini Live: ${e.message}", e)
            Toast.makeText(this, "Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Actually start Gemini Live (called after connection check)
     */
    private fun proceedWithGeminiLive() {
        Log.d(TAG, "🎙️ Starting Gemini Live with dual connection...")

        // Enable glass headset mode (prefer the full SCO-enabled helper)
        enableGlassHeadsetForGeminiLive()

        val systemInstruction = """
            You are Imi Glass, an intelligent AI assistant integrated into smart glasses.
            Keep your responses very concise (1-2 sentences maximum).
            Be helpful, friendly, and conversational.
            Respond in the same language the user speaks.
            You can help with questions, provide information, and assist with tasks.
        """.trimIndent()

        // Wait for SCO to be active and stable before starting the live conversation.
        mainScope.launch(Dispatchers.Main) {
            try {
                val scoReady = awaitScoState(AudioManager.SCO_AUDIO_STATE_CONNECTED, 400L)
                if (scoReady) {
                    // Small stabilization time for SCO DSP/codec (shorter for snappier start)
                    delay(100)
                    Log.d(TAG, "SCO confirmed and stable - starting Gemini Live")
                } else {
                    Log.w(TAG, "SCO not confirmed within timeout - starting Gemini Live anyway (phone mic fallback)")
                }

                try {
                    geminiLiveService?.startLiveConversation(systemInstruction)
                    updateConversation("System", "🎧 Glass Mic Active - Speak naturally!")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start Gemini Live after SCO wait: ${e.message}")
                    Toast.makeText(this@MainActivity, "Failed to start Gemini Live: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error waiting for SCO before Gemini Live: ${e.message}")
                try {
                    geminiLiveService?.startLiveConversation(systemInstruction)
                } catch (ex: Exception) { Log.e(TAG, "Fallback startGeminiLive failed: ${ex.message}") }
            }
        }
    }

    /**
     * Simplified glass headset enable (Option A - relies on system audio)
     */
    private fun enableGlassHeadsetSimple() {
        try {
            audioManager?.let { am ->
                Log.d(TAG, "🎧 Enabling Glass Headset (Option A - System Audio)")

                // Simple 3-step process
                am.mode = AudioManager.MODE_IN_CALL
                am.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )

                if (am.isBluetoothScoAvailableOffCall) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }

                // Set volume to max
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0)

                Log.d(TAG, "✅ Glass Headset Mode enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable glass headset: ${e.message}", e)
        }
    }

    /**
     * Add diagnostic button to your UI
     */
    private fun setupConnectionDiagnostics() {
        // Add this to your layout or menu
        // Example: binding.btnDiagnostics?.setOnClickListener { showConnectionDiagnostics() }
    }

    /**
     * Show connection diagnostics dialog
     */
    private fun showConnectionDiagnostics() {
        val bleConnected = checkBLEConnection()
        val audioConnected = checkAudioConnection()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val scoActive = audioManager?.isBluetoothScoOn == true

        val status = buildString {
            appendLine("📊 Connection Diagnostics\n")
            appendLine("${if (bleConnected) "✅" else "❌"} BLE Connection (Data)")
            appendLine("   • Photos, commands, text")
            appendLine("${if (audioConnected) "✅" else "❌"} Audio Connection (System BT)")
            appendLine("   • Microphone & speaker")
            appendLine("   • SCO Active: ${if (scoActive) "✅" else "❌"}\n")

            if (!bleConnected) {
                appendLine("⚠️ BLE not connected")
                appendLine("   → Click 'BT' button in app\n")
            }

            if (!audioConnected) {
                appendLine("⚠️ Audio not connected")
                appendLine("   → Enable 'Phone Audio' in")
                appendLine("   → Bluetooth Settings\n")
            }

            if (bleConnected && audioConnected) {
                appendLine("🎉 All systems ready!")
                appendLine("Say 'Hey Imi' to start")
            }
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Connection Status")
            .setMessage(status)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }

        if (!audioConnected) {
            dialog.setNeutralButton("Setup Audio") { _, _ ->
                showAudioSetupGuide()
            }
        }

        dialog.create().show()
    }

    private suspend fun awaitScoState(targetState: Int, timeoutMs: Long): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                val currentOn = audioManager?.isBluetoothScoOn == true
                if (targetState == AudioManager.SCO_AUDIO_STATE_CONNECTED && currentOn) return@withContext true
                if (targetState == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && !currentOn) return@withContext true
            } catch (e: Exception) {}

            scoStateDeferred = CompletableDeferred()
            val completed = try {
                withTimeoutOrNull(timeoutMs) {
                    scoStateDeferred?.await() == targetState
                } ?: false
            } catch (e: Exception) { false }
            scoStateDeferred = null
            completed
        }
    }

    private suspend fun tryStartScoWithRetries(totalTimeoutMs: Long): Boolean {
        Log.d(TAG, "🔄 Simple SCO start (automatic routing)")
        val am = audioManager ?: return false
        try {
            // Set audio mode to IN_CALL to encourage system routing
            am.mode = AudioManager.MODE_IN_CALL

            // Request audio focus for voice communication
            try {
                am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            } catch (e: Exception) {
                Log.w(TAG, "Audio focus request failed: ${e.message}")
            }

            // Start SCO if available
            if (am.isBluetoothScoAvailableOffCall) {
                try {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                } catch (e: Exception) {
                    Log.w(TAG, "startBluetoothSco failed: ${e.message}")
                }
            }

            // Wait up to totalTimeoutMs for SCO to become active
            val deadline = System.currentTimeMillis() + totalTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (am.isBluetoothScoOn) return true
                delay(100)
            }
            return am.isBluetoothScoOn
        } catch (e: Exception) {
            Log.e(TAG, "SCO start failed: ${e.message}")
            return false
        }
    }

    private suspend fun streamPcmAudioToGlass(text: String, utteranceId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Synthesize TTS to WAV file (16kHz, 16-bit PCM mono to match Glass specs)
            val tempFile = File.createTempFile("tts_pcm_", ".wav", cacheDir)
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "$utteranceId-pcm")
            
            tts?.synthesizeToFile(text, params, tempFile, "$utteranceId-pcm")
            
            // Wait for synthesis (max 5 seconds)
            var waitCount = 0
            while ((!tempFile.exists() || tempFile.length() == 0L) && waitCount < 50) {
                delay(100)
                waitCount++
            }
            
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.e(TAG, "TTS synthesis failed or timeout")
                tempFile.delete()
                return@withContext false
            }
            
            val audioBytes = tempFile.readBytes()
            tempFile.delete()
            
            // Skip 44-byte WAV header to get raw PCM
            val pcmData = if (audioBytes.size > 44) audioBytes.copyOfRange(44, audioBytes.size) else audioBytes
            Log.d(TAG, "PCM data size: ${pcmData.size} bytes")
            
            // Send PCM in chunks (larger chunks = faster transmission)
            val chunkSize = 2048
            var offset = 0
            var successCount = 0
            var failCount = 0
            
            while (offset < pcmData.size) {
                val end = minOf(offset + chunkSize, pcmData.size)
                val chunk = pcmData.copyOfRange(offset, end)
                
                val sent = suspendCancellableCoroutine<Boolean> { cont ->
                    LargeDataHandler.getInstance().glassesControl(chunk) { code, _ ->
                        if (!cont.isCompleted) cont.resume(code == 0)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!cont.isCompleted) cont.resume(false)
                    }, 5000)
                }
                
                if (sent) successCount++ else failCount++
                offset = end
                delay(10) // Small delay between chunks
            }
            
            val success = failCount < (successCount / 3)
            Log.d(TAG, "PCM streaming: success=$successCount, fail=$failCount, result=$success")
            return@withContext success
            
        } catch (e: Exception) {
            Log.e(TAG, "PCM streaming error: ${e.message}")
            return@withContext false
        }
    }
    
    private fun speakViaSco(text: String, utteranceId: String) {
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
        try {
            audioManager?.let { am ->
                try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
                try { am.setBluetoothScoOn(true) } catch (_: Exception) {}
                try { am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.w(TAG, "Error preparing SCO for speakViaSco: ${e.message}") }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    private fun startListening() {
        if (isGeminiLiveMode) {
            Log.d(TAG, "Skipping SpeechRecognizer start because Gemini Live mode is active")
            return
        }
        // If we're in a backoff window due to errors, skip
        if (System.currentTimeMillis() < speechBackoffUntil) {
            Log.w(TAG, "Start suppressed due to backoff")
            return
        }
        
        // 1. Force Stop Hotword Detector (Important!)
        try {
            Log.d(TAG, "🛑 Stopping HotHelper before speech recognition")
            HotHelper.getInstance(this).stop()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping HotHelper: ${e.message}", e)
        }

        // 2. Stop TTS if speaking
        if (tts?.isSpeaking == true) {
            tts?.stop()
        }

        isListening = true
        // Enter conversation mode so recognizer remains active for longer inputs
        isInConversationMode = true
        try {
            val svc = Intent(this, ListeningService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start ListeningService: ${e.message}")
        }
        
        // Prefer re-using the SpeechRecognizer to avoid create/destroy latency.
        // Cancel any in-progress work and start listening quickly. If the
        // recognizer is null (very rare), create it on-demand.
        mainScope.launch(Dispatchers.Main) {
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling recognizer: ${e.message}")
            }

            // tiny pause to allow audio routing to settle
            delay(50)

            startSpeechRecognition()
        }
    }
    
    private fun startSpeechRecognition() {
        mainScope.launch(Dispatchers.Main) {
            try {
                if (speechRecognizer == null) {
                    Log.d(TAG, "🎙️ Creating SpeechRecognizer instance (on-demand)")
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
                    initSpeechListener()

                    // Give system a short time to initialize the recognizer
                    delay(100)
                } else {
                    Log.d(TAG, "🎙️ Reusing existing SpeechRecognizer")
                    // Small settle time to avoid immediate client-busy issues
                    delay(50)
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    // Make recognizer more tolerant to silence and longer utterances
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)
                    // Add this to identify your app to the system
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                }
                
                Log.d(TAG, "🎙️ Listening started...")
                speechRecognizer?.startListening(intent)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recognition: ${e.message}")
                isListening = false
            }
        }
    }
    
    

    private fun initSpeechListener() {
         speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "🛑 User started speaking - checking interrupt mode")
                try {
                    // If INTERRUPT MODE IS ENABLED and AI is currently playing, interrupt immediately
                    if (isInterruptEnabled && aiIsPlaying) {
                        Log.d(TAG, "🛑 [INTERRUPT MODE ON] Stopping AI because user started speaking")
                        try {
                            geminiLiveService?.interruptCurrentResponse()
                        } catch (ex: Exception) {
                            Log.w(TAG, "Failed to interrupt Gemini service: ${ex.message}")
                        }
                        aiIsPlaying = false
                    } else if (!isInterruptEnabled && aiIsPlaying) {
                        Log.d(TAG, "ℹ️ [INTERRUPT MODE OFF] AI continues speaking - user must wait")
                    }
                } catch (e: Exception) {

                    if (tts?.isSpeaking == true) {
                        tts?.stop()
                        currentTtsJob?.cancel()
                    }

                    // Clear the incomplete response from history if present
                    if (aiHistory.isNotEmpty() && aiHistory.last().first == "model") {
                        aiHistory.removeAt(aiHistory.size - 1)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error handling user speech start: ${e.message}")
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    binding.etCommand.setText(text)
                    noMatchRetryCount = 0
                    updateConversation("You", text)
                    
                    if (text.lowercase().matches(Regex(".*(stop|exit|bye|band karo).*"))) {
                        isInConversationMode = false
                        val goodbye = "Goodbye! Say hey imi to continue our chat."
                        updateConversation("Imi", goodbye)
                        speakOut(goodbye, "GOODBYE")
                        isListening = false
                        voiceCommandEnabled = false
                        binding.btnVoice.isEnabled = false
                        pendingAction = null // Clear any pending action
                        // Keep conversation history - don't clear aiHistory
                        return
                    }
                    handleSpokenCommand(text)
                }
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                Log.w(TAG, "🔥 Speech error: $error, conversationMode=$isInConversationMode")
                val now = System.currentTimeMillis()

                // Treat transient/no-match errors separately so they don't count toward critical failures
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> {
                        // Common when recognizer hears nothing useful. Retry a few times quickly.
                        noMatchRetryCount = (noMatchRetryCount + 1).coerceAtMost(12)
                        lastSpeechErrorTime = now
                        
                        // After 10 retries (longer conversation), exit conversation mode
                        if (noMatchRetryCount >= 10) {
                            Log.d(TAG, "NO_MATCH (#$noMatchRetryCount) - exiting conversation mode")
                            isInConversationMode = false
                            noMatchRetryCount = 0
                            try { speechRecognizer?.cancel() } catch (_: Exception) {}
                            // Restart wake word detection
                            try {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    HotHelper.getInstance(this@MainActivity).start()
                                }, 500)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to restart wake word: ${e.message}")
                            }
                            return
                        }
                        
                        // Much longer delays to avoid buzz interrupting AI responses - 4-5 seconds
                        val retryDelay = if (noMatchRetryCount < 5) 4000L else 5000L
                        Log.d(TAG, "NO_MATCH (#$noMatchRetryCount) - retrying in ${retryDelay}ms")
                        Handler(Looper.getMainLooper()).postDelayed({ if (isInConversationMode) startListening() }, retryDelay)
                        return
                    }
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        // User didn't speak; quick retry
                        lastSpeechErrorTime = now
                        Log.d(TAG, "SPEECH_TIMEOUT - retrying")
                        Handler(Looper.getMainLooper()).postDelayed({ if (isInConversationMode) startListening() }, 800)
                        return
                    }
                    SpeechRecognizer.ERROR_CLIENT -> {
                        // ERROR_CLIENT (13) - recognizer not ready, retry with longer delay
                        Log.w(TAG, "ERROR_CLIENT - recognizer not ready, retrying in 1s")
                        try { speechRecognizer?.destroy() } catch (_: Exception) {}
                        speechRecognizer = null
                        Handler(Looper.getMainLooper()).postDelayed({ 
                            if (isInConversationMode) startListening() 
                        }, 1000)
                        return
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Recognizer busy, wait and retry
                        Log.w(TAG, "ERROR_RECOGNIZER_BUSY - waiting 500ms")
                        Handler(Looper.getMainLooper()).postDelayed({ 
                            if (isInConversationMode) startListening() 
                        }, 500)
                        return
                    }
                }

                // Non-transient errors increment counter
                if (now - lastSpeechErrorTime > speechErrorWindowMs) {
                    speechErrorCount = 0
                }
                lastSpeechErrorTime = now
                speechErrorCount++

                // If too many errors in short window, set longer backoff and rebuild recognizer
                if (speechErrorCount >= speechErrorThreshold) {
                    val backoffMs = 8000L
                    speechBackoffUntil = now + backoffMs
                    Log.w(TAG, "Too many speech errors ($speechErrorCount). Backing off for ${backoffMs}ms")
                    try { speechRecognizer?.destroy() } catch (_: Exception) {}
                    speechRecognizer = null
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            if (speechRecognizer == null) {
                                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
                                initSpeechListener()
                            }
                            speechErrorCount = 0
                            speechBackoffUntil = 0L
                            Log.d(TAG, "Speech recognizer recreated after backoff")
                            if (isInConversationMode) startListening()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to recreate SpeechRecognizer: ${e.message}")
                        }
                    }, backoffMs)
                    return
                }

                // Handle client/audio/busy errors by cleaning up audio and recreating recognizer
                if (error == SpeechRecognizer.ERROR_AUDIO ||
                    error == SpeechRecognizer.ERROR_CLIENT ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {

                    Log.w(TAG, "Handling critical recognizer error: $error - performing audio cleanup and scheduling recreate")
                    try { HotHelper.getInstance(this@MainActivity).stop() } catch (_: Exception) {}
                    try { if (tts?.isSpeaking == true) tts?.stop() } catch (_: Exception) {}
                    try { audioManager?.abandonAudioFocus(audioFocusListener) } catch (_: Exception) {}

                    // Only stop Bluetooth SCO if it is currently on to avoid repeated toggles
                    try {
                        if (audioManager?.isBluetoothScoOn == true) {
                            audioManager?.stopBluetoothSco()
                            audioManager?.setBluetoothScoOn(false)
                        }
                    } catch (_: Exception) {}

                    try { if (audioManager?.mode != AudioManager.MODE_NORMAL) audioManager?.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}

                    try { speechRecognizer?.destroy() } catch (_: Exception) {}
                    speechRecognizer = null

                    // Longer backoff for critical errors to avoid tight recreate loops
                    val criticalBackoffMs = 5000L
                    speechBackoffUntil = now + criticalBackoffMs
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            // Check that backoff expired and audio system is idle
                            if (System.currentTimeMillis() < speechBackoffUntil) return@postDelayed
                            if (speechRecognizer == null) {
                                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@MainActivity)
                                initSpeechListener()
                            }
                            speechErrorCount = 0
                            speechBackoffUntil = 0L
                            Log.d(TAG, "Speech recognizer recreated after backoff")
                            if (isInConversationMode) {
                                // ensure TTS finished and Bluetooth SCO is not active before starting
                                Handler(Looper.getMainLooper()).postDelayed({ if (isInConversationMode && tts?.isSpeaking != true && audioManager?.isBluetoothScoOn != true) startListening() }, 300)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to recreate SpeechRecognizer after critical error: ${e.message}")
                        }
                    }, criticalBackoffMs)
                    return
                }

                // Default: try a normal restart after a short delay
                val normalDelay = 1000L
                Handler(Looper.getMainLooper()).postDelayed({ if (isInConversationMode) startListening() }, normalDelay)
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private suspend fun waitForScoReleaseThenStartListening() {
        withContext(Dispatchers.Main) { delay(100) }
        awaitScoState(AudioManager.SCO_AUDIO_STATE_DISCONNECTED, wakeToListenDelayMs)
        
        withContext(Dispatchers.Main) {
            var waitCount = 0
            while (tts?.isSpeaking == true && waitCount < 20) {
                delay(100)
                waitCount++
            }
            delay(sttSafetyDelayMs)
            startListening()
        }
    }
    
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothEvent(event: BluetoothEvent) {
        when (event.type) {
            BluetoothEvent.EventType.CONNECTED -> {
                Toast.makeText(this, "Glass Connected", Toast.LENGTH_SHORT).show()
                // Warm SCO on glass connect so mic is ready for wake detection
                try {
                    mainScope.launch {
                        scoHelper?.let { helper ->
                            Log.d(TAG, "🔧 Warming SCO because glass connected")
                            val ok = helper.connectScoWithRetries()
                            Log.d(TAG, "🔧 SCO warm result: $ok")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to warm SCO on connect: ${e.message}")
                }
            }
            BluetoothEvent.EventType.DISCONNECTED -> {
                Toast.makeText(this, "Glass Disconnected", Toast.LENGTH_SHORT).show()
                try {
                    scoHelper?.cleanup()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cleanup SCO on disconnect: ${e.message}")
                }
            }
            BluetoothEvent.EventType.VOICE_TEXT -> {
                val text = event.data as? String ?: return
                if (text == "wake up") {
                    try { HotHelper.getInstance(this).stop() } catch (e: Exception) {}
                    
                    // 🎤 ALWAYS use Gemini Live API (REST API disabled)
                    Log.d(TAG, "🎙️ Starting Gemini Live mode after Hey Imi")
                    isGeminiLiveMode = true
                    isInConversationMode = true
                    updateConversation("System", "Hey Imi detected! Starting Gemini Live...")
                    
                    // Try common device Download paths and res/raw resource, else fallback to beeps
                    try {
                        val candidates = listOf(
                            File("/sdcard/Download/bmw_warning_chime.mp3"),
                            File("/storage/emulated/0/Download/bmw_warning_chime.mp3"),
                            File(Environment.getExternalStorageDirectory(), "Download/bmw_warning_chime.mp3"),
                            File(filesDir, "bmw_warning_chime.mp3")
                        )

                        var played = false
                        for (candidate in candidates) {
                            if (candidate.exists() && candidate.canRead()) {
                                Log.d(TAG, "Playing wake chime from device path: ${candidate.absolutePath}")
                                val mp = MediaPlayer()
                                try {
                                    mp.setDataSource(candidate.absolutePath)
                                    // Use music stream for higher audible volume and set max volume
                                    mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
                                    mp.prepare()
                                    try { mp.setVolume(1.0f, 1.0f) } catch (_: Exception) {}
                                    mp.start()
                                    mp.setOnCompletionListener {
                                        try { it.release() } catch (_: Exception) {}
                                        try { startGeminiAfterSco() } catch (e: Exception) { Log.e(TAG, "Failed to start Gemini Live after chime: ${e.message}") }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error playing chime file at ${candidate.absolutePath}: ${e.message}")
                                    try { mp.release() } catch (_: Exception) {}
                                }
                                played = true
                                break
                            }
                        }

                        if (!played) {
                            // Try packaged raw resource: res/raw/bmw_warning_chime.mp3 (if user adds it)
                            val resId = resources.getIdentifier("bmw_warning_chime", "raw", packageName)
                            if (resId != 0) {
                                Log.d(TAG, "Playing wake chime from res/raw resource")
                                val mp = MediaPlayer.create(this, resId)
                                mp?.let { player ->
                                    try {
                                        // Ensure playback on music stream and full volume
                                        try { player.setAudioStreamType(AudioManager.STREAM_MUSIC) } catch (_: Exception) {}
                                        try { player.setVolume(1.0f, 1.0f) } catch (_: Exception) {}
                                    } catch (_: Exception) {}
                                    player.setOnCompletionListener {
                                        try { it.release() } catch (_: Exception) {}
                                        try { startGeminiAfterSco() } catch (e: Exception) { Log.e(TAG, "Failed to start Gemini Live after chime: ${e.message}") }
                                    }
                                    player.start()
                                    played = true
                                }
                            }
                        }

                        if (!played) {
                                Log.w(TAG, "No chime found; starting Gemini Live immediately (no beep fallback)")
                                try { startGeminiAfterSco() } catch (e: Exception) { Log.e(TAG, "Failed to start Gemini Live: ${e.message}") }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to play chime, starting Gemini Live immediately: ${e.message}")
                        try { startGeminiLiveConversation() } catch (ex: Exception) { Log.e(TAG, "Failed to start Gemini Live: ${ex.message}") }
                    }
                    
                    voiceCommandEnabled = true
                    binding.btnVoice.isEnabled = true
                }
            }
            BluetoothEvent.EventType.PHOTO_CAPTURED -> {
                Log.d(TAG, "📥 PHOTO_CAPTURED EventBus event received, data type: ${event.data?.javaClass?.simpleName}")
                (event.data as? ByteArray)?.let { photoBytes ->
                    Log.d(TAG, "📸 PHOTO_CAPTURED event - ${photoBytes.size} bytes received")
                    
                    lastCapturedPhoto = photoBytes 
                    savePhoto(photoBytes) // Gallery mein save

                    // UI Update
                    try {
                        runOnUiThread {
                            binding.llPhotoPreview.visibility = android.view.View.VISIBLE
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(photoBytes, 0, photoBytes.size)
                            binding.ivConversationImage.setImageBitmap(bmp)
                        }
                    } catch (e: Exception) { Log.w(TAG, "UI preview update failed") }

                    // ==================================================================
                    // 🔥 FIX START: Logic Corrected for Gemini Live
                    // ==================================================================
                    
                    // Check: 
                    // 1. Agar 'isProcessingVisionRequest' TRUE hai (Matlab Gemini AI ne tool call kiya tha)
                    // 2. Ya purana 'isWaitingForVisionPhoto' TRUE hai (Voice triggered Vision Chat)
                    // ❌ REMOVED: isGeminiLiveMode - Normal conversation mein photo click pe vision analysis NAHI chahiye
                    // Vision analysis sirf explicit vision commands pe hogi (samne kya hai, etc.)
                    
                    if (isProcessingVisionRequest || isWaitingForVisionPhoto) {
                        
                        Log.d(TAG, "🚀 Sending image to Gemini Live (Vision Request Active)")
                        
                        // Check if this is a Vision Chat trigger from voice command
                        val visionQuery = pendingVisionQuery
                        if (visionQuery != null) {
                            Log.d(TAG, "👁️ Vision query from voice: $visionQuery")
                            speakOut("Photo dekh raha hun... Analyzing photo", "VISION")
                        } else {
                            speakOut("Dekh raha hu...", "VIEW")
                        }
                        
                        // Clear the pending query
                        pendingVisionQuery = null

                        if (geminiLiveService != null) {
                            try {
                                // 🔥 Resize & compress before sending to speed up transfer
                                val fastImage = resizeAndCompressImage(photoBytes)
                                geminiLiveService?.sendRealtimeImage(fastImage)
                                Log.d(TAG, "🚀 Image sent to Live Service directly via WebSocket (compressed)")
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ WebSocket send failed, trying fallback: ${e.message}")
                                analyzeImageWithGemini(photoBytes)
                            }
                        } else {
                            Log.w(TAG, "⚠️ Live Service NULL, falling back to HTTP")
                            analyzeImageWithGemini(photoBytes)
                        }

                        // Flags reset
                        isProcessingVisionRequest = false
                        isWaitingForVisionPhoto = false
                        lastVisionRequestType = null
                    } else {
                        // ✅ Normal photo - just save, NO vision analysis
                        Log.d(TAG, "📸 Photo saved locally (Normal mode - no vision analysis)")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "📷 Photo saved to gallery", Toast.LENGTH_SHORT).show()
                        }
                    }
                    // ==================================================================
                    // 🔥 FIX END
                    // ==================================================================

                } ?: run {
                    Log.e(TAG, "❌ PHOTO_CAPTURED event but no photo data")
                }
            }
            BluetoothEvent.EventType.BATTERY_LEVEL -> {
                (event.data as? Int)?.let { batteryPercent ->
                    glassBatteryLevel = batteryPercent
                    Log.d(TAG, "🔋 Glass battery: $batteryPercent%")
                }
            }
            else -> {}
        }
    }

    private fun savePhoto(bytes: ByteArray) {
        try {
            Log.d(TAG, "📸 Saving photo: ${bytes.size} bytes")
            runOnUiThread { 
                Toast.makeText(this, "📸 Capturing photo...", Toast.LENGTH_SHORT).show() 
            }
            
            val fileName = "glass_photo_${System.currentTimeMillis()}.jpg"
            val file = File(getExternalFilesDir(null), fileName)
            file.writeBytes(bytes)
            lastSavedPhotoFile = file
            Log.d(TAG, "✅ Photo written to temp: ${file.absolutePath}")
            
            // Upload to server if enabled
            if (prefs.getBoolean("auto_upload_enabled", false)) {
                uploadPhotoToServer(file)
            }
            
            saveToPublicGallery(file)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving photo: ${e.message}", e)
            runOnUiThread { 
                Toast.makeText(this, "❌ Failed to save photo: ${e.message}", Toast.LENGTH_LONG).show() 
            }
        }
    }

    private fun saveLastPhotoToDownloads() {
        val src = lastSavedPhotoFile
        if (src == null || !src.exists()) {
            runOnUiThread { Toast.makeText(this, "No photo available to download", Toast.LENGTH_SHORT).show() }
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ImiGlass")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) throw Exception("MediaStore insert failed")

                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(src).use { fis -> fis.copyTo(out) }
                } ?: throw Exception("Failed to open output stream for download")

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)

                runOnUiThread { Toast.makeText(this, "Downloaded to Downloads/ImiGlass/${src.name}", Toast.LENGTH_LONG).show() }
            } else {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val destDir = File(downloads, "ImiGlass")
                if (!destDir.exists()) destDir.mkdirs()
                val dest = File(destDir, src.name)
                FileInputStream(src).use { fis -> dest.outputStream().use { fos -> fis.copyTo(fos) } }
                MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf("image/jpeg")) { _, _ -> }
                runOnUiThread { Toast.makeText(this, "Downloaded to ${dest.absolutePath}", Toast.LENGTH_LONG).show() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save last photo to downloads: ${e.message}", e)
            runOnUiThread { Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
    
    /**
     * Upload photo to configured server
     */
    private fun uploadPhotoToServer(file: File) {
        imageUploadService?.uploadImage(
            imageFile = file,
            onSuccess = { response ->
                Log.d(TAG, "✅ Image uploaded successfully: $response")
                runOnUiThread {
                    Toast.makeText(this, "☁️ Photo uploaded to server", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { error ->
                Log.e(TAG, "❌ Image upload failed: $error")
                runOnUiThread {
                    Toast.makeText(this, "❌ Upload failed: $error", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
    
    private fun saveToPublicGallery(sourceFile: File) {
         if (!sourceFile.exists()) {
             Log.e(TAG, "❌ Source file does not exist: ${sourceFile.absolutePath}")
             runOnUiThread { 
                 Toast.makeText(this, "❌ Photo file not found", Toast.LENGTH_SHORT).show() 
             }
             return
         }
         
         Log.d(TAG, "💾 Saving to gallery: ${sourceFile.name} (${sourceFile.length()} bytes)")
         
         try {
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                 // Android 10+ (API 29+): Use MediaStore with scoped storage
                 val values = ContentValues().apply {
                     put(MediaStore.MediaColumns.DISPLAY_NAME, sourceFile.name)
                     put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                     put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/ImiGlass")
                     put(MediaStore.MediaColumns.IS_PENDING, 1)
                 }

                 val uri = contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                 if (uri == null) {
                     Log.e(TAG, "❌ Failed to create MediaStore entry")
                     runOnUiThread { 
                         Toast.makeText(this, "❌ Failed to save to gallery (MediaStore insert failed)", Toast.LENGTH_LONG).show() 
                     }
                     return
                 }
                 
                 Log.d(TAG, "📝 MediaStore URI created: $uri")
                 
                 // Write file content to MediaStore
                 contentResolver.openOutputStream(uri)?.use { out -> 
                     FileInputStream(sourceFile).use { fis -> 
                         fis.copyTo(out) 
                     }
                 } ?: run {
                     Log.e(TAG, "❌ Failed to open output stream for MediaStore URI")
                     contentResolver.delete(uri, null, null)
                     runOnUiThread { 
                         Toast.makeText(this, "❌ Failed to write photo to gallery", Toast.LENGTH_LONG).show() 
                     }
                     return
                 }
                 
                 // Mark as complete (no longer pending)
                 values.clear()
                 values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                 contentResolver.update(uri, values, null, null)
                 
                 val savedUri = uri.toString()
                 Log.d(TAG, "✅ Photo saved to gallery: $savedUri")
                 runOnUiThread { 
                     Toast.makeText(this, "✅ Photo saved to Gallery\nDCIM/ImiGlass/${sourceFile.name}", Toast.LENGTH_LONG).show() 
                 }
             } else {
                 // Pre-Android 10: Direct file write to DCIM/ImiGlass
                 val picturesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "ImiGlass")
                 if (!picturesDir.exists()) {
                     val created = picturesDir.mkdirs()
                     Log.d(TAG, "📁 Created ImiGlass directory: $created")
                 }
                 
                 val dest = File(picturesDir, sourceFile.name)
                 FileInputStream(sourceFile).use { fis -> 
                     dest.outputStream().use { fos -> 
                         fis.copyTo(fos) 
                     }
                 }
                 
                 Log.d(TAG, "✅ Photo copied to: ${dest.absolutePath}")
                 
                 // Notify MediaScanner to make photo visible in gallery
                 MediaScannerConnection.scanFile(this, arrayOf(dest.absolutePath), arrayOf("image/jpeg")) { path, uri ->
                     Log.d(TAG, "📷 Media scan complete: $path -> $uri")
                 }
                 
                 val savedPath = dest.absolutePath
                 runOnUiThread { 
                     Toast.makeText(this, "✅ Photo saved to Gallery\n${dest.name}", Toast.LENGTH_LONG).show() 
                 }
                 Log.d(TAG, "✅ Photo saved to gallery: $savedPath")
             }
         } catch (e: Exception) {
             Log.e(TAG, "❌ Error saving to gallery: ${e.message}", e)
             e.printStackTrace()
             runOnUiThread { 
                 Toast.makeText(this, "❌ Failed to save photo to gallery: ${e.message}", Toast.LENGTH_LONG).show() 
             }
         }
    }

    private fun updateConversation(speaker: String, message: String) {
        runOnUiThread {
            binding.tvConversation.append("$speaker: $message\n\n")
            binding.tvConversation.post {
                (binding.tvConversation.parent as? android.widget.ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    private fun handleSpokenCommand(command: String) {
        // Check if there's a pending follow-up action (e.g., waiting for song name, contact name, etc.)
        if (pendingAction != null) {
            val action = pendingAction
            pendingAction = null // Clear before executing to allow new actions
            action?.invoke(command)
            return
        }
        
        val lowerCmd = command.lowercase()
        
        // PRIORITY 0: PHOTO/VIDEO CAPTURE COMMANDS (SIRF click, no vision analysis!)
        // ══════════════════════════════════════════════════════════════════════════
        // ✅ These commands ONLY capture photo/video - NO vision analysis!
        // Check these BEFORE any vision-related commands
        // ══════════════════════════════════════════════════════════════════════════
        
        // Photo click commands - ONLY take photo, NO vision analysis
        val isPhotoClickCommand = lowerCmd.contains("photo click") || 
            lowerCmd.contains("click photo") ||
            lowerCmd.contains("photo lo") || 
            lowerCmd.contains("photo lelo") ||
            lowerCmd.contains("photo khinch") ||
            lowerCmd.contains("photo khicho") ||
            lowerCmd.contains("foto lo") ||
            lowerCmd.contains("take photo") ||
            lowerCmd.contains("take a photo") ||
            lowerCmd.contains("take picture") ||
            lowerCmd.contains("click picture") ||
            lowerCmd.contains("capture photo") ||
            (lowerCmd.contains("photo") && !lowerCmd.contains("analyze") && !lowerCmd.contains("samne") && !lowerCmd.contains("dekh"))
        
        if (isPhotoClickCommand) {
            Log.d(TAG, "📷 PHOTO CLICK command (no vision): '$command'")
            SafeBleCommandHelper.takePhoto()
            speakOut("Photo le raha hu", "ACTION")
            return
        }
        
        // Video recording commands - ONLY record, NO vision analysis
        val isVideoCommand = (lowerCmd.contains("video") || lowerCmd.contains("recording")) &&
            (lowerCmd.contains("start") || lowerCmd.contains("shuru") || lowerCmd.contains("on") || lowerCmd.contains("begin") || lowerCmd.contains("record"))
        
        val isStopVideoCommand = (lowerCmd.contains("video") || lowerCmd.contains("recording")) &&
            (lowerCmd.contains("stop") || lowerCmd.contains("band") || lowerCmd.contains("off") || lowerCmd.contains("end") || lowerCmd.contains("finish"))
        
        if (isStopVideoCommand) {
            Log.d(TAG, "🎬 STOP VIDEO command: '$command'")
            SafeBleCommandHelper.stopRecording()
            speakOut("Video recording band", "ACTION")
            return
        }
        
        if (isVideoCommand) {
            Log.d(TAG, "🎬 START VIDEO command: '$command'")
            SafeBleCommandHelper.startRecording()
            speakOut("Video recording shuru", "ACTION")
            return
        }
        
        // PRIORITY 1: Direct action commands (before VoiceCommandInterpreter)
        
        // ══════════════════════════════════════════════════════════════════════════
        // LIVE CAMERA STREAMING COMMANDS
        // ══════════════════════════════════════════════════════════════════════════
        // Start continuous or interactive live camera based on wording
        if (lowerCmd.contains("continuous camera") || lowerCmd.contains("keep watching") ||
            lowerCmd.contains("continuous dekho") || lowerCmd.contains("continuous dekhte raho") ||
            lowerCmd.contains("keep watching") ) {
            // Explicit continuous mode request
            startLiveCameraStream()
            return
        }

        // Interactive Vision Mode (step-by-step) - default when user says live camera/vision mode
        if (lowerCmd.contains("start live camera") || lowerCmd.contains("live camera on") ||
            lowerCmd.contains("enable live vision") || lowerCmd.contains("live camera") ||
            lowerCmd.contains("vision mode") || lowerCmd.contains("camera on") ||
            lowerCmd.contains("live camera shuru karo") || lowerCmd.contains("camera chalu rakho")) {
            startInteractiveVisionMode()
            return
        }
        
        if (lowerCmd.contains("stop live camera") || lowerCmd.contains("live camera off") ||
            lowerCmd.contains("disable live vision") || lowerCmd.contains("stop watching") ||
            lowerCmd.contains("live camera band karo") || lowerCmd.contains("camera band karo")) {
            stopLiveCameraStream()
            stopWifiP2PLiveCamera() // Also stop WiFi mode if running
            return
        }

        // Interactive Vision Mode controls: Next/Click and Stop
        if (isInteractiveVisionMode && (lowerCmd.contains("next") || lowerCmd.contains("another") || lowerCmd.contains("aur dikhao") || lowerCmd.contains("click") || lowerCmd.contains("naya"))) {
            if (!isProcessingVisionRequest) {
                captureAndAnalyzeInteractive()
            } else {
                speakOut("Abhi purani photo process ho rahi hai, kripya wait karein.", "WAIT")
            }
            return
        }

        if (lowerCmd.contains("stop camera") || lowerCmd.contains("band karo") || lowerCmd.contains("exit vision") || lowerCmd.contains("vision band karo")) {
            if (isInteractiveVisionMode) stopInteractiveVisionMode()
            return
        }
        
        // WiFi P2P Live Camera - Fast mode via WiFi hotspot
        if (lowerCmd.contains("wifi live camera") || lowerCmd.contains("fast live camera") ||
            lowerCmd.contains("wifi camera shuru") || lowerCmd.contains("fast camera") ||
            lowerCmd.contains("wifi mode") && lowerCmd.contains("camera")) {
            startWifiP2PLiveCamera()
            return
        }
        
        // WiFi Transfer commands - download photos/videos from glasses
        if (lowerCmd.contains("download") || lowerCmd.contains("transfer") ||
            lowerCmd.contains("wifi transfer") || lowerCmd.contains("get my photos") ||
            lowerCmd.contains("get my pictures") || lowerCmd.contains("get my videos") ||
            lowerCmd.contains("sync photos") || lowerCmd.contains("sync pictures") ||
            lowerCmd.contains("मेरी फ़ोटो") || lowerCmd.contains("photos lao") ||
            lowerCmd.contains("photo download") || lowerCmd.contains("video download")) {
            startWifiTransfer()
            return
        }
        
        // Glass Media Gallery - View photos/videos from glass via WiFi hotspot
        if (lowerCmd.contains("glass gallery") || lowerCmd.contains("glass photos") ||
            lowerCmd.contains("glass videos") || lowerCmd.contains("glass media") ||
            lowerCmd.contains("show my photos") || lowerCmd.contains("show my videos") ||
            lowerCmd.contains("open gallery") || lowerCmd.contains("glass ki photos") ||
            lowerCmd.contains("glass ki gallery") || lowerCmd.contains("meri gallery") ||
            lowerCmd.contains("photos dikhao") || lowerCmd.contains("videos dikhao")) {
            openGlassMediaGallery()
            return
        }
        
        // Battery query commands
        if (lowerCmd.contains("battery") || lowerCmd.contains("charge") ||
            lowerCmd.contains("बैटरी") || lowerCmd.contains("kitna") && (lowerCmd.contains("battery") || lowerCmd.contains("charge"))) {
            checkGlassBattery()
            return
        }
        
        // Object detection commands - analyze what's in front using camera
        // Simpler detection: if command asks about seeing/viewing/identifying something, use camera
        val isVisionRequest = 
            // Direct camera mentions
            lowerCmd.contains("camera") ||
            // "See/look/show" patterns
            (lowerCmd.contains("see") && (lowerCmd.contains("what") || lowerCmd.contains("front"))) ||
            (lowerCmd.contains("look") && lowerCmd.contains("at")) ||
            (lowerCmd.contains("show") && lowerCmd.contains("me")) ||
            // "What is" patterns
            (lowerCmd.contains("what") && (lowerCmd.contains("this") || lowerCmd.contains("that") || 
             lowerCmd.contains("front") || lowerCmd.contains("in front"))) ||
            // "Who is" patterns  
            (lowerCmd.contains("who") && lowerCmd.contains("front")) ||
            // Identification requests
            lowerCmd.contains("identify") || lowerCmd.contains("scan") || lowerCmd.contains("detect") ||
            lowerCmd.contains("analyze") || lowerCmd.contains("recognize") ||
            // Hindi patterns
            lowerCmd.contains("samne kya") || lowerCmd.contains("ye kya") ||
            lowerCmd.contains("kaun hai") || lowerCmd.contains("dekho") || 
            lowerCmd.contains("dekhke batao") || lowerCmd.contains("camera se") ||
            lowerCmd.contains("camera on") || lowerCmd.contains("camera ko")
        
        if (isVisionRequest) {
            Log.d(TAG, "🎥 Vision request detected: '$command'")
            
            // 🆕 CHECK: If VisionChat is already open, do nothing (let VisionChat's listener handle it)
            if (visionChatOpenFlag) {
                Log.d(TAG, "👁️ VisionChat already open - listener will handle vision request automatically")
                return  // VisionChatActivity's VisionTranscriptionListener will capture new photo
            }
            
            // VisionChat NOT open - trigger it with auto-capture
            Log.d(TAG, "👓 VisionChat not open - opening with auto-capture for: '$command'")
            triggerVisionChatFromLive(command, forceNewCapture = true)
            return
        }
        
        // Direct Vision Chat commands with auto-capture
        if (lowerCmd.contains("vision chat pe jao") || lowerCmd.contains("vision chat kholo") ||
            lowerCmd.contains("open vision chat") || lowerCmd.contains("start vision chat") ||
            lowerCmd.contains("launch vision") || lowerCmd.contains("image chat") ||
            lowerCmd.contains("photo analysis") || lowerCmd.contains("analyze photo") ||
            (lowerCmd.contains("vision") && (lowerCmd.contains("capture") || lowerCmd.contains("click"))) ||
            (lowerCmd.contains("photo") && lowerCmd.contains("analyze"))) {
            Log.d(TAG, "👓 Vision Chat command with auto-capture triggered")
            triggerVisionChatWithGemini(command)
            return
        }
        
        // Vision Chat commands
        if (lowerCmd.contains("open vision chat") || lowerCmd.contains("vision chat") ||
            lowerCmd.contains("show vision") || lowerCmd.contains("image analysis")) {
            Log.d(TAG, "👓 Opening Vision Chat")
            openVisionChat()
            return
        }
        
        // Spotify open commands - auto-play music
        if (lowerCmd.contains("open spotify") || lowerCmd.contains("launch spotify")) {
            playMusic("popular songs") // Auto-play when opening Spotify
            return
        }
        
        // YouTube open commands - auto-play trending
        if (lowerCmd.contains("open youtube") || lowerCmd.contains("launch youtube")) {
            playYouTube("trending") // Auto-play trending when opening YouTube
            return
        }
        
        // Interrupt mode toggle commands (enable/disable AI interruption when user speaks)
        if (lowerCmd.contains("enable interrupt") || lowerCmd.contains("interrupt on") || 
            lowerCmd.contains("interrupt mode enable")) {
            isInterruptEnabled = true
            Toast.makeText(this, "Interrupt Mode: ON", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (lowerCmd.contains("disable interrupt") || lowerCmd.contains("interrupt off") || 
            lowerCmd.contains("interrupt mode disable")) {
            isInterruptEnabled = false
            Toast.makeText(this, "Interrupt Mode: OFF", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Music/Song commands
        if (lowerCmd.contains("play") && (lowerCmd.contains("song") || lowerCmd.contains("music") || 
            lowerCmd.contains("spotify"))) {
            playMusic(command)
            return
        }
        
        // Weather commands
        if (lowerCmd.contains("weather") || lowerCmd.contains("temperature") || 
            lowerCmd.contains("mausam")) {
            getWeather(command)
            // Quick resume listening
            Handler(Looper.getMainLooper()).postDelayed({
                if (isInConversationMode) startListening()
            }, 800)
            return
        }
        
        // News queries with location support
        if (lowerCmd.contains("news") || lowerCmd.contains("current events") || 
            lowerCmd.contains("what's happening") || lowerCmd.contains("headlines") ||
            lowerCmd.contains("khabar") || lowerCmd.contains("समाचार")) {
            getLocationBasedNews(command)
            // Quick resume listening
            Handler(Looper.getMainLooper()).postDelayed({
                if (isInConversationMode) startListening()
            }, 800)
            return
        }
        
        // Video recording commands for glasses
        if ((lowerCmd.contains("start") || lowerCmd.contains("begin") || lowerCmd.contains("record")) && 
            (lowerCmd.contains("video") || lowerCmd.contains("recording"))) {
            startVideoRecording()
            return
        }
        
        // Stop video recording
        if ((lowerCmd.contains("stop") || lowerCmd.contains("end") || lowerCmd.contains("finish")) && 
            (lowerCmd.contains("video") || lowerCmd.contains("recording") || lowerCmd.contains("record"))) {
            stopVideoRecording()
            return
        }
        
        // Alternative: "video stop" or "recording stop"
        if ((lowerCmd.contains("video") || lowerCmd.contains("recording")) && 
            (lowerCmd.contains("stop") || lowerCmd.contains("end") || lowerCmd.contains("finish"))) {
            stopVideoRecording()
            return
        }
        
        // Jokes and entertainment
        if (lowerCmd.contains("joke") || lowerCmd.contains("funny") || 
            lowerCmd.contains("मजाक") || lowerCmd.contains("hasao")) {
            tellJoke()
            return
        }
        
        // Roasting/sarcasm
        if (lowerCmd.contains("roast") || lowerCmd.contains("insult") || 
            lowerCmd.contains("savage")) {
            roastUser(command)
            return
        }
        
        // Suggestions/advice
        if (lowerCmd.contains("suggest") || lowerCmd.contains("advice") || 
            lowerCmd.contains("should i") || lowerCmd.contains("recommendation") ||
            lowerCmd.contains("future") && lowerCmd.contains("goal")) {
            giveSuggestion(command)
            return
        }
        
        // Historical/cultural queries
        if (lowerCmd.contains("who is") || lowerCmd.contains("who was") ||
            lowerCmd.contains("tell me about") || lowerCmd.contains("history of") ||
            lowerCmd.contains("what is the history") || lowerCmd.contains("cultural")) {
            getHistoricalKnowledge(command)
            return
        }
        
        // YouTube commands
        if (lowerCmd.contains("youtube") || lowerCmd.contains("video")) {
            playYouTube(command)
            return
        }

        // Call commands - support multiple patterns
        if (lowerCmd.contains("call") || lowerCmd.contains("phone") ||
            lowerCmd.contains("dial") || lowerCmd.contains("ring") ||
            // Hindi patterns
            lowerCmd.contains("call karo") || lowerCmd.contains("phone karo") ||
            lowerCmd.contains("ko call") || lowerCmd.contains("ko phone")) {
            callSomeone(command)
            return
        }
        
        // Distance/Travel commands
        if (lowerCmd.contains("distance") || lowerCmd.contains("how far") ||
            (lowerCmd.contains("from") && lowerCmd.contains("to"))) {
            calculateDistance(command)
            return
        }
        
        // Navigation commands
        if (lowerCmd.contains("navigate") || lowerCmd.contains("direction") ||
            lowerCmd.contains("take me to") || lowerCmd.contains("go to")) {
            navigateTo(command)
            return
        }
        
        // Location tracking
        if (lowerCmd.contains("my location") || lowerCmd.contains("where am i") ||
            lowerCmd.contains("track location")) {
            trackLocation()
            return
        }
        
        // PRIORITY 2: VoiceCommandInterpreter for glass-specific actions
        val (action, data) = VoiceCommandInterpreter.detectAction(command)
        
        when (action) {
            GlassAction.TAKE_PHOTO -> {
                SafeBleCommandHelper.takePhoto()
                speakOut("Taking photo", "ACTION")
            }
            GlassAction.START_VIDEO -> {
                SafeBleCommandHelper.startRecording()
                speakOut("Starting video", "ACTION")
            }
            GlassAction.STOP_VIDEO -> {
                SafeBleCommandHelper.stopRecording()
                speakOut("Stopping video", "ACTION")
            }
            GlassAction.GET_NEWS -> getLatestNews()
            GlassAction.SEARCH_WEB -> searchWeb(data)
            // Default: fallback to Gemini chat for everything else
            else -> chatWithGemini(command)
        }
    }
    
    private fun playMusic(query: String) {
        val cleanQuery = query.replace("play music", "").replace("play song", "").replace("play", "").replace("spotify", "").trim()
        
        // If no specific song, play general music on Spotify
        val songToPlay = if (cleanQuery.isBlank() || cleanQuery.length < 3) {
            "popular songs" // Default playlist
        } else {
            cleanQuery
        }
        
        actuallyPlayMusic(songToPlay)
        
        // Continue conversation in background
        Handler(Looper.getMainLooper()).postDelayed({
            if (isInConversationMode) startListening()
        }, 500)
    }
    
    private fun actuallyPlayMusic(songName: String) {
        try {
            val spotifyPackage = "com.spotify.music"
            var played = false
            
            // PRIORITY 1: Android media play intent - works best for auto-play
            val playIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, songName)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(MediaStore.EXTRA_MEDIA_ARTIST, "") // Empty = any artist
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            // Try with Spotify first
            if (isPackageInstalled(spotifyPackage)) {
                playIntent.setPackage(spotifyPackage)
                try {
                    startActivity(playIntent)
                    speakOut("Playing $songName on Spotify", "ACTION")
                    played = true
                    Log.d(TAG, "✅ Launched Spotify with MEDIA_PLAY_FROM_SEARCH")
                } catch (e: Exception) {
                    Log.w(TAG, "Spotify MEDIA_PLAY failed: ${e.message}")
                }
            }
            
            // PRIORITY 2: Try with any music player if Spotify didn't work
            if (!played) {
                playIntent.setPackage(null)
                try {
                    startActivity(playIntent)
                    speakOut("Playing $songName", "ACTION")
                    played = true
                    Log.d(TAG, "✅ Launched with generic MEDIA_PLAY_FROM_SEARCH")
                } catch (e: Exception) {
                    Log.w(TAG, "Generic MEDIA_PLAY failed: ${e.message}")
                }
            }
            
            // FALLBACK 1: Direct Spotify track action
            if (!played && isPackageInstalled(spotifyPackage)) {
                try {
                    val spotifyIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("spotify:search:${Uri.encode(songName)}")
                        setPackage(spotifyPackage)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(spotifyIntent)
                    
                    // Click first result automatically after 3 seconds
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            // Simulate ENTER key to play first result
                            val runtime = Runtime.getRuntime()
                            runtime.exec("input keyevent KEYCODE_DPAD_DOWN") // Move to first result
                            Thread.sleep(300)
                            runtime.exec("input keyevent KEYCODE_ENTER") // Click it
                            Log.d(TAG, "🎵 Auto-clicked first Spotify result")
                        } catch (ex: Exception) {
                            Log.w(TAG, "Auto-click failed: ${ex.message}")
                        }
                    }, 3000)
                    
                    speakOut("Searching $songName on Spotify", "ACTION")
                    played = true
                } catch (e: Exception) {
                    Log.w(TAG, "Spotify search failed: ${e.message}")
                }
            }
            
            // LAST RESORT: Web player
            if (!played) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/${Uri.encode(songName)}"))
                webIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(webIntent)
                speakOut("Opening $songName", "ACTION")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error playing music: ${e.message}")
            speakOut("Can't play music", "ERROR")
        }
    }
    
    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun playYouTube(query: String) {
        val cleanQuery = query.replace("open youtube", "").replace("youtube", "").replace("play", "").replace("video", "").trim()
        
        // If query is empty or just "open youtube", open YouTube trending/home
        val videoToPlay = if (cleanQuery.isBlank() || cleanQuery.length < 3) {
            "trending" // Default to YouTube home/trending
        } else {
            cleanQuery
        }
        
        actuallyPlayYouTube(videoToPlay)
        
        // Continue conversation in background
        Handler(Looper.getMainLooper()).postDelayed({
            if (isInConversationMode) startListening()
        }, 500)
    }
    
    private fun actuallyPlayYouTube(videoName: String) {
        try {
            val youtubePackage = "com.google.android.youtube"
            var opened = false
            
            // PRIORITY 1: Direct YouTube search-and-play intent (opens search results)
            if (isPackageInstalled(youtubePackage) && videoName != "trending") {
                // Search YouTube and auto-play first video
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val videoId = searchYouTubeFirstVideo(videoName)
                        withContext(Dispatchers.Main) {
                            if (videoId != null) {
                                // Play video directly using video ID
                                try {
                                    val watchIntent = Intent(Intent.ACTION_VIEW, 
                                        Uri.parse("vnd.youtube://watch?v=$videoId")).apply {
                                        setPackage(youtubePackage)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    startActivity(watchIntent)
                                    speakOut("Playing $videoName", "ACTION")
                                    opened = true
                                    Log.d(TAG, "📺 Auto-playing YouTube video: $videoId")
                                } catch (e: Exception) {
                                    // Fallback to web watch URL
                                    val webWatchIntent = Intent(Intent.ACTION_VIEW, 
                                        Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    startActivity(webWatchIntent)
                                    speakOut("Playing $videoName", "ACTION")
                                    opened = true
                                }
                            } else {
                                // API failed, fallback to search results
                                Log.w(TAG, "No video found, showing search results")
                                val searchIntent = Intent(Intent.ACTION_VIEW, 
                                    Uri.parse("vnd.youtube://results?search_query=${Uri.encode(videoName)}")).apply {
                                    setPackage(youtubePackage)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                startActivity(searchIntent)
                                speakOut("Showing results for $videoName", "ACTION")
                                opened = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "YouTube search error: ${e.message}")
                        withContext(Dispatchers.Main) {
                            // Fallback to search results page
                            val searchIntent = Intent(Intent.ACTION_VIEW, 
                                Uri.parse("vnd.youtube://results?search_query=${Uri.encode(videoName)}")).apply {
                                setPackage(youtubePackage)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(searchIntent)
                            speakOut("Searching $videoName", "ACTION")
                            opened = true
                        }
                    }
                }
                return // Exit function, coroutine handles the rest
            }
            
            // PRIORITY 2: YouTube home for trending
            if (!opened && videoName == "trending" && isPackageInstalled(youtubePackage)) {
                val homeIntent = packageManager.getLaunchIntentForPackage(youtubePackage)
                homeIntent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(homeIntent)
                speakOut("Opening YouTube", "ACTION")
                opened = true
            }
            
            // FALLBACK: Web YouTube in browser (will show search results)
            if (!opened) {
                val webIntent = Intent(Intent.ACTION_VIEW, 
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(videoName)}"))
                webIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(webIntent)
                speakOut("Searching $videoName on YouTube", "ACTION")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "YouTube error: ${e.message}")
            speakOut("Can't play YouTube", "ERROR")
        }
    }
    
    /**
     * Search YouTube using Data API v3 and return first video ID
     * NOTE: Requires YouTube Data API key in BuildConfig or replace YOUR_API_KEY_HERE
     */
    private suspend fun searchYouTubeFirstVideo(query: String): String? = withContext(Dispatchers.IO) {
        try {
            // YouTube Data API v3 endpoint
            // Get free API key from: https://console.cloud.google.com/apis/credentials
            val apiKey = "AIzaSyDpvor4Te-1r38i6sOVUrWbXwsxEchcZX8" // Replace with your API key
            val url = "https://www.googleapis.com/youtube/v3/search?" +
                    "part=snippet&" +
                    "q=${Uri.encode(query)}&" +
                    "type=video&" +
                    "maxResults=1&" +
                    "key=$apiKey"
            
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val json = response.body?.string()
                if (json != null) {
                    // Parse JSON to get video ID
                    val gson = Gson()
                    val jsonObject = gson.fromJson(json, com.google.gson.JsonObject::class.java)
                    val items = jsonObject.getAsJsonArray("items")
                    
                    if (items != null && items.size() > 0) {
                        val firstItem = items[0].asJsonObject
                        val videoId = firstItem.getAsJsonObject("id")?.get("videoId")?.asString
                        Log.d(TAG, "🎥 Found YouTube video: $videoId for query: $query")
                        return@withContext videoId
                    }
                }
            } else {
                Log.w(TAG, "YouTube API error: ${response.code} ${response.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "YouTube search exception: ${e.message}")
        }
        return@withContext null
    }

    private fun callSomeone(command: String) {
        // Extract contact name from various patterns
        val lowerCmd = command.lowercase()
        var cleanName = command
        
        // Remove common call-related words to extract the name
        val patterns = listOf(
            "call ", "phone ", "dial ", "ring ",
            "call karo ", "phone karo ", "ko call ", "ko phone ",
            "कॉल करो ", "फोन करो "
        )
        
        for (pattern in patterns) {
            cleanName = cleanName.replace(pattern, "", ignoreCase = true)
        }
        
        // Further cleanup
        cleanName = cleanName.trim()
            .replace(" please", "", ignoreCase = true)
            .replace(" now", "", ignoreCase = true)
            .replace("karo", "", ignoreCase = true)
            .trim()
        
        if (cleanName.isBlank() || cleanName.length < 2) {
            speakOut("Who do you want to call?", "QUERY")
            isInConversationMode = true
            pendingAction = { contactName ->
                lookupAndCall(contactName)
                // Resume listening after call
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isInConversationMode) startListening()
                }, 500)
            }
            return
        }
        
        Log.d(TAG, "📞 Calling contact: '$cleanName' from command: '$command'")
        lookupAndCall(cleanName)
        // Resume listening
        Handler(Looper.getMainLooper()).postDelayed({
            if (isInConversationMode) startListening()
        }, 500)
    }
    
    private fun lookupAndCall(contactName: String) {
        // Check for contacts permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            speakOut("I need contacts permission to find $contactName", "ERROR")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_READ_CONTACTS)
            return
        }
        
        try {
            // Search contacts for matching name
            val phoneNumber = findContactPhoneNumber(contactName)
            
            if (phoneNumber != null) {
                speakOut("Calling $contactName", "ACTION")
                makePhoneCall(phoneNumber, contactName)
            } else {
                speakOut("I couldn't find $contactName in your contacts. Should I dial the number anyway?", "ERROR")
                // Fallback to dialer with the spoken name
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            speakOut("Failed to make call: ${e.message}", "ERROR")
        }
    }
    
    private fun findContactPhoneNumber(name: String): String? {
        try {
            // Try exact match first (case insensitive)
            var cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )
            
            cursor?.use {
                if (it.moveToFirst()) {
                    val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    if (phoneIndex >= 0 && nameIndex >= 0) {
                        val foundName = it.getString(nameIndex)
                        val foundNumber = it.getString(phoneIndex)
                        Log.d(TAG, "✅ Found contact: $foundName -> $foundNumber")
                        return foundNumber
                    }
                }
            }
            
            // Try searching first name or last name separately
            val nameParts = name.split(" ")
            if (nameParts.size > 1) {
                for (part in nameParts) {
                    if (part.length >= 2) {
                        cursor = contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                            arrayOf("%$part%"),
                            null
                        )
                        
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val phoneIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                                if (phoneIndex >= 0 && nameIndex >= 0) {
                                    val foundName = it.getString(nameIndex)
                                    val foundNumber = it.getString(phoneIndex)
                                    Log.d(TAG, "✅ Found contact by partial match: $foundName -> $foundNumber")
                                    return foundNumber
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching contacts: ${e.message}", e)
        }
        
        Log.w(TAG, "❌ No contact found for: $name")
        return null
    }
    
    private fun makePhoneCall(phoneNumber: String, contactName: String) {
        // Check for call permission (Android 6.0+)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // Request permission first
            speakOut("I need call permission to make calls", "INFO")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL_PHONE)
            // Store pending call for retry
            Handler(Looper.getMainLooper()).postDelayed({
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    makePhoneCall(phoneNumber, contactName)
                }
            }, 1500)
            return
        }
        
        // Actually place the call - ACTION_CALL auto-dials
        try {
            speakOut("Calling $contactName", "ACTION")
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Log.d(TAG, "📞 Auto-dialing $contactName at $phoneNumber")
            
            // Continue listening in background during call
            Handler(Looper.getMainLooper()).postDelayed({
                if (isInConversationMode) startListening()
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Call error: ${e.message}")
            // Fallback: Try again with ACTION_CALL
            try {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(callIntent)
                speakOut("Calling $contactName now", "ACTION")
            } catch (ex: Exception) {
                Log.e(TAG, "Final call attempt failed: ${ex.message}")
                speakOut("Unable to make call", "ERROR")
            }
        }
    }
    
    private fun navigateTo(place: String) {
        if (place.isBlank()) {
            speakOut("Please specify a destination", "ERROR")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(place)}"))
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                speakOut("Navigating to $place", "ACTION")
            } else {
                // Fallback to Google Maps search
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(place)}"))
                startActivity(fallback)
                speakOut("Opening maps for $place", "ACTION")
            }
        } catch (e: Exception) {
            speakOut("Navigation failed", "ERROR")
        }
    }
    
    private fun searchWeb(query: String) {
        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, query)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                speakOut("Searching for $query", "ACTION")
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
                startActivity(webIntent)
                speakOut("Searching web", "ACTION")
            }
        } catch (e: Exception) {
            speakOut("Search failed", "ERROR")
        }
    }
    
    private fun getNews() {
        // Redirect to comprehensive world news function
        getWorldNews("latest news")
    }
    
    private fun getLatestNews() {
        // Same as getNews() - fetch current world news without opening Chrome
        getWorldNews("latest news")
    }
    
    /**
     * Perform web search using Gemini AI
     * Detects queries like "search for...", "google...", "find on web..."
     */
    private fun performWebSearch(command: String) {
        Log.d(TAG, "🔍 Performing web search: $command")
        
        // Extract search query by removing command keywords
        val searchQuery = command.lowercase()
            .replace("search for", "")
            .replace("search", "")
            .replace("google", "")
            .replace("find on web", "")
            .replace("look up", "")
            .replace("web search", "")
            .replace("tell me about", "")
            .replace("what is", "")
            .replace("who is", "")
            .replace("where is", "")
            .replace("how to", "")
            .trim()
        
        if (searchQuery.isBlank()) {
            speakOut("What would you like me to search for?", "INFO")
            return
        }
        
        speakOut("Searching the web for $searchQuery", "INFO")
        updateConversation("You", command)
        
        mainScope.launch {
            try {
                // Use Gemini to search and provide comprehensive answer
                val searchPrompt = """Search the web and provide detailed, accurate information about: $searchQuery
                    |
                    |Please provide:
                    |1. A clear and concise answer to the query
                    |2. Key facts and important details
                    |3. Current and up-to-date information (as of 2024-2025)
                    |4. If it's a person, include their profession and notable achievements
                    |5. If it's a place, include location and significance
                    |6. If it's a concept, explain it clearly
                    |
                    |Keep the response informative but conversational, as if explaining to a friend.
                    |Limit to 5-7 sentences maximum for voice readability.""".trimMargin()
                
                val searchResult = geminiClient?.chat(searchPrompt, aiHistory) ?: ""
                
                if (searchResult.isNotBlank()) {
                    Log.d(TAG, "✅ Web search successful")
                    aiHistory.add("user" to command)
                    aiHistory.add("model" to searchResult)
                    updateConversation("Imi", searchResult)
                    speakOnGlass(searchResult, "SEARCH_RESPONSE")
                } else {
                    Log.w(TAG, "❌ Web search returned empty result")
                    speakOut("I couldn't find information on that. Try rephrasing your query.", "ERROR")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error performing web search: ${e.message}", e)
                speakOut("Error searching the web. Please try again.", "ERROR")
            }
        }
    }
    
    private fun getWorldNews(query: String) {
        speakOut("Getting latest news for you", "INFO")
        
        mainScope.launch {
            try {
                // Use NewsAPI to fetch real news
                newsApiService?.getNews(country = "us", query = null) { newsText ->
                    runOnUiThread {
                        if (newsText.contains("couldn't fetch") || newsText.contains("API key")) {
                            // Fallback to Gemini if NewsAPI fails
                            Log.d(TAG, "NewsAPI unavailable, using Gemini fallback")
                            mainScope.launch {
                                val newsPrompt = """Give me a brief summary of the top 5 current news headlines from around the world right now. 
                                    |Include what's happening in different areas like politics, technology, sports, or any major breaking events. 
                                    |Keep it concise but informative - about 2-3 sentences per headline.
                                    |Format it clearly with numbers.""".trimMargin()
                                
                                val newsSummary = geminiClient?.chat(newsPrompt, aiHistory) ?: ""
                                
                                if (newsSummary.isNotBlank()) {
                                    aiHistory.add("user" to "latest news")
                                    aiHistory.add("model" to newsSummary)
                                    updateConversation("Imi", newsSummary)
                                    speakOnGlass(newsSummary, "NEWS_RESPONSE")
                                } else {
                                    speakOut("Unable to fetch news right now", "ERROR")
                                }
                            }
                        } else {
                            // Use real news from NewsAPI
                            aiHistory.add("user" to "latest news")
                            aiHistory.add("model" to newsText)
                            updateConversation("Imi", newsText)
                            speakOnGlass(newsText, "NEWS_RESPONSE")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching news: ${e.message}")
                speakOut("Error getting news. Please try again.", "ERROR")
            }
        }
    }
    
    private fun getHistoricalKnowledge(query: String) {
        // Enhanced prompt with personality and context awareness
        val conversationContext = if (aiHistory.isNotEmpty()) {
            val recentHistory = aiHistory.takeLast(4).joinToString("\n") { "${it.first}: ${it.second}" }
            "Previous conversation:\n$recentHistory\n\n"
        } else ""
        
        val enhancedPrompt = """${conversationContext}You are Imi, a friendly, witty AI assistant with personality. 
            |You remember our previous conversations and build on them.
            |Answer this question with accurate facts and engaging explanation: $query
            |Be conversational, add a touch of humor when appropriate, and keep it concise (2-4 sentences).
            |""".trimMargin()
        
        speakOut("Let me tell you about that", "INFO")
        
        aiHistory.add("user" to query)
        
        currentTtsJob = mainScope.launch {
            try {
                val response = geminiClient?.chat(enhancedPrompt, aiHistory) ?: 
                    "I apologize, I don't have that information right now."
                
                aiHistory.add("model" to response)
                updateConversation("Imi", response)
                speakOnGlass(response, "HISTORY_RESPONSE")
            } catch (e: Exception) {
                Log.e(TAG, "Error getting historical info: ${e.message}")
                speakOnGlass("I'm having trouble accessing that information right now", "ERROR")
            }
        }
    }
    
    private fun analyzeCurrentView() {
        Log.d(TAG, "📸 Vision request - triggering camera capture")
        speakOut("Taking photo", "ACTION")
        
        // Set flag so PHOTO_CAPTURED event auto-triggers analysis
        lastVisionRequestType = "general"
        isWaitingForVisionPhoto = true
        
        // Clear previous photo
        lastCapturedPhoto = null
        
        // Request photo from glasses camera using SafeBleCommandHelper
        // This handles the SDK bug (ArrayIndexOutOfBoundsException in GlassModelControlResponse)
        SafeBleCommandHelper.takePhoto { success, error ->
            if (!success) {
                Log.e(TAG, "❌ Camera trigger failed: $error")
                isWaitingForVisionPhoto = false
                speakOut("Camera unavailable. Please try again.", "ERROR")
            } else {
                Log.d(TAG, "✅ Camera trigger sent, waiting for photo...")
            }
        }
        
        // Photo will be auto-analyzed when PHOTO_CAPTURED event fires
        // Add timeout in case photo never arrives (20 seconds for slower Bluetooth transfers)
        Handler(Looper.getMainLooper()).postDelayed({
            if (isWaitingForVisionPhoto) {
                isWaitingForVisionPhoto = false
                Log.w(TAG, "⚠️ Photo timeout - no photo received after 20 seconds")
                speakOut("Photo not captured. Trying alternative transfer methods...", "ERROR")

                // Try a best-effort capture via SDK reflection (some SDKs expose capturePhoto())
                try {
                    val captureMethod = LargeDataHandler::class.java.getMethod("capturePhoto")
                    val maybeBytes = captureMethod.invoke(LargeDataHandler.getInstance())
                    if (maybeBytes is ByteArray && maybeBytes.isNotEmpty()) {
                        Log.d(TAG, "✅ Fallback capturePhoto() returned ${maybeBytes.size} bytes")
                        // route to same analyzer as normal photo
                        when (lastVisionRequestType) {
                            "person" -> performPersonAnalysis(maybeBytes)
                            else -> performObjectDetection(maybeBytes)
                        }
                        lastVisionRequestType = null
                        return@postDelayed
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback capturePhoto() not available or failed: ${e.message}")
                }

                // If Bluetooth failed, try WiFi transfer to pull the latest album images (best-effort)
                try {
                    val outDir = MyApplication.instance.getAlbumDirFile()
                    mainScope.launch {
                        try {
                            Log.d(TAG, "🔁 Attempting WiFi album transfer as fallback")
                            val result = wifiTransferManager?.startTransfer(outDir)
                            when (result) {
                                is com.sdk.glassessdksample.ui.wifi.WifiTransferManager.TransferResult.Success -> {
                                    // pick newest file in output dir
                                    val files = outDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                                    val newest = files?.firstOrNull()
                                    if (newest != null) {
                                        val bytes = newest.readBytes()
                                        Log.d(TAG, "✅ WiFi fallback downloaded ${newest.name} (${bytes.size} bytes)")
                                        when (lastVisionRequestType) {
                                            "person" -> performPersonAnalysis(bytes)
                                            else -> performObjectDetection(bytes)
                                        }
                                    } else {
                                        Log.w(TAG, "WiFi transfer succeeded but no files found in ${outDir.absolutePath}")
                                        speakOut("Could not retrieve photo from glasses.", "ERROR")
                                    }
                                }
                                is com.sdk.glassessdksample.ui.wifi.WifiTransferManager.TransferResult.NoFiles -> {
                                    Log.w(TAG, "WiFi transfer: no files to download")
                                    speakOut("No photos found on the glasses.", "ERROR")
                                }
                                is com.sdk.glassessdksample.ui.wifi.WifiTransferManager.TransferResult.Error -> {
                                    Log.e(TAG, "WiFi transfer error: ${result.message}")
                                    speakOut("Failed to retrieve photo via WiFi.", "ERROR")
                                }
                                null -> {
                                    Log.w(TAG, "WiFi transfer manager not configured")
                                    speakOut("Could not retrieve photo from glasses.", "ERROR")
                                }
                            }
                        } catch (ex: Exception) {
                            Log.e(TAG, "WiFi fallback failed: ${ex.message}", ex)
                            speakOut("Could not retrieve photo from glasses.", "ERROR")
                        } finally {
                            lastVisionRequestType = null
                        }
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to start WiFi fallback: ${ex.message}")
                    speakOut("Could not retrieve photo from glasses.", "ERROR")
                    lastVisionRequestType = null
                }
            }
        }, 20000) // 20 second timeout for slow Bluetooth transfers
    }

    /**
     * Capture and analyze a person in front of the user with a focused Gemini Vision prompt.
     * This triggers the same camera flow but marks the request as a 'person' analysis so
     * the PHOTO_CAPTURED handler will route to `performPersonAnalysis`.
     */
    private fun analyzePersonInFront() {
        Log.d(TAG, "📸 Person analysis request - triggering camera capture")
        speakOut("Let me see who's there. Taking photo...", "ACTION")

        // Mark this photo as a person analysis request
        lastVisionRequestType = "person"
        isWaitingForVisionPhoto = true
        lastCapturedPhoto = null

        // Use SafeBleCommandHelper to avoid SDK ArrayIndexOutOfBoundsException bug
        SafeBleCommandHelper.takePhoto { success, error ->
            if (!success) {
                Log.e(TAG, "❌ Camera trigger failed for person analysis: $error")
                isWaitingForVisionPhoto = false
                lastVisionRequestType = null
                speakOut("Camera unavailable. Please try again.", "ERROR")
            } else {
                Log.d(TAG, "✅ Camera trigger sent for person analysis, waiting for photo...")
            }
        }

        // 20s timeout similar to analyzeCurrentView
        Handler(Looper.getMainLooper()).postDelayed({
            if (isWaitingForVisionPhoto) {
                isWaitingForVisionPhoto = false
                Log.w(TAG, "⚠️ Photo timeout (person analysis) - no photo received after 20 seconds")
                speakOut("Photo not captured. Trying alternative transfer methods...", "ERROR")

                // Try SDK reflection capture as a fast fallback
                try {
                    val captureMethod = LargeDataHandler::class.java.getMethod("capturePhoto")
                    val maybeBytes = captureMethod.invoke(LargeDataHandler.getInstance())
                    if (maybeBytes is ByteArray && maybeBytes.isNotEmpty()) {
                        Log.d(TAG, "✅ Fallback capturePhoto() returned ${maybeBytes.size} bytes (person analysis)")
                        performPersonAnalysis(maybeBytes)
                        lastVisionRequestType = null
                        return@postDelayed
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback capturePhoto() not available or failed for person analysis: ${e.message}")
                }

                // WiFi album transfer fallback
                try {
                    val outDir = MyApplication.instance.getAlbumDirFile()
                    mainScope.launch {
                        try {
                            Log.d(TAG, "🔁 Attempting WiFi album transfer as fallback (person analysis)")
                            val result = wifiTransferManager?.startTransfer(outDir)
                            when (result) {
                                is com.sdk.glassessdksample.ui.wifi.WifiTransferManager.TransferResult.Success -> {
                                    val files = outDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
                                    val newest = files?.firstOrNull()
                                    if (newest != null) {
                                        val bytes = newest.readBytes()
                                        Log.d(TAG, "✅ WiFi fallback downloaded ${newest.name} (${bytes.size} bytes)")
                                        performPersonAnalysis(bytes)
                                    } else {
                                        Log.w(TAG, "WiFi transfer succeeded but no files found in ${outDir.absolutePath}")
                                        speakOut("Could not retrieve photo from glasses.", "ERROR")
                                    }
                                }
                                is com.sdk.glassessdksample.ui.wifi.WifiTransferManager.TransferResult.NoFiles -> {
                                    Log.w(TAG, "WiFi transfer: no files to download")
                                    speakOut("No photos found on the glasses.", "ERROR")
                                }
                                is com.sdk.glassessdksample.ui.wifi.WifiTransferManager.TransferResult.Error -> {
                                    Log.e(TAG, "WiFi transfer error: ${result.message}")
                                    speakOut("Failed to retrieve photo via WiFi.", "ERROR")
                                }
                                null -> {
                                    Log.w(TAG, "WiFi transfer manager not configured")
                                    speakOut("Could not retrieve photo from glasses.", "ERROR")
                                }
                            }
                        } catch (ex: Exception) {
                            Log.e(TAG, "WiFi fallback failed (person analysis): ${ex.message}", ex)
                            speakOut("Could not retrieve photo from glasses.", "ERROR")
                        } finally {
                            lastVisionRequestType = null
                        }
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed to start WiFi fallback (person analysis): ${ex.message}")
                    speakOut("Could not retrieve photo from glasses.", "ERROR")
                    lastVisionRequestType = null
                }
            }
        }, 20000)
    }

    private fun performPersonAnalysis(imageBytes: ByteArray) {
        aiHistory.add("user" to "Identify the person in front of me and describe details.")

        currentTtsJob = mainScope.launch {
            try {
                Log.d(TAG, "🔎 Performing person-focused vision analysis (${imageBytes.size} bytes)")

                val personPrompt = """You will be given a photo. Focus on PERSON DETECTION and DESCRIPTIVE DETAILS.
                    |Prioritize: gender, approximate age, facial features (hair, beard, glasses), clothing (color, style, accessories), body posture/position, activity, environment (office/home/outdoors), and any distinctive marks (tattoos, jewelry).
                    |Do not guess specific identities. Use cautious language for uncertain attributes (e.g., "appears to be approx. 30 years old").
                    |Return a concise, human-friendly description suitable for voice output. Include short bullets for clothing and distinctive features.
                """.trimMargin()

                val response = visionClient?.analyzeImage(imageBytes, personPrompt) ?: "I couldn't analyze the image."

                Log.d(TAG, "✅ Person analysis complete: $response")

                aiHistory.add("model" to response)
                updateConversation("Imi", response)
                speakOnGlass(response, "VISION_RESPONSE")

                Handler(Looper.getMainLooper()).postDelayed({
                    if (isInConversationMode) startListening()
                }, 500)
            } catch (e: Exception) {
                Log.e(TAG, "Error in person analysis: ${e.message}", e)
                speakOnGlass("I couldn't analyze the person in front of you. Please try again.", "ERROR")
            }
        }
    }
    
    private fun checkGlassBattery() {
        val batteryMsg = if (glassBatteryLevel != null) {
            "Glass battery is at $glassBatteryLevel percent"
        } else {
            "Glass battery level is currently unavailable"
        }
        Log.d(TAG, "🔋 Battery query: $batteryMsg")
        speakOut(batteryMsg, "INFO")
    }
    
    /**
     * Open Vision Chat activity for enhanced image analysis with Gemini Vision
     */
    private fun openVisionChat(autoCapture: Boolean = false, forceNew: Boolean = false, query: String? = null) {
        try {
            val intent = Intent(this, VisionChatActivity::class.java)
            if (autoCapture) {
                // Use literal strings "auto_capture", "force_new_capture", "vision_query" to match VisionChatActivity
                intent.putExtra("auto_capture", true)
                intent.putExtra("force_new_capture", forceNew)
                if (query != null) intent.putExtra("vision_query", query)
            }
            // Ensure onNewIntent is called if already running
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            
            startActivity(intent)
            speakOut("Opening Vision Chat for detailed analysis", "ACTION")
            Log.d(TAG, "👓 Vision Chat launched (auto=$autoCapture)")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Vision Chat", e)
            speakOut("Failed to open Vision Chat", "ERROR")
        }
    }
    
    /**
     * Trigger Vision Chat analysis and share results back to Gemini conversation
     * This captures an image, sends it to Vision Chat, and makes results available to main AI
     */
    private fun triggerVisionChatWithGemini(userQuery: String) {
        speakOut("Opening Vision Chat for analysis", "VISION")
        
        // Add to conversation that analysis is starting
        val analysisNote = "Opening Vision Chat for detailed AI analysis..."
        aiHistory.add("user" to userQuery)
        aiHistory.add("model" to analysisNote)
        updateConversation("Imi", analysisNote)
        
        // Open Vision Chat immediately and let IT handle the capture
        // This avoids double capture and handles re-entry gracefully with onNewIntent
        Handler(Looper.getMainLooper()).postDelayed({
            openVisionChat(autoCapture = true, forceNew = true, query = userQuery)
        }, 500)
    }
    
    private fun performObjectDetection(imageBytes: ByteArray) {
        aiHistory.add("user" to "What objects do you see?")
        
        currentTtsJob = mainScope.launch {
            try {
                Log.d(TAG, "🔍 Analyzing image (${imageBytes.size} bytes) with Gemini Vision...")

                val visionPrompt = """You will be given a photo. Produce two outputs separated by a blank line.
                    |
                    |1) VOICE_SUMMARY: a concise 1-2 sentence summary suitable for speaking aloud (start this section with the literal token "VOICE_SUMMARY:").
                    |
                    |2) DETAILED_DESCRIPTION: after a blank line, produce detailed bullet points under the header "DETAILED_DESCRIPTION:" covering the following (be specific, factual, and avoid guessing identity):
                    |   - Main objects/items (name precisely if possible, include counts and relative positions)
                    |   - Any visible text (transcribe exactly)
                    |   - Colors, brands, models if visible
                    |   - People present: gender, approximate age range, facial features (glasses/beard/hair), clothing (color/style/accessories), posture/activity
                    |   - Environment/context (office/home/outdoors, notable landmarks)
                    |   - Distinctive features (tattoos, jewelry, scars, unique clothing)
                    |   - Any safety/privacy concerns (e.g., personal data visible)
                    |
                    |Constraints:
                    |- Do NOT attempt to identify a person's real identity.
                    |- Use cautious language for uncertain attributes (e.g., "appears to be", "likely").
                    |- Keep VOICE_SUMMARY short and natural for TTS.
                    |
                    |Return the VOICE_SUMMARY first, then a blank line, then DETAILED_DESCRIPTION with bullet points.
                """.trimMargin()

                val response = visionClient?.analyzeImage(imageBytes, visionPrompt) ?: "I couldn't analyze the image."

                Log.d(TAG, "✅ Vision analysis complete: ${response.take(200)}...")

                aiHistory.add("model" to response)
                updateConversation("Imi", response)

                // Extract first paragraph (VOICE_SUMMARY) for quick voice reply
                val voiceSummary = response.split("\n\n").firstOrNull()?.removePrefix("VOICE_SUMMARY:")?.trim() ?: response
                speakOnGlass(voiceSummary, "VISION_RESPONSE")
                
                // Continue conversation
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isInConversationMode) startListening()
                }, 500)
            } catch (e: Exception) {
                Log.e(TAG, "Error in object detection: ${e.message}", e)
                speakOnGlass("I couldn't analyze what I see. Please try again.", "ERROR")
            }
        }
    }
    
    private fun getWeather(query: String) {
        val lowerQuery = query.lowercase()
        
        // Try to extract city name from query
        val cityMatch = Regex("(weather|mausam|temperature)\\s+(in|for|of|at)?\\s*([a-zA-Z\\s]+)").find(lowerQuery)
        val cityName = cityMatch?.groupValues?.get(3)?.trim()
        
        if (!cityName.isNullOrBlank() && cityName.length > 2) {
            // User specified a city
            getWeatherForLocation(cityName)
        } else {
            // Get weather for current location
            getCurrentLocationWeather()
        }
    }
    
    private fun getCurrentLocationWeather() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            speakOut("I need location permission to get weather information", "ERROR")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 301)
            return
        }
        
        speakOut("Getting weather for your location", "INFO")
        
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val lat = location.latitude
                val lon = location.longitude
                
                // First fetch verified weather from WeatherService, then use Gemini to produce a natural summary
                weatherService?.getWeatherByCoords(lat, lon) { weatherResult, errorMsg ->
                    if (weatherResult != null) {
                        // Build a prompt for Gemini with the verified weather data
                        val verified = "Verified weather data: location=${weatherResult.locationName}, temp=${String.format(Locale.US, "%.1f", weatherResult.temperatureC)}°C, condition=${weatherResult.condition}, humidity=${weatherResult.humidity}%, wind=${String.format(Locale.US, "%.1f", weatherResult.windSpeed)} m/s."
                        val weatherPrompt = """$verified\n\nNow create a concise 2-sentence spoken summary for the user based on this verified data. Keep it friendly and clear."""

                        // Prefer a concise programmatic summary to avoid repetitive Gemini phrasing.
                        val concise = String.format(Locale.US, "%.1f°C, %s in %s. Humidity %d%%.",
                            weatherResult.temperatureC, weatherResult.condition, weatherResult.locationName, weatherResult.humidity)
                        updateConversation("Imi", concise)
                        speakOnGlass(concise, "WEATHER_RESPONSE")
                    } else {
                        // No verified data - either no API key or fetch failed; fallback to Gemini only
                        if (!errorMsg.isNullOrBlank()) speakOut(errorMsg, "ERROR")
                        // Fallback: if verified data unavailable, use Gemini; otherwise handled above.
                        mainScope.launch {
                            try {
                                val weatherPrompt = "Get current weather information for coordinates: $lat, $lon. Provide: temperature, weather condition, and location name. Keep response concise (2-3 sentences)."
                                val weatherInfo = geminiClient?.chat(weatherPrompt, aiHistory) ?: ""
                                if (weatherInfo.isNotBlank()) {
                                    updateConversation("Imi", weatherInfo)
                                    speakOnGlass(weatherInfo, "WEATHER_RESPONSE")
                                } else {
                                    speakOut("Unable to get weather information", "ERROR")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Weather error fallback: ${e.message}")
                                speakOut("Unable to get weather information right now", "ERROR")
                            }
                        }
                    }
                }
            } else {
                speakOut("Location unavailable. Please try again.", "ERROR")
            }
        }.addOnFailureListener {
            speakOut("Failed to get location", "ERROR")
        }
    }
    
    private fun getWeatherForLocation(cityName: String) {
        speakOut("Getting weather for $cityName", "INFO")
        
        // If the user asked for a STATE or country (e.g., "California", "USA"),
        // prefer a web search for "<location> weather" because Gemini's geocoding
        // may occasionally return ambiguous local coordinates.
        val lowerCity = cityName.lowercase()
        val usStatesOrCountry = listOf("california", "texas", "florida", "new york", "ny", "usa", "united states", "america")
        if (usStatesOrCountry.any { lowerCity.contains(it) }) {
            // Use web search flow for better current weather accuracy for states/countries
            performWebSearch("$cityName weather")
            return
        }

        // Prefer verified weather from WeatherService when available
        weatherService?.getWeatherByCity(cityName) { weatherResult, errorMsg ->
            if (weatherResult != null) {
                val verified = "Verified weather data: location=${weatherResult.locationName}, temp=${String.format(Locale.US, "%.1f", weatherResult.temperatureC)}°C, condition=${weatherResult.condition}, humidity=${weatherResult.humidity}%, wind=${String.format(Locale.US, "%.1f", weatherResult.windSpeed)} m/s."
                val weatherPrompt = """$verified\n\nNow create a concise 2-sentence spoken summary for the user based on this verified data. Keep it friendly and clear."""

                // Prefer a concise programmatic summary to avoid repetitiveness
                val concise = String.format(Locale.US, "%.1f°C, %s in %s. Humidity %d%%.",
                    weatherResult.temperatureC, weatherResult.condition, weatherResult.locationName, weatherResult.humidity)
                aiHistory.add("model" to concise)
                updateConversation("Imi", concise)
                speakOnGlass(concise, "WEATHER_RESPONSE")
            } else {
                if (!errorMsg.isNullOrBlank()) speakOut(errorMsg, "ERROR")
                // Fallback to Gemini-only behavior
                mainScope.launch {
                    try {
                        val weatherPrompt = "Get current weather information for $cityName. Provide current temperature in Celsius, weather condition, and any notable info. Keep response concise (2-3 sentences)."
                        val weatherInfo = geminiClient?.chat(weatherPrompt, aiHistory) ?: ""
                        if (weatherInfo.isNotBlank()) {
                            aiHistory.add("model" to weatherInfo)
                            updateConversation("Imi", weatherInfo)
                            speakOnGlass(weatherInfo, "WEATHER_RESPONSE")
                        } else {
                            speakOut("Unable to get weather for $cityName", "ERROR")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Weather error fallback: ${e.message}")
                        speakOut("Unable to get weather information right now", "ERROR")
                    }
                }
            }
        } ?: run {
            // WeatherService not initialized - fallback to Gemini-only
            mainScope.launch {
                try {
                    val weatherPrompt = "Get current weather information for $cityName. Provide current temperature in Celsius, weather condition, and any notable info. Keep response concise (2-3 sentences)."
                    val weatherInfo = geminiClient?.chat(weatherPrompt, aiHistory) ?: ""
                    if (weatherInfo.isNotBlank()) {
                        aiHistory.add("model" to weatherInfo)
                        updateConversation("Imi", weatherInfo)
                        speakOnGlass(weatherInfo, "WEATHER_RESPONSE")
                    } else {
                        speakOut("Unable to get weather for $cityName", "ERROR")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Weather error fallback: ${e.message}")
                    speakOut("Unable to get weather information right now", "ERROR")
                }
            }
        }
    }
    
    // ========== NEW FEATURES: News, Video, Jokes, Roasting, Suggestions ==========
    
    /**
     * Get location-based news using NewsAPI
     */
    private fun getLocationBasedNews(query: String) {
        Log.d(TAG, "📰 Fetching news for query: $query")
        speakOut("Getting latest news", "INFO")
        
        val lowerQuery = query.lowercase()
        
        // Extract location from query
        val location = when {
            lowerQuery.contains("mumbai") || lowerQuery.contains("मुंबई") -> "Mumbai"
            lowerQuery.contains("delhi") || lowerQuery.contains("दिल्ली") -> "Delhi"
            lowerQuery.contains("bangalore") || lowerQuery.contains("bengaluru") -> "Bangalore"
            lowerQuery.contains("india") || lowerQuery.contains("भारत") -> "India"
            lowerQuery.contains("maharashtra") || lowerQuery.contains("महाराष्ट्र") -> "Maharashtra"
            lowerQuery.contains("rajasthan") || lowerQuery.contains("राजस्थान") -> "Rajasthan"
            lowerQuery.contains("world") || lowerQuery.contains("international") -> null
            else -> null
        }
        
        mainScope.launch {
            try {
                if (location != null) {
                    // Location-specific news search
                    newsApiService?.searchNews(location) { newsSummary ->
                        runOnUiThread {
                            aiHistory.add("model" to newsSummary)
                            updateConversation("Imi", newsSummary)
                            speakOnGlass(newsSummary, "NEWS_RESPONSE")
                        }
                    }
                } else {
                    // Top headlines for India
                    newsApiService?.getNews(country = "in", query = null) { newsSummary ->
                        runOnUiThread {
                            aiHistory.add("model" to newsSummary)
                            updateConversation("Imi", newsSummary)
                            speakOnGlass(newsSummary, "NEWS_RESPONSE")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "News error: ${e.message}")
                speakOut("Unable to fetch news right now", "ERROR")
            }
        }
    }
    
    /**
     * Start video recording on glasses
     */
    private fun startVideoRecording() {
        Log.d(TAG, "🎥 Starting video recording")
        speakOut("Starting video recording", "ACTION")
        
        // Use SafeBleCommandHelper to avoid SDK bug crash
        SafeBleCommandHelper.startRecording(
            onSuccess = { code ->
                Log.d(TAG, "✅ Video recording started (code: $code)")
                runOnUiThread { updateConversation("Imi", "Video recording started") }
            },
            onError = { error ->
                Log.e(TAG, "❌ Failed to start video: $error")
                runOnUiThread { speakOut("Failed to start video recording", "ERROR") }
            }
        )
    }
    
    /**
     * Stop video recording on glasses
     */
    private fun stopVideoRecording() {
        Log.d(TAG, "🎥 Stopping video recording")
        speakOut("Stopping video recording", "ACTION")
        
        // Use SafeBleCommandHelper to avoid SDK bug crash
        SafeBleCommandHelper.stopRecording(
            onSuccess = { code ->
                Log.d(TAG, "✅ Video recording stopped successfully (code: $code)")
                runOnUiThread {
                    updateConversation("Imi", "Video recording stopped. Video saved to glasses memory.")
                    speakOut("Video saved", "ACTION")
                }
            },
            onError = { error ->
                Log.e(TAG, "❌ Failed to stop video: $error")
                runOnUiThread {
                    speakOut("Stop command sent to glasses", "ACTION")
                    updateConversation("Imi", "Stop command sent to glasses")
                }
            }
        )
    }
    
    /**
     * Tell a joke using Gemini with personality
     */
    private fun tellJoke() {
        Log.d(TAG, "😄 Telling joke")
        speakOut("Let me tell you something funny", "INFO")
        
        val jokePrompt = """You are Imi, a witty AI assistant with great sense of humor.
            |Tell me a short, clever joke or funny observation.
            |Keep it clean, relatable, and under 3 sentences.
            |Make it smart and engaging - no cringey dad jokes!""".trimMargin()
        
        aiHistory.add("user" to "tell me a joke")
        
        mainScope.launch {
            try {
                val joke = geminiClient?.chat(jokePrompt, emptyList()) ?: 
                    "Why don't scientists trust atoms? Because they make up everything!"
                
                aiHistory.add("model" to joke)
                updateConversation("Imi", joke)
                speakOnGlass(joke, "JOKE_RESPONSE")
            } catch (e: Exception) {
                Log.e(TAG, "Joke error: ${e.message}")
                speakOut("I forgot my joke! Try again later.", "ERROR")
            }
        }
    }
    
    /**
     * Roast the user (playfully) using Gemini
     */
    private fun roastUser(query: String) {
        Log.d(TAG, "🔥 Roasting mode activated")
        speakOut("Oh you want a roast? Okay then", "INFO")
        
        val conversationContext = aiHistory.takeLast(6).joinToString("\\n") { "${it.first}: ${it.second}" }
        
        val roastPrompt = """You are Imi, a sassy AI with sharp wit. 
            |Based on this conversation: $conversationContext
            |Give a playful, clever roast about: $query
            |Be witty and sarcastic but keep it fun (not mean). 2-3 sentences max.
            |Example style: "You're asking ME for advice? That's like asking a fish for directions on land!""".trimMargin()
        
        aiHistory.add("user" to query)
        
        mainScope.launch {
            try {
                val roast = geminiClient?.chat(roastPrompt, emptyList()) ?: 
                    "I would roast you, but you're already burnt out from asking me so many questions!"
                
                aiHistory.add("model" to roast)
                updateConversation("Imi", roast)
                speakOnGlass(roast, "ROAST_RESPONSE")
            } catch (e: Exception) {
                Log.e(TAG, "Roast error: ${e.message}")
                speakOut("Can't roast you right now, but trust me - I would have destroyed you!", "ERROR")
            }
        }
    }
    
    /**
     * Give suggestions/advice using context and history
     */
    private fun giveSuggestion(query: String) {
        Log.d(TAG, "💡 Giving suggestion")
        speakOut("Let me think about that", "INFO")
        
        val conversationContext = if (aiHistory.isNotEmpty()) {
            "Previous conversation context:\\n" + 
            aiHistory.takeLast(8).joinToString("\\n") { "${it.first}: ${it.second}" }
        } else ""
        
        val suggestionPrompt = """${conversationContext}
            |
            |You are Imi, a thoughtful AI mentor who remembers previous conversations.
            |User asks: $query
            |
            |Provide personalized advice considering:
            |1. Our conversation history (if any)
            |2. Practical, actionable suggestions
            |3. Encouraging but realistic tone
            |Keep it concise (3-4 sentences) and conversational.""".trimMargin()
        
        aiHistory.add("user" to query)
        
        mainScope.launch {
            try {
                val suggestion = geminiClient?.chat(suggestionPrompt, aiHistory) ?: 
                    "Based on what we've discussed, I'd suggest taking small steps consistently. Break your goal into manageable tasks and track your progress."
                
                aiHistory.add("model" to suggestion)
                updateConversation("Imi", suggestion)
                speakOnGlass(suggestion, "SUGGESTION_RESPONSE")
            } catch (e: Exception) {
                Log.e(TAG, "Suggestion error: ${e.message}")
                speakOut("I can't give advice right now, but follow your instincts!", "ERROR")
            }
        }
    }
    
    // ========== END NEW FEATURES ==========
    
    private fun trackLocation() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                speakOut("Location permission needed", "ERROR")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 301)
                return
            }
            
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val lat = location.latitude
                    val lon = location.longitude
                    speakOut("Your location is $lat, $lon", "INFO")
                    updateConversation("Imi", "Location: Lat $lat, Lon $lon")
                    
                    // Open in maps
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon"))
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    }
                } else {
                    speakOut("Location unavailable", "ERROR")
                }
            }.addOnFailureListener {
                speakOut("Failed to get location", "ERROR")
            }
        } catch (e: Exception) {
            speakOut("Location tracking failed", "ERROR")
        }
    }
    
    private fun calculateDistance(query: String) {
        // Parse origin and destination from query (e.g., "distance from cityA to cityB")
        val lowerQuery = query.lowercase()
        
        val fromMatch = Regex("from\\s+(\\w+(?:\\s+\\w+)?)").find(lowerQuery)
        val toMatch = Regex("to\\s+(\\w+(?:\\s+\\w+)?)").find(lowerQuery)
        
        if (fromMatch != null && toMatch != null) {
            val origin = fromMatch.groupValues[1]
            val destination = toMatch.groupValues[1]
            
            pendingDistanceOrigin = origin
            pendingDistanceDestination = destination
            
            // Show on Google Maps first
            showDistanceOnMap(origin, destination)
            
            // Ask for travel mode
            speakOut("Showing route from $origin to $destination. How would you like to travel? By car, train, bus, or flight?", "QUERY")
            pendingAction = { travelMode ->
                provideTravelRecommendations(origin, destination, travelMode)
            }
        } else {
            // Fallback to Gemini to extract and calculate
            speakOut("Let me calculate the distance for you.", "INFO")
            chatWithGemini("Calculate distance: $query and provide travel options")
        }
    }
    
    private fun showDistanceOnMap(origin: String, destination: String) {
        try {
            // Open Google Maps with directions
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://www.google.com/maps/dir/?api=1&origin=${Uri.encode(origin)}&destination=${Uri.encode(destination)}"
            ))
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening maps: ${e.message}")
        }
    }
    
    private fun provideTravelRecommendations(origin: String, destination: String, travelMode: String) {
        val mode = travelMode.lowercase()
        
        val response = when {
            mode.contains("car") || mode.contains("drive") -> {
                "You can drive from $origin to $destination. Would you like me to open navigation?"
            }
            mode.contains("train") -> {
                "You can take a train from $origin to $destination. Opening IRCTC for booking."
            }
            mode.contains("bus") -> {
                "You can take a bus from $origin to $destination. Opening RedBus for booking."
            }
            mode.contains("flight") || mode.contains("plane") -> {
                "You can fly from $origin to $destination. Opening flight booking site."
            }
            else -> {
                "I can help you travel by car, train, bus, or flight. Which would you prefer?"
            }
        }
        
        speakOut(response, "INFO")
        
        // Open booking sites based on mode
        try {
            val bookingUrl = when {
                mode.contains("train") -> "https://www.irctc.co.in/nget/train-search"
                mode.contains("bus") -> "https://www.redbus.in/"
                mode.contains("flight") || mode.contains("plane") -> "https://www.google.com/flights?q=flights+from+${Uri.encode(origin)}+to+${Uri.encode(destination)}"
                mode.contains("car") || mode.contains("drive") -> {
                    // Open Google Maps navigation
                    val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse(
                        "google.navigation:q=${Uri.encode(destination)}"
                    ))
                    if (navIntent.resolveActivity(packageManager) != null) {
                        startActivity(navIntent)
                    }
                    return
                }
                else -> null
            }
            
            bookingUrl?.let {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it))
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening booking site: ${e.message}")
        }
    }

    private fun chatWithGemini(command: String) {
        geminiClient ?: return
        if (tts?.isSpeaking == true) {
            tts?.stop()
            currentTtsJob?.cancel()
        }
        
        // Check if user is asking about previous conversation
        val lowerCmd = command.lowercase()
        if (lowerCmd.contains("what did we talk") || lowerCmd.contains("previous conversation") || 
            lowerCmd.contains("what was our chat") || lowerCmd.contains("conversation history")) {
            
            if (aiHistory.isEmpty()) {
                speakOut("We haven't talked about anything yet in this session.", "INFO")
                return
            }
            
            // Summarize conversation using Gemini
            val summaryPrompt = "Summarize our conversation history briefly in 2-3 sentences."
            aiHistory.add("user" to summaryPrompt)
            
            currentTtsJob = mainScope.launch {
                try {
                    val summary = visionClient?.chat(summaryPrompt) ?: "Error"
                    aiHistory.add("model" to summary)
                    updateConversation("Imi", summary)
                    speakOnGlass(summary, "AI_RESPONSE")
                } catch (e: Exception) {
                    speakOnGlass("Error retrieving conversation history", "AI_RESPONSE")
                }
            }
            return
        }
        
        // Add current command to conversation history without clearing previous context
        aiHistory.add("user" to command)
        
        // Limit history to prevent excessive memory usage (keep last 30 exchanges)
        if (aiHistory.size > 60) { // 60 = 30 user + 30 AI responses
            aiHistory.subList(0, aiHistory.size - 60).clear()
        }
        
        currentTtsJob = mainScope.launch {
            try {
                val reply = visionClient?.chat(command) ?: "Error connecting to AI"
                aiHistory.add("model" to reply)
                updateConversation("Imi", reply)
                
                speakOnGlass(reply, "AI_RESPONSE")
            } catch (e: Exception) {
                speakOnGlass("Error connecting to AI", "AI_RESPONSE")
            }
        }
    }
    
    private fun initGlassWifiListener() {
        val storagePath = MyApplication.instance.getAlbumDirFile().absolutePath
        GlassesControl.getInstance(MyApplication.instance)?.setWifiDownloadListener(object : GlassesControl.WifiFilesDownloadListener {
            override fun voiceFromGlasses(pcmData: ByteArray) {
                HotHelper.getInstance(this@MainActivity).processGlassAudio(pcmData)
            }
            override fun eisEnd(f: String, p: String) { saveToPublicGallery(File(p)) }
            override fun eisError(f: String, s: String, e: String) {}
            override fun fileCount(i: Int, t: Int) {}
            override fun fileDownloadComplete() {}
            override fun fileDownloadError(t: Int, e: Int) {}
            override fun fileProgress(n: String, p: Int) {}
            override fun fileWasDownloadSuccessfully(e: GlassAlbumEntity) { saveToPublicGallery(File(storagePath, e.fileName)) }
            override fun recordingToPcm(f: String, p: String, d: Int) {}
            override fun recordingToPcmError(f: String, e: String) {}
            override fun onGlassesControlSuccess() {}
            override fun onGlassesFail(e: Int) {}
            override fun voiceFromGlassesStatus(s: Int) {}
            override fun wifiSpeed(s: String) {}
        })
    }

    private fun initView() {
        // Initialize chat RecyclerView
        chatAdapter = ChatAdapter(chatMessages)
        binding.rvChatMessages.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            adapter = chatAdapter
        }
        
        // Update location and time
        updateLocationAndTime()
        
        // Load glass image from assets
        try {
            val inputStream = assets.open("glass1.png")
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            binding.ivGlassImage.setImageBitmap(bitmap)
            inputStream.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading glass1.png: ${e.message}")
        }
        
        // Handle send button
        binding.btnSendMessage.setOnClickListener {
            val message = binding.etChatInput.text.toString().trim()
            if (message.isNotEmpty()) {
                // Add user message
                chatAdapter.addMessage(ChatMessage(text = message, isFromUser = true))
                binding.rvChatMessages.scrollToPosition(chatMessages.size - 1)
                binding.etChatInput.setText("")
                
                // Process with Gemini
                processUserMessage(message)
            }
        }
        
        binding.btnVoice.isEnabled = voiceCommandEnabled
        // Setup switch if it exists in layout, else ignore
        // Glass microphone UI removed — do not auto-enable glass mic on wake.
        // Any manual glass mic enablement must be done from Device Bind flow.
        
        binding.btnVoice.setOnClickListener {
             if (voiceCommandEnabled) EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.VOICE_TEXT, "wake up"))
        }
        
        // Quick wake button - bypass voice detection, directly trigger wake
        binding.btnQuickWake.setOnClickListener {
            Log.d(TAG, "🎙️ Quick Wake button pressed - triggering wake event")
            EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.VOICE_TEXT, "wake up"))
        }
        
        // Interrupt Mode Switch - Enable/Disable AI interruption when user speaks
        binding.switchInterruptMode.setOnCheckedChangeListener { _, isChecked ->
            isInterruptEnabled = isChecked
            val status = if (isChecked) "ON - AI stops when you speak" else "OFF - AI finishes speaking"
            Toast.makeText(this, "Interrupt Mode: $status", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "🎙️ Interrupt Mode: ${if (isChecked) "ENABLED" else "DISABLED"}")
        }
        
        // Vision Chat removed per user request
        
        binding.btnBt.setOnClickListener { startKtxActivity<DeviceBindActivity>() }
        
        // Setup other buttons - use SafeBleCommandHelper to avoid SDK bug crash
        binding.btnCamera.setOnClickListener { SafeBleCommandHelper.takePhoto() }
        binding.btnVideo.setOnClickListener { SafeBleCommandHelper.startRecording() }
        
        // Glass Gallery button
        binding.btnGlassGallery.setOnClickListener { openGlassMediaGallery() }
        
        // Vision Chat button - AI-powered image analysis via Bluetooth
        binding.btnVisionChat.setOnClickListener { openVisionChat() }
        
        // Upload settings removed per user request
    }
    
    /**
     * Process user message from chat input
     */
    private fun processUserMessage(message: String) {
        lifecycleScope.launch {
            try {
                // Get AI response
                val response = withContext(Dispatchers.IO) {
                    geminiClient?.chat(message, aiHistory) ?: "I'm sorry, I couldn't process that."
                }
                
                // Add to history
                aiHistory.add(Pair("user", message))
                aiHistory.add(Pair("model", response))
                
                // Update UI
                chatAdapter.addMessage(ChatMessage(text = response, isFromUser = false))
                binding.rvChatMessages.scrollToPosition(chatMessages.size - 1)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message: ${e.message}", e)
                chatAdapter.addMessage(ChatMessage(text = "Sorry, I encountered an error.", isFromUser = false))
                binding.rvChatMessages.scrollToPosition(chatMessages.size - 1)
            }
        }
    }
    
    /**
     * Update current location and time display
     */
    private fun updateLocationAndTime() {
        // Update time every minute
        lifecycleScope.launch {
            while (isActive) {
                val currentTime = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
                val currentDate = java.text.SimpleDateFormat("EEE, MMM dd", Locale.getDefault()).format(java.util.Date())
                binding.tvCurrentTime.text = "$currentDate • $currentTime"
                delay(60000) // Update every minute
            }
        }
        
        // Get current location
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this@MainActivity)
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        location?.let {
                            // Get address from coordinates
                            val geocoder = android.location.Geocoder(this@MainActivity, Locale.getDefault())
                            try {
                                val addresses = geocoder.getFromLocation(it.latitude, it.longitude, 1)
                                if (addresses?.isNotEmpty() == true) {
                                    val address = addresses[0]
                                    val locationText = buildString {
                                        address.locality?.let { append(it) }
                                        if (address.locality != null && address.adminArea != null) append(", ")
                                        address.adminArea?.let { append(it) }
                                        if (address.countryName != null && (address.locality != null || address.adminArea != null)) append(", ")
                                        address.countryName?.let { append(it) }
                                    }
                                    lifecycleScope.launch(Dispatchers.Main) {
                                        binding.tvLocationName.text = locationText.ifEmpty { "Location unavailable" }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error getting address: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting location: ${e.message}")
                }
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Save conversation history when app goes to background
        saveConversationHistory()
        
        // DON'T stop listening - keep conversation active in background
        Log.d(TAG, "App paused - conversation continues in background (mode: $isInConversationMode)")
    }
    
    /**
     * Save conversation history to SharedPreferences using Gson
     */
    private fun saveConversationHistory() {
        try {
            val gson = Gson()
            val historyJson = gson.toJson(aiHistory)
            prefs.edit()
                .putString("conversation_history", historyJson)
                .putLong("conversation_timestamp", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Conversation history saved (${aiHistory.size} entries)")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving conversation history: ${e.message}")
        }
    }
    
    /**
     * Load conversation history from SharedPreferences
     * Only load if it's from the same session (within last hour)
     */
    private fun loadConversationHistory() {
        try {
            val historyJson = prefs.getString("conversation_history", null)
            val timestamp = prefs.getLong("conversation_timestamp", 0)
            val currentTime = System.currentTimeMillis()
            
            // Only load history if it's recent (within last hour)
            if (historyJson != null && (currentTime - timestamp) < 3600000) {
                val gson = Gson()
                val type = object : TypeToken<MutableList<Pair<String, String>>>() {}.type
                val loadedHistory: MutableList<Pair<String, String>>? = gson.fromJson(historyJson, type)
                

    
                loadedHistory?.let {
                    aiHistory.clear()
                    aiHistory.addAll(it)
                    Log.d(TAG, "Conversation history loaded (${aiHistory.size} entries)")
                }
            } else if (historyJson != null) {
                // Clear old history
                prefs.edit()
                    .remove("conversation_history")
                    .remove("conversation_timestamp")
                    .apply()
                Log.d(TAG, "Old conversation history cleared")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading conversation history: ${e.message}")
            // Clear corrupted data
            prefs.edit()
                .remove("conversation_history")
                .remove("conversation_timestamp")
                .apply()
        }
    }

    // Ensure permissions then enable Bluetooth SCO routing to use glasses mic
    private fun checkAndEnableGlassMic() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_BLUETOOTH_CONNECT)
            return
        }
        enableGlassMic()
    }

    private fun enableGlassMic() {
        try {
            audioManager?.let { am ->
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                try { am.requestAudioFocus(audioFocusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) } catch (_: Exception) {}
            }

            // Register SCO listener and try to start SCO with retries; only start STT after connected
            try {
                setupScoConnectionListener()
            } catch (_: Exception) {}

            mainScope.launch {
                val scoOk = tryStartScoWithRetries(2000L)
                if (scoOk) {
                    Log.d(TAG, "SCO connected — switching to glass mic and starting listener")
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Glass mic active", Toast.LENGTH_SHORT).show() }
                    try {
                        // Wait a short time for SCO to stabilize before starting the recognizer
                        Handler(Looper.getMainLooper()).postDelayed({
                            try { startListening() } catch (ex: Exception) { Log.w(TAG, "startListening after SCO failed: ${ex.message}") }
                        }, 700)
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to schedule startListening after SCO: ${ex.message}")
                    }
                } else {
                    Log.w(TAG, "SCO failed to connect — falling back to phone mic")
                    withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "SCO connect failed — using phone mic", Toast.LENGTH_SHORT).show() }
                    disableGlassMic()
                    try { startListening() } catch (ex: Exception) { Log.w(TAG, "startListening fallback failed: ${ex.message}") }
                }
            }

        } catch (ex: Exception) {
            Log.e(TAG, "enableGlassMic error: ${ex.message}")
        }
    }

    private fun disableGlassMic() {
        try {
            audioManager?.let { am ->
                try { am.stopBluetoothSco() } catch (_: Exception) {}
                try { am.isBluetoothScoOn = false } catch (_: Exception) {}
                try { am.abandonAudioFocus(audioFocusListener) } catch (_: Exception) {}
                try { am.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
                Toast.makeText(this, "Using phone mic", Toast.LENGTH_SHORT).show()
            }
            try {
                scoConnectionReceiver?.let { unregisterReceiver(it) }
            } catch (_: Exception) {}
            scoConnectionReceiver = null
        } catch (ex: Exception) {
            Log.e(TAG, "disableGlassMic error: ${ex.message}")
        }
    }
    
    inner class MyDeviceNotifyListener : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, rsp: GlassesDeviceNotifyRsp?) {
            try {
                // Defensive logging: log all incoming data for debugging parser issues
                Log.d(TAG, "📥 Bluetooth command received - cmdType: $cmdType, rsp: $rsp")
                
                // Log raw response details if available (helps debug parser crashes)
                try {
                    val rawData = rsp?.javaClass?.getMethod("getData")?.invoke(rsp) as? ByteArray
                    if (rawData != null) {
                        val hexString = rawData.joinToString(" ") { "%02x".format(it) }
                        Log.d(TAG, "📦 Raw packet (${rawData.size} bytes): $hexString")
                    }
                } catch (e: Exception) {
                    // Reflection may fail, that's ok - just skip logging
                }
                
                if (rsp == null) {
                    Log.w(TAG, "Received null response")
                    return
                }
                
                try {
                    // Get data bytes from response using reflection or toString parsing
                    val dataBytes: ByteArray? = try {
                        rsp.javaClass.getMethod("getData").invoke(rsp) as? ByteArray
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not extract data bytes via reflection: ${e.message}")
                        null
                    }
                
                // Early filter: ignore tiny acknowledgement/ACK packets that some
                // glasses firmwares send back after a display write. These often
                // contain short strings like "ok" or "ACK" and should NOT be
                // treated as user input (they cause loops like "ok ok").
                dataBytes?.let { db ->
                    if (db.size <= 6) {
                        // 1) Try to parse as short ASCII ACK/text (common case)
                        try {
                            val shortText = String(db, Charsets.UTF_8).trim().lowercase()
                            val ackPattern = "^(ok|ack|received|done|success|\u0006|\u0004)$".toRegex()
                            if (shortText.matches(ackPattern) || shortText == "okok") {
                                Log.d(TAG, "Ignoring short ACK/text from glasses: '$shortText'")
                                return
                            }
                        } catch (e: Exception) {
                            // fall through to binary heuristics
                        }

                        // 2) Binary/hex heuristics: many vendor ACKs are short non-printable packets
                        val printable = db.count { it.toInt() in 0x20..0x7E }
                        val printableRatio = printable.toDouble() / db.size
                        if (printableRatio < 0.5) {
                            val zeros = db.count { it == 0x00.toByte() }
                            val ffs = db.count { it == 0xFF.toByte() }
                            // Common vendor ACK pattern starts with 0xBC and small fixed-length frames
                            if (db[0] == 0xBC.toByte() || zeros + ffs >= (db.size / 2)) {
                                val hex = db.joinToString(" ") { "%02x".format(it) }
                                Log.d(TAG, "Ignoring binary ACK-like packet from glasses: $hex")
                                return
                            }
                        }
                    }
                }

                when (cmdType) {
                    // Voice command from glasses
                    0x01 -> {
                        dataBytes?.let { voiceData ->
                            try {
                                val voiceText = String(voiceData, Charsets.UTF_8).trim()
                                Log.d(TAG, "🎤 Voice command from glasses: '$voiceText'")
                                
                                // Process the voice command
                                runOnUiThread {
                                    binding.etCommand.setText(voiceText)
                                    updateConversation("You (Glass)", voiceText)
                                    
                                    // Check if it's a wake word
                                    val lowerText = voiceText.lowercase()
                                    if (lowerText.contains("hey imi") || lowerText.contains("wake up")) {
                                        isInConversationMode = true
                                        
                                        // ---------------------------------------------------------
                                        // ⚡ FAST FIX: Don't wait for Gemini AI just to say "Yes Sir"
                                        // ---------------------------------------------------------
                                        
                                        // 1. Update UI immediately
                                        val fastReply = "Yes Sir" 
                                        updateConversation("Imi", fastReply)
                                        
                                        // 2. Speak immediately (No Internet Wait)
                                        speakOnGlass(fastReply, "WAKE_ACK")
                                        
                                        // 3. Enable buttons
                                        voiceCommandEnabled = true
                                        binding.btnVoice.isEnabled = true
                                        
                                        // 4. Fast path: initialize and start Gemini Live for low-latency conversational streaming
                                        try {
                                            isGeminiLiveMode = true
                                            // Initialize if not already done
                                            if (geminiLiveService == null) {
                                                initializeGeminiLive()
                                            }

                                            // Start Gemini Live quickly on a short coroutine so speakOnGlass can return
                                            mainScope.launch {
                                                // tiny settle so audio routing from speak finishes
                                                delay(80)
                                                startGeminiLiveConversation()
                                            }
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Failed to start Gemini Live fast-path: ${e.message}")
                                            isGeminiLiveMode = false
                                        }
                                        
                                    } else {
                                        // Process normal command with AI
                                        handleSpokenCommand(voiceText)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing voice command: ${e.message}", e)
                            }
                        }
                    }
                    
                    // Button press from glasses
                    0x02 -> {
                        dataBytes?.let { buttonData ->
                            try {
                                if (buttonData.isNotEmpty()) {
                                    val buttonCode = buttonData[0].toInt()
                                    // Check if there's a second byte indicating press type (single/double/long)
                                    val pressType = if (buttonData.size > 1) buttonData[1].toInt() else 0
                                    
                                    Log.d(TAG, "🔘 Button pressed on glasses: buttonCode=$buttonCode, pressType=$pressType (bytes=${buttonData.joinToString(" ") { "%02x".format(it) }})")
                                    
                                    runOnUiThread {
                                        when (buttonCode) {
                                            1 -> {
                                                Log.d(TAG, "🔘 Button 1 pressed - taking photo")
                                                Toast.makeText(this@MainActivity, "📷 Taking photo", Toast.LENGTH_SHORT).show()
                                                SafeBleCommandHelper.takePhoto()
                                                speakOut("Taking photo", "ACTION")
                                            }
                                            2 -> {
                                                // Button 2: Check for double-click if pressType byte is present
                                                // Common values: 0x01 = single, 0x02 = double, 0x03 = long
                                                if (pressType == 2 || pressType == 0x02) {
                                                    // Double-click detected from hardware
                                                    Log.d(TAG, "🔘🔘 Button 2 DOUBLE-CLICK detected via hardware")
                                                    Toast.makeText(this@MainActivity, "🎙️🎙️ Double-click wake!", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    // Single-click or no press type - treat as wake
                                                    Log.d(TAG, "🔘 Button 2 pressed - WAKE TRIGGERED")
                                                    Toast.makeText(this@MainActivity, "🎙️ Hey IMI listening...", Toast.LENGTH_SHORT).show()
                                                }
                                                
                                                // Immediate feedback: vibration + sound
                                                try {
                                                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                                    } else {
                                                        @Suppress("DEPRECATION")
                                                        vibrator?.vibrate(100)
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "Vibration failed: ${e.message}")
                                                }
                                                
                                                // Play wake chime for audio feedback (if available)
                                                try {
                                                    val chimeResId = resources.getIdentifier("chime", "raw", packageName)
                                                    if (chimeResId != 0) {
                                                        val mediaPlayer = MediaPlayer()
                                                        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC)
                                                        val afd = resources.openRawResourceFd(chimeResId)
                                                        if (afd != null) {
                                                            mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                                            afd.close()
                                                            mediaPlayer.prepare()
                                                            mediaPlayer.setVolume(1.0f, 1.0f)
                                                            mediaPlayer.start()
                                                            mediaPlayer.setOnCompletionListener { it.release() }
                                                        }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "Wake chime not available: ${e.message}")
                                                }
                                                
                                                // Stop HotHelper and trigger wake
                                                try { HotHelper.getInstance(this@MainActivity).stop() } catch (e: Exception) {
                                                    Log.e(TAG, "Failed to stop HotHelper: ${e.message}")
                                                }
                                                EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.VOICE_TEXT, "wake up"))
                                                Log.d(TAG, "✅ Wake event posted successfully")
                                            }
                                            3 -> {
                                                // Treat button 3 as a quick wake: trigger same wake flow
                                                Log.d(TAG, "🔘 Glass button 3 pressed - triggering wake event")
                                                Toast.makeText(this@MainActivity, "🎙️ Hey IMI listening...", Toast.LENGTH_SHORT).show()
                                                try { HotHelper.getInstance(this@MainActivity).stop() } catch (e: Exception) {}
                                                EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.VOICE_TEXT, "wake up"))
                                            }
                                            else -> {
                                                Log.d(TAG, "❓ Unknown button code: $buttonCode")
                                                Toast.makeText(this@MainActivity, "Button $buttonCode pressed", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "Button data is empty")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing button press: ${e.message}", e)
                            }
                        }
                    }
                    
                    // Photo data from glasses
                    0x03 -> {
                        dataBytes?.let { photoData ->
                            try {
                                Log.d(TAG, "📷 [Photo Received] Photo from glasses (${photoData.size} bytes) | Live Camera: $isLiveCameraActive")
                                
                                // Save photo to storage
                                savePhoto(photoData)
                                Log.d(TAG, "💾 Photo saved successfully")
                                
                                // 🔥 IMPORTANT: Broadcast photo to all activities via EventBus
                                // This allows VisionChatActivity to receive photos even when it's in foreground
                                Log.d(TAG, "📡 Broadcasting PHOTO_CAPTURED event via EventBus")
                                EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.PHOTO_CAPTURED, photoData))
                                
                                // If live camera is active, analyze immediately
                                if (isLiveCameraActive) {
                                    Log.d(TAG, "🎥 [Live Camera] ACTIVE - Triggering analysis now")
                                    analyzeLiveStreamPhoto(photoData)
                                } else {
                                    // Normal photo capture - just confirm saved
                                    Log.d(TAG, "📸 Normal photo mode - no analysis")
                                    runOnUiThread {
                                        speakOut("Photo saved to gallery", "PHOTO_SAVED")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Error processing photo from glasses: ${e.message}", e)
                                e.printStackTrace()
                            }
                        } ?: Log.e(TAG, "❌ Photo data is null!")
                    }
                    
                    // Text message/command from glasses
                    0x04 -> {
                        dataBytes?.let { textData ->
                            try {
                                val textMessage = String(textData, Charsets.UTF_8).trim()
                                Log.d(TAG, "💬 Text command from glasses: '$textMessage'")
                                
                                runOnUiThread {
                                    binding.etCommand.setText(textMessage)
                                    updateConversation("You (Glass)", textMessage)
                                    handleSpokenCommand(textMessage)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error processing text command: ${e.message}", e)
                            }
                        }
                    }
                    
                    // Photo captured notification (cmdType 0x73 = 115)
                    0x73 -> {
                        Log.d(TAG, "📸 PHOTO_CAPTURED notification from glasses")
                        // Photo data will arrive separately via EventBus PHOTO_CAPTURED event
                        // Just log that we got the notification
                    }
                    
                    else -> {
                        Log.d(TAG, "⚠️ Unknown command type: $cmdType")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in parseData: ${e.message}", e)
            }
            
            } catch (outerException: Exception) {
                // Outermost defensive catch - prevents any crashes from propagating
                Log.e(TAG, "❌ CRITICAL: Uncaught exception in parseData wrapper", outerException)
                Log.e(TAG, "cmdType: $cmdType, rsp: $rsp")
                
                // Log stack trace to help identify parser bugs
                if (outerException is ArrayIndexOutOfBoundsException) {
                    Log.e(TAG, "Parser ArrayIndexOutOfBoundsException - likely malformed BLE packet")
                }
            }
        }
    }
    
    /**
     * Start WiFi transfer to download photos/videos from glasses
     */
    private fun startWifiTransfer() {
        speakOut("Starting WiFi transfer. Please make sure glasses are in transfer mode.", "TRANSFER")
        
        mainScope.launch {
            try {
                val outputDir = MyApplication.instance.getAlbumDirFile()
                
                wifiTransferManager?.setStatusListener(object : WifiTransferManager.TransferStatusListener {
                    override fun onStatusUpdate(status: String) {
                        Log.d(TAG, "📶 WiFi Transfer: $status")
                        runOnUiThread {
                            updateConversation("Imi", status)
                        }
                    }
                    
                    override fun onProgressUpdate(fileName: String, current: Int, total: Int) {
                        Log.d(TAG, "📥 Downloading: $fileName ($current/$total)")
                    }
                    
                    override fun onTransferComplete(downloadedFiles: Int, totalFiles: Int) {
                        Log.d(TAG, "✅ Transfer complete: $downloadedFiles files")
                        runOnUiThread {
                            val message = "Transfer complete! Downloaded $downloadedFiles files to gallery."
                            speakOut(message, "TRANSFER_DONE")
                            updateConversation("Imi", message)
                        }
                    }
                    
                    override fun onTransferError(error: String) {
                        Log.e(TAG, "❌ Transfer error: $error")
                        runOnUiThread {
                            speakOut("Transfer failed. $error", "TRANSFER_ERR")
                            updateConversation("Imi", "Transfer failed: $error")
                        }
                    }
                })
                
                val result = wifiTransferManager?.startTransfer(outputDir)
                
                when (result) {
                    is WifiTransferManager.TransferResult.Success -> {
                        Log.d(TAG, "✅ Downloaded ${result.downloadedFiles} files from ${result.glassesIP}")
                    }
                    is WifiTransferManager.TransferResult.NoFiles -> {
                        speakOut("No files to download from glasses.", "TRANSFER")
                    }
                    is WifiTransferManager.TransferResult.Error -> {
                        speakOut("Transfer error: ${result.message}", "TRANSFER_ERR")
                    }
                    null -> {
                        speakOut("WiFi transfer not available.", "TRANSFER_ERR")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "WiFi transfer failed: ${e.message}", e)
                speakOut("WiFi transfer failed. Please check connection and try again.", "TRANSFER_ERR")
            }
            
            // Resume conversation after transfer
            Handler(Looper.getMainLooper()).postDelayed({
                if (isInConversationMode) startListening()
            }, 2000)
        }
    }
    
    // ========== GEMINI LIVE API INTEGRATION ==========
    
    /**
     * Handle tool calls from Gemini Live API
     */
    private fun handleGeminiToolCall(toolName: String, args: Map<String, Any>): String {
        // ONLY analyze_view should open Vision Chat (for "what is in front of me" type queries)
        // take_photo should just take photo, not open Vision Chat
        if (toolName == "analyze_view") {
            try {
                val question = args["question"] as? String ?: "What is in front of me?"
                Log.d(TAG, "👁️ Tool call 'analyze_view' - Opening Vision Chat for: $question")
                
                // Open Vision Chat with auto-capture (this handles camera + hotspot + P2P + download + analysis)
                runOnUiThread {
                    triggerVisionChatFromLive(question)
                }
                
                return "Opening Vision Chat to analyze the view. Please wait..."
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger Vision Chat: ${e.message}", e)
                return "Failed to open Vision Chat: ${e.message}"
            }
        }
        Log.d(TAG, "🔧 Handling tool call: $toolName")
        
        return try {
            when (toolName) {
                "make_phone_call" -> {
                    val contactName = args["contact_name"] as? String ?: return "Error: No contact name provided"
                    runOnUiThread {
                        lookupAndCall(contactName)
                    }
                    "Calling $contactName..."
                }
                
                "play_music" -> {
                    val songOrArtist = args["song_or_artist"] as? String ?: "popular songs"
                    runOnUiThread {
                        try {
                            val intent = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                            intent.putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                            intent.putExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE, songOrArtist)
                            intent.putExtra(android.app.SearchManager.QUERY, songOrArtist)
                            intent.setPackage("com.spotify.music")
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

                            try {
                                startActivity(intent)
                                Log.d(TAG, "Started Spotify play intent for: $songOrArtist")
                            } catch (e: Exception) {
                                Log.w(TAG, "Spotify start failed: ${e.message}, falling back to generic player")
                                try {
                                    intent.setPackage(null)
                                    startActivity(intent)
                                    Log.d(TAG, "Started generic play intent for: $songOrArtist")
                                } catch (e2: Exception) {
                                    Log.e(TAG, "No music app found to play: ${e2.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error building play intent: ${e.message}")
                        }
                    }
                    "Playing $songOrArtist on Spotify..."
                }
                
                "play_youtube" -> {
                    val query = args["search_query"] as? String ?: "trending"
                    runOnUiThread {
                        playYouTube(query)
                    }
                    "Playing $query on YouTube..."
                }
                
                "open_camera" -> {
                    val mode = args["mode"] as? String ?: "view"
                    runOnUiThread {
                        when (mode) {
                            "photo" -> openCameraForPhoto()
                            "video" -> openCameraForVideo()
                            else -> openCamera()
                        }
                    }
                    "Opening camera in $mode mode..."
                }
                
                "take_photo" -> {
                    runOnUiThread {
                        takePhoto()
                    }
                    "Taking photo..."
                }
                // ------- Local web tool handlers -------
                "dictionary" , "define_word" -> {
                    val word = args["word"] as? String ?: args["query"] as? String ?: return "Error: No word provided"
                    try {
                        kotlinx.coroutines.runBlocking {
                            return@runBlocking LocalToolHandlers.dictionaryLookup(word)
                        }
                    } catch (e: Exception) {
                        "Error fetching definition: ${e.message}"
                    }
                }
                "wiki_summary", "wikipedia" -> {
                    val q = args["query"] as? String ?: args["topic"] as? String ?: return "Error: No topic provided"
                    try {
                        kotlinx.coroutines.runBlocking {
                            return@runBlocking LocalToolHandlers.wikiSummary(q)
                        }
                    } catch (e: Exception) {
                        "Error fetching wiki summary: ${e.message}"
                    }
                }
                "web_search", "web_search_instant" -> {
                    val q = args["query"] as? String ?: return "Error: No query provided"
                    try {
                        kotlinx.coroutines.runBlocking {
                            return@runBlocking LocalToolHandlers.webSearchInstant(q)
                        }
                    } catch (e: Exception) {
                        "Error performing web search: ${e.message}"
                    }
                }
                "get_stock", "stock_quote" -> {
                    val symbol = args["symbol"] as? String ?: args["ticker"] as? String ?: return "Error: No symbol provided"
                    try {
                        kotlinx.coroutines.runBlocking {
                            return@runBlocking LocalToolHandlers.stockQuote(symbol)
                        }
                    } catch (e: Exception) {
                        "Error fetching stock: ${e.message}"
                    }
                }
                "get_weather", "weather" -> {
                    val city = args["city"] as? String ?: args["location"] as? String ?: return "Error: No location provided"
                    try {
                        kotlinx.coroutines.runBlocking {
                            return@runBlocking LocalToolHandlers.weatherForCity(city)
                        }
                    } catch (e: Exception) {
                        "Error fetching weather: ${e.message}"
                    }
                }
                "web_search_full" -> {
                    val q = args["query"] as? String ?: args["q"] as? String ?: return "Error: No query provided"
                    try {
                        kotlinx.coroutines.runBlocking {
                            return@runBlocking LocalToolHandlers.webSearchFull(q)
                        }
                    } catch (e: Exception) {
                        "Error performing full web search: ${e.message}"
                    }
                }
                "google_search" -> {
                    val q = args["query"] as? String ?: args["q"] as? String ?: return "Error: No query provided"
                    try {
                        kotlinx.coroutines.runBlocking {
                            return@runBlocking LocalToolHandlers.googleSearch(q)
                        }
                    } catch (e: Exception) {
                        "Error performing Google search: ${e.message}"
                    }
                }
                // Accept tool name with camelCase from model: googleSearch
                "googleSearch" -> {
                    val q = args["query"] as? String ?: args["q"] as? String ?: return "Error: No query provided"
                    try {
                        kotlinx.coroutines.runBlocking {
                            return@runBlocking LocalToolHandlers.googleSearch(q)
                        }
                    } catch (e: Exception) {
                        "Error performing Google search: ${e.message}"
                    }
                }
                
                "record_video" -> {
                    val action = args["action"] as? String ?: "start"
                    runOnUiThread {
                        if (action == "start") {
                            startVideoRecording()
                        } else {
                            stopVideoRecording()
                        }
                    }
                    if (action == "start") "Started video recording..." else "Stopped video recording."
                }
                
                "capture_new_frame" -> {
                    // 🆕 User wants to capture a NEW photo (not follow-up on existing)
                    Log.d(TAG, "📸 Tool call 'capture_new_frame' - Opening Vision Chat for NEW capture")
                    runOnUiThread {
                        // Clear any previous image context by opening fresh Vision Chat
                        triggerVisionChatFromLive("What is in front of me?", forceNewCapture = true)
                    }
                    "Capturing new photo from glasses camera..."
                }
                
                "analyze_view" -> {
                    val question = args["question"] as? String ?: "What is in front of me?"
                    runOnUiThread {
                        analyzeCurrentView()
                    }
                    "Analyzing what's in front of you..."
                }
                
                "open_maps" -> {
                    val destination = args["destination"] as? String
                    val mode = args["mode"] as? String ?: "driving"
                    runOnUiThread {
                        openMapsNavigation(destination, mode)
                    }
                    if (destination != null) "Opening navigation to $destination..." else "Opening Google Maps..."
                }
                
                "send_message" -> {
                    val contactName = args["contact_name"] as? String ?: return "Error: No contact name provided"
                    val message = args["message"] as? String ?: return "Error: No message provided"
                    val app = args["app"] as? String ?: "whatsapp"
                    runOnUiThread {
                        if (app == "whatsapp") {
                            sendWhatsAppMessage(contactName, message)
                        } else {
                            sendSmsMessage(contactName, message)
                        }
                    }
                    "Sending message to $contactName via $app..."
                }
                
                "set_reminder" -> {
                    val reminderText = args["reminder_text"] as? String ?: return "Error: No reminder text provided"
                    val time = args["time"] as? String
                    runOnUiThread {
                        setReminder(reminderText, time)
                    }
                    "Setting reminder: $reminderText"
                }
                
                "get_weather" -> {
                    val location = (args["location"] as? String)?.trim()
                    try {
                        val city = if (location.isNullOrBlank()) "" else location
                        val client = OkHttpClient()
                        val encoded = java.net.URLEncoder.encode(if (city.isBlank()) "" else city, "UTF-8")
                        // Use wttr.in for quick weather summary (no API key required)
                        val url = if (city.isBlank()) "https://wttr.in/?format=3" else "https://wttr.in/$encoded?format=3"
                        val request = Request.Builder().url(url).build()
                        val resp = client.newCall(request).execute()
                        val body = resp.body?.string()?.trim() ?: ""
                        if (body.isBlank()) return "Weather unavailable"
                        // wttr.in returns concise one-line like: City: +20°C, ☀
                        return body
                    } catch (e: Exception) {
                        Log.e(TAG, "Weather fetch error: ${e.message}")
                        return "Sorry, couldn't fetch weather right now."
                    }
                }

                "web_search" -> {
                    val query = (args["query"] as? String)?.trim() ?: return "Error: No query provided"
                    try {
                        val client = OkHttpClient()
                        val url = "https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&no_html=1&skip_disambig=1"
                        val request = Request.Builder().url(url).build()
                        val resp = client.newCall(request).execute()
                        val body = resp.body?.string() ?: ""
                        if (!body.contains("AbstractText")) return "No quick answer found for '$query'"
                        val obj = org.json.JSONObject(body)
                        val abstractText = obj.optString("AbstractText", "")
                        if (abstractText.isNullOrBlank()) {
                            // Fallback to first RelatedTopic title
                            val related = obj.optJSONArray("RelatedTopics")
                            if (related != null && related.length() > 0) {
                                val first = related.getJSONObject(0)
                                val text = first.optString("Text", first.optString("Result", ""))
                                if (!text.isNullOrBlank()) return text
                            }
                            return "No concise answer found for '$query'"
                        }
                        // Keep concise
                        if (abstractText.length > 200) abstractText.substring(0, 197) + "..." else abstractText
                    } catch (e: Exception) {
                        "Search failed: ${e.message}"
                    }
                }

                "get_news" -> {
                    val q = (args["query"] as? String)?.trim()
                    try {
                        val queryParam = if (q.isNullOrBlank()) "top news" else q
                        val client = OkHttpClient()
                        val url = "https://news.google.com/rss/search?q=${java.net.URLEncoder.encode(queryParam, "UTF-8")}&hl=en-US&gl=US&ceid=US:en"
                        val request = Request.Builder().url(url).build()
                        val resp = client.newCall(request).execute()
                        val body = resp.body?.string() ?: ""
                        if (body.isBlank()) return "No news available"

                        // Simple XML title extraction for <item><title>
                        val titles = Regex("""<item.*?>.*?<title>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                            .findAll(body)
                            .map {
                                val raw = it.groupValues[1]
                                raw.replace(Regex("&amp;"), "&")
                                    .replace(Regex("""<!\[CDATA\[(.*?)\]\]>""", setOf(RegexOption.DOT_MATCHES_ALL)), "$1")
                            }
                            .toList()

                        if (titles.isEmpty()) return "No news articles found"
                        // Return top 3 headlines concisely
                        return titles.take(3).joinToString("; ") { it.replace(Regex("""\s+"""), " ").trim() }
                    } catch (e: Exception) {
                        Log.e(TAG, "News fetch failed: ${e.message}")
                        return "Sorry, couldn't fetch news right now."
                    }
                }

                "play_shayari" -> {
                    val theme = (args["theme"] as? String)?.lowercase()
                    val shayaris = listOf(
                        "Zindagi ek safar hai, muskurate raho.",
                        "Aankhon mein basi hai teri tasveer, dil mein teri yaad.",
                        "Khamoshi bhi kahin dil ki dastaan keh deti hai.",
                        "Chand taaron ka sama ho, khushiyon ka raaz ho.",
                        "Safar lamba sahi, par hausla kabhi kam na ho."
                    )
                    // If user gave a theme, try to pick a matching item; otherwise random
                    val pick = if (!theme.isNullOrBlank()) {
                        shayaris.firstOrNull { it.lowercase().contains(theme) } ?: shayaris.random()
                    } else shayaris.random()
                    pick
                }
                
                "control_volume" -> {
                    val action = args["action"] as? String ?: return "Error: No volume action provided"
                    runOnUiThread {
                        controlVolume(action)
                    }
                    "Volume $action executed."
                }
                
                else -> "Unknown function: $toolName"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing tool $toolName: ${e.message}", e)
            "Error executing $toolName: ${e.message}"
        }
    }
    
    /**
     * Open camera in view mode (Glass camera)
     */
    private fun openCamera() {
        try {
            Log.d(TAG, "📷 Opening glass camera (view mode)")
            SafeBleCommandHelper.takePhoto()
            speakOut("Opening camera", "ACTION")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open glass camera: ${e.message}")
        }
    }
    
    /**
     * Open camera for photo (Glass camera)
     */
    private fun openCameraForPhoto() {
        try {
            Log.d(TAG, "📷 Taking photo with glass camera")
            SafeBleCommandHelper.takePhoto()
            speakOut("Taking photo", "ACTION")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take photo with glass: ${e.message}")
        }
    }
    
    /**
     * Open camera for video (Glass camera)
     */
    private fun openCameraForVideo() {
        try {
            Log.d(TAG, "🎥 Starting video with glass camera")
            SafeBleCommandHelper.startRecording()
            speakOut("Starting video", "ACTION")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video with glass: ${e.message}")
        }
    }
    
    /**
     * Take a photo (Glass camera)
     */
    private fun takePhoto() {
        try {
            Log.d(TAG, "📷 Taking photo with glass camera")
            SafeBleCommandHelper.takePhoto()
            speakOut("Taking photo", "ACTION")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take photo with glass: ${e.message}")
        }
    }
    
    /**
     * Open Google Maps for navigation
     */
    private fun openMapsNavigation(destination: String?, mode: String) {
        try {
            val modeParam = when (mode) {
                "walking" -> "w"
                "transit" -> "r"
                else -> "d" // driving
            }
            
            val uri = if (destination != null) {
                Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$modeParam")
            } else {
                Uri.parse("geo:0,0")
            }
            
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.setPackage("com.google.android.apps.maps")
            
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // Fallback to web maps
                val webUri = if (destination != null) {
                    "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=$mode"
                } else {
                    "https://www.google.com/maps"
                }
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open maps: ${e.message}")
        }
    }
    
    /**
     * Send SMS message
     */
    private fun sendSmsMessage(contactName: String, message: String) {
        try {
            val phoneNumber = findContactPhoneNumber(contactName)
            if (phoneNumber != null) {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$phoneNumber")
                    putExtra("sms_body", message)
                }
                startActivity(intent)
            } else {
                speakOut("Could not find contact $contactName", "MSG_ERROR")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS: ${e.message}")
        }
    }
    
    /**
     * Send WhatsApp message
     */
    private fun sendWhatsAppMessage(contactName: String, message: String) {
        try {
            val phoneNumber = findContactPhoneNumber(contactName)
            if (phoneNumber != null) {
                // Format phone number for WhatsApp (remove spaces, dashes, etc.)
                val formattedNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
                val uri = Uri.parse("https://wa.me/$formattedNumber?text=${Uri.encode(message)}")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.setPackage("com.whatsapp")
                
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    // Fallback without package restriction
                    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
                    startActivity(fallbackIntent)
                }
            } else {
                speakOut("Could not find contact $contactName", "MSG_ERROR")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send WhatsApp message: ${e.message}")
        }
    }
    
    /**
     * Set a reminder
     */
    private fun setReminder(reminderText: String, time: String?) {
        try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, reminderText)
                putExtra(CalendarContract.Events.DESCRIPTION, "Reminder set by Imi Glass")
                
                // Set time if provided (simplified - just uses current time + 1 hour)
                val calendar = java.util.Calendar.getInstance()
                calendar.add(java.util.Calendar.HOUR_OF_DAY, 1)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, calendar.timeInMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, calendar.timeInMillis + 3600000)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set reminder: ${e.message}")
            // Fallback to alarm
            try {
                val alarmIntent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, reminderText)
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                }
                startActivity(alarmIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to set alarm: ${e2.message}")
            }
        }
    }
    
    /**
     * Control device volume
     */
    private fun controlVolume(action: String) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            
            when (action.lowercase()) {
                "up" -> audioManager.adjustVolume(android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                "down" -> audioManager.adjustVolume(android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                "mute" -> audioManager.adjustVolume(android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
                "unmute" -> audioManager.adjustVolume(android.media.AudioManager.ADJUST_UNMUTE, android.media.AudioManager.FLAG_SHOW_UI)
                "max" -> {
                    val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, maxVolume, android.media.AudioManager.FLAG_SHOW_UI)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to control volume: ${e.message}")
        }
    }

    /**
     * Initialize Gemini Live Service with callbacks
     */
    private fun initializeGeminiLive() {
        geminiLiveService = GeminiLiveService(this, object : GeminiLiveService.GeminiLiveCallbacks {
            override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
                runOnUiThread {
                    // Update UI with live transcriptions
                    if (input.isNotEmpty()) {
                        updateConversation("You", input)
                        
                        // ⛔ REMOVED: Thinking tune in normal conversation
                        // Thinking tune ONLY plays in Vision Chat (VisionChatActivity)
                        // Normal conversation mein tuning nahi chahiye
                    }
                    if (output.isNotEmpty()) {
                        // ⛔ REMOVED: Stop thinking tune (not used in normal conversation anymore)
                        
                        updateConversation("Imi", output)

                        // Only update the glasses display when the AI output is final to
                        // avoid spamming the glasses with many interim updates (which
                        // cause frequent BLE ACKs and can create "ok ok" echoes).
                        if (isFinal) {
                            try {
                                val utf8 = output.toByteArray(Charsets.UTF_8)
                                val safeLen = minOf(utf8.size, 250)
                                val displayCmd = ByteArray(safeLen + 2)
                                displayCmd[0] = 0x03.toByte()
                                displayCmd[1] = (safeLen and 0xFF).toByte()
                                System.arraycopy(utf8, 0, displayCmd, 2, safeLen)
                                LargeDataHandler.getInstance().glassesControl(displayCmd) { code, err ->
                                    if (code != 0) Log.w(TAG, "Glass display write failed code:$code err:$err")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Glass display error: ${e.message}")
                            }
                        }
                    }
                    
                    Log.d(TAG, "📝 Transcription - User: '$input', AI: '$output', Final: $isFinal")
                }
            }
            
            override fun onTurnComplete(fullInput: String, fullOutput: String) {
                runOnUiThread {
                    Log.d(TAG, "✅ Turn complete - Input: '$fullInput', Output: '$fullOutput'")
                    
                    // Save to conversation history
                    if (fullInput.isNotEmpty() && fullOutput.isNotEmpty()) {
                        aiHistory.add(Pair(fullInput, fullOutput))
                        saveConversationHistory()
                    }
                    
                    // ❌ REMOVED: Vision Chat triggers from Gemini Live - Vision Chat sirf manual open hoga
                    val lowerInput = fullInput.lowercase()
                    
                    // 🛑 Check for stop vision streaming commands
                    if (isStopVisionCommand(lowerInput)) {
                        Log.d(TAG, "🛑 Stop vision streaming command detected: '$fullInput'")
                        sendStopVisionStreamingBroadcast()
                        return@runOnUiThread
                    }
                    
                    // 📷 PHOTO CLICK commands - sirf photo click, NO vision analysis!
                    val isPhotoClickCommand = lowerInput.contains("photo click") || 
                        lowerInput.contains("click photo") ||
                        lowerInput.contains("photo lo") || 
                        lowerInput.contains("photo lelo") ||
                        lowerInput.contains("photo lele") ||
                        lowerInput.contains("photo khinch") ||
                        lowerInput.contains("photo khicho") ||
                        lowerInput.contains("foto lo") ||
                        lowerInput.contains("take photo") ||
                        lowerInput.contains("take a photo") ||
                        lowerInput.contains("take picture") ||
                        lowerInput.contains("click picture") ||
                        lowerInput.contains("capture photo") ||
                        lowerInput.contains("capture image")
                    
                    if (isPhotoClickCommand) {
                        Log.d(TAG, "📷 PHOTO CLICK in Gemini Live (no vision): '$fullInput'")
                        SafeBleCommandHelper.takePhoto()
                        // Don't trigger vision chat - just take photo
                        return@runOnUiThread
                    }
                    
                    // 🎬 VIDEO commands - sirf video start/stop, NO vision chat!
                    val isVideoStartCommand = (lowerInput.contains("video") || lowerInput.contains("recording")) &&
                        (lowerInput.contains("start") || lowerInput.contains("shuru") || 
                         lowerInput.contains("on") || lowerInput.contains("begin") || 
                         lowerInput.contains("record") || lowerInput.contains("chalu"))
                    
                    val isVideoStopCommand = (lowerInput.contains("video") || lowerInput.contains("recording")) &&
                        (lowerInput.contains("stop") || lowerInput.contains("band") || 
                         lowerInput.contains("off") || lowerInput.contains("end") || 
                         lowerInput.contains("finish") || lowerInput.contains("roko"))
                    
                    if (isVideoStopCommand) {
                        Log.d(TAG, "🎬 STOP VIDEO in Gemini Live: '$fullInput'")
                        SafeBleCommandHelper.stopRecording()
                        return@runOnUiThread
                    }
                    
                    if (isVideoStartCommand) {
                        Log.d(TAG, "🎬 START VIDEO in Gemini Live (no vision): '$fullInput'")
                        SafeBleCommandHelper.startRecording()
                        return@runOnUiThread
                    }
                    
                    // ❌ REMOVED: isVisionChatTrigger check - Vision Chat sirf manual open hoga, voice se nahi
                    
                    // Check if user wants to exit
                    if (lowerInput.contains("goodbye") || 
                        lowerInput.contains("exit") || 
                        lowerInput.contains("stop conversation")) {
                        Log.d(TAG, "User requested to exit conversation")
                        stopGeminiLiveConversation()
                    }
                }
            }
            
            override fun onToolCall(toolName: String, args: Map<String, Any>): String {
                Log.d(TAG, "🔧 Tool call: $toolName with args: $args")
                return handleGeminiToolCall(toolName, args)
            }
            
            override fun onAudioPlaybackStart() {
                runOnUiThread {
                    Log.d(TAG, "🔊 AI audio playback started")
                    aiIsPlaying = true
                    // Optionally update UI to show AI is speaking
                    
                    // 🆕 AI started speaking - stop thinking tune immediately
                    stopGeminiThinkingTune()
                }
            }
            
            override fun onAudioPlaybackEnd() {
                runOnUiThread {
                    Log.d(TAG, "🎤 AI audio playback ended, listening...")
                    aiIsPlaying = false
                    // Optionally update UI to show listening state
                    
                    // 🆕 Reset last user input time so tune doesn't trigger immediately
                    lastUserInputTime = 0
                }
            }
            
            override fun onError(error: String) {
                runOnUiThread {
                    Log.e(TAG, "❌ Gemini Live error: $error")
                    
                    // 🆕 Stop thinking tune on error
                    stopGeminiThinkingTune()
                    
                    Toast.makeText(this@MainActivity, "Gemini Live error: $error", Toast.LENGTH_LONG).show()
                    updateConversation("System", "Error: $error")
                    
                    // Fallback to traditional mode
                    isGeminiLiveMode = false
                    stopGeminiLiveConversation()
                }
            }
            
            override fun onConnectionStatusChanged(isConnected: Boolean) {
                runOnUiThread {
                    val status = if (isConnected) "🟢 Gemini Live Connected" else "🔴 Disconnected"
                    Log.d(TAG, status)
                    
                    if (!isConnected && isGeminiLiveMode) {
                        // Connection lost, restart wake word detection
                        isGeminiLiveMode = false
                        isInConversationMode = false
                        try {
                            Handler(Looper.getMainLooper()).postDelayed({
                                HotHelper.getInstance(this@MainActivity).start()
                            }, 1000)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to restart wake word: ${e.message}")
                        }
                    }
                }
            }
        })
        
        Log.d(TAG, "✅ Gemini Live Service initialized")
    }
    
    // ADD this new function to enable glass headset automatically:

    private fun enableGlassHeadsetForGeminiLive() {
        try {
            audioManager?.let { am ->
                Log.d(TAG, "🎧 Configuring audio for Glass Headset Mode")
                
                // Set audio mode for Bluetooth voice call
                am.mode = AudioManager.MODE_IN_CALL
                
                // Start Bluetooth SCO for glass mic/speaker
                if (!am.isBluetoothScoOn) {
                    Log.d(TAG, "📡 Starting Bluetooth SCO...")
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                    
                    // Wait briefly for SCO to connect
                    mainScope.launch {
                        var retries = 0
                        while (!am.isBluetoothScoOn && retries < 15) {
                            delay(100)
                            retries++
                        }
                        
                        if (am.isBluetoothScoOn) {
                            Log.d(TAG, "✅ Bluetooth SCO connected - Glass headset ready")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "🎧 Glass Headset Active", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Log.w(TAG, "⚠️ Bluetooth SCO connection timeout")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "⚠️ Glass connection weak, using phone", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                
                // Request audio focus for voice call
                try {
                    am.requestAudioFocus(
                        audioFocusListener, 
                        AudioManager.STREAM_VOICE_CALL, 
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Audio focus request failed: ${e.message}")
                }
                
                // Set max volume for glass speaker
                val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVolume, 0)
                
                Log.d(TAG, "✅ Glass Headset Mode enabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable glass headset: ${e.message}", e)
        }
    }
    
    /**
     * 🆕 Start playing thinking tune for Gemini Live delays (loops until stopped)
     */
    private fun startGeminiThinkingTune() {
        try {
            stopGeminiThinkingTune() // Stop any existing playback first
            
            geminiThinkingPlayer = MediaPlayer.create(this, R.raw.thinking_tune)
            geminiThinkingPlayer?.apply {
                isLooping = true // Loop until AI responds
                setVolume(0.4f, 0.4f) // 40% volume so it's subtle
                start()
            }
            thinkingTuneStartTime = System.currentTimeMillis()
            Log.d(TAG, "🎵 Gemini thinking tune started")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing thinking tune: ${e.message}")
        }
    }
    
    /**
     * 🆕 Stop thinking tune when AI starts responding
     */
    private fun stopGeminiThinkingTune() {
        try {
            geminiThinkingPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
            geminiThinkingPlayer = null
            if (thinkingTuneStartTime > 0) {
                val playedMs = System.currentTimeMillis() - thinkingTuneStartTime
                Log.d(TAG, "🎵 Gemini thinking tune stopped (played ${playedMs}ms)")
                thinkingTuneStartTime = 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping thinking tune: ${e.message}")
        }
    }
    
    /**
     * Stop Gemini Live conversation session
     */
    private fun stopGeminiLiveConversation(keepHeadsetConnected: Boolean = false) {
        try {
            Log.d(TAG, "🛑 Stopping Gemini Live conversation... (keepHeadset=$keepHeadsetConnected)")
            geminiLiveService?.stopLiveConversation()
            
            // ✅ ONLY disable glass headset if explicitly requested
            // Don't disconnect SCO - it breaks wake word and causes glass disconnect!
            if (!keepHeadsetConnected) {
                // Just stop the Gemini session, keep SCO connected for wake word
                audioManager?.let { am ->
                    // Keep SCO ON for wake word detection
                    Log.d(TAG, "🔇 Keeping SCO connected for wake word detection")
                }
            }
            
            isGeminiLiveMode = false
            isInConversationMode = false
            
            updateConversation("System", "Gemini Live conversation ended")
            
            // Restart wake word detection
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    HotHelper.getInstance(this).start()
                    Log.d(TAG, "✅ Wake word detection restarted")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart wake word: ${e.message}")
                }
            }, 500)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Gemini Live: ${e.message}", e)
        }
    }

    // ADD this function to disable glass headset:

    private fun disableGlassHeadset() {
        try {
            audioManager?.let { am ->
                Log.d(TAG, "🔇 Disabling Glass Headset Mode")
                
                // Stop Bluetooth SCO
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                    Log.d(TAG, "📡 Bluetooth SCO stopped")
                }
                
                // Restore normal audio mode
                am.mode = AudioManager.MODE_NORMAL
                
                // Abandon audio focus
                am.abandonAudioFocus(audioFocusListener)
                
                Log.d(TAG, "✅ Glass Headset Mode disabled")
                Toast.makeText(this, "Glass headset disconnected", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable glass headset: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIVE CAMERA STREAMING - Continuous Vision Analysis
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Start live camera streaming mode
     * DIRECT BLUETOOTH PHOTO CAPTURE - No WiFi/Hotspot needed!
     * Photos come directly via Bluetooth → Gemini AI → Voice response
     */
    fun startLiveCameraStream() {
        if (isLiveCameraActive) {
            Log.d(TAG, "[Live Camera] Already active")
            speakOut("Live camera pehle se chal rahi hai", "INFO")
            return
        }
        
        Log.d(TAG, "📷 [Live Camera] Starting DIRECT Bluetooth capture mode...")
        
        // Initialize vision client
        if (visionClient == null) {
            visionClient = GeminiAIClient()
        }
        
        isLiveCameraActive = true
        lastLiveCameraAnalysis = ""
        lastLiveCameraSpeakTime = 0L
        
        speakOut("Live camera shuru. Main batata rahunga kya dikh raha hai.", "LIVE_CAMERA")
        updateConversation("System", "📷 Live Camera ON - Direct Bluetooth Mode")
        Toast.makeText(this, "📷 Live Camera ON", Toast.LENGTH_LONG).show()
        
        // Start continuous photo capture loop
        liveCameraJob = mainScope.launch {
            Log.d(TAG, "🔄 [Live Camera] Starting capture loop...")
            delay(500) // Brief delay to let speech finish
            
            while (isLiveCameraActive) {
                try {
                    // Take photo - comes via Bluetooth directly!
                    Log.d(TAG, "📸 [Live Camera] Capturing photo via Bluetooth...")
                    
                    LargeDataHandler.getInstance().glassesControl(
                        byteArrayOf(0x02, 0x01, 0x01) // Take photo command
                    ) { code, error ->
                        if (code == 0 || code == 65) {
                            Log.d(TAG, "✅ [Live Camera] Photo command sent (code: $code)")
                            // Photo will arrive via cmdType 0x03 → analyzeLiveStreamPhoto()
                        } else {
                            Log.w(TAG, "⚠️ [Live Camera] Photo command: code=$code")
                        }
                    }
                    
                    // Wait before next capture
                    delay(CAMERA_CAPTURE_INTERVAL_MS)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "[Live Camera] Error: ${e.message}")
                    if (!isLiveCameraActive) break
                    delay(1000)
                }
            }
            Log.d(TAG, "🔄 [Live Camera] Capture loop ended")
        }
    }
    
    /**
     * Stop live camera streaming
     */
    fun stopLiveCameraStream() {
        if (!isLiveCameraActive) {
            Log.d(TAG, "[Live Camera] Already stopped")
            return
        }
        
        Log.d(TAG, "📷 [Live Camera] Stopping...")
        
        isLiveCameraActive = false
        liveCameraJob?.cancel()
        liveCameraJob = null
        lastLiveCameraAnalysis = ""
        lastLiveCameraSpeakTime = 0L
        
        speakOut("Live camera band.", "LIVE_CAMERA")
        updateConversation("System", "📷 Live Camera OFF")
        Toast.makeText(this, "📷 Live Camera OFF", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "📷 [Live Camera] Stopped")
    }
    
    /**
     * Analyze captured photo using Gemini Vision API
     * Called when a photo is received while live camera is active
     * Speaks the analysis via live audio
     */
    private fun analyzeLiveStreamPhoto(imageBytes: ByteArray) {
        if (!isLiveCameraActive) {
            Log.d(TAG, "[Live Camera] Not active, skipping analysis")
            return
        }
        
        // Check if image is valid
        if (imageBytes.size < 1000) {
            Log.w(TAG, "[Live Camera] Image too small (${imageBytes.size} bytes), skipping")
            return
        }
        
        mainScope.launch {
            try {
                Log.d(TAG, "🔍 [Live Camera] Analyzing frame (${imageBytes.size} bytes)...")
                
                // Simple prompt for faster response
                val prompt = """Describe what you see in 1-2 short sentences. Be direct and natural."""
                
                // Send to Gemini Vision API
                Log.d(TAG, "📤 [Live Camera] Sending to Gemini...")
                val response = withContext(Dispatchers.IO) {
                    try {
                        val result = visionClient?.analyzeImage(imageBytes, prompt)
                        Log.d(TAG, "📥 [Live Camera] Got response: $result")
                        result
                    } catch (e: Exception) {
                        Log.e(TAG, "[Live Camera] Gemini API error: ${e.message}", e)
                        null
                    }
                }
                
                // Skip if response is null, empty, or error
                if (response.isNullOrBlank()) {
                    Log.w(TAG, "[Live Camera] Empty response")
                    return@launch
                }
                
                if (response.contains("try again", ignoreCase = true) || 
                    response.contains("error", ignoreCase = true) ||
                    response.contains("couldn't", ignoreCase = true)) {
                    Log.w(TAG, "[Live Camera] Error response: $response")
                    return@launch  // Don't speak error messages
                }
                
                Log.d(TAG, "📥 [Live Camera] Gemini says: $response")
                
                // Check similarity
                val similarity = if (lastLiveCameraAnalysis.isNotEmpty()) {
                    calculateSimilarity(response, lastLiveCameraAnalysis)
                } else 0.0
                
                // Speak if different enough (< 70% similar)
                if (similarity < 0.70 || lastLiveCameraAnalysis.isEmpty()) {
                    lastLiveCameraAnalysis = response
                    lastLiveCameraSpeakTime = System.currentTimeMillis()
                    
                    // Update UI
                    withContext(Dispatchers.Main) {
                        updateConversation("📷 Live", response)
                    }
                    
                    // SPEAK!
                    speakOnGlass(response, "LIVE_VISION")
                    Log.d(TAG, "✅ [Live Camera] SPOKEN: $response")
                } else {
                    Log.d(TAG, "⏭️ [Live Camera] Skipped - ${(similarity * 100).toInt()}% similar")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ [Live Camera] Analysis error: ${e.message}", e)
                // Don't speak errors - just log
            }
        }
    }
    
    /**
     * Calculate text similarity (0.0 to 1.0)
     * Simple word-based similarity to avoid repeating same observations
     */
    private fun calculateSimilarity(text1: String, text2: String): Double {
        if (text1.isEmpty() || text2.isEmpty()) return 0.0
        
        val words1 = text1.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        val words2 = text2.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }.toSet()
        
        if (words1.isEmpty() || words2.isEmpty()) return 0.0
        
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        
        return if (union > 0) intersection.toDouble() / union.toDouble() else 0.0
    }

    /**
     * Interactive Vision Mode - Step-by-step user-controlled photo capture
     */
    private fun startInteractiveVisionMode() {
        isInteractiveVisionMode = true
        isProcessingVisionRequest = false

        val msg = "Vision Mode On. Main dekhne ke liye taiyaar hu. Jab bhi photo leni ho, boliye 'Click' ya 'Next'."
        speakOut(msg, "VISION_START")
        updateConversation("System", "👁️ Interactive Vision Mode Active")
    }

    private fun stopInteractiveVisionMode() {
        isInteractiveVisionMode = false
        isProcessingVisionRequest = false
        speakOut("Vision Mode band kar diya hai.", "VISION_STOP")
        updateConversation("System", "👁️ Vision Mode Ended")
    }

    private fun captureAndAnalyzeInteractive() {
        if (isProcessingVisionRequest) return
        isProcessingVisionRequest = true

        speakOut("Photo le raha hu...", "CAPTURE_START")

        // Use SafeBleCommandHelper to avoid SDK bug crash
        SafeBleCommandHelper.takePhoto(
            onSuccess = { code ->
                Log.d(TAG, "✅ Command sent (code: $code), waiting for photo...")
                // Safety timeout: if photo doesn't arrive within 25s, reset
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isProcessingVisionRequest) {
                        Log.w(TAG, "⚠️ Photo timeout")
                        speakOut("Photo mobile tak nahi pahunchi. Dobara 'Click' boliye.", "TIMEOUT")
                        isProcessingVisionRequest = false
                    }
                }, 25000)
            },
            onError = { error ->
                Log.e(TAG, "❌ Camera trigger failed: $error")
                runOnUiThread {
                    speakOut("Camera connect nahi ho paya. Phir se try karein.", "ERROR")
                    isProcessingVisionRequest = false
                }
            },
            timeoutMs = 15000
        )
    }

    private fun analyzeImageWithGemini(imageBytes: ByteArray) {
        mainScope.launch {
            try {
                val prompt = """
                    Describe exactly what is in this image. 
                    Identify objects, people (gender/age estimate), text, or animals.
                    Keep the response concise (2-3 sentences) and direct for a blind user.
                    Start directly with "I see..." or "This is..."
                """.trimIndent()

                val response = withContext(Dispatchers.IO) {
                    try {
                        visionClient?.analyzeImage(imageBytes, prompt)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Gemini analyze error: ${e.message}", e)
                        null
                    }
                }

                if (response.isNullOrBlank()) {
                    speakOut("Mujhe samajh nahi aaya, kripya dobara click karein.", "AI_ERROR")
                } else {
                    Log.d(TAG, "✅ Gemini Response: $response")
                    updateConversation("Imi", response)
                    try { speakOnGlass(response, "AI_RESPONSE") } catch (e: Exception) { speakOut(response, "AI_RESPONSE") }
                }

            } catch (e: Exception) {
                Log.e(TAG, "❌ AI Error: ${e.message}", e)
                speakOut("Internet issue hai ya AI respond nahi kar raha.", "NET_ERROR")
            } finally {
                // Allow next interactive capture
                isProcessingVisionRequest = false
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // WIFI P2P LIVE CAMERA - Fast Image Transfer via WiFi + Mobile Data for AI
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Initialize WiFi P2P Live Camera
     * Hybrid approach: WiFi for fast images, Mobile Data for Gemini AI
     */
    private fun initWifiP2PLiveCamera() {
        wifiP2PLiveCamera = WifiP2PLiveCamera(this)
        wifiP2PLiveCamera?.setListener(object : WifiP2PLiveCamera.LiveCameraListener {
            override fun onPhotoReceived(imageBytes: ByteArray) {
                Log.d(TAG, "📷 [WiFi P2P] Photo received: ${imageBytes.size} bytes")
                // Analyze using the same function as Bluetooth mode
                analyzeWifiP2PPhoto(imageBytes)
            }
            
            override fun onStatusUpdate(status: String) {
                Log.i(TAG, "📶 [WiFi P2P] Status: $status")
                runOnUiThread {
                    updateConversation("System", "📶 $status")
                }
            }
            
            override fun onError(error: String) {
                Log.e(TAG, "❌ [WiFi P2P] Error: $error")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        })
        
        // Pre-bind cellular network for Gemini API
        wifiP2PLiveCamera?.bindToCellularNetwork()
        
        Log.i(TAG, "✅ WiFi P2P Live Camera initialized")
    }
    
    /**
     * Start WiFi P2P Live Camera mode
     * Connect to Glass hotspot for fast image transfer
     * Use Mobile Data for Gemini API calls
     */
    fun startWifiP2PLiveCamera() {
        if (isWifiP2PLiveMode) {
            speakOut("WiFi live camera pehle se chal rahi hai", "INFO")
            return
        }
        
        // Stop Bluetooth live camera if active
        if (isLiveCameraActive) {
            stopLiveCameraStream()
        }
        
        Log.d(TAG, "📶 [WiFi P2P] Starting live camera...")
        
        // Initialize vision client if needed
        if (visionClient == null) {
            visionClient = GeminiAIClient()
        }
        
        isWifiP2PLiveMode = true
        lastLiveCameraAnalysis = ""
        lastLiveCameraSpeakTime = 0L
        
        speakOut("WiFi live camera shuru. Glass hotspot se connect karein.", "WIFI_LIVE")
        updateConversation("System", "📶 WiFi P2P Live Camera ON")
        Toast.makeText(this, "📶 WiFi P2P Live Camera ON", Toast.LENGTH_LONG).show()
        
        // Start streaming with 2 second interval
        wifiP2PLiveCamera?.startStreaming(CAMERA_CAPTURE_INTERVAL_MS)
    }
    
    /**
     * Stop WiFi P2P Live Camera mode
     */
    fun stopWifiP2PLiveCamera() {
        if (!isWifiP2PLiveMode) {
            return
        }
        
        Log.d(TAG, "📶 [WiFi P2P] Stopping live camera...")
        
        isWifiP2PLiveMode = false
        wifiP2PLiveCamera?.stopStreaming()
        lastLiveCameraAnalysis = ""
        
        speakOut("WiFi live camera band.", "WIFI_LIVE")
        updateConversation("System", "📶 WiFi P2P Live Camera OFF")
        Toast.makeText(this, "📶 WiFi P2P Live Camera OFF", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * Analyze photo received via WiFi P2P
     * Uses Mobile Data for Gemini API (bound cellular network)
     */
    private fun analyzeWifiP2PPhoto(imageBytes: ByteArray) {
        if (!isWifiP2PLiveMode) {
            return
        }
        
        // Validate image
        if (imageBytes.size < 1000) {
            Log.w(TAG, "[WiFi P2P] Image too small (${imageBytes.size} bytes)")
            return
        }
        
        mainScope.launch {
            try {
                Log.d(TAG, "🔍 [WiFi P2P] Analyzing frame...")
                
                val prompt = """Describe what you see in 1-2 short sentences. Be direct and natural."""
                
                val response = withContext(Dispatchers.IO) {
                    try {
                        // Use vision client (already configured with Gemini)
                        visionClient?.analyzeImage(imageBytes, prompt)
                    } catch (e: Exception) {
                        Log.e(TAG, "[WiFi P2P] Gemini API error: ${e.message}")
                        null
                    }
                }
                
                // Skip empty or error responses
                if (response.isNullOrBlank()) {
                    return@launch
                }
                
                if (response.contains("try again", ignoreCase = true) || 
                    response.contains("error", ignoreCase = true) ||
                    response.contains("couldn't", ignoreCase = true)) {
                    return@launch
                }
                
                Log.d(TAG, "📥 [WiFi P2P] Gemini: $response")
                
                // Check similarity
                val similarity = if (lastLiveCameraAnalysis.isNotEmpty()) {
                    calculateSimilarity(response, lastLiveCameraAnalysis)
                } else 0.0
                
                // Speak if different enough
                if (similarity < 0.70 || lastLiveCameraAnalysis.isEmpty()) {
                    lastLiveCameraAnalysis = response
                    lastLiveCameraSpeakTime = System.currentTimeMillis()
                    
                    withContext(Dispatchers.Main) {
                        updateConversation("📶 WiFi Live", response)
                    }
                    
                    speakOnGlass(response, "WIFI_VISION")
                    Log.d(TAG, "✅ [WiFi P2P] SPOKEN: $response")
                } else {
                    Log.d(TAG, "⏭️ [WiFi P2P] Skipped - ${(similarity * 100).toInt()}% similar")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "[WiFi P2P] Analysis error: ${e.message}")
            }
        }
    }
    
    /**
     * Toggle between WiFi P2P and Bluetooth live camera modes
     */
    fun toggleLiveCameraMode() {
        if (isWifiP2PLiveMode) {
            stopWifiP2PLiveCamera()
        } else if (isLiveCameraActive) {
            stopLiveCameraStream()
        } else {
            // Default to Bluetooth mode (more reliable)
            // Use WiFi P2P when specifically requested
            startLiveCameraStream()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // GLASS MEDIA GALLERY - View Photos/Videos from Glass via WiFi Hotspot
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Open Glass Media Gallery
     * Shows photos/videos from glass with WiFi hotspot transfer
     */
    private fun openGlassMediaGallery() {
        Log.d(TAG, "📷 Opening Glass Media Gallery...")
        speakOut("Glass gallery khol raha hoon", "GALLERY")

        // --- NEW CODE: Stop conflicting live camera services before opening gallery ---
        try {
            stopLiveCameraStream() // Bluetooth camera stop
            stopWifiP2PLiveCamera() // WiFi AI camera stop (Network release)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping cameras: ${e.message}")
        }

        try {
            GlassMediaGalleryActivity.launch(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening gallery: ${e.message}")
            speakOut("Gallery nahi khul saki", "ERROR")
        }
    }

    /**
     * Helper to resize and compress image for faster AI processing
     */
    private fun resizeAndCompressImage(originalBytes: ByteArray): ByteArray {
        try {
            // 1. Decode bytes to Bitmap
            val originalBitmap = android.graphics.BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size) ?: return originalBytes
            
            // 2. Resize to width ~640px (Fastest for AI) while keeping aspect ratio
            val targetWidth = 640
            val aspectRatio = originalBitmap.height.toFloat() / originalBitmap.width.toFloat()
            val targetHeight = (targetWidth * aspectRatio).toInt()
            
            val resizedBitmap = android.graphics.Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)
            
            // 3. Compress to JPEG at 60% quality
            val outputStream = java.io.ByteArrayOutputStream()
            resizedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, outputStream)
            
            val compressedBytes = outputStream.toByteArray()
            Log.d(TAG, "📉 Image compressed: ${originalBytes.size} -> ${compressedBytes.size} bytes")
            
            return compressedBytes
        } catch (e: Exception) {
            Log.e(TAG, "Image compression failed: ${e.message}")
            return originalBytes // Fallback to original if fail
        }
    }
    
    /**
     * Check if user input is asking to see/describe what's in front
     * Supports Hindi + English phrases
     */
    private fun isVisionChatTrigger(input: String): Boolean {
        val visionTriggers = listOf(
            // English triggers
            "what is in front of me",
            "what's in front of me", 
            "who is in front of me",
            "who's in front of me",
            "what do you see",
            "what can you see",
            "describe what you see",
            "look at this",
            // ❌ REMOVED: "take a photo" - ye sirf photo click hai, vision nahi
            // ❌ REMOVED: "capture image" - ye sirf photo click hai, vision nahi
            "analyze this",
            "what is this",
            "what's this",
            "what am i looking at",
            "describe this",
            "tell me what you see",
            "show me",
            
            // Hindi triggers (romanized) - ONLY VISION ANALYSIS commands
            "mere samne kya hai",
            "mere samne kaun hai",
            "samne kya hai",
            "samne kaun hai",
            "yeh kya hai",
            "ye kya hai",
            "kya dekh rahe ho",
            "dekho yeh",
            "dekho ye",
            // ❌ REMOVED: "photo lo", "photo lelo", "photo khicho" - ye sirf photo click hai
            "camera se dekho",
            "batao kya hai",
            "batao kaun hai",
            "mujhe batao",
            "kya dikh raha hai"
        )
        
        return visionTriggers.any { trigger -> input.contains(trigger) }
    }
    
    /**
     * Check if user wants to stop vision streaming
     */
    private fun isStopVisionCommand(input: String): Boolean {
        val stopTriggers = listOf(
            "stop",
            "stop streaming",
            "stop vision",
            "band karo",
            "band kar",
            "ruko",
            "rok do",
            "bas karo",
            "bas kar",
            "enough"
        )
        
        return stopTriggers.any { trigger -> input.contains(trigger) }
    }
    
    /**
     * Send broadcast to stop vision streaming
     */
    private fun sendStopVisionStreamingBroadcast() {
        val stopIntent = Intent(VisionChatActivity.ACTION_STOP_VISION_STREAMING)
        sendBroadcast(stopIntent)
        Log.i(TAG, "🛑 Sent stop vision streaming broadcast")
    }
    
    /**
     * Trigger Vision Chat from Gemini Live conversation
     * Opens VisionChatActivity with auto-capture
     * 🆕 Keeps Gemini Live ACTIVE so vision result can be spoken through it
     * 🆕 Adds 300-500ms delay and thinking sound
     * @param forceNewCapture If true, clears any previous image and forces new capture
     */
    private fun triggerVisionChatFromLive(userQuery: String, forceNewCapture: Boolean = false) {
        Log.d(TAG, "👁️ Triggering Vision Chat from Gemini Live for: $userQuery (forceNew=$forceNewCapture)")
        
        // 🆕 ALWAYS open VisionChatActivity (or bring to front via onNewIntent)
        // Even if already open, sending intent with FLAG_ACTIVITY_SINGLE_TOP triggers onNewIntent()
        if (visionChatOpenFlag) {
            Log.d(TAG, "👁️ VisionChat already open - sending onNewIntent to trigger capture")
        }
        
        // 🆕 DON'T stop Gemini Live - keep it active for speaking vision result
        // The same Gemini Live voice will speak the image description
        Log.d(TAG, "🎙️ Keeping Gemini Live active for vision result voice")
        
        // 🆕 🔇 MUTE Gemini Live output during vision processing
        // It will stay silent until image description is ready
        geminiLiveService?.muteOutput()
        Log.d(TAG, "🔇 Gemini Live muted - will be silent during vision processing")
        
        // 🆕 Thinking sound is now WAV tune in VisionChatActivity (not "hmm hmm")
        // geminiLiveService?.playThinkingSound()  // REMOVED - WAV tune plays instead
        
        // 🆕 Add 300-500ms delay after chimi before opening Vision Chat
        mainScope.launch {
            delay(400) // 400ms delay
            
            // Open VisionChatActivity with auto-capture flag
            try {
                val intent = Intent(this@MainActivity, VisionChatActivity::class.java).apply {
                    putExtra(VisionChatActivity.EXTRA_AUTO_CAPTURE, true)
                    putExtra(VisionChatActivity.EXTRA_VISION_QUERY, userQuery)
                    // 🆕 Force new capture - clears any previous image
                    putExtra(VisionChatActivity.EXTRA_FORCE_NEW_CAPTURE, forceNewCapture)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                visionChatOpenFlag = true  // Mark as open
                startActivity(intent)
                Log.d(TAG, "👓 Vision Chat launched with auto-capture for: $userQuery")
            } catch (e: Exception) {
                Log.e(TAG, "Error launching Vision Chat", e)
                visionChatOpenFlag = false
                // Unmute and use Gemini Live to speak error if available
                geminiLiveService?.unmuteOutput()
                geminiLiveService?.speakText("Vision Chat nahi khul paya. Error ho gaya.")
            }
        }
    }
    
    // Vision Chat integration variable (reusing existing isWaitingForVisionPhoto from line 77)
    private var pendingVisionQuery: String? = null
    
    // 🆕 Track if VisionChatActivity is currently open
    // Prevents opening duplicate VisionChat when user says vision command again
    @Volatile
    private var isVisionChatOpen = false
    
    /**
     * 🆕 Called when VisionChatActivity closes - reset the flag
     * This is a companion function that VisionChatActivity can call
     */
    companion object {
        @Volatile
        var visionChatOpenFlag = false
    }
}
