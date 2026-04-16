package com.sdk.glassessdksample.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R

/**
 * Adapter for displaying Meeting Minutes in a RecyclerView
 */
class MeetingMinutesAdapter(
    private var meetings: List<MeetingMinute>,
    private val onView: (MeetingMinute) -> Unit,
    private val onDelete: (MeetingMinute) -> Unit
) : RecyclerView.Adapter<MeetingMinutesAdapter.MeetingViewHolder>() {

    inner class MeetingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tv_meeting_title)
        val tvDate: TextView = itemView.findViewById(R.id.tv_meeting_date)
        val tvDuration: TextView = itemView.findViewById(R.id.tv_meeting_duration)
        val tvSummaryPreview: TextView = itemView.findViewById(R.id.tv_summary_preview)
        val tvWordCount: TextView = itemView.findViewById(R.id.tv_word_count)
        val btnView: TextView = itemView.findViewById(R.id.btn_view_meeting)
        val btnDelete: TextView = itemView.findViewById(R.id.btn_delete_meeting)
        val cardMeeting: View = itemView.findViewById(R.id.card_meeting)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeetingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_meeting_minute, parent, false)
        return MeetingViewHolder(view)
    }

    override fun onBindViewHolder(holder: MeetingViewHolder, position: Int) {
        val meeting = meetings[position]
        
        holder.tvTitle.text = meeting.title
        holder.tvDate.text = meeting.getFormattedDate()
        holder.tvDuration.text = meeting.getDuration()
        
        // Show speaker info if available
        val wordCountText = if (meeting.speakerCount > 0) {
            val peopleText = if (meeting.speakerCount == 1) "1 person" else "${meeting.speakerCount} people"
            "📝 ${meeting.getWordCount()} words | 👥 $peopleText"
        } else {
            "📝 ${meeting.getWordCount()} words"
        }
        holder.tvWordCount.text = wordCountText
        
        // Summary preview - show first 120 characters
        val summaryPreview = if (meeting.summary.isNotBlank()) {
            meeting.summary.take(120) + if (meeting.summary.length > 120) "..." else ""
        } else {
            "No summary available"
        }
        holder.tvSummaryPreview.text = summaryPreview
        
        // Click handlers
        holder.cardMeeting.setOnClickListener { onView(meeting) }
        holder.btnView.setOnClickListener { onView(meeting) }
        holder.btnDelete.setOnClickListener { onDelete(meeting) }
    }

    override fun getItemCount(): Int = meetings.size
    
    fun updateMeetings(newMeetings: List<MeetingMinute>) {
        meetings = newMeetings
        notifyDataSetChanged()
    }
}
