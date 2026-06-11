package com.sdk.glassessdksample.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R

/**
 * Activity for viewing and managing Meeting Minutes
 */
class MeetingMinutesActivity : AppCompatActivity() {

    private lateinit var meetingManager: MeetingMinutesManager
    private lateinit var meetingsAdapter: MeetingMinutesAdapter
    private lateinit var rvMeetings: RecyclerView
    private lateinit var btnStartMic: ImageView
    private lateinit var btnBack: ImageView
    private lateinit var layoutEmptyState: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meeting_minutes)

        meetingManager = MeetingMinutesManager(this)

        setupUI()
        loadMeetings()
    }

    private fun setupUI() {
        rvMeetings = findViewById(R.id.rv_meetings)
        btnStartMic = findViewById(R.id.btn_start_mic)
        btnBack = findViewById(R.id.btn_back)
        layoutEmptyState = findViewById(R.id.layout_empty_state)

        btnBack.setOnClickListener { finish() }

        // Setup RecyclerView
        meetingsAdapter = MeetingMinutesAdapter(
            meetings = emptyList(),
            onView = { meeting -> showMeetingDetails(meeting) },
            onDelete = { meeting -> confirmDeleteMeeting(meeting) }
        )

        rvMeetings.apply {
            layoutManager = LinearLayoutManager(this@MeetingMinutesActivity)
            adapter = meetingsAdapter
        }

        // Mic button to start a new meeting
        btnStartMic.setOnClickListener {
            promptMeetingTitle()
        }
    }

    private fun loadMeetings() {
        val meetings = meetingManager.getAllMeetings()
        meetingsAdapter.updateMeetings(meetings)
        
        // Show/hide empty state
        if (meetings.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            rvMeetings.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            rvMeetings.visibility = View.VISIBLE
        }
    }

    private fun promptMeetingTitle() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_meeting_title, null)
        val input = dialogView.findViewById<EditText>(R.id.et_meeting_title)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btn_cancel)
        val btnStart = dialogView.findViewById<TextView>(R.id.btn_start)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnStart.setOnClickListener {
            val title = input.text.toString().trim()
            dialog.dismiss()
            showLiveTranscriptInfo(title)
        }

        dialog.show()
    }

    /** "Live Transcript" speaker-detection info screen shown before recording begins */
    private fun showLiveTranscriptInfo(title: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_live_transcript_info, null)
        val btnContinue = dialogView.findViewById<TextView>(R.id.btn_continue)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnContinue.setOnClickListener {
            dialog.dismiss()
            startNewMeeting(title)
        }

        dialog.show()
    }

    private fun startNewMeeting(title: String) {
        val intent = Intent(this, ActiveMeetingActivity::class.java)
        intent.putExtra(ActiveMeetingActivity.EXTRA_MEETING_TITLE, title)
        startActivity(intent)
    }

    private fun showMeetingDetails(meeting: MeetingMinute) {
        // Inflate custom dialog layout
        val dialogView = layoutInflater.inflate(R.layout.dialog_meeting_details, null)
        
        // Get views from dialog
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_title)
        val tvDate = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_date)
        val tvDuration = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_duration)
        val tvWordCount = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_word_count)
        val tvSpeakerCount = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_speaker_count)
        val layoutSpeakerCount = dialogView.findViewById<android.widget.LinearLayout>(R.id.layout_speaker_count)
        val tvSummary = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_summary)
        val tvTranscript = dialogView.findViewById<android.widget.TextView>(R.id.tv_dialog_transcript)
        val layoutTranscript = dialogView.findViewById<android.widget.LinearLayout>(R.id.layout_transcript_section)
        val btnToggleTranscript = dialogView.findViewById<android.widget.TextView>(R.id.btn_toggle_transcript)
        val btnClose = dialogView.findViewById<android.widget.TextView>(R.id.btn_dialog_close)
        val btnDelete = dialogView.findViewById<android.widget.TextView>(R.id.btn_dialog_delete)
        
        // Set meeting data
        tvTitle.text = meeting.title
        tvDate.text = meeting.getFormattedStartTime()
        tvDuration.text = "Duration: ${meeting.getDuration()}"
        tvWordCount.text = "Words: ${meeting.getWordCount()}"
        
        // Show speaker count if available
        if (meeting.speakerCount > 0) {
            layoutSpeakerCount.visibility = View.VISIBLE
            val participantsText = if (meeting.speakerCount == 1) {
                "👤 Participants: 1 person detected"
            } else {
                "👥 Participants: ${meeting.speakerCount} people in meeting"
            }
            tvSpeakerCount.text = participantsText
        } else {
            layoutSpeakerCount.visibility = View.GONE
        }
        
        // Add speaker stats to summary if available
        val summaryWithStats = if (meeting.speakerCount > 0 && meeting.speakerTranscript.isNotBlank()) {
            val stats = meeting.getSpeakerStats()
            val statsText = StringBuilder()
            val totalWords = stats.values.sum()
            
            if (totalWords > 0) {
                statsText.append("\n\n━━━ WHO SPOKE IN THIS MEETING ━━━\n\n")
                stats.entries.sortedByDescending { it.value }.forEach { (speaker, words) ->
                    val percentage = (words * 100 / totalWords)
                    statsText.append("🗣️ Speaker $speaker: $words words ($percentage% of conversation)\n")
                }
                statsText.append("\n━━━━━━━━━━━━━━━━━━━━━━━\n")
            }
            
            (meeting.summary.ifBlank { "No summary available" }) + statsText.toString()
        } else {
            meeting.summary.ifBlank { "No summary available" }
        }
        
        tvSummary.text = summaryWithStats
        
        // Use speaker transcript if available, otherwise plain transcript
        val transcriptText = if (meeting.speakerTranscript.isNotBlank()) {
            // Format transcript with better spacing
            val formatted = meeting.speakerTranscript
                .replace("\n\n", "\n")
                .replace("Speaker ", "\n\n🗣️ Speaker ")
                .trim()
            "\n" + formatted
        } else {
            meeting.transcript.ifBlank { "No transcript" }
        }
        tvTranscript.text = transcriptText
        
        // Create dialog
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        
        // Make dialog background transparent so our custom layout shows through
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // Toggle transcript visibility
        var transcriptVisible = false
        btnToggleTranscript.setOnClickListener {
            transcriptVisible = !transcriptVisible
            if (transcriptVisible) {
                layoutTranscript.visibility = View.VISIBLE
                btnToggleTranscript.text = "▲ Hide Transcript"
            } else {
                layoutTranscript.visibility = View.GONE
                btnToggleTranscript.text = "▼ Show Transcript"
            }
        }
        
        // Close button
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        // Delete button
        btnDelete.setOnClickListener {
            dialog.dismiss()
            confirmDeleteMeeting(meeting)
        }
        
        dialog.show()
    }

    private fun confirmDeleteMeeting(meeting: MeetingMinute) {
        AlertDialog.Builder(this)
            .setTitle("Delete Meeting?")
            .setMessage("Are you sure you want to delete \"${meeting.title}\"?")
            .setPositiveButton("Delete") { _, _ ->
                meetingManager.deleteMeeting(meeting.id)
                Toast.makeText(this, "Meeting deleted", Toast.LENGTH_SHORT).show()
                loadMeetings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        loadMeetings() // Refresh meetings when returning to this screen
    }
}
