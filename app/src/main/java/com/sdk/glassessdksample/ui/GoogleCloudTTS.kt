package com.sdk.glassessdksample.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Google Cloud Text-to-Speech API Client
 * Provides Gemini AI quality voice synthesis using WaveNet/Neural2 voices
 * 
 * Voice Options:
 * - WaveNet: Natural sounding, premium quality
 * - Neural2: Latest Google technology, most natural
 * - Standard: Good quality, faster synthesis
 */
class GoogleCloudTTS(private val context: Context) {
    
    companion object {
        private const val TAG = "GoogleCloudTTS"
        private const val TTS_API_URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
        
        // Premium WaveNet voices (most natural sounding)
        val WAVENET_VOICES = mapOf(
            "en-US" to listOf(
                "en-US-Wavenet-F",  // Female, warm
                "en-US-Wavenet-C",  // Female, friendly
                "en-US-Wavenet-H",  // Female, professional
                "en-US-Wavenet-D",  // Male, warm
                "en-US-Wavenet-A"   // Male, friendly
            ),
            "en-IN" to listOf(
                "en-IN-Wavenet-A",  // Female, Indian English
                "en-IN-Wavenet-B",  // Male, Indian English
                "en-IN-Wavenet-C",  // Male, Indian English
                "en-IN-Wavenet-D"   // Female, Indian English
            ),
            "hi-IN" to listOf(
                "hi-IN-Wavenet-A",  // Female, Hindi
                "hi-IN-Wavenet-B",  // Male, Hindi
                "hi-IN-Wavenet-C",  // Male, Hindi
                "hi-IN-Wavenet-D"   // Female, Hindi
            )
        )
        
        // Neural2 voices (latest, most natural)
        val NEURAL2_VOICES = mapOf(
            "en-US" to listOf(
                "en-US-Neural2-F",  // Female
                "en-US-Neural2-C",  // Female
                "en-US-Neural2-H",  // Female
                "en-US-Neural2-D",  // Male
                "en-US-Neural2-A"   // Male
            ),
            "en-IN" to listOf(
                "en-IN-Neural2-A",  // Female
                "en-IN-Neural2-B",  // Male
                "en-IN-Neural2-C",  // Male
                "en-IN-Neural2-D"   // Female
            ),
            "hi-IN" to listOf(
                "hi-IN-Neural2-A",  // Female
                "hi-IN-Neural2-B",  // Male
                "hi-IN-Neural2-C",  // Male
                "hi-IN-Neural2-D"   // Female
            )
        )
    }
    
    private var apiKey: String? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentVoice: String = "en-US-Neural2-F" // Default: Female US English Neural2
    private var speakingRate: Float = 1.0f
    private var pitch: Float = 0.0f // -20.0 to 20.0
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    
    // Callbacks
    var onSpeakStart: ((String) -> Unit)? = null
    var onSpeakDone: ((String) -> Unit)? = null
    var onSpeakError: ((String, String) -> Unit)? = null
    
    /**
     * Initialize with API key
     */
    fun initialize(googleCloudApiKey: String) {
        this.apiKey = googleCloudApiKey
        Log.d(TAG, "✅ GoogleCloudTTS initialized")
    }
    
    /**
     * Set voice by language code (auto-selects best voice)
     */
    fun setLanguage(languageCode: String) {
        val voiceList = NEURAL2_VOICES[languageCode] ?: WAVENET_VOICES[languageCode]
        currentVoice = voiceList?.firstOrNull() ?: "en-US-Neural2-F"
        Log.d(TAG, "🗣️ Voice set to: $currentVoice for language: $languageCode")
    }
    
    /**
     * Set specific voice name
     */
    fun setVoice(voiceName: String) {
        currentVoice = voiceName
        Log.d(TAG, "🗣️ Voice set to: $currentVoice")
    }
    
    /**
     * Set speaking rate (0.25 to 4.0, default 1.0)
     */
    fun setSpeakingRate(rate: Float) {
        speakingRate = rate.coerceIn(0.25f, 4.0f)
    }
    
    /**
     * Set pitch (-20.0 to 20.0, default 0.0)
     */
    fun setPitch(pitchValue: Float) {
        pitch = pitchValue.coerceIn(-20f, 20f)
    }
    
    /**
     * Check if API key is configured
     */
    fun isConfigured(): Boolean {
        return !apiKey.isNullOrBlank() && apiKey != "YOUR_GOOGLE_CLOUD_API_KEY_HERE"
    }
    
    /**
     * Synthesize speech and play it
     */
    suspend fun speak(text: String, utteranceId: String = "TTS"): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.w(TAG, "⚠️ Google Cloud API key not configured")
            return@withContext false
        }
        
        if (text.isBlank()) return@withContext false
        
        try {
            Log.d(TAG, "🎤 Synthesizing: ${text.take(50)}...")
            onSpeakStart?.invoke(utteranceId)
            
            // Detect language and auto-set voice
            val isHindi = text.any { it in '\u0900'..'\u097F' }
            val languageCode = if (isHindi) "hi-IN" else "en-US"
            
            // Use appropriate voice for detected language
            val voice = if (isHindi) {
                NEURAL2_VOICES["hi-IN"]?.firstOrNull() ?: "hi-IN-Neural2-A"
            } else {
                currentVoice
            }
            
            // Build request JSON
            val requestJson = JSONObject().apply {
                put("input", JSONObject().apply {
                    // Use SSML for better prosody
                    put("ssml", """
                        <speak>
                            <prosody rate="${speakingRate}" pitch="${pitch}st">
                                $text
                            </prosody>
                        </speak>
                    """.trimIndent())
                })
                put("voice", JSONObject().apply {
                    put("languageCode", languageCode)
                    put("name", voice)
                })
                put("audioConfig", JSONObject().apply {
                    put("audioEncoding", "MP3")
                    put("speakingRate", speakingRate)
                    put("pitch", pitch)
                    put("volumeGainDb", 2.0) // Slightly louder
                    put("effectsProfileId", org.json.JSONArray().apply {
                        put("headphone-class-device") // Optimized for earphones/glasses
                    })
                    put("sampleRateHertz", 24000) // High quality
                })
            }
            
            val request = Request.Builder()
                .url("$TTS_API_URL?key=$apiKey")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val error = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "❌ TTS API error: ${response.code} - $error")
                onSpeakError?.invoke(utteranceId, error)
                return@withContext false
            }
            
            val responseBody = response.body?.string() ?: return@withContext false
            val jsonResponse = JSONObject(responseBody)
            val audioContent = jsonResponse.getString("audioContent")
            
            // Decode base64 audio
            val audioBytes = Base64.decode(audioContent, Base64.DEFAULT)
            
            // Save to temp file and play
            val tempFile = File(context.cacheDir, "tts_audio_$utteranceId.mp3")
            FileOutputStream(tempFile).use { it.write(audioBytes) }
            
            // Play audio
            withContext(Dispatchers.Main) {
                playAudio(tempFile, utteranceId)
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ TTS error: ${e.message}", e)
            onSpeakError?.invoke(utteranceId, e.message ?: "Unknown error")
            false
        }
    }
    
    /**
     * Play audio file
     */
    private fun playAudio(audioFile: File, utteranceId: String) {
        try {
            // Stop any existing playback
            stop()
            
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(audioFile.absolutePath)
                prepare()
                
                setOnCompletionListener {
                    Log.d(TAG, "✅ Audio playback complete: $utteranceId")
                    onSpeakDone?.invoke(utteranceId)
                    release()
                    mediaPlayer = null
                    // Clean up temp file
                    try { audioFile.delete() } catch (_: Exception) {}
                }
                
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "❌ MediaPlayer error: $what, $extra")
                    onSpeakError?.invoke(utteranceId, "Playback error: $what")
                    release()
                    mediaPlayer = null
                    true
                }
                
                start()
                Log.d(TAG, "🔊 Playing high-quality TTS audio")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to play audio: ${e.message}", e)
            onSpeakError?.invoke(utteranceId, e.message ?: "Playback failed")
        }
    }
    
    /**
     * Stop current playback
     */
    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping playback: ${e.message}")
        }
    }
    
    /**
     * Check if currently speaking
     */
    fun isSpeaking(): Boolean {
        return mediaPlayer?.isPlaying == true
    }
    
    /**
     * Release resources
     */
    fun release() {
        stop()
        httpClient.dispatcher.executorService.shutdown()
    }
}
