package com.sdk.glassessdksample.ui

/**
 * Data class representing a single chat message in the conversation overlay
 * 
 * @param text The message text content
 * @param isFromUser True if this message is from the user, false if from AI
 * @param isFinal True if this is the final version of the message (not a partial/streaming update)
 * @param timestamp The time when the message was created
 */
data class ChatMessage(
    var text: String,
    val isFromUser: Boolean,
    var isFinal: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    // Unique ID for DiffUtil
    val id: String = "${timestamp}_${if (isFromUser) "user" else "ai"}_${System.nanoTime()}"
}
