package com.sdk.glassessdksample.ui

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sdk.glassessdksample.ui.sync.BackendSync

/**
 * Manager for storing and retrieving Meeting Minutes
 * Uses SharedPreferences for persistent storage
 */
class MeetingMinutesManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("meeting_minutes_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val TAG = "MeetingMinutesManager"
    
    companion object {
        private const val KEY_MEETINGS = "meetings_list"
        private const val KEY_ACTIVE_MEETING = "active_meeting"
    }
    
    /**
     * Get all meetings, sorted by start time (newest first)
     */
    fun getAllMeetings(): List<MeetingMinute> {
        val json = prefs.getString(KEY_MEETINGS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<MeetingMinute>>() {}.type
            val meetings: List<MeetingMinute> = gson.fromJson(json, type)
            meetings.sortedByDescending { it.startTime }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading meetings: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Save a meeting
     */
    fun saveMeeting(meeting: MeetingMinute) {
        val meetings = getAllMeetings().toMutableList()
        val existingIndex = meetings.indexOfFirst { it.id == meeting.id }
        
        if (existingIndex != -1) {
            meetings[existingIndex] = meeting
        } else {
            meetings.add(meeting)
        }
        
        saveMeetings(meetings)
        Log.d(TAG, "Meeting saved: ${meeting.title} (${meeting.getDuration()})")
        BackendSync.pushUpdateMeeting(appContext, meeting)
    }
    
    /**
     * Start a new meeting
     */
    fun startMeeting(title: String): MeetingMinute {
        val meeting = MeetingMinute(
            title = title.ifBlank { "Meeting ${getFormattedDateTime()}" },
            startTime = System.currentTimeMillis(),
            transcript = "",
            isActive = true
        )
        
        // Save as active meeting
        prefs.edit().putString(KEY_ACTIVE_MEETING, gson.toJson(meeting)).apply()
        Log.d(TAG, "Meeting started: ${meeting.title}")
        BackendSync.pushStartMeeting(appContext, meeting)
        return meeting
    }
    
    /**
     * Get the currently active meeting
     */
    fun getActiveMeeting(): MeetingMinute? {
        val json = prefs.getString(KEY_ACTIVE_MEETING, null) ?: return null
        return try {
            gson.fromJson(json, MeetingMinute::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading active meeting: ${e.message}", e)
            null
        }
    }
    
    /**
     * Update active meeting transcript
     */
    fun updateActiveMeetingTranscript(additionalText: String) {
        val meeting = getActiveMeeting() ?: return
        val updatedMeeting = meeting.copy(
            transcript = if (meeting.transcript.isEmpty()) additionalText
                        else "${meeting.transcript} $additionalText"
        )
        prefs.edit().putString(KEY_ACTIVE_MEETING, gson.toJson(updatedMeeting)).apply()
        BackendSync.pushAppendTranscript(appContext, meeting.id, additionalText)
    }
    
    /**
     * End the active meeting and save it
     */
    fun endMeeting(summary: String): MeetingMinute? {
        val meeting = getActiveMeeting() ?: return null
        
        val completedMeeting = meeting.copy(
            endTime = System.currentTimeMillis(),
            summary = summary,
            isActive = false
        )
        
        // Save to meetings list (local cache only; the server push happens below
        // as an end-meeting PUT against the already-created active meeting).
        saveMeetings(getAllMeetings().toMutableList().also { list ->
            val i = list.indexOfFirst { it.id == completedMeeting.id }
            if (i != -1) list[i] = completedMeeting else list.add(completedMeeting)
        })

        // Clear active meeting
        prefs.edit().remove(KEY_ACTIVE_MEETING).apply()

        Log.d(TAG, "Meeting ended: ${completedMeeting.title} (${completedMeeting.getDuration()})")
        BackendSync.pushUpdateMeeting(appContext, completedMeeting)
        return completedMeeting
    }
    
    /**
     * Cancel active meeting without saving
     */
    fun cancelActiveMeeting() {
        val active = getActiveMeeting()
        prefs.edit().remove(KEY_ACTIVE_MEETING).apply()
        Log.d(TAG, "Active meeting cancelled")
        if (active != null) BackendSync.pushCancelMeeting(appContext, active.id)
    }
    
    /**
     * Delete a meeting
     */
    fun deleteMeeting(meetingId: String) {
        val meetings = getAllMeetings().toMutableList()
        meetings.removeAll { it.id == meetingId }
        saveMeetings(meetings)
        Log.d(TAG, "Meeting deleted: $meetingId")
        BackendSync.pushDeleteMeeting(appContext, meetingId)
    }
    
    /**
     * Search meetings by keyword
     */
    fun searchMeetings(keyword: String): List<MeetingMinute> {
        val lowerKeyword = keyword.lowercase()
        return getAllMeetings().filter {
            it.title.lowercase().contains(lowerKeyword) ||
            it.transcript.lowercase().contains(lowerKeyword) ||
            it.summary.lowercase().contains(lowerKeyword)
        }
    }
    
    /**
     * Get meeting context for AI - includes recent meeting summaries AND full transcripts
     * This allows AI to answer questions about past meetings with full details
     */
    fun getMeetingsContextForAI(): String {
        val meetings = getAllMeetings().take(5) // Get last 5 meetings with full details
        
        if (meetings.isEmpty()) {
            return ""
        }
        
        val contextBuilder = StringBuilder()
        contextBuilder.append("PAST MEETINGS (for reference when user asks about meetings):\n\n")
        
        meetings.forEachIndexed { index, meeting ->
            contextBuilder.append("${index + 1}. ${meeting.title}\n")
            contextBuilder.append("   Date: ${meeting.getFormattedStartTime()}\n")
            contextBuilder.append("   Duration: ${meeting.getDuration()}\n")
            
            // Include full summary
            if (meeting.summary.isNotBlank()) {
                contextBuilder.append("   Summary: ${meeting.summary}\n")
            }
            
            // Include full transcript for most recent 3 meetings (truncate older ones)
            if (meeting.transcript.isNotBlank()) {
                if (index < 3) {
                    // Full transcript for recent meetings (max 2000 chars to keep context manageable)
                    val transcript = if (meeting.transcript.length > 2000) {
                        meeting.transcript.take(2000) + "... (transcript truncated)"
                    } else {
                        meeting.transcript
                    }
                    contextBuilder.append("   Full Transcript: $transcript\n")
                } else {
                    // Truncated transcript for older meetings
                    val transcript = if (meeting.transcript.length > 500) {
                        meeting.transcript.take(500) + "..."
                    } else {
                        meeting.transcript
                    }
                    contextBuilder.append("   Transcript (excerpt): $transcript\n")
                }
            }
            contextBuilder.append("\n")
        }
        
        contextBuilder.append("You have access to meeting transcripts and summaries. ")
        contextBuilder.append("When user asks about meetings, reference this information with specific details.\n")
        
        return contextBuilder.toString()
    }
    
    /**
     * Replace the local cache with the authoritative server list (used by [BackendSync]
     * during the launch pull). Does NOT push back to the server.
     */
    fun replaceAllFromServer(serverMeetings: List<MeetingMinute>) {
        saveMeetings(serverMeetings)
        Log.d(TAG, "Local cache replaced with ${serverMeetings.size} server meetings")
    }

    private fun saveMeetings(meetings: List<MeetingMinute>) {
        val json = gson.toJson(meetings)
        prefs.edit().putString(KEY_MEETINGS, json).apply()
    }
    
    private fun getFormattedDateTime(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }
}
