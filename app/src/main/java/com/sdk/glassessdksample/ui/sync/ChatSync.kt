package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.SessionManager
import java.util.concurrent.Executors

/**
 * Fire-and-forget pusher that posts AI conversation turns to the backend
 * via POST /v1/chat-summaries. No session management required — each turn
 * is a self-contained summary.
 */
object ChatSync {

    private const val TAG = "ChatSync"

    private val io = Executors.newSingleThreadExecutor()

    private fun isLoggedIn(ctx: Context) = SessionManager(ctx).isLoggedIn

    /**
     * [surface] is used as the summary title (e.g. "Mark 1 Glasses" / "Mark 2 Glasses").
     * [startTime] and [endTime] bracket the turn; both default to now if omitted.
     */
    fun pushTurn(
        context: Context,
        surface: String,
        userText: String,
        aiText: String,
        startTime: Long = System.currentTimeMillis(),
        endTime: Long = System.currentTimeMillis()
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
                    endTime = endTime
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
