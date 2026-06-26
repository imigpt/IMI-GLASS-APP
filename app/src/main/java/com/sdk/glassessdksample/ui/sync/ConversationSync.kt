package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.SessionManager
import java.util.concurrent.Executors

/**
 * Fire-and-forget pusher for conversation sessions (full transcript history).
 *
 * The local [com.sdk.glassessdksample.ui.ConversationSessionStore] stays the UI's
 * source of truth; these helpers mirror each session start and turn append to the
 * backend so the History timeline follows the user across devices.
 *
 * The local session id (e.g. "sess_<timestamp>") is passed straight through as the
 * remote session id, so the same id links the transcript here and any chat
 * summaries posted via [ChatSync].
 */
object ConversationSync {

    private const val TAG = "ConversationSync"

    private val io = Executors.newSingleThreadExecutor()

    private fun isLoggedIn(ctx: Context) = SessionManager(ctx).isLoggedIn

    /** Mirror a freshly started local session to the backend (idempotent server-side). */
    fun pushStartSession(context: Context, sessionId: String, startedAt: Long) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx) || sessionId.isBlank()) return
        io.execute {
            try {
                val r = ConversationsApi(ctx).startSession(sessionId, startedAt)
                if (r is ConversationsApi.Result.Err) Log.w(TAG, "startSession -> ${r.message}")
            } catch (e: Exception) {
                Log.w(TAG, "pushStartSession failed: ${e.message}")
            }
        }
    }

    /**
     * Mirror a completed turn (user text + AI text) to the backend. If the session
     * was never started server-side (e.g. the start push failed), start it first so
     * the turn is never lost.
     */
    fun pushTurn(
        context: Context,
        sessionId: String?,
        userText: String,
        aiText: String,
        at: Long = System.currentTimeMillis()
    ) {
        if (sessionId.isNullOrBlank()) return
        if (userText.isBlank() && aiText.isBlank()) return
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) return
        io.execute {
            try {
                val api = ConversationsApi(ctx)
                var result = api.appendTurn(sessionId, userText, aiText, at)
                if (result is ConversationsApi.Result.Err && result.code == 404) {
                    // Session not on the server yet — create it, then retry the turn.
                    api.startSession(sessionId, at)
                    result = api.appendTurn(sessionId, userText, aiText, at)
                }
                if (result is ConversationsApi.Result.Err) Log.w(TAG, "appendTurn -> ${result.message}")
            } catch (e: Exception) {
                Log.w(TAG, "pushTurn failed: ${e.message}")
            }
        }
    }

    fun pushClearAll(context: Context) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) return
        io.execute {
            try {
                ConversationsApi(ctx).deleteAllSessions()
            } catch (e: Exception) {
                Log.w(TAG, "pushClearAll failed: ${e.message}")
            }
        }
    }
}
