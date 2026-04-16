package com.sdk.glassessdksample.ui

/**
 * Data class representing a conversation in the chat drawer
 * Now supports unified chat with images and vision context
 * 
 * @param id Unique identifier for the conversation
 * @param title Title/name of the conversation
 * @param preview Preview of the last message
 * @param timestamp Last message timestamp
 * @param messages List of messages in this conversation
 * @param imageIds List of image memory IDs referenced in this conversation
 * @param hasVisionContent Whether this conversation includes vision/image content
 */
data class Conversation(
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String,
    var preview: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val imageIds: MutableList<String> = mutableListOf(),
    var hasVisionContent: Boolean = false
) {
    /**
     * Get a human-readable time string
     */
    fun getTimeString(): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60000 -> "Just now"
            diff < 3600000 -> "${diff / 60000} minutes ago"
            diff < 86400000 -> "${diff / 3600000} hours ago"
            diff < 604800000 -> "${diff / 86400000} days ago"
            else -> "${diff / 604800000} weeks ago"
        }
    }
    
    /**
     * Update preview from last message
     */
    fun updatePreview() {
        if (messages.isNotEmpty()) {
            val lastMsg = messages.last()
            val prefix = when {
                lastMsg.hasImage() -> "📸 "
                lastMsg.messageType == MessageType.VISION_RESULT -> "👁️ "
                lastMsg.messageType == MessageType.SYSTEM -> "ℹ️ "
                else -> ""
            }
            preview = prefix + lastMsg.text.take(50) + if (lastMsg.text.length > 50) "..." else ""
        }
    }
    
    /**
     * Get count of images in this conversation
     */
    fun getImageCount(): Int = messages.count { it.hasImage() }
    
    /**
     * Build conversation history string for AI context
     */
    fun buildHistoryForAI(maxMessages: Int = 15): String {
        return messages.takeLast(maxMessages).joinToString("\n") { msg ->
            val role = if (msg.isFromUser) "User" else "AI"
            val imageNote = if (msg.hasImage()) " [shared an image]" else ""
            val imageDesc = if (msg.imageDescription != null) " [Image: ${msg.imageDescription}]" else ""
            "$role$imageNote$imageDesc: ${msg.text}"
        }
    }
}
