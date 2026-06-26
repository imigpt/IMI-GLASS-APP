package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.AuthApi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One message inside a remote conversation session. */
data class RemoteSessionMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
    val timestamp: Long,
    val messageType: String
)

/** A full conversation session (turn-by-turn transcript) from `/v1/conversations`. */
data class RemoteSession(
    val id: String,
    val title: String,
    val preview: String?,
    val hasVisionContent: Boolean,
    val imageIds: List<String>,
    val messages: List<RemoteSessionMessage>,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Networking layer for the Conversation Sessions API (Part 2 of the backend spec):
 * `/v1/conversations/sessions` (start), `/v1/conversations/{id}/turns` (append),
 * and the list/get/delete endpoints on `/v1/conversations`.
 *
 * Blocking — invoke off the main thread via [ConversationSync].
 */
class ConversationsApi(context: Context) {

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
     * POST /v1/conversations/sessions — start (or idempotently fetch) a session.
     * Passing the client-side [id] keeps the local and remote session ids aligned.
     */
    fun startSession(id: String, startedAt: Long = System.currentTimeMillis()): Result<RemoteSession> {
        val body = JSONObject().put("id", id).put("startedAt", startedAt)
        return request("POST", "/v1/conversations/sessions", body) { parseSession(it) }
    }

    /** POST /v1/conversations/{id}/turns — append a user+AI turn. */
    fun appendTurn(
        sessionId: String,
        userText: String?,
        aiText: String?,
        at: Long = System.currentTimeMillis()
    ): Result<RemoteSession> {
        val body = JSONObject().apply {
            userText?.takeIf { it.isNotBlank() }?.let { put("userText", it) }
            aiText?.takeIf { it.isNotBlank() }?.let { put("aiText", it) }
            put("at", at)
        }
        return request("POST", "/v1/conversations/$sessionId/turns", body) { parseSession(it) }
    }

    fun getSession(id: String): Result<RemoteSession> =
        request("GET", "/v1/conversations/$id", null) { parseSession(it) }

    /** GET /v1/conversations — paged, newest first. */
    fun listSessions(): Result<List<RemoteSession>> {
        val out = mutableListOf<RemoteSession>()
        var page = 1
        while (true) {
            val token = authApi.ensureValidAccessToken()
                ?: return Result.Err("Not signed in", auth = true)
            val url = "${AuthApi.BASE_URL}/v1/conversations".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("page", page.toString())
                ?.addQueryParameter("pageSize", "100")
                ?.build() ?: return Result.Err("Bad URL")
            val req = Request.Builder().url(url).get()
                .addHeader("Authorization", "Bearer $token").build()
            when (val r = execute(req) { it }) {
                is Result.Err -> return r
                is Result.Ok -> {
                    val items = r.value.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        items.optJSONObject(i)?.let { out.add(parseSession(it)) }
                    }
                    if (!r.value.optBoolean("hasMore", false) || items.length() == 0) {
                        return Result.Ok(out)
                    }
                    page++
                }
            }
        }
    }

    fun deleteSession(id: String): Result<Unit> =
        request("DELETE", "/v1/conversations/$id", null) { }

    fun deleteAllSessions(): Result<Int> =
        request("DELETE", "/v1/conversations", null) { it.optInt("deleted", 0) }

    // ---------------------------------------------------------------------
    // JSON mapping
    // ---------------------------------------------------------------------

    private fun parseSession(json: JSONObject): RemoteSession = RemoteSession(
        id = json.optString("id"),
        title = json.optString("title", "Conversation"),
        preview = json.optString("preview").takeIf { it.isNotBlank() && it != "null" },
        hasVisionContent = json.optBoolean("hasVisionContent", false),
        imageIds = json.optJSONArray("imageIds").toStringList(),
        messages = parseMessages(json.optJSONArray("messages")),
        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
    )

    private fun parseMessages(arr: JSONArray?): List<RemoteSessionMessage> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { m ->
                RemoteSessionMessage(
                    id = m.optLong("id"),
                    text = m.optString("text"),
                    fromUser = m.optBoolean("fromUser", false),
                    timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                    messageType = m.optString("messageType", "TEXT")
                )
            }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }

    // ---------------------------------------------------------------------
    // HTTP plumbing
    // ---------------------------------------------------------------------

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
            "PUT" -> builder.put(reqBody ?: EMPTY_BODY)
            "PATCH" -> builder.patch(reqBody ?: EMPTY_BODY)
            "DELETE" -> if (reqBody != null) builder.delete(reqBody) else builder.delete()
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
        private const val TAG = "ConversationsApi"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = "".toRequestBody(null)
    }
}
