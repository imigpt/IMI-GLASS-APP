package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.AuthApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One support ticket, as `/v1/support/tickets` returns it. */
data class SupportTicket(
    val id: String,
    val message: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String
)

/**
 * Networking layer for the in-app support ticket feature
 * (`POST /v1/support/tickets`, `GET /v1/support/tickets`), which lands
 * directly in the admin panel's Support Tickets queue.
 *
 * Deliberately one field: the request body is just `{ "message": "..." }`.
 * `userId` and the account's registered email/name are attached server-side
 * from the caller's own access token — there is no field for them and no
 * way to file a ticket under someone else's identity.
 *
 * Follows the same request/Result shape as [ImportedProfileApi]: tokens come
 * from [AuthApi.ensureValidAccessToken], which refreshes an expired one
 * first, and every call must run off the main thread.
 */
class SupportTicketApi(context: Context) {

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
     * POST /v1/support/tickets — files a new ticket under the signed-in user.
     * [message] must be 1-4000 characters; enforced client-side too so the
     * user gets immediate feedback instead of a round trip for an empty box.
     */
    fun create(message: String): Result<SupportTicket> {
        val trimmed = message.trim().take(MAX_MESSAGE_CHARS)
        if (trimmed.isEmpty()) {
            return Result.Err("Message is empty", code = 400)
        }
        val payload = JSONObject().apply { put("message", trimmed) }
        return request("POST", PATH, payload) { parseTicket(it) }
    }

    /** GET /v1/support/tickets — this user's own tickets, newest first. */
    fun listMine(page: Int = 1, pageSize: Int = 20): Result<List<SupportTicket>> =
        request("GET", "$PATH?page=$page&pageSize=$pageSize", null) { json ->
            val array = json.optJSONArray("items")
            if (array == null) emptyList()
            else (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { parseTicket(it) }
            }
        }

    private fun parseTicket(json: JSONObject) = SupportTicket(
        id = json.optString("id"),
        message = json.optString("message"),
        status = json.optString("status"),
        createdAt = json.optString("createdAt"),
        updatedAt = json.optString("updatedAt")
    )

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
                    val parsed = if (raw.isBlank()) JSONObject()
                    else runCatching { JSONObject(raw) }.getOrNull() ?: JSONObject()
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
        private const val TAG = "SupportTicketApi"
        private const val PATH = "/v1/support/tickets"
        /** Server-side cap; over this the request 400s. */
        const val MAX_MESSAGE_CHARS = 4_000
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = "".toRequestBody(null)
    }
}
