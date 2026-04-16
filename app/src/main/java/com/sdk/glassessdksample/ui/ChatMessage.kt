package com.sdk.glassessdksample.ui

/**
 * Message types for unified chat
 */
enum class MessageType {
    TEXT,           // Normal text message
    IMAGE,          // Image message (from glasses capture)
    IMAGE_WITH_TEXT,// Image + AI analysis text
    SYSTEM,         // System messages (e.g., "Image captured", "Analyzing...")
    VISION_RESULT   // AI vision analysis result
}

/**
 * Data class representing a single chat message in the unified conversation
 * Supports text, images, and multi-modal content seamlessly
 * 
 * @param text The message text content
 * @param isFromUser True if this message is from the user, false if from AI
 * @param isFinal True if this is the final version of the message (not a partial/streaming update)
 * @param timestamp The time when the message was created
 * @param imagePath Local file path of an attached image (null if text-only)
 * @param messageType Type of message for rendering
 * @param imageDescription AI-generated description of the image (for context memory)
 * @param referencedImageId ID of a previously shared image being referenced
 */
data class ChatMessage(
    var text: String,
    val isFromUser: Boolean,
    var isFinal: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    var imagePath: String? = null,
    var messageType: MessageType = MessageType.TEXT,
    var imageDescription: String? = null,
    var referencedImageId: String? = null
) {
    // Unique ID for DiffUtil
    val id: String = "${timestamp}_${if (isFromUser) "user" else "ai"}_${System.nanoTime()}"
    
    /** Check if this message has an image */
    fun hasImage(): Boolean = imagePath != null
    
    /** Check if this is a vision-related message */
    fun isVisionMessage(): Boolean = messageType == MessageType.IMAGE || 
        messageType == MessageType.IMAGE_WITH_TEXT || 
        messageType == MessageType.VISION_RESULT
}
