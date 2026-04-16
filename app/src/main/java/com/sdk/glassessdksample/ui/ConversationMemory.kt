package com.sdk.glassessdksample.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.*

/**
 * ConversationMemory - Multi-modal context awareness engine
 * 
 * Tracks all images shared in conversations and their AI descriptions,
 * enabling the AI to reference previous images contextually.
 * 
 * Features:
 * - Remembers all images with descriptions across sessions
 * - Builds rich context prompts including image history
 * - Detects when user refers to previous images ("like the one I showed you")
 * - Provides conversation summary for Gemini API context window
 */
class ConversationMemory(private val context: Context) {

    companion object {
        private const val TAG = "ConversationMemory"
        private const val PREFS_NAME = "IMI_CONVERSATION_MEMORY"
        private const val KEY_IMAGE_HISTORY = "image_history"
        private const val KEY_CONVERSATION_CONTEXT = "conversation_context"
        private const val MAX_IMAGE_HISTORY = 50  // Keep last 50 images
        private const val MAX_CONTEXT_MESSAGES = 30  // Keep last 30 messages for context
        
        // Keywords that indicate user is referring to a previous image
        private val IMAGE_REFERENCE_KEYWORDS = listOf(
            "like the one", "that image", "the picture", "what i showed you",
            "earlier image", "previous image", "last image", "the photo",
            "remember the", "same image", "that one", "the one i showed",
            "go back to", "that thing", "what was that", "show me again",
            "pehle wali", "woh photo", "pichhli image", "woh image",
            "jo dikhaya tha", "yaad hai", "wahi image", "pehle jo"
        )
        
        // Keywords that suggest user wants to capture a new image
        val VISION_TRIGGER_KEYWORDS = listOf(
            "what is in front", "what do you see", "look at this",
            "take a picture", "capture", "show me", "what's this",
            "what is this", "describe what", "tell me what you see",
            "can you see", "identify this", "what am i looking at",
            "kya hai ye", "yeh kya hai", "dekho", "photo lo",
            "samne kya hai", "dikhao", "batao kya dikh raha",
            "chimi", "see this", "analyze this", "scan this"
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // In-memory cache
    private val imageHistory = mutableListOf<ImageMemory>()
    private val recentMessages = mutableListOf<ContextMessage>()
    
    init {
        loadFromDisk()
    }

    /**
     * Record an image that was shared in conversation
     */
    fun recordImage(
        imagePath: String,
        aiDescription: String,
        userQuery: String? = null,
        conversationId: String? = null
    ): String {
        val imageMemory = ImageMemory(
            id = UUID.randomUUID().toString().take(8),
            imagePath = imagePath,
            description = aiDescription,
            userQuery = userQuery ?: "",
            conversationId = conversationId ?: "",
            timestamp = System.currentTimeMillis()
        )
        
        imageHistory.add(0, imageMemory)
        
        // Trim to max size
        while (imageHistory.size > MAX_IMAGE_HISTORY) {
            imageHistory.removeAt(imageHistory.size - 1)
        }
        
        saveToDisk()
        Log.d(TAG, "Recorded image: ${imageMemory.id} - ${aiDescription.take(50)}")
        return imageMemory.id
    }
    
    /**
     * Record a message for context tracking
     */
    fun recordMessage(text: String, isFromUser: Boolean, hasImage: Boolean = false, imageId: String? = null) {
        recentMessages.add(ContextMessage(
            text = text,
            isFromUser = isFromUser,
            hasImage = hasImage,
            imageId = imageId,
            timestamp = System.currentTimeMillis()
        ))
        
        // Trim to max
        while (recentMessages.size > MAX_CONTEXT_MESSAGES) {
            recentMessages.removeAt(0)
        }
        
        saveToDisk()
    }
    
    /**
     * Check if user message references a previous image
     */
    fun detectImageReference(userMessage: String): ImageMemory? {
        val lowerMessage = userMessage.lowercase()
        
        // Check if user is referencing a previous image
        val isReferencing = IMAGE_REFERENCE_KEYWORDS.any { keyword ->
            lowerMessage.contains(keyword)
        }
        
        if (isReferencing && imageHistory.isNotEmpty()) {
            // Return the most recent image by default
            // Could be made smarter with NLP later
            Log.d(TAG, "User referenced previous image: ${imageHistory[0].id}")
            return imageHistory[0]
        }
        
        return null
    }
    
    /**
     * Check if user message is requesting vision/image capture
     */
    fun isVisionRequest(userMessage: String): Boolean {
        val lowerMessage = userMessage.lowercase()
        return VISION_TRIGGER_KEYWORDS.any { keyword ->
            lowerMessage.contains(keyword)
        }
    }
    
    /**
     * Build a context-rich prompt for the AI including image history
     */
    fun buildContextPrompt(currentMessage: String): String {
        val sb = StringBuilder()
        
        // Add image history context
        if (imageHistory.isNotEmpty()) {
            sb.appendLine("\n[VISUAL CONTEXT - Images the user has shared previously:]")
            
            val recentImages = imageHistory.take(5) // Last 5 images
            recentImages.forEachIndexed { index, img ->
                val timeAgo = getTimeAgo(img.timestamp)
                sb.appendLine("  Image ${index + 1} ($timeAgo): ${img.description.take(200)}")
                if (img.userQuery.isNotEmpty()) {
                    sb.appendLine("    User asked: \"${img.userQuery}\"")
                }
            }
            sb.appendLine("[End of visual context]\n")
        }
        
        // Add recent conversation context
        if (recentMessages.isNotEmpty()) {
            sb.appendLine("[RECENT CONVERSATION:]")
            val recent = recentMessages.takeLast(10)
            recent.forEach { msg ->
                val role = if (msg.isFromUser) "User" else "AI"
                val imageNote = if (msg.hasImage) " [with image]" else ""
                sb.appendLine("  $role$imageNote: ${msg.text.take(150)}")
            }
            sb.appendLine("[End of conversation context]\n")
        }
        
        sb.appendLine("Current user message: $currentMessage")
        
        return sb.toString()
    }
    
    /**
     * Build a vision-specific prompt that includes previous image context
     */
    fun buildVisionContextPrompt(userQuery: String, referencedImage: ImageMemory? = null): String {
        val sb = StringBuilder()
        
        if (referencedImage != null) {
            sb.appendLine("The user is referring to a previous image they showed you.")
            sb.appendLine("Previous image description: ${referencedImage.description}")
            sb.appendLine("They originally asked: \"${referencedImage.userQuery}\"")
            sb.appendLine()
        }
        
        if (imageHistory.size > 1) {
            sb.appendLine("Context: User has shared ${imageHistory.size} images in total.")
            sb.appendLine("Most recent was: ${imageHistory[0].description.take(100)}")
            sb.appendLine()
        }
        
        sb.appendLine(userQuery)
        
        return sb.toString()
    }
    
    /**
     * Get a specific image from history by ID
     */
    fun getImage(imageId: String): ImageMemory? {
        return imageHistory.find { it.id == imageId }
    }
    
    /**
     * Get the most recently shared image
     */
    fun getLastImage(): ImageMemory? {
        return imageHistory.firstOrNull()
    }
    
    /**
     * Get all images in current session
     */
    fun getImageHistory(): List<ImageMemory> = imageHistory.toList()
    
    /**
     * Clear all memory
     */
    fun clearMemory() {
        imageHistory.clear()
        recentMessages.clear()
        saveToDisk()
    }
    
    private fun getTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000}m ago"
            diff < 86_400_000 -> "${diff / 3_600_000}h ago"
            else -> {
                val sdf = SimpleDateFormat("MMM dd", Locale.US)
                sdf.format(Date(timestamp))
            }
        }
    }
    
    private fun loadFromDisk() {
        try {
            val imageJson = prefs.getString(KEY_IMAGE_HISTORY, null)
            if (imageJson != null) {
                val type = object : TypeToken<List<ImageMemory>>() {}.type
                val loaded = gson.fromJson<List<ImageMemory>>(imageJson, type)
                imageHistory.clear()
                imageHistory.addAll(loaded)
            }
            
            val contextJson = prefs.getString(KEY_CONVERSATION_CONTEXT, null)
            if (contextJson != null) {
                val type = object : TypeToken<List<ContextMessage>>() {}.type
                val loaded = gson.fromJson<List<ContextMessage>>(contextJson, type)
                recentMessages.clear()
                recentMessages.addAll(loaded)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading memory", e)
        }
    }
    
    private fun saveToDisk() {
        try {
            prefs.edit()
                .putString(KEY_IMAGE_HISTORY, gson.toJson(imageHistory))
                .putString(KEY_CONVERSATION_CONTEXT, gson.toJson(recentMessages))
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving memory", e)
        }
    }
}

/**
 * Represents a single image in conversation memory
 */
data class ImageMemory(
    val id: String,
    val imagePath: String,
    val description: String,
    val userQuery: String,
    val conversationId: String,
    val timestamp: Long
)

/**
 * Lightweight message for context tracking
 */
data class ContextMessage(
    val text: String,
    val isFromUser: Boolean,
    val hasImage: Boolean = false,
    val imageId: String? = null,
    val timestamp: Long
)
