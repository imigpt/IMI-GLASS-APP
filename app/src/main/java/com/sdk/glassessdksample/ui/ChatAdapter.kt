package com.sdk.glassessdksample.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
        // AI action row
        val aiActionRow: LinearLayout? = itemView.findViewById(R.id.aiActionRow)
        val btnCopy: ImageView? = itemView.findViewById(R.id.btnCopyMessage)
        val btnLike: ImageView? = itemView.findViewById(R.id.btnLikeMessage)
        val btnDislike: ImageView? = itemView.findViewById(R.id.btnDislikeMessage)
        val btnShare: ImageView? = itemView.findViewById(R.id.btnShareMessage)
        val btnSearchWeb: ImageView? = itemView.findViewById(R.id.btnSearchWeb)
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
        holder.messageText.textSize = 15f
        holder.aiAvatar?.visibility = View.GONE
        holder.aiActionRow?.visibility = View.GONE

        // Whether this message should render as a plain AI answer (no bubble,
        // with the copy/like/dislike/share action row) per the new design.
        val isAiAnswer = !message.isFromUser &&
            message.isFinal &&
            message.messageType != MessageType.SYSTEM

        // --- Style based on message type and source ---
        when (message.messageType) {
            MessageType.SYSTEM -> {
                // System message - centered and subtle
                holder.containerLayout.gravity = Gravity.CENTER_HORIZONTAL
                holder.messageCard.setBackgroundResource(R.drawable.bg_chat_bubble_system)
                holder.messageText.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                holder.messageText.textSize = 12f
            }
            else -> {
                if (message.isFromUser) {
                    // User: orange bubble, right-aligned
                    holder.containerLayout.gravity = Gravity.END
                    holder.messageCard.setBackgroundResource(R.drawable.bg_chat_bubble_user_v2)
                } else {
                    // AI: plain text, left-aligned, no bubble
                    holder.containerLayout.gravity = Gravity.START
                    holder.messageCard.background = null
                }
            }
        }

        // --- AI answer action row (copy / like / dislike / share) ---
        if (isAiAnswer && message.text.isNotBlank()) {
            holder.aiActionRow?.visibility = View.VISIBLE
            holder.btnCopy?.setOnClickListener { copyToClipboard(context, message.text) }
            holder.btnShare?.setOnClickListener { shareText(context, message.text) }
            holder.btnSearchWeb?.setOnClickListener { searchOnWeb(context, message.text) }
            holder.btnLike?.setOnClickListener {
                Toast.makeText(context, "Thanks for the feedback", Toast.LENGTH_SHORT).show()
            }
            holder.btnDislike?.setOnClickListener {
                Toast.makeText(context, "Thanks — we'll improve", Toast.LENGTH_SHORT).show()
            }
        }
        
        // --- Visual indicator for partial/streaming messages ---
        if (!message.isFinal) {
            holder.messageCard.alpha = 0.7f
        } else {
            holder.messageCard.alpha = 1.0f
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("IMI AI", text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    }

    private fun searchOnWeb(context: Context, text: String) {
        val query = Uri.encode(text.take(500))
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query"))
        context.startActivity(intent)
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
