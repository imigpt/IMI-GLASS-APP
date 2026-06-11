package com.sdk.glassessdksample.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R

/**
 * Renders a single conversation as chat bubbles for the History thread view.
 * Each entry is (isUser, text); user turns render as orange right-aligned bubbles,
 * AI turns as dark left-aligned bubbles.
 */
class HistoryBubbleAdapter(
    private var messages: List<Pair<Boolean, String>>
) : RecyclerView.Adapter<HistoryBubbleAdapter.BubbleViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_AI = 1
    }

    class BubbleViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView.findViewById(R.id.tv_bubble_text)
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].first) TYPE_USER else TYPE_AI

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BubbleViewHolder {
        val layout = if (viewType == TYPE_USER) {
            R.layout.item_history_bubble_user
        } else {
            R.layout.item_history_bubble_ai
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return BubbleViewHolder(view)
    }

    override fun onBindViewHolder(holder: BubbleViewHolder, position: Int) {
        holder.text.text = messages[position].second
    }

    override fun getItemCount(): Int = messages.size

    fun update(newMessages: List<Pair<Boolean, String>>) {
        messages = newMessages
        notifyDataSetChanged()
    }
}
