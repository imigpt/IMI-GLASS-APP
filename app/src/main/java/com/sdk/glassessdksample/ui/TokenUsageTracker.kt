package com.sdk.glassessdksample.ui

import android.content.Context
import com.google.ai.client.generativeai.type.UsageMetadata

/**
 * Persists token usage totals for AI features and exposes snapshots for Settings UI.
 */
object TokenUsageTracker {

    private const val PREFS_NAME = "imi_token_usage"

    enum class Mode(val key: String) {
        AI_CHAT("ai_chat"),
        SEEING("seeing"),
        VOICE_CHAT("voice_chat")
    }

    data class UsageStats(
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val requestCount: Int
    )

    data class Snapshot(
        val total: UsageStats,
        val aiChat: UsageStats,
        val seeing: UsageStats,
        val voiceChat: UsageStats
    )

    @Synchronized
    fun track(context: Context?, mode: Mode, usageMetadata: UsageMetadata?) {
        if (context == null || usageMetadata == null) return

        track(
            context = context,
            mode = mode,
            promptTokens = usageMetadata.promptTokenCount,
            completionTokens = usageMetadata.candidatesTokenCount,
            totalTokens = usageMetadata.totalTokenCount
        )
    }

    @Synchronized
    fun track(context: Context?, mode: Mode, promptTokens: Int, completionTokens: Int, totalTokens: Int) {
        if (context == null) return

        val prompt = sanitize(promptTokens)
        val completion = sanitize(completionTokens)
        val combined = prompt + completion
        val total = if (totalTokens > 0) sanitize(totalTokens) else combined

        // Ignore empty updates to avoid inflating request counts with invalid payloads.
        if (prompt == 0L && completion == 0L && total == 0L) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = readStats(prefs, mode)
        val updated = current.copy(
            promptTokens = current.promptTokens + prompt,
            completionTokens = current.completionTokens + completion,
            totalTokens = current.totalTokens + total,
            requestCount = current.requestCount + 1
        )
        writeStats(prefs, mode, updated)
    }

    fun getSnapshot(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val aiChat = readStats(prefs, Mode.AI_CHAT)
        val seeing = readStats(prefs, Mode.SEEING)
        val voiceChat = readStats(prefs, Mode.VOICE_CHAT)

        val total = UsageStats(
            promptTokens = aiChat.promptTokens + seeing.promptTokens + voiceChat.promptTokens,
            completionTokens = aiChat.completionTokens + seeing.completionTokens + voiceChat.completionTokens,
            totalTokens = aiChat.totalTokens + seeing.totalTokens + voiceChat.totalTokens,
            requestCount = aiChat.requestCount + seeing.requestCount + voiceChat.requestCount
        )

        return Snapshot(
            total = total,
            aiChat = aiChat,
            seeing = seeing,
            voiceChat = voiceChat
        )
    }

    @Synchronized
    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun sanitize(value: Int): Long = if (value < 0) 0L else value.toLong()

    private fun readStats(prefs: android.content.SharedPreferences, mode: Mode): UsageStats {
        val prompt = prefs.getLong("${mode.key}_prompt", 0L)
        val completion = prefs.getLong("${mode.key}_completion", 0L)
        val total = prefs.getLong("${mode.key}_total", 0L)
        val requests = prefs.getInt("${mode.key}_requests", 0)
        return UsageStats(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = total,
            requestCount = requests
        )
    }

    private fun writeStats(
        prefs: android.content.SharedPreferences,
        mode: Mode,
        stats: UsageStats
    ) {
        prefs.edit()
            .putLong("${mode.key}_prompt", stats.promptTokens)
            .putLong("${mode.key}_completion", stats.completionTokens)
            .putLong("${mode.key}_total", stats.totalTokens)
            .putInt("${mode.key}_requests", stats.requestCount)
            .apply()
    }
}
