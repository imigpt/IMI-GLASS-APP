package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.AuthApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Networking layer for the IMI Glass Chat Summaries API (`POST /v1/chat-summaries`).
 * Each conversation turn is posted as a self-contained summary so no session
 * management is required on the client side.
 */
class ChatApi(context: Context) {

    private val appContext = context.applicationContext
    private val authApi = AuthApi(appContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val message: String, val auth: Boolean = false, val code: Int = 0) : Result<Nothing>()
    }

    /**
     * Posts a conversation turn to /v1/chat-summaries.
     *
     * [title]    – human-readable label (e.g. the surface name "Mark 2 Glasses")
     * [type]     – "MEETING_SUMMARY" or "QUICK_NOTES"
     * [userText] – what the user said
     * [aiText]   – what the AI replied
     * [startTime]/[endTime] – epoch-ms timestamps bracketing the turn; both default to now
     */
    fun postSummary(
        title: String,
        type: String = "QUICK_NOTES",
        userText: String,
        aiText: String,
        startTime: Long = System.currentTimeMillis(),
        endTime: Long = System.currentTimeMillis(),
        tags: List<String> = emptyList()
    ): Result<Unit> {
        val content = buildString {
            if (userText.isNotBlank()) append("[User] ").append(userText)
            if (aiText.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("[AI] ").append(aiText)
            }
        }

        val body = JSONObject().apply {
            put("title", title)
            put("type", type)
            put("content", content)
            put("keyPoints", JSONArray())
            put("actionItems", JSONArray())
            put("participants", JSONArray())
            put("tags", JSONArray(tags))
            put("startTime", startTime)
            put("endTime", endTime)
        }

        return request("POST", "/v1/chat-summaries", body) { }
    }

    private fun <T> request(
        method: String,
        path: String,
        body: JSONObject?,
        map: (JSONObject) -> T
    ): Result<T> {
        val token = authApi.ensureValidAccessToken()
            ?: return Result.Err("Not signed in", auth = true)
        val reqBody = body?.toString()?.toRequestBody(JSON_MEDIA)
        val builder = Request.Builder()
            .url("${AuthApi.BASE_URL}$path")
            .addHeader("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post(reqBody ?: EMPTY_BODY)
            else -> builder.get()
        }
        return execute(builder.build(), map)
    }

    private fun <T> execute(req: Request, map: (JSONObject) -> T): Result<T> {
        return try {
            client.newCall(req).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val parsed = if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
                    Result.Ok(map(parsed))
                } else if (response.code == 401) {
                    Result.Err("Session expired", auth = true, code = 401)
                } else {
                    Log.w(TAG, "${req.method} ${req.url} -> HTTP ${response.code}: ${raw.take(300)}")
                    Result.Err(parseError(raw, response.code), code = response.code)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request ${req.method} ${req.url} failed: ${e.message}")
            Result.Err("Network error: ${e.message}")
        }
    }

    private fun parseError(raw: String, code: Int): String =
        runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Request failed (HTTP $code)"

    companion object {
        private const val TAG = "ChatApi"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = "".toRequestBody(null)
    }
}
