package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.SessionManager
import java.util.concurrent.Executors

/**
 * Fire-and-forget pusher that posts AI conversation turns to the backend
 * via POST /v1/chat-summaries. Each turn is a self-contained summary, now
 * tagged with the channel (VOICE/TEXT), surface and conversation session id
 * (Part 3 enhancements) so summaries tie back to the full transcript.
 */
object ChatSync {

    private const val TAG = "ChatSync"

    /** Modality of the chat turn, used for the summary's `channel` field. */
    enum class Channel(val wire: String) { VOICE("VOICE"), TEXT("TEXT") }

    private val io = Executors.newSingleThreadExecutor()

    private fun isLoggedIn(ctx: Context) = SessionManager(ctx).isLoggedIn

    /**
     * [surface] is the device tag (e.g. "Mark 1 Glasses" / "Mark 2 Glasses"), used
     * for both the summary title and the new `surface` field.
     * [channel] marks the turn as voice or text. [sessionId] links it to the
     * conversation transcript. [startTime]/[endTime] bracket the turn.
     */
    fun pushTurn(
        context: Context,
        surface: String,
        userText: String,
        aiText: String,
        startTime: Long = System.currentTimeMillis(),
        endTime: Long = System.currentTimeMillis(),
        channel: Channel = Channel.VOICE,
        sessionId: String? = null,
        tokensUsed: Int? = null
    ) {
        if (userText.isBlank() && aiText.isBlank()) return
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) return
        io.execute {
            try {
                val result = ChatApi(ctx).postSummary(
                    title = surface,
                    type = "QUICK_NOTES",
                    userText = userText,
                    aiText = aiText,
                    startTime = startTime,
                    endTime = endTime,
                    channel = channel.wire,
                    surface = surface,
                    sessionId = sessionId,
                    tokensUsed = tokensUsed
                )
                if (result is ChatApi.Result.Err) {
                    Log.w(TAG, "postSummary failed: ${result.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "pushTurn failed: ${e.message}")
            }
        }
    }
}
