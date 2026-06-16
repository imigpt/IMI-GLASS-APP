package com.sdk.glassessdksample.auth

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Networking layer for the IMI Glass auth APIs (register / login / logout / refresh).
 *
 * All public calls deliver their result on the main thread via the supplied
 * callback. Errors are surfaced as a human-readable message extracted from the
 * backend's `{ "error": { "message": ... } }` envelope when available.
 */
class AuthApi(context: Context) {

    private val appContext = context.applicationContext
    private val session = SessionManager(appContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    sealed class Result {
        /** [user] may be null for endpoints that don't return a profile (e.g. logout). */
        data class Success(val user: JSONObject?) : Result()
        data class Error(val message: String) : Result()
    }

    // ---------------------------------------------------------------------
    // Register
    // ---------------------------------------------------------------------

    fun register(
        fullName: String,
        email: String,
        password: String,
        confirmPassword: String,
        callback: (Result) -> Unit
    ) {
        val body = JSONObject().apply {
            put("fullName", fullName)
            put("email", email)
            put("password", password)
            put("confirmPassword", confirmPassword)
        }
        postJson("$BASE_URL/v1/auth/register", body, authToken = null) { resp, err ->
            if (err != null) {
                deliver(callback, Result.Error(err))
                return@postJson
            }
            // Register returns the created user but no token; caller follows up with login.
            deliver(callback, Result.Success(resp))
        }
    }

    // ---------------------------------------------------------------------
    // Login
    // ---------------------------------------------------------------------

    fun login(email: String, password: String, callback: (Result) -> Unit) {
        val body = JSONObject().apply {
            put("email", email)
            put("password", password)
        }
        postJson("$BASE_URL/v1/auth/login", body, authToken = null) { resp, err ->
            if (err != null) {
                deliver(callback, Result.Error(err))
                return@postJson
            }
            if (resp == null) {
                deliver(callback, Result.Error("Empty response from server"))
                return@postJson
            }
            persistLoginResponse(resp)
            deliver(callback, Result.Success(resp.optJSONObject("user")))
        }
    }

    // ---------------------------------------------------------------------
    // Logout
    // ---------------------------------------------------------------------

    fun logout(callback: (Result) -> Unit) {
        val token = session.accessToken
        // Clear locally regardless of server outcome so the user is always signed out.
        if (token.isNullOrBlank()) {
            session.clear()
            deliver(callback, Result.Success(null))
            return
        }
        postJson("$BASE_URL/v1/auth/logout", JSONObject(), authToken = token) { _, err ->
            session.clear()
            if (err != null) {
                // Local session is already gone; report softly.
                Log.w(TAG, "Server logout failed (cleared locally anyway): $err")
            }
            deliver(callback, Result.Success(null))
        }
    }

    // ---------------------------------------------------------------------
    // Refresh
    // ---------------------------------------------------------------------

    /**
     * Synchronously refresh the access token using the stored refresh token.
     * Must be called off the main thread. Returns true on success.
     */
    private fun refreshBlocking(): Boolean {
        val refresh = session.refreshToken ?: return false
        val body = JSONObject().apply { put("refreshToken", refresh) }
        val request = buildRequest("$BASE_URL/v1/auth/refresh", body, authToken = null)
        return try {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Refresh failed: ${response.code}")
                    return false
                }
                val json = JSONObject(raw)
                // The backend is inconsistent: login returns camelCase, refresh returns
                // snake_case (access_token / expires_in). Accept either.
                val newAccess = json.firstString("accessToken", "access_token")
                    ?: return false
                session.updateTokens(
                    accessToken = newAccess,
                    refreshToken = json.firstString("refreshToken", "refresh_token"),
                    expiresInSeconds = json.firstLong("expiresIn", "expires_in", default = DEFAULT_EXPIRES_IN)
                )
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh error: ${e.message}")
            false
        }
    }

    /**
     * Returns a valid access token, refreshing first if it's expired.
     * Must be called off the main thread. Null means the user must re-login.
     */
    fun ensureValidAccessToken(): String? {
        if (!session.isAccessTokenExpired()) return session.accessToken
        return if (refreshBlocking()) session.accessToken else null
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private fun persistLoginResponse(resp: JSONObject) {
        val user = resp.optJSONObject("user")
        // Tolerate both camelCase and snake_case (the backend mixes them across endpoints).
        session.saveSession(
            accessToken = resp.firstString("accessToken", "access_token").orEmpty(),
            refreshToken = resp.firstString("refreshToken", "refresh_token"),
            expiresInSeconds = resp.firstLong("expiresIn", "expires_in", default = DEFAULT_EXPIRES_IN),
            userId = user?.firstString("userId", "user_id", "id"),
            fullName = user?.firstString("fullName", "full_name", "name"),
            email = user?.optString("email"),
            plan = user?.optString("plan"),
            profileImageUrl = user?.firstString("profileImageUrl", "profile_image_url")
                ?.takeIf { it != "null" }
        )
    }

    /** Returns the first non-blank string value among [keys], or null. */
    private fun JSONObject.firstString(vararg keys: String): String? {
        for (k in keys) {
            val v = optString(k)
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    /** Returns the first present long value among [keys], or [default]. */
    private fun JSONObject.firstLong(vararg keys: String, default: Long): Long {
        for (k in keys) {
            if (has(k) && !isNull(k)) return optLong(k, default)
        }
        return default
    }

    private fun buildRequest(url: String, body: JSONObject, authToken: String?): Request {
        val reqBody = body.toString().toRequestBody(JSON_MEDIA)
        return Request.Builder()
            .url(url)
            .post(reqBody)
            .apply { if (!authToken.isNullOrBlank()) addHeader("Authorization", "Bearer $authToken") }
            .build()
    }

    /** POST a JSON body; callback receives (parsedBody|null, errorMessage|null) on a background thread. */
    private fun postJson(
        url: String,
        body: JSONObject,
        authToken: String?,
        onResult: (JSONObject?, String?) -> Unit
    ) {
        val request = buildRequest(url, body, authToken)
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Request to $url failed: ${e.message}")
                onResult(null, "Network error. Please check your connection.")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val raw = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        val parsed = if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrNull()
                        onResult(parsed, null)
                    } else {
                        onResult(null, parseError(raw, response.code))
                    }
                }
            }
        })
    }

    /** Extract `error.message` from the backend envelope, falling back to a generic message. */
    private fun parseError(raw: String, code: Int): String {
        return runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Request failed (HTTP $code)"
    }

    private fun deliver(callback: (Result) -> Unit, result: Result) {
        mainHandler.post { callback(result) }
    }

    companion object {
        private const val TAG = "AuthApi"
        const val BASE_URL = "http://136.243.196.163:8080"
        private const val DEFAULT_EXPIRES_IN = 3600L
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
