package com.sdk.glassessdksample.ui

import java.util.UUID

/**
 * Quick Note data model for storing user notes and AI-generated reminders
 * Supports optional image attachment (stored as file path)
 */
data class QuickNote(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val imagePath: String? = null,  // Path to attached image file (null = no image)
    val timestamp: Long = System.currentTimeMillis(),
    val createdBy: CreatedBy = CreatedBy.USER
) {
    enum class CreatedBy {
        USER,    // Manually created by user
        AI       // Automatically created by AI
    }
    
    fun hasImage(): Boolean = !imagePath.isNullOrBlank()
    
    fun getFormattedTimestamp(): String {
        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    /** Compact date shown on list rows, e.g. "10/05/26". */
    fun getShortDate(): String {
        val sdf = java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    /** Full month name used to group notes, e.g. "June". */
    fun getMonthLabel(): String {
        val sdf = java.text.SimpleDateFormat("MMMM", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    /** Stable key for grouping/ordering months (year * 12 + month), newest first. */
    fun getMonthKey(): Int {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp
        return cal.get(java.util.Calendar.YEAR) * 12 + cal.get(java.util.Calendar.MONTH)
    }
}
