package com.sdk.glassessdksample.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
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
        val btnView: TextView = itemView.findViewById(R.id.btn_view_meeting)
        val btnMenu: TextView = itemView.findViewById(R.id.btn_meeting_menu)
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
        holder.tvDate.text = meeting.getTimeRange()

        // Click handlers
        holder.cardMeeting.setOnClickListener { onView(meeting) }
        holder.btnView.setOnClickListener { onView(meeting) }

        // Overflow menu: View / Delete
        holder.btnMenu.setOnClickListener { anchor ->
            val popup = PopupMenu(anchor.context, anchor)
            popup.menu.add("View transcript")
            popup.menu.add("Delete")
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "View transcript" -> onView(meeting)
                    "Delete" -> onDelete(meeting)
                }
                true
            }
            popup.show()
        }
    }

    override fun getItemCount(): Int = meetings.size

    fun updateMeetings(newMeetings: List<MeetingMinute>) {
        meetings = newMeetings
        notifyDataSetChanged()
    }
}
