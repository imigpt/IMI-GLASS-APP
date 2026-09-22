package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.AuthApi
import com.sdk.glassessdksample.ui.profile.ProfileSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One imported profile, as `/v1/profile/imported-summary` returns it. */
data class ImportedProfile(
    val source: String,
    val body: String,
    val importedAt: Long,
    val updatedAt: String?
)

/**
 * Networking layer for the imported ChatGPT/Claude profile
 * (`/v1/profile/imported-summary`), which backs the admin panel's
 * "Imported Profile" tab.
 *
 * Wire-format notes (from BACKEND_SPEC_IMPORTED_PROFILE_SYNC.md, verified
 * against production):
 *  - `source` must be exactly "CHATGPT" or "CLAUDE". The schema is a strict
 *    enum and 400s on any other casing. [ProfileSource.name] already matches,
 *    but it is mapped explicitly below so renaming a case can't silently break
 *    the wire format.
 *  - `importedAt` is epoch MILLISECONDS as a number; an ISO string 400s.
 *  - `body` is 1-4000 characters. An empty body is rejected rather than
 *    treated as a clear — clearing is DELETE.
 *
 * Tokens come from [AuthApi.ensureValidAccessToken], which refreshes an expired
 * one first. That matters here: an import spends minutes in a web view before
 * Save is tapped, which is long enough for the access token to go stale.
 */
class ImportedProfileApi(context: Context) {

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
     * PUT /v1/profile/imported-summary — upserts this user's profile for
     * [source]. [importedAt] is epoch ms, defaulting to now.
     */
    fun upload(
        body: String,
        source: ProfileSource,
        importedAt: Long = System.currentTimeMillis()
    ): Result<ImportedProfile> {
        val trimmed = body.trim().take(MAX_BODY_CHARS)
        if (trimmed.isEmpty()) {
            return Result.Err("Profile body is empty; use delete() to clear.", code = 400)
        }
        val payload = JSONObject().apply {
            put("source", source.wireValue)
            put("body", trimmed)
            put("importedAt", importedAt)
        }
        return request("PUT", PATH, payload) { parseProfile(it) }
    }

    /**
     * GET /v1/profile/imported-summary — every profile saved for this user.
     * An empty list is the correct answer for someone who hasn't imported.
     */
    fun listAll(): Result<List<ImportedProfile>> =
        request("GET", PATH, null) { json ->
            val array = json.optJSONArray("profiles")
            if (array == null) emptyList()
            else (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { parseProfile(it) }
            }
        }

    /**
     * DELETE /v1/profile/imported-summary/{source} — always 204, whether or not
     * a profile existed, so deleting something never imported is not an error.
     */
    fun delete(source: ProfileSource): Result<Unit> =
        request("DELETE", "$PATH/${source.wireValue}", null) { }

    private fun parseProfile(json: JSONObject) = ImportedProfile(
        source = json.optString("source"),
        body = json.optString("body"),
        importedAt = json.optLong("importedAt"),
        updatedAt = if (json.isNull("updatedAt")) null else json.optString("updatedAt")
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
            "PUT" -> builder.put(reqBody ?: EMPTY_BODY)
            "POST" -> builder.post(reqBody ?: EMPTY_BODY)
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
        private const val TAG = "ImportedProfileApi"
        private const val PATH = "/v1/profile/imported-summary"
        /** Server-side cap; over this the request 400s. */
        const val MAX_BODY_CHARS = 4_000
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = "".toRequestBody(null)
    }
}
