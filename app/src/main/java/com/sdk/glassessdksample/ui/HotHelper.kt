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
import android.media.AudioManager
import com.sdk.glassessdksample.wakeword.HeyImiWakeWordDetector
import org.greenrobot.eventbus.EventBus
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * HotHelper - Wake Word Detection Manager
 * 
 * ========================================================================
 * 🎤 NOW USING ONNX-BASED "HEY IMI" WAKE WORD DETECTION! 
 * ========================================================================
 * 
 * This replaces Picovoice Porcupine with a custom ONNX model.
 * 
 * Advantages:
 * - ✅ No API Key Required
 * - ✅ Custom trained for "Hey IMI" (96% accurate)
 * - ✅ Free to use - no licensing costs
 * - ✅ Works offline
 * 
 * Required ONNX Model Files (in assets/models/):
 * - melspectrogram.onnx
 * - embedding_model.onnx
 * - hey_imi_model.onnx
 * 
 * Optional Chime Sound (for wake acknowledgment):
 * - res/raw/chime.mp3 OR assets/sounds/chime.mp3
 * 
 * To switch back to Picovoice, see HotHelper_Picovoice_Backup.kt
 * ========================================================================
 */
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
        
        // Buffer size for audio processing (1.5s at 16kHz)
        private const val BUFFER_SIZE = 24000
    }

    // ONNX-based Hey IMI detector (replaces Porcupine)
    private var heyImiDetector: HeyImiWakeWordDetector? = null
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
    private var useGlassBLEAudio = false

    init {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    /**
     * Allow external callers to prefer glass BLE audio for wake detection.
     * When enabled, processGlassAudio() will be used instead of phone mic.
     */
    fun setPreferGlassBleAudio(prefer: Boolean) {
        useGlassBLEAudio = prefer
    }

    /**
     * Set detection threshold (0.0 to 1.0)
     * Lower = more sensitive but more false positives
     * Higher = less sensitive but fewer false positives
     * Default: 0.3 (recommended)
     */
    fun setThreshold(value: Float) {
        heyImiDetector?.setThreshold(value)
        Log.d(TAG, "Detection threshold set to: $value")
    }

    fun start() {
        if (isStarted) {
            Log.d(TAG, "Already listening, ignoring start()")
            return
        }
        
        try {
            // Initialize detector if needed
            if (heyImiDetector == null) {
                initHeyImiDetector()
            }
            
            if (useGlassBLEAudio) {
                // For Glass BLE audio, mark as started but don't use AudioRecord
                // Audio will come from processGlassAudio()
                isStarted = true
                Log.i(TAG, "✅ Glass BLE audio mode - Ready to process voiceFromGlasses()")
            } else {
                // Prefer SCO (glass mic) when available
                attemptScoThenStartDetector()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting wake word detection: ${e.message}", e)
        }
    }

    /**
     * Initialize the ONNX-based Hey IMI wake word detector
     */
    private fun initHeyImiDetector() {
        try {
            Log.i(TAG, "🔄 Initializing Hey IMI ONNX Detector...")
            
            heyImiDetector = HeyImiWakeWordDetector(context) { confidence ->
                Log.i(TAG, "🔥 HEY IMI DETECTED! Confidence: ${"%.2f".format(confidence)}")
                
                // Mark as stopped (detector auto-stops on detection)
                isStarted = false
                
                // Clear Glass audio buffer
                synchronized(bufferLock) {
                    audioBuffer.clear()
                }
                
                // Chime is already played by the detector
                // Post wake word event via EventBus
                Log.d(TAG, "📢 Posting 'wake up' event to EventBus")
                EventBus.getDefault().post(
                    BluetoothEvent(BluetoothEvent.EventType.VOICE_TEXT, "wake up")
                )
            }
            
            // Initialize ONNX models
            heyImiDetector?.initialize()
            
            Log.i(TAG, "✅ Hey IMI ONNX Detector initialized successfully")
            Log.i(TAG, "   📦 Models loaded from assets/models/")
            Log.i(TAG, "   🎯 Threshold: ${heyImiDetector?.getThreshold()}")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Hey IMI detector: ${e.message}", e)
            Log.e(TAG, "   Make sure ONNX model files are in assets/models/:")
            Log.e(TAG, "   - melspectrogram.onnx")
            Log.e(TAG, "   - embedding_model.onnx")
            Log.e(TAG, "   - hey_imi_model.onnx")
            heyImiDetector = null
        }
    }

    /**
     * Attempt to start SCO (if HFP/headset profile available) and wait briefly
     * for SCO to become active before starting detection.
     */
    private fun attemptScoThenStartDetector() {
        val am = audioManager
        
        // If SCO already on, start immediately
        if (am?.isBluetoothScoOn == true) {
            Log.d(TAG, "SCO already active — starting Hey IMI detector immediately")
            startDetectorInternal()
            return
        }

        // If no Bluetooth/HFP available, just start detector
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val hfpConnected = try {
            adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }

        if (!hfpConnected) {
            Log.d(TAG, "HFP not connected — starting Hey IMI detector on default mic")
            startDetectorInternal()
            return
        }

        // Register receiver and start SCO
        registerScoReceiverForStart()
        try {
            Log.d(TAG, "Attempting to start Bluetooth SCO for wake detection")
            am?.startBluetoothSco()
        } catch (e: Exception) {
            Log.w(TAG, "startBluetoothSco() failed: ${e.message}")
        }

        // Fallback after timeout
        mainHandler.postDelayed({
            Log.d(TAG, "SCO wait timeout — falling back to default mic")
            unregisterScoReceiverForStart()
            startDetectorInternal()
        }, SCO_WAIT_TIMEOUT_MS)
    }

    private fun registerScoReceiverForStart() {
        unregisterScoReceiverForStart()
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                Log.d(TAG, "HotHelper SCO state: $state")
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    Log.d(TAG, "SCO active — starting Hey IMI detector on SCO mic")
                    unregisterScoReceiverForStart()
                    startDetectorInternal()
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

    /**
     * Internal method to start the Hey IMI detector
     */
    private fun startDetectorInternal() {
        try {
            heyImiDetector?.start()
            isStarted = true
            Log.i(TAG, "🎤 Hey IMI wake word detection STARTED")
            Log.i(TAG, "   Say 'Hey IMI' to trigger...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Hey IMI detector: ${e.message}", e)
        }
    }

    fun stop() {
        if (!isStarted) return
        try {
            heyImiDetector?.stop()
            isStarted = false
            synchronized(bufferLock) {
                audioBuffer.clear()
            }
            Log.i(TAG, "🛑 Hey IMI detector stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Hey IMI detector", e)
        }
    }

    /**
     * Ensure runtime permissions required for recording and Bluetooth connectivity.
     * Returns true if all required permissions are already granted.
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
            heyImiDetector?.cleanup()
            heyImiDetector = null
            Log.i(TAG, "✅ Hey IMI detector released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Hey IMI detector", e)
        }
    }
    
    /**
     * Process Glass BLE audio for wake word detection
     * @param pcmData Raw PCM audio from Glass microphone (16-bit PCM, 16kHz, mono)
     */
    fun processGlassAudio(pcmData: ByteArray) {
        if (!isStarted) return
        
        // Forward to ONNX detector
        heyImiDetector?.processExternalAudio(pcmData)
    }
    
    /**
     * Play the chime/acknowledgment sound manually
     * Note: Chime is automatically played on wake word detection
     */
    fun playChimeSound() {
        heyImiDetector?.playChimeSound()
    }
    
    /**
     * Check if detector is currently listening
     */
    fun isListening(): Boolean = isStarted
    
    /**
     * Get current detection threshold
     */
    fun getThreshold(): Float = heyImiDetector?.getThreshold() ?: 0.3f
}
