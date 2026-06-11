package com.sdk.glassessdksample.ui

import com.sdk.glassessdksample.RemoteConfigManager
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.MediaRecorder
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sdk.glassessdksample.BuildConfig
import com.sdk.glassessdksample.R
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import java.util.*

/**
 * Activity for recording meeting audio, then converting to text at the end
 */
class ActiveMeetingActivity : AppCompatActivity() {

    private val TAG = "ActiveMeetingActivity"
    
    private lateinit var meetingManager: MeetingMinutesManager
    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String? = null
    
    private lateinit var tvMeetingTitle: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLiveParticipants: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var tvSummaryStatus: TextView
    private lateinit var timerContainer: View
    private lateinit var summaryContainer: View
    private lateinit var btnPause: ImageView
    private lateinit var btnSwitch: ImageView
    private lateinit var btnEndMeeting: ImageView
    
    private var currentMeeting: MeetingMinute? = null
    private var isRecording = false
    private var isPaused = false
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Audio focus management
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_active_meeting)
        
        meetingManager = MeetingMinutesManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        setupUI()
        checkPermissionsAndStart()
    }

    private fun setupUI() {
        tvMeetingTitle = findViewById(R.id.tv_meeting_title)
        tvDuration = findViewById(R.id.tv_duration)
        tvStatus = findViewById(R.id.tv_status)
        tvLiveParticipants = findViewById(R.id.tv_live_participants)
        tvTranscript = findViewById(R.id.tv_transcript)
        tvSummaryStatus = findViewById(R.id.tv_summary_status)
        timerContainer = findViewById(R.id.timer_container)
        summaryContainer = findViewById(R.id.summary_container)
        btnPause = findViewById(R.id.btn_pause)
        btnSwitch = findViewById(R.id.btn_switch)
        btnEndMeeting = findViewById(R.id.btn_end_meeting)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { confirmEndMeeting() }

        btnPause.setOnClickListener {
            if (isPaused) resumeRecording() else pauseRecording()
        }

        btnEndMeeting.setOnClickListener {
            confirmEndMeeting()
        }
    }
    
    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        } else {
            startMeeting()
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startMeeting()
            } else {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    
    private fun startMeeting() {
        val meetingTitle = intent.getStringExtra(EXTRA_MEETING_TITLE) ?: "Meeting ${getFormattedDateTime()}"
        
        // Start new meeting
        currentMeeting = meetingManager.startMeeting(meetingTitle)
        tvMeetingTitle.text = "Title: $meetingTitle"
        
        // Request audio focus
        requestAudioFocus()
        
        Toast.makeText(this, "🎤 Recording audio - AI paused during meeting", Toast.LENGTH_LONG).show()
        
        // Start recording audio
        startRecording()
        
        // Start duration timer
        startDurationTimer()
        
        Log.d(TAG, "Meeting started: $meetingTitle")
    }
    
    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                }
                .build()
            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
            Log.d(TAG, "Audio focus requested (GAIN_TRANSIENT_EXCLUSIVE)")
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { Log.d(TAG, "Audio focus changed: $it") },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
        }
    }
    
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
        Log.d(TAG, "Audio focus abandoned")
    }
    
    private fun startRecording() {
        try {
            // Create audio file path
            val audioDir = getExternalFilesDir(null)
            audioFilePath = "${audioDir?.absolutePath}/meeting_${System.currentTimeMillis()}.3gp"
            
            // Initialize MediaRecorder
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            
            mediaRecorder?.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFilePath)
                prepare()
                start()
            }
            
            isRecording = true
            isPaused = false

            tvStatus.text = "🎙️ Recording audio"
            btnPause.setImageResource(R.drawable.ic_pause_bars)

            Log.d(TAG, "Audio recording started: $audioFilePath")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio recording: ${e.message}", e)
            Toast.makeText(this, "Error starting recording: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun pauseRecording() {
        // Pause with MediaRecorder supported in API 24+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.pause()
                isPaused = true
                tvStatus.text = "⏸️ Paused"
                btnPause.setImageResource(R.drawable.ic_play_triangle)
                Log.d(TAG, "Recording paused")
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing: ${e.message}")
                Toast.makeText(this, "Error pausing recording", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Pause not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun resumeRecording() {
        // Resume with MediaRecorder supported in API 24+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                mediaRecorder?.resume()
                isPaused = false
                tvStatus.text = "🎙️ Recording audio"
                btnPause.setImageResource(R.drawable.ic_pause_bars)
                Log.d(TAG, "Recording resumed")
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming: ${e.message}")
                Toast.makeText(this, "Error resuming recording", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Resume not supported on this device", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun confirmEndMeeting() {
        AlertDialog.Builder(this)
            .setTitle("End Meeting?")
            .setMessage("Meeting will be saved and summarized.")
            .setPositiveButton("End Meeting") { _, _ ->
                endMeeting()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun endMeeting() {
        // Stop recording
        isRecording = false
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder: ${e.message}")
        }
        abandonAudioFocus()
        handler.removeCallbacksAndMessages(null)

        // Switch from the recording timer to the summary-generation view (mockup screen 5)
        timerContainer.visibility = View.GONE
        summaryContainer.visibility = View.VISIBLE
        tvStatus.text = "✅ Recording complete"
        tvSummaryStatus.text = "Generating Summary…"
        btnPause.isEnabled = false
        btnSwitch.isEnabled = false
        btnEndMeeting.isEnabled = false

        if (audioFilePath == null) {
            Toast.makeText(this, "No audio recorded", Toast.LENGTH_SHORT).show()
            meetingManager.cancelActiveMeeting()
            finish()
            return
        }
        
        // Convert audio to text using Gemini, then generate summary
        scope.launch {
            try {
                // Step 1: Transcribe audio
                runOnUiThread {
                    tvSummaryStatus.text = "Transcribing audio…"
                    tvTranscript.text = "Please wait while your audio is being processed.\nThis may take a few moments."
                }
                
                val transcript = transcribeAudioWithGemini(audioFilePath!!)
                
                if (transcript.isBlank()) {
                    Toast.makeText(this@ActiveMeetingActivity, "No speech detected in recording", Toast.LENGTH_SHORT).show()
                    meetingManager.cancelActiveMeeting()
                    finish()
                    return@launch
                }
                
                // Update meeting with transcript
                meetingManager.updateActiveMeetingTranscript(transcript)
                
                // Step 2: Generate summary
                runOnUiThread {
                    tvSummaryStatus.text = "Generating Summary…"
                }
                
                val summary = generateMeetingSummary(transcript)
                val completedMeeting = meetingManager.endMeeting(summary)
                
                // Clean up audio file
                try {
                    java.io.File(audioFilePath!!).delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting audio file: ${e.message}")
                }
                
                if (completedMeeting != null) {
                    // Show summary dialog
                    runOnUiThread {
                        showSummaryDialog(completedMeeting)
                    }
                } else {
                    Toast.makeText(this@ActiveMeetingActivity, "Error saving meeting", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error ending meeting: ${e.message}", e)
                Toast.makeText(this@ActiveMeetingActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
    
    private suspend fun transcribeAudioWithGemini(audioFilePath: String): String = withContext(Dispatchers.IO) {
        try {
            val apiKey = RemoteConfigManager.geminiApiKey
            val audioFile = java.io.File(audioFilePath)
            
            if (!audioFile.exists()) {
                return@withContext "Error: Audio file not found"
            }
            
            // Read audio file as base64
            val audioBytes = audioFile.readBytes()
            val audioBase64 = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
            
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            // Use Google Cloud Speech-to-Text API with speaker diarization
            val requestBody = com.google.gson.Gson().toJson(mapOf(
                "config" to mapOf(
                    "encoding" to "AMR_WB",
                    "sampleRateHertz" to 16000,
                    "languageCode" to "en-US",
                    "enableAutomaticPunctuation" to true,
                    "diarizationConfig" to mapOf(
                        "enableSpeakerDiarization" to true,
                        "minSpeakerCount" to 1,
                        "maxSpeakerCount" to 10
                    ),
                    "model" to "latest_long"
                ),
                "audio" to mapOf(
                    "content" to audioBase64
                )
            ))
            
            val request = okhttp3.Request.Builder()
                .url("https://speech.googleapis.com/v1/speech:recognize?key=$apiKey")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaType(),
                    requestBody
                ))
                .build()
            
            Log.d(TAG, "Sending audio for transcription with speaker diarization (${audioBytes.size} bytes)")
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Speech-to-Text error: $responseBody")
                // Fallback to Gemini if Speech-to-Text fails
                return@withContext transcribeWithGeminiFallback(audioFile, audioBase64, client)
            }
            
            val jsonResponse = org.json.JSONObject(responseBody)
            val results = jsonResponse.optJSONArray("results")
            
            if (results != null && results.length() > 0) {
                val plainTranscript = StringBuilder()
                val speakerTranscript = StringBuilder()
                var speakerCount = 0
                val speakerWords = mutableMapOf<Int, MutableList<String>>()
                
                // Parse results with speaker labels
                for (i in 0 until results.length()) {
                    val result = results.getJSONObject(i)
                    val alternatives = result.getJSONArray("alternatives")
                    
                    if (alternatives.length() > 0) {
                        val alternative = alternatives.getJSONObject(0)
                        plainTranscript.append(alternative.getString("transcript")).append(" ")
                        
                        // Check if words with speaker tags exist
                        val words = alternative.optJSONArray("words")
                        if (words != null) {
                            for (j in 0 until words.length()) {
                                val word = words.getJSONObject(j)
                                val text = word.getString("word")
                                val speakerTag = word.optInt("speakerTag", -1)
                                
                                if (speakerTag > 0) {
                                    if (speakerTag > speakerCount) speakerCount = speakerTag
                                    speakerWords.getOrPut(speakerTag) { mutableListOf() }.add(text)
                                }
                            }
                        }
                    }
                }
                
                // Build speaker-separated transcript
                if (speakerWords.isNotEmpty()) {
                    var currentSpeaker = -1
                    for (i in 0 until results.length()) {
                        val result = results.getJSONObject(i)
                        val alternatives = result.getJSONArray("alternatives")
                        if (alternatives.length() > 0) {
                            val alternative = alternatives.getJSONObject(0)
                            val words = alternative.optJSONArray("words")
                            if (words != null) {
                                for (j in 0 until words.length()) {
                                    val word = words.getJSONObject(j)
                                    val text = word.getString("word")
                                    val speakerTag = word.optInt("speakerTag", -1)
                                    
                                    if (speakerTag > 0 && speakerTag != currentSpeaker) {
                                        if (currentSpeaker != -1) speakerTranscript.append("\\n\\n")
                                        speakerTranscript.append("Speaker $speakerTag: ")
                                        currentSpeaker = speakerTag
                                    }
                                    speakerTranscript.append(text).append(" ")
                                }
                            }
                        }
                    }
                }
                
                val transcript = plainTranscript.toString().trim()
                val speakerText = speakerTranscript.toString().trim()
                
                // Save speaker information
                runOnUiThread {
                    val activeMeeting = meetingManager.getActiveMeeting()
                    if (activeMeeting != null) {
                        val updatedMeeting = activeMeeting.copy(
                            transcript = transcript,
                            speakerTranscript = speakerText,
                            speakerCount = speakerCount
                        )
                        meetingManager.saveMeeting(updatedMeeting)
                        
                        // Update live participants display
                        updateLiveParticipantsDisplay(speakerCount)
                    }
                }
                
                Log.d(TAG, "Transcription successful: ${transcript.length} chars, $speakerCount speakers")
                return@withContext transcript
            }
            
            "Transcription failed. No content returned."
        } catch (e: Exception) {
            Log.e(TAG, "Error transcribing audio: ${e.message}", e)
            "Error transcribing audio: ${e.message}"
        }
    }
    
    // Fallback transcription using Gemini (no speaker diarization)
    private suspend fun transcribeWithGeminiFallback(
        audioFile: java.io.File,
        audioBase64: String,
        client: okhttp3.OkHttpClient
    ): String {
        try {
            Log.d(TAG, "Using Gemini fallback for transcription")
            val apiKey = RemoteConfigManager.geminiApiKey
            
            val requestBody = com.google.gson.Gson().toJson(mapOf(
                "contents" to listOf(
                    mapOf(
                        "parts" to listOf(
                            mapOf(
                                "text" to "Please transcribe this audio recording completely. Provide only the transcription, nothing else."
                            ),
                            mapOf(
                                "inline_data" to mapOf(
                                    "mime_type" to "audio/3gpp",
                                    "data" to audioBase64
                                )
                            )
                        )
                    )
                )
            ))
            
            val request = okhttp3.Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaType(),
                    requestBody
                ))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                return "Error: Unable to transcribe audio"
            }
            
            val jsonResponse = org.json.JSONObject(responseBody)
            val candidates = jsonResponse.getJSONArray("candidates")
            if (candidates.length() > 0) {
                val content = candidates.getJSONObject(0).getJSONObject("content")
                val parts = content.getJSONArray("parts")
                if (parts.length() > 0) {
                    return parts.getJSONObject(0).getString("text")
                }
            }
            
            return "Transcription failed."
        } catch (e: Exception) {
            Log.e(TAG, "Gemini fallback failed: ${e.message}", e)
            return "Error transcribing audio"
        }
    }
    
    private suspend fun generateMeetingSummary(transcript: String): String = withContext(Dispatchers.IO) {
        try {
            val apiKey = RemoteConfigManager.geminiApiKey
            val client = okhttp3.OkHttpClient()
            
            val activeMeeting = meetingManager.getActiveMeeting()
            val speakerInfo = if (activeMeeting != null && activeMeeting.speakerCount > 0) {
                "\\n\\n📊 Speakers Detected: ${activeMeeting.speakerCount}"
            } else {
                ""
            }
            
            val prompt = """
                Please provide a concise meeting summary based on this transcript:
                
                $transcript
                
                Include:
                1. Key Discussion Points (bullet points)
                2. Decisions Made (if any)
                3. Action Items (if any)
                4. Important Notes$speakerInfo
                
                Keep it clear and professional.
            """.trimIndent()
            
            val requestBody = com.google.gson.Gson().toJson(mapOf(
                "contents" to listOf(
                    mapOf("parts" to listOf(mapOf("text" to prompt)))
                )
            ))
            
            val request = okhttp3.Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey")
                .post(okhttp3.RequestBody.create(
                    "application/json".toMediaType(),
                    requestBody
                ))
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                Log.e(TAG, "Gemini API error: $responseBody")
                return@withContext "Error generating summary. Transcript saved."
            }
            
            val jsonResponse = org.json.JSONObject(responseBody)
            val candidates = jsonResponse.getJSONArray("candidates")
            if (candidates.length() > 0) {
                val content = candidates.getJSONObject(0).getJSONObject("content")
                val parts = content.getJSONArray("parts")
                if (parts.length() > 0) {
                    return@withContext parts.getJSONObject(0).getString("text")
                }
            }
            
            "Summary generation failed. Transcript saved."
        } catch (e: Exception) {
            Log.e(TAG, "Error generating summary: ${e.message}", e)
            "Error generating summary: ${e.message}"
        }
    }
    
    private fun showSummaryDialog(meeting: MeetingMinute) {
        val speakerInfo = if (meeting.speakerCount > 0) {
            val peopleText = if (meeting.speakerCount == 1) "1 person" else "${meeting.speakerCount} people"
            val stats = meeting.getSpeakerStats()
            val totalWords = stats.values.sum()
            val statsText = StringBuilder("\n👥 Participants: $peopleText detected\n")
            
            if (totalWords > 0 && stats.isNotEmpty()) {
                statsText.append("\n🗣️ What Each Person Said:\n")
                stats.entries.sortedByDescending { it.value }.forEach { (speaker, words) ->
                    val percentage = (words * 100 / totalWords)
                    statsText.append("   Person $speaker: $percentage% ($words words)\n")
                }
            }
            statsText.toString()
        } else {
            ""
        }
        
        val message = """
            ✅ Meeting Saved Successfully!
            
            📅 ${meeting.getFormattedStartTime()}
            ⏱️ Duration: ${meeting.getDuration()}
            📝 Words: ${meeting.getWordCount()}$speakerInfo
            
            ━━━━━━━━━ SUMMARY ━━━━━━━━━
            ${meeting.summary?.ifBlank { "No summary available" } ?: "No summary"}
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("📋 " + meeting.title)
            .setMessage(message)
            .setPositiveButton("View All Meetings") { _, _ ->
                val intent = Intent(this, MeetingMinutesActivity::class.java)
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Close") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    private fun startDurationTimer() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                updateDuration()
                handler.postDelayed(this, 1000) // Update every second
            }
        }, 1000)
    }
    
    private fun updateDuration() {
        currentMeeting?.let { meeting ->
            val durationMs = System.currentTimeMillis() - meeting.startTime
            val minutes = (durationMs / 1000 / 60).toInt()
            val seconds = ((durationMs / 1000) % 60).toInt()
            tvDuration.text = String.format("%02d :\n%02d", minutes, seconds)
        }
    }
    
    private fun updateLiveParticipantsDisplay(speakerCount: Int) {
        if (speakerCount > 0) {
            tvLiveParticipants.visibility = View.VISIBLE
            val participantsText = if (speakerCount == 1) {
                "👤 1 person detected in meeting"
            } else {
                "👥 $speakerCount people detected in meeting"
            }
            tvLiveParticipants.text = participantsText
        } else {
            tvLiveParticipants.visibility = View.GONE
        }
    }
    
    private fun getFormattedDateTime(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())
        return sdf.format(Date())
    }
    
    override fun onBackPressed() {
        confirmEndMeeting()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        abandonAudioFocus()
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recorder: ${e.message}")
        }
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
    
    companion object {
        const val EXTRA_MEETING_TITLE = "meeting_title"
        private const val REQUEST_RECORD_AUDIO = 300
    }
}
