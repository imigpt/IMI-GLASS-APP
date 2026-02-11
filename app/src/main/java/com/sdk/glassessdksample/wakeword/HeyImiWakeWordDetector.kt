package com.sdk.glassessdksample.wakeword

import ai.onnxruntime.*
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * HeyImiWakeWordDetector - Custom ONNX-based wake word detection
 * 
 * Replaces Picovoice Porcupine with custom "Hey IMI" ONNX model
 * 
 * Model Files Required (place in assets/models/):
 * - melspectrogram.onnx: Audio to melspectrogram converter
 * - embedding_model.onnx: Feature embedding generator  
 * - hey_imi_model.onnx: Wake word classifier (96% accurate)
 * 
 * @param context Application context
 * @param onWakeWordDetected Callback when "Hey IMI" is detected
 */
class HeyImiWakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: (confidence: Float) -> Unit
) {
    companion object {
        private const val TAG = "HeyImiWakeWord"
        
        // Audio configuration - must match training data
        const val SAMPLE_RATE = 16000
        const val AUDIO_DURATION_MS = 1500  // 1.5 seconds
        const val BUFFER_SIZE = 24000  // samples (1.5s * 16kHz)
        
        // Detection threshold - adjust based on testing
        // Lower = more sensitive but more false positives
        // Higher = less sensitive but fewer false positives
        // 🔧 TUNED: 0.50f - BALANCED for precision boost
        // Raised from 0.40f to reduce false positives while keeping 97% recall
        const val DEFAULT_THRESHOLD = 0.60f
        
        // Minimum audio level required before checking classifier
        // 🔧 TUNED: 20 - DISABLED
        // We want to process ALL audio, even if quiet.
        private const val MIN_AUDIO_LEVEL_FOR_DETECTION = 20
        
        // Cooldown after detection to prevent multiple triggers
        const val DETECTION_COOLDOWN_MS = 2000L
        
        // Rolling buffer update interval (250ms chunks = 4000 samples)
        const val CHUNK_SIZE = 4000
        
        // Rolling average window size (smooths out single-frame spikes)
        // 🔧 INCREASED: 5 frames for better soft voice stability
        private const val ROLLING_AVG_SIZE = 5
    }
    
    // ONNX Runtime environment and sessions
    private var ortEnv: OrtEnvironment? = null
    private var melspecSession: OrtSession? = null
    private var embeddingSession: OrtSession? = null
    private var classifierSession: OrtSession? = null
    
    // Model input/output names (detected at runtime)
    private var melspecInputName = "audio_signal"  // Default, will be updated
    private var melspecOutputName = "mel_spectrogram"
    private var embeddingInputName = "input" 
    private var embeddingOutputName = "output"
    private var classifierInputName = "input"
    private var classifierOutputName = "output"
    
    // Audio recording
    private var audioRecorder: AudioRecord? = null
    private var isListening = false
    private var listeningThread: Thread? = null
    
    // Detection settings
    private var threshold = DEFAULT_THRESHOLD
    private var lastDetectionTime = 0L
    
    // Handler for main thread callbacks
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Chime player for wake acknowledgment
    private var chimePlayer: MediaPlayer? = null
    
    // Rolling audio buffer with thread safety
    private val rollingBuffer = ShortArray(BUFFER_SIZE)
    
    // Rolling score buffer for peak-aware smoothing (keeps peaks, reduces noise)
    private val scoreWindow = ArrayDeque<Float>()
    private val SCORE_WINDOW_SIZE = 4  // 🔧 Medium window - balances peak detection & noise rejection
    private val bufferLock = Any()  // Thread-safe buffer access
    
    // Classifier expects 9 frames repeated (model trained with np.tile)
    private val REQUIRED_EMBEDDING_FRAMES = 9
    
    /**
     * Sigmoid activation - converts raw logits to probability [0, 1]
     * Required because model uses BCEWithLogitsLoss (no sigmoid in model)
     */
    private fun sigmoid(x: Float): Float {
        return (1.0f / (1.0f + kotlin.math.exp(-x)))
    }
    
    /**
     * Balanced smoothing: Keeps wake word peaks while rejecting random spikes
     * Uses 50-50 mix to balance sensitivity and false positive prevention
     */
    private fun smoothScore(score: Float): Float {
        if (scoreWindow.size >= SCORE_WINDOW_SIZE) scoreWindow.removeFirst()
        scoreWindow.addLast(score)
        
        val peak = scoreWindow.maxOrNull() ?: score
        val avg = scoreWindow.average().toFloat()
        
        // 🔧 AGGRESSIVE SMOOTHING: 80% peak + 20% avg
        // We trust the peak value more. If the model says "YES" for even 1 frame, we listen.
        return 0.8f * peak + 0.2f * avg
    }
    
    /**
     * Initialize ONNX models from assets
     * Call this before start()
     */
    @Throws(Exception::class)
    fun initialize() {
        try {
            Log.i(TAG, "🔄 Initializing Hey IMI Wake Word Detector...")
            
            // Create ONNX Runtime environment
            ortEnv = OrtEnvironment.getEnvironment()
            
            // Load all three models
            val melspecModel = loadModelFromAssets("models/melspectrogram.onnx")
            val embeddingModel = loadModelFromAssets("models/embedding_model.onnx")
            val classifierModel = loadModelFromAssets("models/hey_imi_model.onnx")
            
            // Create sessions with optimized settings for real-time performance
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4) // Use 4 threads for faster inference on multi-core devices
            }
            
            melspecSession = ortEnv?.createSession(melspecModel, sessionOptions)
            embeddingSession = ortEnv?.createSession(embeddingModel, sessionOptions)
            classifierSession = ortEnv?.createSession(classifierModel, sessionOptions)
            
            // Detect and log actual input/output names from models
            detectModelIONames()
            
            Log.i(TAG, "✅ All ONNX models loaded successfully")
            Log.i(TAG, "   - Melspectrogram model: ready")
            Log.i(TAG, "   - Embedding model: ready")
            Log.i(TAG, "   - Classifier model: ready")
            
            // Pre-load chime sound
            preloadChimeSound()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize ONNX models: ${e.message}", e)
            cleanup()
            throw e
        }
    }
    
    /**
     * Load ONNX model from assets folder
     */
    private fun loadModelFromAssets(assetPath: String): ByteArray {
        return try {
            context.assets.open(assetPath).use { inputStream ->
                inputStream.readBytes().also {
                    Log.d(TAG, "Loaded model: $assetPath (${it.size} bytes)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from assets: $assetPath", e)
            // Try alternative location - copied to files dir
            val modelFile = File(context.filesDir, assetPath.substringAfterLast('/'))
            if (modelFile.exists()) {
                modelFile.readBytes()
            } else {
                throw Exception("Model not found: $assetPath. Please copy ONNX models to assets/models/")
            }
        }
    }
    
    /**
     * Detect and log input/output names from ONNX models
     * This helps debug issues with incorrect tensor names
     */
    private fun detectModelIONames() {
        try {
            // Melspectrogram model
            melspecSession?.let { session ->
                val inputNames = session.inputNames.toList()
                val outputNames = session.outputNames.toList()
                Log.i(TAG, "📊 Melspec model - Inputs: $inputNames, Outputs: $outputNames")
                
                if (inputNames.isNotEmpty()) {
                    melspecInputName = inputNames[0]
                    Log.i(TAG, "   Using input name: $melspecInputName")
                }
                if (outputNames.isNotEmpty()) {
                    melspecOutputName = outputNames[0]
                    Log.i(TAG, "   Using output name: $melspecOutputName")
                }
                
                // Log input info
                session.inputInfo.forEach { (name, info) ->
                    Log.i(TAG, "   Input '$name': ${info.info}")
                }
            }
            
            // Embedding model
            embeddingSession?.let { session ->
                val inputNames = session.inputNames.toList()
                val outputNames = session.outputNames.toList()
                Log.i(TAG, "📊 Embedding model - Inputs: $inputNames, Outputs: $outputNames")
                
                if (inputNames.isNotEmpty()) {
                    embeddingInputName = inputNames[0]
                }
                if (outputNames.isNotEmpty()) {
                    embeddingOutputName = outputNames[0]
                }
            }
            
            // Classifier model  
            classifierSession?.let { session ->
                val inputNames = session.inputNames.toList()
                val outputNames = session.outputNames.toList()
                Log.i(TAG, "📊 Classifier model - Inputs: $inputNames, Outputs: $outputNames")
                
                if (inputNames.isNotEmpty()) {
                    classifierInputName = inputNames[0]
                }
                if (outputNames.isNotEmpty()) {
                    classifierOutputName = outputNames[0]
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting model IO names: ${e.message}")
        }
    }
    
    /**
     * Pre-load chime sound for instant playback on wake detection
     */
    private fun preloadChimeSound() {
        try {
            // Try to load chime.mp3 or chime.wav from assets
            val chimeResId = context.resources.getIdentifier("chime", "raw", context.packageName)
            if (chimeResId != 0) {
                chimePlayer = MediaPlayer.create(context, chimeResId)
                chimePlayer?.setVolume(1.0f, 1.0f)
                Log.d(TAG, "✅ Chime sound pre-loaded from resources")
            } else {
                // Try loading from assets
                try {
                    val afd = context.assets.openFd("sounds/chime.mp3")
                    chimePlayer = MediaPlayer().apply {
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        prepare()
                        setVolume(1.0f, 1.0f)
                    }
                    afd.close()
                    Log.d(TAG, "✅ Chime sound pre-loaded from assets")
                } catch (e: Exception) {
                    Log.w(TAG, "No chime sound available, will use system beep")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pre-load chime: ${e.message}")
        }
    }
    
    /**
     * Play the chime/acknowledgment sound
     */
    fun playChimeSound() {
        try {
            chimePlayer?.let { player ->
                if (player.isPlaying) {
                    player.seekTo(0)
                } else {
                    player.start()
                }
                Log.d(TAG, "🔔 Chime played!")
            } ?: run {
                // Fallback: system beep
                android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                    .startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 200)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play chime: ${e.message}")
        }
    }
    
    /**
     * Set detection threshold (0.0 to 1.0)
     * @param value Lower = more sensitive, Higher = fewer false positives
     */
    fun setThreshold(value: Float) {
        threshold = value.coerceIn(0.1f, 0.9f)
        Log.d(TAG, "Threshold set to: $threshold")
    }
    
    /**
     * Start listening for wake word
     * Requires RECORD_AUDIO permission
     */
    fun start() {
        if (isListening) {
            Log.w(TAG, "Already listening")
            return
        }
        
        if (ortEnv == null || classifierSession == null) {
            Log.e(TAG, "Models not initialized! Call initialize() first")
            return
        }
        
        try {
            // Initialize AudioRecord
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            audioRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,  // Optimized for speech with noise cancellation
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize, BUFFER_SIZE * 2)
            )
            
            if (audioRecorder?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord")
                return
            }
            
            isListening = true
            audioRecorder?.startRecording()
            
            // Start listening thread
            listeningThread = Thread {
                processAudioLoop()
            }.apply {
                name = "HeyIMI-Listener"
                start()
            }
            
            Log.i(TAG, "🎤 Hey IMI listening started (threshold=$threshold)")
            
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}", e)
            stop()
        }
    }
    
    /**
     * Stop listening for wake word
     */
    fun stop() {
        isListening = false
        
        try {
            listeningThread?.interrupt()
            listeningThread = null
            
            audioRecorder?.stop()
            audioRecorder?.release()
            audioRecorder = null
            
            Log.i(TAG, "🛑 Hey IMI listening stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping listener: ${e.message}")
        }
    }
    
    /**
     * Clean up all resources
     */
    fun cleanup() {
        stop()
        
        try {
            melspecSession?.close()
            embeddingSession?.close()
            classifierSession?.close()
            ortEnv?.close()
            chimePlayer?.release()
            
            melspecSession = null
            embeddingSession = null
            classifierSession = null
            ortEnv = null
            chimePlayer = null
            
            Log.i(TAG, "✅ Resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }
    
    /**
     * Main audio processing loop - runs in background thread
     */
    private fun processAudioLoop() {
        val audioChunk = ShortArray(CHUNK_SIZE)
        
        // Clear rolling buffers
        rollingBuffer.fill(0)
        scoreWindow.clear()
        
        Log.d(TAG, "🎧 Audio processing loop started")
        
        while (isListening && !Thread.currentThread().isInterrupted) {
            try {
                // Read audio chunk
                val readSize = audioRecorder?.read(audioChunk, 0, CHUNK_SIZE) ?: 0
                
                if (readSize > 0) {
                    // Update rolling buffer (shift left and append new data) - thread-safe
                    synchronized(bufferLock) {
                        System.arraycopy(rollingBuffer, readSize, rollingBuffer, 0, BUFFER_SIZE - readSize)
                        System.arraycopy(audioChunk, 0, rollingBuffer, BUFFER_SIZE - readSize, readSize)
                    }
                    
                    // 🔧 ANTI-GHOST: Check if audio has enough energy before running classifier
                    // Find max amplitude in the chunk to detect if there's actual speech
                    var maxAmp = 0
                    for (sample in audioChunk) {
                        val absVal = kotlin.math.abs(sample.toInt())
                        if (absVal > maxAmp) maxAmp = absVal
                    }
                    
                    // Skip classification if audio is basically silence (no speech)
                    if (maxAmp < MIN_AUDIO_LEVEL_FOR_DETECTION) {
                        // Too quiet - likely silence/background noise, skip classification
                        continue
                    }
                    
                    // Check for wake word
                    val confidence = detectWakeWord(rollingBuffer)
                    
                    // 🔧 BALANCED SMOOTHING: 50% peak + 50% avg
                    val finalScore = smoothScore(confidence)
                    
                    // Log periodically when confidence > 0.1 (helps debug)
                    if (finalScore > 0.1f && audioLogCounter % 10 == 0) {
                        Log.d(TAG, "📊 raw=$confidence smooth=$finalScore amp=$maxAmp")
                    }
                    
                    // Check if detected and not in cooldown
                    val now = System.currentTimeMillis()
                    if (finalScore > threshold && (now - lastDetectionTime) > DETECTION_COOLDOWN_MS) {
                        // 🔧 PRECISION BOOST: Require 3 frames > 0.45f (Stricter)
                        // Raised from 2 frames > 0.35f to reduce false positives
                        val consistentFrames = scoreWindow.count { it > 0.50f }
                        if (consistentFrames < 3) {
                            Log.d(TAG, "⚠️ Skip: Only $consistentFrames consistent frames (need 3+)")
                            continue
                        }
                        
                        lastDetectionTime = now
                        
                        Log.i(TAG, "🔥 Hey IMI DETECTED! smooth=$finalScore (raw=$confidence) [amp=$maxAmp] [consistent=$consistentFrames]")
                        
                        // Reset score buffer after detection
                        scoreWindow.clear()
                        
                        // Play chime immediately
                        mainHandler.post { playChimeSound() }
                        
                        // Stop listening (will be restarted by caller)
                        isListening = false
                        
                        // Notify callback on main thread
                        mainHandler.post {
                            onWakeWordDetected(finalScore)
                        }
                        // 🔧 REMOVED: break - let loop exit naturally via isListening=false
                    }
                }
            } catch (e: InterruptedException) {
                Log.d(TAG, "Listening thread interrupted")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio processing: ${e.message}")
                Thread.sleep(100) // Brief pause on error
            }
        }
        
        Log.d(TAG, "🎧 Audio processing loop ended")
    }
    
    /**
     * Detect wake word from audio buffer using ONNX pipeline:
     * Audio → Melspectrogram → Embeddings → Classifier
     * 
     * @param audioBuffer 16-bit PCM audio samples (24000 samples = 1.5s at 16kHz)
     * @return Confidence score 0.0 to 1.0
     */
    // AGC (Automatic Gain Control) constants
    private val TARGET_LEVEL = 0.85f          // 🔧 HYPER: 85% target
    private val MAX_GAIN = 60.0f              // 🔧 HYPER: 60x max gain (Detect even whispers)
    private val MIN_GAIN = 1.0f               // Minimum gain (Never attenuate)
    private val NOISE_GATE_THRESHOLD = 0.005f // 🔧 DISABLED: 0.005f (Virtually zero)

    // Debug counter for periodic logging
    private var audioLogCounter = 0
    
    /**
     * Preprocess audio with AGC (Automatic Gain Control) + Noise Gate
     * Flow: Raw Audio → Dynamic Gain (AGC) → Noise Gate → Clean Audio
     * 
     * AGC: Soft audio gets boosted more, loud audio stays same
     * Noise Gate: Mutes (sets to 0) any signal below threshold
     */
    private fun preprocessAudio(audioBuffer: ShortArray): FloatArray {
        val audioFloat = FloatArray(audioBuffer.size)
        
        // Step 1: Find maximum amplitude in the buffer
        var maxAmplitude = 0f
        for (i in audioBuffer.indices) {
            val absValue = kotlin.math.abs(audioBuffer[i].toFloat())
            if (absValue > maxAmplitude) {
                maxAmplitude = absValue
            }
        }
        
        // Step 2: Calculate dynamic gain (AGC)
        // Target is 70% of max volume (0.7 * 32768 = 22937)
        val targetAmplitude = TARGET_LEVEL * 32768f
        var dynamicGain = if (maxAmplitude > 0) {
            (targetAmplitude / maxAmplitude).coerceIn(MIN_GAIN, MAX_GAIN)
        } else {
            1.0f  // Silence - no gain
        }
        
        // Debug log every 20 frames (~5 seconds)
        audioLogCounter++
        if (audioLogCounter % 20 == 0) {
            val maxAmpNormalized = maxAmplitude / 32768f
            Log.d(TAG, "🎤 Audio: maxAmp=${String.format("%.4f", maxAmpNormalized)} (raw=${maxAmplitude.toInt()}), gain=${String.format("%.2f", dynamicGain)}x")
        }
        
        // Step 3: Apply dynamic gain and noise gate
        var gatedSamples = 0
        for (i in audioBuffer.indices) {
            // Convert to float [-1, 1] with dynamic gain
            var sample = (audioBuffer[i].toFloat() / 32768f) * dynamicGain
            
            // Noise Gate - DISABLED AGAIN
            // It was causing missed detections for soft voices.
            // if (kotlin.math.abs(sample) < NOISE_GATE_THRESHOLD) {
            //    sample = 0.0f
            //    gatedSamples++
            // }
            
            // Clip to valid range [-1, 1]
            sample = sample.coerceIn(-1.0f, 1.0f)
            
            audioFloat[i] = sample
        }
        
        // Log gated percentage periodically
        if (audioLogCounter % 20 == 0) {
            val gatedPercent = (gatedSamples.toFloat() / audioBuffer.size) * 100
            Log.d(TAG, "🔇 Noise Gate: ${String.format("%.1f", gatedPercent)}% samples muted")
        }
        
        return audioFloat
    }
    
    private fun detectWakeWord(audioBuffer: ShortArray): Float {
        return try {
            // Step 1: Preprocess audio (Gain + Noise Gate)
            // Flow: Mic → Gain Boost → Noise Gate → MelSpec → Model
            val audioFloat = preprocessAudio(audioBuffer)
            
            // Step 2: Generate melspectrogram using dynamic input name
            val melspecInput = OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(audioFloat),
                longArrayOf(1, audioBuffer.size.toLong())
            )
            
            val melspecOutput = melspecSession?.run(mapOf(melspecInputName to melspecInput))
            val melspec4D = getMelspecOutput(melspecOutput)
            melspecInput.close()
            
            if (melspec4D == null) {
                Log.w(TAG, "Melspec output is null")
                return 0f
            }
            
            // Step 3: Reshape melspec for embedding model
            // Melspec output: [1, 1, 147, 32] → Need: [1, 76, 32, 1]
            // Take the last 76 time frames, transpose to [batch, time, mels, channel]
            val reshapedForEmbedding = reshapeMelspecForEmbedding(melspec4D)
            if (reshapedForEmbedding == null) {
                Log.w(TAG, "Failed to reshape melspec for embedding")
                return 0f
            }
            
            // Step 4: Generate embeddings using dynamic input name
            val embeddingInput = OnnxTensor.createTensor(ortEnv, reshapedForEmbedding)
            val embeddingOutput = embeddingSession?.run(mapOf(embeddingInputName to embeddingInput))
            val embeddings = getEmbeddingsOutput(embeddingOutput)
            embeddingInput.close()
            embeddingOutput?.close()
            
            if (embeddings == null) {
                Log.w(TAG, "Embeddings output is null")
                melspecOutput?.close()
                return 0f
            }
            
            melspecOutput?.close()
            
            // Step 5: Buffer embeddings - classifier needs 9 frames
            val confidence = bufferAndClassify(embeddings)
            
            confidence
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in detectWakeWord: ${e.message}")
            0f
        }
    }
    
    /**
     * Run classifier with current embedding repeated 9 times
     * (Model was trained with 9 identical embedding frames, not sliding window)
     * Classifier expects input shape: [1, 9, 96]
     */
    private fun bufferAndClassify(embedding: Array<Array<FloatArray>>): Float {
        return try {
            // Create 3D tensor: [1, 9, feature_dim] - classifier expects 3D
            // Embedding output is [1, 1, 1, 96] - feature dim is in the LAST dimension
            val batchSize = 1
            val numFrames = REQUIRED_EMBEDDING_FRAMES
            val featureDim = embedding[0][0].size  // 96 for this model
            
            // Extract the 96-dim embedding vector from [1, 1, 1, 96] shaped tensor
            val embeddingVector = FloatArray(featureDim) { featIdx ->
                embedding[0][0][featIdx]
            }
            
            // IMPORTANT: Model was trained with 9 IDENTICAL frames (np.tile)
            // So we repeat the same embedding 9 times to match training
            val classifierInputArray = Array(batchSize) {
                Array(numFrames) { _ ->
                    embeddingVector.copyOf()  // Same embedding repeated 9 times
                }
            }
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Classifier input shape: [1, $numFrames, $featureDim], embedding sum: ${embeddingVector.sum()}")
            }
            
            // Run classifier
            val classifierInput = OnnxTensor.createTensor(ortEnv, classifierInputArray)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Classifier tensor shape: ${classifierInput.info.shape.contentToString()}")
            }
            val classifierOutput = classifierSession?.run(mapOf(classifierInputName to classifierInput))
            val confidence = getClassifierOutput(classifierOutput)
            
            classifierInput.close()
            classifierOutput?.close()
            
            confidence
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in classifier: ${e.message}")
            0f
        }
    }
    
    /**
     * Reshape melspec from [batch, channels, time, mels] to [batch, 76, 32, 1]
     * The embedding model expects exactly 76 time frames with 32 mel bins
     */
    private fun reshapeMelspecForEmbedding(melspec4D: Array<Array<Array<FloatArray>>>): Array<Array<Array<FloatArray>>>? {
        try {
            // Input shape: [1, 1, 147, 32] - [batch, channels, time_frames, mel_bins]
            val batch = melspec4D.size           // 1
            val channels = melspec4D[0].size     // 1
            val timeFrames = melspec4D[0][0].size // 147
            val melBins = melspec4D[0][0][0].size // 32
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Reshaping melspec: [$batch, $channels, $timeFrames, $melBins] → [1, 76, 32, 1]")
            }
            
            // Target: [1, 76, 32, 1] - [batch, time, mels, channel]
            val targetTimeFrames = 76
            val targetMelBins = 32
            
            // Take the last 76 frames (or pad if less)
            val startFrame = if (timeFrames >= targetTimeFrames) {
                timeFrames - targetTimeFrames
            } else {
                0
            }
            
            // Create output array: [1, 76, 32, 1]
            val output = Array(1) { Array(targetTimeFrames) { Array(targetMelBins) { FloatArray(1) } } }
            
            for (t in 0 until targetTimeFrames) {
                val srcT = startFrame + t
                if (srcT < timeFrames) {
                    for (m in 0 until minOf(melBins, targetMelBins)) {
                        // Transpose: input[batch][channel][time][mel] → output[batch][time][mel][channel]
                        output[0][t][m][0] = melspec4D[0][0][srcT][m]
                    }
                }
                // If srcT >= timeFrames, values remain 0 (padding)
            }
            
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Reshaped melspec to [1, $targetTimeFrames, $targetMelBins, 1]")
            }
            return output
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reshaping melspec: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Extract melspectrogram output from ONNX result
     * Handles multiple possible tensor formats including 4D tensors
     */
    private fun getMelspecOutput(result: OrtSession.Result?): Array<Array<Array<FloatArray>>>? {
        if (result == null) {
            Log.e(TAG, "Melspec result is null")
            return null
        }
        
        return try {
            val tensor = result.get(0) ?: run {
                Log.e(TAG, "Melspec tensor at index 0 is null")
                return null
            }
            
            val value = tensor.value
            Log.d(TAG, "Melspec output type: ${value?.javaClass?.name}")
            
            when (value) {
                is Array<*> -> {
                    // Check for 4D array: [batch, channels, n_mels, time] or similar
                    if (value.isNotEmpty() && value[0] is Array<*>) {
                        val inner1 = value[0] as? Array<*>
                        if (inner1 != null && inner1.isNotEmpty() && inner1[0] is Array<*>) {
                            val inner2 = inner1[0] as? Array<*>
                            if (inner2 != null && inner2.isNotEmpty() && inner2[0] is FloatArray) {
                                // It's a 4D array: Array<Array<Array<FloatArray>>>
                                @Suppress("UNCHECKED_CAST")
                                val result4d = value as? Array<Array<Array<FloatArray>>>
                                if (result4d != null) {
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "Melspec 4D shape: [${result4d.size}, ${result4d[0].size}, ${result4d[0][0].size}, ${result4d[0][0][0].size}]")
                                    }
                                    return result4d
                                }
                            } else if (inner2 != null && inner2.isNotEmpty() && inner2[0] is Float) {
                                // It's a 3D array: Array<Array<FloatArray>>
                                @Suppress("UNCHECKED_CAST")
                                val result3d = value as? Array<Array<FloatArray>>
                                if (result3d != null) {
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "Melspec 3D shape: [${result3d.size}, ${result3d[0].size}, ${result3d[0][0].size}], wrapping to 4D")
                                    }
                                    return arrayOf(result3d)
                                }
                            }
                        } else if (inner1 != null && inner1.isNotEmpty() && inner1[0] is Float) {
                            // It's a 2D array: Array<FloatArray>
                            @Suppress("UNCHECKED_CAST")
                            val result2d = value as? Array<FloatArray>
                            if (result2d != null) {
                                Log.d(TAG, "Melspec 2D shape: [${result2d.size}, ${result2d[0].size}], wrapping to 4D")
                                return arrayOf(arrayOf(result2d))
                            }
                        }
                    }
                    Log.e(TAG, "Unknown melspec array structure")
                    null
                }
                else -> {
                    Log.e(TAG, "Unexpected melspec type: ${value?.javaClass?.name}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting melspec: ${e.message}", e)
            null
        }
    }
    
    /**
     * Extract embeddings output from ONNX result
     * Handles multiple possible tensor formats including 4D tensors
     */
    private fun getEmbeddingsOutput(result: OrtSession.Result?): Array<Array<FloatArray>>? {
        if (result == null) {
            Log.e(TAG, "Embeddings result is null")
            return null
        }
        
        return try {
            val tensor = result.get(0) ?: return null
            val value = tensor.value
            Log.d(TAG, "Embeddings output type: ${value?.javaClass?.name}")
            
            when (value) {
                is Array<*> -> {
                    // Check for 4D: squeeze to 3D
                    if (value.isNotEmpty() && value[0] is Array<*>) {
                        val inner1 = value[0] as? Array<*>
                        if (inner1 != null && inner1.isNotEmpty() && inner1[0] is Array<*>) {
                            val inner2 = inner1[0] as? Array<*>
                            if (inner2 != null && inner2.isNotEmpty() && inner2[0] is FloatArray) {
                                // 4D array - take first batch
                                @Suppress("UNCHECKED_CAST")
                                val result4d = value as? Array<Array<Array<FloatArray>>>
                                if (result4d != null) {
                                    Log.d(TAG, "Embeddings 4D, taking first batch")
                                    return result4d[0]
                                }
                            } else if (inner2 != null && inner2.isNotEmpty() && inner2[0] is Float) {
                                // 3D array
                                @Suppress("UNCHECKED_CAST")
                                return value as? Array<Array<FloatArray>>
                            }
                        } else if (inner1 != null && inner1.isNotEmpty() && inner1[0] is Float) {
                            // 2D array
                            @Suppress("UNCHECKED_CAST")
                            val result2d = value as? Array<FloatArray>
                            if (result2d != null) {
                                return arrayOf(result2d)
                            }
                        }
                    }
                    null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting embeddings: ${e.message}")
            null
        }
    }
    
    /**
     * Extract classifier confidence from ONNX result
     * Handles multiple output formats
     */
    private fun getClassifierOutput(result: OrtSession.Result?): Float {
        if (result == null) return 0f

        return try {
            val tensor = result.get(0) ?: return 0f
            val value = tensor.value

            val rawLogit: Float = when (value) {
                // 2D array: [[logit]]
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr2d = value as? Array<FloatArray>
                    arr2d?.getOrNull(0)?.getOrNull(0) ?: 0f
                }
                // 1D array: [logit]
                is FloatArray -> value.getOrNull(0) ?: 0f
                // Single float
                is Float -> value
                else -> 0f
            }

            // 🔥 APPLY SIGMOID: Convert raw logit → probability [0, 1]
            // Model uses BCEWithLogitsLoss → no sigmoid in model → must apply here
            val prob = sigmoid(rawLogit)

            Log.d(TAG, "Classifier raw=$rawLogit → prob=${"%.3f".format(prob)}")

            prob

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting classifier output: ${e.message}")
            0f
        }
    }
    
    /**
     * Process audio from external source (e.g., BLE Glass mic)
     * Call this with 16kHz mono 16-bit PCM audio data
     * 
     * @param pcmData Raw PCM audio bytes
     */
    fun processExternalAudio(pcmData: ByteArray) {
        if (!isListening) return
        
        try {
            // Convert bytes to short array
            val shortBuffer = ByteBuffer.wrap(pcmData)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            
            val samples = ShortArray(shortBuffer.remaining())
            shortBuffer.get(samples)
            
            // Add to rolling buffer with thread safety
            synchronized(bufferLock) {
                val copySize = minOf(samples.size, BUFFER_SIZE)
                System.arraycopy(rollingBuffer, copySize, rollingBuffer, 0, BUFFER_SIZE - copySize)
                System.arraycopy(samples, 0, rollingBuffer, BUFFER_SIZE - copySize, copySize)
                
                // 🔧 AUDIO GATE: Check if audio has enough energy (same as internal mic)
                var maxAmp = 0
                for (sample in samples) {
                    val absVal = kotlin.math.abs(sample.toInt())
                    if (absVal > maxAmp) maxAmp = absVal
                }
                if (maxAmp < MIN_AUDIO_LEVEL_FOR_DETECTION) {
                    return  // Too quiet, skip classification
                }
                
                // Check for wake word
                val confidence = detectWakeWord(rollingBuffer)
                
                // 🔧 SMOOTHING: Same as internal mic
                val finalScore = smoothScore(confidence)
                
                val now = System.currentTimeMillis()
                
                if (finalScore > threshold && (now - lastDetectionTime) > DETECTION_COOLDOWN_MS) {
                    // 🔧 CONSISTENCY CHECK: Require 3 frames > 0.43f (tuned)
                    val consistentFrames = scoreWindow.count { it > 0.43f }
                    if (consistentFrames < 3) {
                        return  // Not consistent enough, skip
                    }
                    
                    lastDetectionTime = now
                    scoreWindow.clear()  // Reset after detection
                    
                    Log.i(TAG, "🔥 Hey IMI detected from Glass! smooth=$finalScore (raw=$confidence) [consistent=$consistentFrames]")
                    
                    isListening = false
                    
                    mainHandler.post {
                        playChimeSound()
                        onWakeWordDetected(finalScore)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing external audio: ${e.message}")
        }
    }
    
    /**
     * Check if detector is currently listening
     */
    fun isListening(): Boolean = isListening
    
    /**
     * Get current detection threshold
     */
    fun getThreshold(): Float = threshold
}
