package com.sdk.glassessdksample.ui

import java.util.UUID

/**
 * Meeting Minute data model for storing meeting transcripts and summaries
 */
data class MeetingMinute(
    val id: String = UUID.randomUUID().toString(),
    val title: String, // e.g., "Team Sync", "Client Call"
    val startTime: Long,
    val endTime: Long = System.currentTimeMillis(),
    val transcript: String, // Full speech-to-text transcript
    val summary: String = "", // AI-generated summary
    val participants: String = "", // Optional: comma-separated names
    val isActive: Boolean = false, // True while meeting is ongoing
    val speakerCount: Int = 0, // Number of unique speakers detected
    val speakerTranscript: String = "" // Transcript with speaker labels (Speaker 1: text, Speaker 2: text)
) {
    fun getDuration(): String {
        val durationMs = endTime - startTime
        val minutes = (durationMs / 1000 / 60).toInt()
        val seconds = ((durationMs / 1000) % 60).toInt()
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }
    
    fun getFormattedStartTime(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(startTime))
    }
    
    fun getFormattedDate(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(startTime))
    }
    
    fun getWordCount(): Int {
        return transcript.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
    }
    
    fun getSpeakerStats(): Map<Int, Int> {
        // Returns speaker number to word count map
        if (speakerTranscript.isBlank()) return emptyMap()
        
        val stats = mutableMapOf<Int, Int>()
        val speakerPattern = Regex("Speaker (\\d+):\\s*(.+?)(?=Speaker \\d+:|$)", RegexOption.DOT_MATCHES_ALL)
        
        speakerPattern.findAll(speakerTranscript).forEach { match ->
            val speakerNum = match.groupValues[1].toIntOrNull() ?: return@forEach
            val text = match.groupValues[2]
            val wordCount = text.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
            stats[speakerNum] = (stats[speakerNum] ?: 0) + wordCount
        }
        
        return stats
    }
}
