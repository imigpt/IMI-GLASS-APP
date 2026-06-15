package com.sdk.glassessdksample.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R

/**
 * Adapter for the "View All" Meeting Minutes screen.
 * Card layout shows title, date/duration, word count and View / Delete actions.
 */
class AllMeetingMinutesAdapter(
    private var meetings: List<MeetingMinute>,
    private val onView: (MeetingMinute) -> Unit,
    private val onDelete: (MeetingMinute) -> Unit
) : RecyclerView.Adapter<AllMeetingMinutesAdapter.MeetingViewHolder>() {

    inner class MeetingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tv_meeting_title)
        val tvDate: TextView = itemView.findViewById(R.id.tv_meeting_date)
        val tvDuration: TextView = itemView.findViewById(R.id.tv_meeting_duration)
        val tvWordCount: TextView = itemView.findViewById(R.id.tv_word_count)
        val btnView: TextView = itemView.findViewById(R.id.btn_view_meeting)
        val btnDelete: TextView = itemView.findViewById(R.id.btn_delete_meeting)
        val cardMeeting: View = itemView.findViewById(R.id.card_meeting)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MeetingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_all_meeting_minute, parent, false)
        return MeetingViewHolder(view)
    }

    override fun onBindViewHolder(holder: MeetingViewHolder, position: Int) {
        val meeting = meetings[position]

        holder.tvTitle.text = meeting.title
        holder.tvDate.text = meeting.getFormattedDate()
        holder.tvDuration.text = meeting.getDuration()
        holder.tvWordCount.text = "Words: ${meeting.getWordCount()} words"

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
