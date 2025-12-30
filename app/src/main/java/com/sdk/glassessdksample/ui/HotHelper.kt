package com.sdk.glassessdksample.ui

import android.content.Context
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineException
import com.sdk.glassessdksample.BuildConfig
import org.greenrobot.eventbus.EventBus
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HotHelper private constructor(private val context: Context) {
    companion object {
        private const val TAG = "HotHelper"
        
        @Volatile
        private var instance: HotHelper? = null

        fun getInstance(context: Context): HotHelper {
            return instance ?: synchronized(this) {
                instance ?: HotHelper(context.applicationContext).also { instance = it }
            }
        }
        
        // Porcupine requires exactly 512 samples per frame at 16kHz
        private const val PORCUPINE_FRAME_LENGTH = 512
    }

    private var porcupineManager: PorcupineManager? = null
    private var porcupine: Porcupine? = null // Direct Porcupine instance for BLE audio
    private var isStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val RESTART_DELAY_MS = 3_000L // Cooldown before restarting listen
    private var audioManager: AudioManager? = null
    private var scoReceiver: BroadcastReceiver? = null
    private val SCO_WAIT_TIMEOUT_MS = 1500L
    
    // Audio buffer for Glass BLE audio processing
    private val audioBuffer = mutableListOf<Short>()
    private val bufferLock = Any()
    
    // Mode: true = Glass BLE audio, false = Phone mic
    private var useGlassBLEAudio = false  // Use PHONE mic for "Hey Imi" detection (more reliable)

    /**
     * Allow external callers to prefer glass BLE audio for wake detection.
     * When enabled, `start()` will initialize direct Porcupine for BLE audio
     * processing and `processGlassAudio()` will be used.
     */
    fun setPreferGlassBleAudio(prefer: Boolean) {
        useGlassBLEAudio = prefer
    }

    fun start() {
        if (isStarted) return
        
        if (useGlassBLEAudio) {
            // Initialize direct Porcupine for Glass BLE audio (no AudioRecord)
            if (porcupine == null) {
                initDirectPorcupine()
            }
            Log.i(TAG, "✅ Glass BLE audio mode - Ready to process voiceFromGlasses()")
        } else {
            // Prefer SCO (glass mic) when available. Start Porcupine after SCO connects
            try {
                if (porcupineManager == null) {
                    // Delay initialization until SCO is ready (or timeout)
                    attemptScoThenStartPorcupine()
                } else {
                    porcupineManager?.start()
                    isStarted = true
                    Log.i(TAG, "PorcupineManager started (existing instance)")
                }
            } catch (e: PorcupineException) {
                Log.e(TAG, "Error starting Porcupine: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error starting Porcupine: ${e.message}")
            }
        }
    }

    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /**
     * Attempt to start SCO (if HFP/headset profile available) and wait briefly
     * for SCO to become active before initializing Porcupine. Falls back to
     * default mic if SCO doesn't connect within timeout.
     */
    private fun attemptScoThenStartPorcupine() {
        val am = audioManager
        // If SCO already on, start immediately
        if (am?.isBluetoothScoOn == true) {
            Log.d(TAG, "SCO already active — starting Porcupine immediately")
            initPorcupine()
            try { porcupineManager?.start(); isStarted = true } catch (_: Exception) {}
            return
        }

        // If no Bluetooth/HFP available, just start Porcupine
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val hfpConnected = try {
            adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }

        if (!hfpConnected) {
            Log.d(TAG, "HFP not connected — starting Porcupine on default mic")
            initPorcupine()
            try { porcupineManager?.start(); isStarted = true } catch (_: Exception) {}
            return
        }

        // Register receiver and start SCO; if SCO connects within timeout, start Porcupine
        registerScoReceiverForStart()
        try {
            Log.d(TAG, "Attempting to start Bluetooth SCO for wake detection")
            am?.startBluetoothSco()
        } catch (e: Exception) {
            Log.w(TAG, "startBluetoothSco() failed: ${e.message}")
        }

        // Fallback after timeout
        mainHandler.postDelayed({
            Log.d(TAG, "SCO wait timeout — falling back to default mic for Porcupine")
            unregisterScoReceiverForStart()
            initPorcupine()
            try { porcupineManager?.start(); isStarted = true } catch (_: Exception) {}
        }, SCO_WAIT_TIMEOUT_MS)
    }

    private fun registerScoReceiverForStart() {
        unregisterScoReceiverForStart()
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                Log.d(TAG, "HotHelper SCO state: $state")
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    Log.d(TAG, "SCO active — initializing Porcupine on SCO mic")
                    unregisterScoReceiverForStart()
                    initPorcupine()
                    try { porcupineManager?.start(); isStarted = true } catch (e: Exception) { Log.w(TAG, "Failed to start Porcupine after SCO: ${e.message}") }
                }
            }
        }
        try {
            context.registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register SCO receiver: ${e.message}")
            scoReceiver = null
        }
    }

    private fun unregisterScoReceiverForStart() {
        scoReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        scoReceiver = null
    }

    private fun initPorcupine() {
        try {
            val builder = PorcupineManager.Builder()
                .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
                .setSensitivity(0.65f)

            // Find keyword file in assets
            val keywordFile = copyKeywordFileFromAssets()
            if (keywordFile != null) {
                builder.setKeywordPath(keywordFile.absolutePath)
                Log.i(TAG, "Using keyword file: ${keywordFile.absolutePath}")
            } else {
                Log.w(TAG, "No custom keyword file found in assets. Attempting to use default JARVIS.")
                try {
                    builder.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set default keyword", e)
                }
            }

            porcupineManager = builder.build(context) { keywordIndex ->
                Log.i(TAG, "🔥 Porcupine detected keyword index=$keywordIndex")
                
                // CRITICAL: Stop Porcupine immediately to release microphone
                // This prevents ERROR_AUDIO (Error 13) in SpeechRecognizer
                try {
                    porcupineManager?.stop()
                    porcupineManager?.delete()
                    porcupineManager = null
                    isStarted = false
                    Log.d(TAG, "✅ Porcupine stopped - microphone released for SpeechRecognizer")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping Porcupine after wake word: ${e.message}")
                }
                
                // Now post the wake word event
                EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.VOICE_TEXT, "wake up"))
                
                // Restart Porcupine after a delay (when conversation mode ends)
                // This will be handled by MainActivity when conversation mode is disabled
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Porcupine: ${e.message}")
        }
    }

    private fun copyKeywordFileFromAssets(): File? {
        try {
            val assetFiles = context.assets.list("") ?: return null
            // Prefer files with 'hey' and 'imi' (case insensitive)
            // Also prioritize .ppn files that match the v4.0.0 version if multiple exist
            val keywordAssetName = assetFiles.filter { it.endsWith(".ppn", ignoreCase = true) }
                .sortedWith(compareByDescending { name ->
                    val n = name.lowercase()
                    var score = 0
                    if (n.contains("hey") && n.contains("imi")) score += 4
                    if (n.contains("hey-imi") || n.contains("hey_imi")) score += 2
                    if (n.contains("v4")) score += 1 
                    score
                }).firstOrNull() ?: return null

            val outputFile = File(context.filesDir, keywordAssetName)
            // Always overwrite if size differs or strictly check existence
            // For dev, overwriting ensures latest asset is used
            if (!outputFile.exists() || outputFile.length() == 0L) {
                context.assets.open(keywordAssetName).use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Copied asset $keywordAssetName to ${outputFile.absolutePath}")
            }
            return outputFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying keyword asset", e)
            return null
        }
    }

    fun stop() {
        if (!isStarted) return
        try {
            porcupineManager?.stop()
            porcupine = null
            isStarted = false
            synchronized(bufferLock) {
                audioBuffer.clear()
            }
            Log.i(TAG, "Porcupine stopped and buffer cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Porcupine", e)
        }
    }

    /**
     * Ensure runtime permissions required for recording and Bluetooth connectivity.
     * Returns true if all required permissions are already granted.
     * If not granted, requests permissions and returns false.
     */
    fun ensurePermissions(activity: Activity, requestCode: Int = 1234): Boolean {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        return if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), requestCode)
            false
        } else {
            true
        }
    }

    fun release() {
        stop()
        try {
            porcupineManager?.delete()
            porcupineManager = null
            porcupine?.delete()
            porcupine = null
            Log.i(TAG, "Porcupine released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Porcupine", e)
        }
    }
    
    /**
     * Process Glass BLE audio for wake word detection
     * @param pcmData Raw PCM audio from Glass microphone (16-bit PCM, 16kHz, mono)
     */
    fun processGlassAudio(pcmData: ByteArray) {
        if (porcupine == null) {
            initDirectPorcupine()
        }
        
        if (porcupine == null || !isStarted) return
        
        try {
            // Convert byte array to short array (16-bit PCM)
            val shortBuffer = ByteBuffer.wrap(pcmData)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            
            val samples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(samples)
            
            // Add to buffer
            synchronized(bufferLock) {
                samples.forEach { audioBuffer.add(it) }
                
                // Process in 512-sample frames
                while (audioBuffer.size >= PORCUPINE_FRAME_LENGTH) {
                    val frame = audioBuffer.take(PORCUPINE_FRAME_LENGTH).toShortArray()
                    audioBuffer.subList(0, PORCUPINE_FRAME_LENGTH).clear()
                    
                    // Process frame with Porcupine
                    val keywordIndex = porcupine?.process(frame) ?: -1
                    if (keywordIndex >= 0) {
                        Log.i(TAG, "🔥 Wake word detected from Glass mic! Index=$keywordIndex")
                        
                        // Stop processing
                        stop()
                        
                        // Post wake word event
                        mainHandler.post {
                            EventBus.getDefault().post(
                                BluetoothEvent(BluetoothEvent.EventType.VOICE_TEXT, "wake up")
                            )
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Glass audio: ${e.message}", e)
        }
    }
    
    /**
     * Initialize direct Porcupine instance for BLE audio processing
     * (not using PorcupineManager which uses AudioRecord)
     */
    private fun initDirectPorcupine() {
        try {
            val builder = Porcupine.Builder()
                .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
                .setSensitivity(0.65f)

            // Find keyword file in assets
            val keywordFile = copyKeywordFileFromAssets()
            if (keywordFile != null) {
                builder.setKeywordPath(keywordFile.absolutePath)
                Log.i(TAG, "Using keyword file for Glass audio: ${keywordFile.absolutePath}")
            } else {
                Log.w(TAG, "No custom keyword file found. Using default JARVIS.")
                builder.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            }

            porcupine = builder.build(context)
            isStarted = true
            Log.i(TAG, "✅ Direct Porcupine initialized for Glass BLE audio")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize direct Porcupine: ${e.message}", e)
        }
    }
}
