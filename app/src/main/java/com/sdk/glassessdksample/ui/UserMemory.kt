package com.sdk.glassessdksample.ui

/**
 * Data class representing user's personal memory and preferences
 * This helps the AI remember and personalize responses
 */
data class UserMemory(
    // Personal Information
    val userName: String = "",
    val occupation: String = "",
    val location: String = "",
    
    // Preferences
    val likes: List<String> = emptyList(),  // Things user likes
    val dislikes: List<String> = emptyList(),  // Things user dislikes
    val interests: List<String> = emptyList(),  // User's interests/hobbies
    
    // Communication Style
    val preferredResponseStyle: ResponseStyle = ResponseStyle.BALANCED,
    val preferredLanguage: String = "English",  // English or Hindi
    
    // Conversation Notes
    val importantNotes: List<String> = emptyList(),  // Key facts to remember
    
    // Auto-learned facts from conversations (gathered automatically, no user input needed)
    val autoLearnedFacts: List<String> = emptyList(),
    
    // Conversation statistics – tracked automatically every time the user chats
    val conversationCount: Int = 0,   // how many separate conversations
    val totalMessages: Int = 0,        // total messages sent by the user
    
    // Metadata
    val lastUpdated: Long = System.currentTimeMillis()
) {
    enum class ResponseStyle(val description: String) {
        BRIEF("Keep answers very short and to the point"),
        BALANCED("Provide concise but complete answers"),
        DETAILED("Provide thorough, detailed explanations")
    }
    
    /**
     * Format memory as context for AI prompt
     */
    fun toPromptContext(): String {
        val parts = mutableListOf<String>()
        
        parts.add("=== USER PROFILE ===")
        
        if (userName.isNotBlank()) {
            parts.add("User's Name: $userName")
        }
        
        if (occupation.isNotBlank()) {
            parts.add("Occupation: $occupation")
        }
        
        if (location.isNotBlank()) {
            parts.add("Location: $location")
        }
        
        if (likes.isNotEmpty()) {
            parts.add("User Likes: ${likes.joinToString(", ")}")
        }
        
        if (dislikes.isNotEmpty()) {
            parts.add("User Dislikes: ${dislikes.joinToString(", ")}")
        }
        
        if (interests.isNotEmpty()) {
            parts.add("User Interests: ${interests.joinToString(", ")}")
        }
        
        parts.add("Preferred Response Style: ${preferredResponseStyle.description}")
        parts.add("Preferred Language: $preferredLanguage")
        
        if (importantNotes.isNotEmpty()) {
            parts.add("\nIMPORTANT NOTES:")
            importantNotes.forEach { note ->
                parts.add("- $note")
            }
        }

        // Auto-learned facts from conversation history
        if (autoLearnedFacts.isNotEmpty()) {
            parts.add("\nAUTO-LEARNED FROM CONVERSATIONS:")
            autoLearnedFacts.forEach { fact ->
                parts.add("- $fact")
            }
        }

        // Conversation stats so the AI knows the relationship depth
        if (conversationCount > 0 || totalMessages > 0) {
            parts.add("\nCONVERSATION HISTORY:")
            parts.add("Total conversations had: $conversationCount")
            parts.add("Total messages from user: $totalMessages")
        }
        
        parts.add("=== END USER PROFILE ===\n")
        
        return if (parts.size > 2) {
            parts.joinToString("\n")
        } else {
            "" // No personalization data yet
        }
    }
    
    /**
     * Check if memory is empty
     */
    fun isEmpty(): Boolean {
        return userName.isBlank() && 
               occupation.isBlank() && 
               location.isBlank() &&
               likes.isEmpty() && 
               dislikes.isEmpty() && 
               interests.isEmpty() &&
               importantNotes.isEmpty() &&
               autoLearnedFacts.isEmpty() &&
               conversationCount == 0
    }
}
