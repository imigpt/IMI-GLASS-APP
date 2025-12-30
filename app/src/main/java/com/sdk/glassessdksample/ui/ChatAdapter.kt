package com.sdk.glassessdksample.ui

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R

/**
 * Adapter for displaying chat messages in a RecyclerView
 * Supports streaming/partial updates and differentiates between user and AI messages
 */
class ChatAdapter(
    private val messages: MutableList<ChatMessage>
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageCard: CardView = itemView.findViewById(R.id.message_card)
        val messageText: TextView = itemView.findViewById(R.id.message_text)
        val containerLayout: LinearLayout = itemView.findViewById(R.id.message_container)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]
        
        holder.messageText.text = message.text
        
        // Style based on message source
        if (message.isFromUser) {
            // User message - align right, blue background
            holder.containerLayout.gravity = Gravity.END
            holder.messageCard.setCardBackgroundColor(0xFF2196F3.toInt()) // Blue
            holder.messageText.setTextColor(0xFFFFFFFF.toInt()) // White text
        } else {
            // AI message - align left, green background
            holder.containerLayout.gravity = Gravity.START
            holder.messageCard.setCardBackgroundColor(0xFF4CAF50.toInt()) // Green
            holder.messageText.setTextColor(0xFFFFFFFF.toInt()) // White text
        }
        
        // Visual indicator for partial/streaming messages
        if (!message.isFinal) {
            holder.messageCard.alpha = 0.7f // Slightly transparent for partial messages
        } else {
            holder.messageCard.alpha = 1.0f
        }
    }

    override fun getItemCount(): Int = messages.size
    
    /**
     * Add a new message to the chat
     */
    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
    
    /**
     * Update the last message (for streaming updates)
     * Returns true if update was successful, false if no messages exist
     */
    fun updateLastMessage(text: String, isFinal: Boolean, isFromUser: Boolean): Boolean {
        if (messages.isEmpty()) return false
        
        val lastMessage = messages.last()
        // Only update if it's the same sender
        if (lastMessage.isFromUser == isFromUser) {
            lastMessage.text = text
            lastMessage.isFinal = isFinal
            notifyItemChanged(messages.size - 1)
            return true
        }
        return false
    }
    
    /**
     * Clear all messages
     */
    fun clearMessages() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }
}
