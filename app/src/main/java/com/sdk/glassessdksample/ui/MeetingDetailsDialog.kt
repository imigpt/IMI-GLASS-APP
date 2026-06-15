package com.sdk.glassessdksample.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.sdk.glassessdksample.R

/**
 * Shared meeting-details dialog used by both the Meeting Minutes list and the
 * "View All" screen. Shows title, info rows, summary and a collapsible transcript.
 */
object MeetingDetailsDialog {

    fun show(context: Context, meeting: MeetingMinute, onDelete: (MeetingMinute) -> Unit) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_meeting_details, null)

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

        tvTitle.text = "Title: ${meeting.title}"
        tvDate.text = meeting.getFormattedStartTime()
        tvDuration.text = "Duration: ${meeting.getDuration()}"
        tvWordCount.text = "Words: ${meeting.getWordCount()}"

        if (meeting.speakerCount > 0) {
            layoutSpeakerCount.visibility = View.VISIBLE
            tvSpeakerCount.text = if (meeting.speakerCount == 1) {
                "👤 Participants: 1 person detected"
            } else {
                "👥 Participants: ${meeting.speakerCount} people in meeting"
            }
        } else {
            layoutSpeakerCount.visibility = View.GONE
        }

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

        val transcriptText = if (meeting.speakerTranscript.isNotBlank()) {
            val formatted = meeting.speakerTranscript
                .replace("\n\n", "\n")
                .replace("Speaker ", "\n\n🗣️ Speaker ")
                .trim()
            "\n" + formatted
        } else {
            meeting.transcript.ifBlank { "No transcript" }
        }
        tvTranscript.text = transcriptText

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        var transcriptVisible = false
        btnToggleTranscript.setOnClickListener {
            transcriptVisible = !transcriptVisible
            if (transcriptVisible) {
                layoutTranscript.visibility = View.VISIBLE
                btnToggleTranscript.text = "Hide Transcript"
            } else {
                layoutTranscript.visibility = View.GONE
                btnToggleTranscript.text = "Show Transcript"
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        btnDelete.setOnClickListener {
            dialog.dismiss()
            onDelete(meeting)
        }

        dialog.show()
    }
}
