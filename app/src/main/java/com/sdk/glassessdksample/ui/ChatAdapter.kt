package com.sdk.glassessdksample.ui

import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Unified Chat Adapter - Seamlessly renders text messages, images, and vision results
 * in a single continuous conversation thread.
 * 
 * Supports:
 * - Text messages (user/AI)
 * - Inline image display from glasses capture
 * - Image + text combined messages (vision analysis results)
 * - System messages
 * - Streaming/partial message updates
 * - Image reference badges
 */
class ChatAdapter(
    private val messages: MutableList<ChatMessage>,
    private val onImageClick: ((ChatMessage) -> Unit)? = null
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageCard: View = itemView.findViewById(R.id.message_card)
        val messageText: TextView = itemView.findViewById(R.id.message_text)
        val containerLayout: LinearLayout = itemView.findViewById(R.id.message_container)
        val aiAvatar: TextView? = itemView.findViewById(R.id.aiAvatar)
        // New unified views (may be null for old layout)
        val imageContainer: FrameLayout? = itemView.findViewById(R.id.imageContainer)
        val messageImage: ImageView? = itemView.findViewById(R.id.messageImage)
        val imageBadge: TextView? = itemView.findViewById(R.id.imageBadge)
        val messageTimestamp: TextView? = itemView.findViewById(R.id.messageTimestamp)
        val imageRefBadge: TextView? = itemView.findViewById(R.id.imageRefBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_unified_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val message = messages[position]
        val context = holder.itemView.context
        
        // --- Set text ---
        if (message.text.isNotEmpty()) {
            holder.messageText.visibility = View.VISIBLE
            holder.messageText.text = message.text
        } else {
            holder.messageText.visibility = View.GONE
        }
        
        // --- Set timestamp ---
        holder.messageTimestamp?.text = timeFormat.format(Date(message.timestamp))
        
        // --- Handle image display ---
        if (message.hasImage() && holder.imageContainer != null && holder.messageImage != null) {
            holder.imageContainer.visibility = View.VISIBLE
            
            try {
                val imgFile = File(message.imagePath!!)
                if (imgFile.exists()) {
                    // Load image efficiently
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 2  // Scale down for memory efficiency
                    }
                    val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath, options)
                    holder.messageImage.setImageBitmap(bitmap)
                    
                    // Show badge for glasses captures
                    if (message.messageType == MessageType.IMAGE || message.messageType == MessageType.IMAGE_WITH_TEXT) {
                        holder.imageBadge?.visibility = View.VISIBLE
                        holder.imageBadge?.text = "From Glasses"
                    } else {
                        holder.imageBadge?.visibility = View.GONE
                    }
                    
                    // Image click listener
                    holder.imageContainer.setOnClickListener {
                        onImageClick?.invoke(message)
                    }
                } else {
                    holder.imageContainer.visibility = View.GONE
                }
            } catch (e: Exception) {
                holder.imageContainer.visibility = View.GONE
            }
        } else {
            holder.imageContainer?.visibility = View.GONE
        }
        
        // --- Handle image reference badge ---
        if (message.referencedImageId != null) {
            holder.imageRefBadge?.visibility = View.VISIBLE
            holder.imageRefBadge?.text = "References earlier image"
        } else {
            holder.imageRefBadge?.visibility = View.GONE
        }

        holder.messageTimestamp?.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        holder.imageRefBadge?.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        holder.messageText.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        holder.messageText.textSize = 14f
        holder.aiAvatar?.visibility = View.GONE
        
        // --- Style based on message type and source ---
        when (message.messageType) {
            MessageType.SYSTEM -> {
                // System message - centered and subtle
                holder.containerLayout.gravity = Gravity.CENTER_HORIZONTAL
                holder.messageCard.setBackgroundResource(R.drawable.bg_chat_bubble_system)
                holder.messageText.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                holder.messageText.textSize = 12f
                holder.aiAvatar?.visibility = View.GONE
            }
            MessageType.VISION_RESULT -> {
                // Vision result - assistant style
                holder.containerLayout.gravity = Gravity.START
                holder.messageCard.setBackgroundResource(R.drawable.bg_chat_bubble_ai)
                holder.aiAvatar?.visibility = View.VISIBLE
                holder.messageText.textSize = 14f
            }
            MessageType.IMAGE, MessageType.IMAGE_WITH_TEXT -> {
                if (message.isFromUser) {
                    holder.containerLayout.gravity = Gravity.END
                    holder.messageCard.setBackgroundResource(R.drawable.bg_chat_bubble_user)
                    holder.aiAvatar?.visibility = View.GONE
                } else {
                    holder.containerLayout.gravity = Gravity.START
                    holder.messageCard.setBackgroundResource(R.drawable.bg_chat_bubble_ai)
                    holder.aiAvatar?.visibility = View.VISIBLE
                }
                holder.messageText.textSize = 14f
            }
            else -> {
                // Normal text message
                if (message.isFromUser) {
                    holder.containerLayout.gravity = Gravity.END
                    holder.messageCard.setBackgroundResource(R.drawable.bg_chat_bubble_user)
                    holder.aiAvatar?.visibility = View.GONE
                } else {
                    holder.containerLayout.gravity = Gravity.START
                    holder.messageCard.setBackgroundResource(R.drawable.bg_chat_bubble_ai)
                    holder.aiAvatar?.visibility = View.VISIBLE
                }
                holder.messageText.textSize = 14f
            }
        }
        
        // --- Visual indicator for partial/streaming messages ---
        if (!message.isFinal) {
            holder.messageCard.alpha = 0.7f
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
     * Add an image message to the chat
     */
    fun addImageMessage(imagePath: String, caption: String = "📸 Image captured", isFromUser: Boolean = true) {
        val message = ChatMessage(
            text = caption,
            isFromUser = isFromUser,
            isFinal = true,
            imagePath = imagePath,
            messageType = MessageType.IMAGE
        )
        addMessage(message)
    }
    
    /**
     * Add a vision analysis result (image + AI text)
     */
    fun addVisionResult(imagePath: String, analysisText: String) {
        val message = ChatMessage(
            text = analysisText,
            isFromUser = false,
            isFinal = true,
            imagePath = imagePath,
            messageType = MessageType.IMAGE_WITH_TEXT
        )
        addMessage(message)
    }
    
    /**
     * Add a system message (centered, subtle)
     */
    fun addSystemMessage(text: String) {
        val message = ChatMessage(
            text = text,
            isFromUser = false,
            isFinal = true,
            messageType = MessageType.SYSTEM
        )
        addMessage(message)
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
     * Update the last AI message with vision result (add image to existing text bubble)
     */
    fun updateLastAIWithImage(imagePath: String, text: String) {
        if (messages.isEmpty()) return
        
        val lastMessage = messages.last()
        if (!lastMessage.isFromUser) {
            lastMessage.text = text
            lastMessage.imagePath = imagePath
            lastMessage.messageType = MessageType.IMAGE_WITH_TEXT
            lastMessage.isFinal = true
            notifyItemChanged(messages.size - 1)
        }
    }
    
    /**
     * Get all messages
     */
    fun getMessages(): List<ChatMessage> = messages.toList()
    
    /**
     * Clear all messages
     */
    fun clearMessages() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }
}
