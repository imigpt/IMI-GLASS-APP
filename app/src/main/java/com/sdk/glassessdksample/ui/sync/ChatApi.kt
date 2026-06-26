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

/** A structured AI chat summary, as returned by the `/v1/chat-summaries` endpoints. */
data class ChatSummary(
    val id: String,
    val title: String,
    val type: String,
    val content: String,
    val keyPoints: List<String>,
    val actionItems: List<String>,
    val participants: List<String>,
    val tags: List<String>,
    val startTime: Long,
    val endTime: Long?,
    val duration: Long,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val createdAt: String,
    val updatedAt: String
)

/**
 * Networking layer for the IMI Glass Chat Summaries API (`/v1/chat-summaries`).
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
        tags: List<String> = emptyList(),
        channel: String? = null,
        surface: String? = null,
        sessionId: String? = null,
        tokensUsed: Int? = null
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
            // Part 3 enhancements — all optional; backend defaults channel to "TEXT".
            channel?.let { put("channel", it) }
            surface?.let { put("surface", it) }
            sessionId?.let { put("sessionId", it) }
            tokensUsed?.let { put("tokensUsed", it) }
        }

        return request("POST", "/v1/chat-summaries", body) { }
    }

    /** GET /v1/chat-summaries — every summary for the current user. */
    fun listAll(): Result<List<ChatSummary>> =
        request("GET", "/v1/chat-summaries", null) { parseList(it) }

    /** GET /v1/chat-summaries/session/{sessionId} — every summary for one session. */
    fun listBySession(sessionId: String): Result<List<ChatSummary>> =
        request("GET", "/v1/chat-summaries/session/$sessionId", null) { parseList(it) }

    /** GET /v1/chat-summaries/active — summaries that are not archived. */
    fun listActive(): Result<List<ChatSummary>> =
        request("GET", "/v1/chat-summaries/active", null) { parseList(it) }

    /** GET /v1/chat-summaries/pinned */
    fun listPinned(): Result<List<ChatSummary>> =
        request("GET", "/v1/chat-summaries/pinned", null) { parseList(it) }

    /** GET /v1/chat-summaries/type/{type} — [type] is "MEETING_SUMMARY" or "QUICK_NOTES". */
    fun listByType(type: String): Result<List<ChatSummary>> =
        request("GET", "/v1/chat-summaries/type/$type", null) { parseList(it) }

    /** GET /v1/chat-summaries/stats -> { total, meetings, quickNotes } */
    fun getStats(): Result<Triple<Int, Int, Int>> =
        request("GET", "/v1/chat-summaries/stats", null) {
            Triple(it.optInt("total", 0), it.optInt("meetings", 0), it.optInt("quickNotes", 0))
        }

    /** GET /v1/chat-summaries/{id} */
    fun getById(summaryId: String): Result<ChatSummary> =
        request("GET", "/v1/chat-summaries/$summaryId", null) { parseSummary(it) }

    /** PATCH /v1/chat-summaries/{id} — all fields optional, only provided ones are updated. */
    fun updateSummary(
        summaryId: String,
        title: String? = null,
        content: String? = null,
        keyPoints: List<String>? = null,
        actionItems: List<String>? = null,
        participants: List<String>? = null,
        tags: List<String>? = null,
        endTime: Long? = null
    ): Result<ChatSummary> {
        val body = JSONObject()
        title?.let { body.put("title", it) }
        content?.let { body.put("content", it) }
        keyPoints?.let { body.put("keyPoints", JSONArray(it)) }
        actionItems?.let { body.put("actionItems", JSONArray(it)) }
        participants?.let { body.put("participants", JSONArray(it)) }
        tags?.let { body.put("tags", JSONArray(it)) }
        endTime?.let { body.put("endTime", it) }
        return request("PATCH", "/v1/chat-summaries/$summaryId", body) { parseSummary(it) }
    }

    /** DELETE /v1/chat-summaries/{id} */
    fun deleteSummary(summaryId: String): Result<Unit> =
        request("DELETE", "/v1/chat-summaries/$summaryId", null) { }

    /** POST /v1/chat-summaries/{id}/pin — toggles pin status. */
    fun togglePin(summaryId: String): Result<Boolean> =
        request("POST", "/v1/chat-summaries/$summaryId/pin", JSONObject()) { it.optBoolean("isPinned") }

    /** POST /v1/chat-summaries/{id}/archive — toggles archive status. */
    fun toggleArchive(summaryId: String): Result<Boolean> =
        request("POST", "/v1/chat-summaries/$summaryId/archive", JSONObject()) { it.optBoolean("isArchived") }

    private fun parseList(json: JSONObject): List<ChatSummary> {
        val data = json.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.let { parseSummary(it) } }
    }

    private fun parseSummary(json: JSONObject): ChatSummary = ChatSummary(
        id = json.optString("id"),
        title = json.optString("title"),
        type = json.optString("type"),
        content = json.optString("content"),
        keyPoints = json.optJSONArray("keyPoints").toStringList(),
        actionItems = json.optJSONArray("actionItems").toStringList(),
        participants = json.optJSONArray("participants").toStringList(),
        tags = json.optJSONArray("tags").toStringList(),
        startTime = json.optLong("startTime"),
        endTime = if (json.isNull("endTime")) null else json.optLong("endTime"),
        duration = json.optLong("duration"),
        isPinned = json.optBoolean("isPinned", false),
        isArchived = json.optBoolean("isArchived", false),
        createdAt = json.optString("createdAt"),
        updatedAt = json.optString("updatedAt")
    )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
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
            "PATCH" -> builder.patch(reqBody ?: EMPTY_BODY)
            "DELETE" -> builder.delete()
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
