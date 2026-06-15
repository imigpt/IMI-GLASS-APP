package com.sdk.glassessdksample.auth

import android.content.Context
import android.content.SharedPreferences

/**
 * Single source of truth for the logged-in user's session.
 *
 * Stores the access/refresh tokens and basic user profile in the same
 * "IMI_PREFS" file that SplashActivity reads for [KEY_IS_LOGGED_IN], so the
 * splash routing stays in sync with the API auth state.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    /** Epoch millis when [accessToken] stops being valid. */
    var accessTokenExpiresAt: Long
        get() = prefs.getLong(KEY_TOKEN_EXPIRES_AT, 0L)
        private set(value) = prefs.edit().putLong(KEY_TOKEN_EXPIRES_AT, value).apply()

    val isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_IS_LOGGED_IN, false) && !accessToken.isNullOrBlank()

    val userId: String? get() = prefs.getString(KEY_USER_ID, null)
    val userName: String? get() = prefs.getString(KEY_USER_NAME, null)
    val userEmail: String? get() = prefs.getString(KEY_USER_EMAIL, null)
    val userPlan: String? get() = prefs.getString(KEY_USER_PLAN, null)
    val profileImageUrl: String? get() = prefs.getString(KEY_PROFILE_IMAGE_URL, null)

    /** True if the access token is missing or within [REFRESH_LEEWAY_MS] of expiry. */
    fun isAccessTokenExpired(): Boolean {
        if (accessToken.isNullOrBlank()) return true
        return System.currentTimeMillis() >= accessTokenExpiresAt - REFRESH_LEEWAY_MS
    }

    /** Persist a full session (after login/register). */
    fun saveSession(
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long,
        userId: String?,
        fullName: String?,
        email: String?,
        plan: String?,
        profileImageUrl: String?
    ) {
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_TOKEN_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000L)
            putString(KEY_USER_ID, userId)
            putString(KEY_USER_NAME, fullName)
            putString(KEY_USER_EMAIL, email)
            putString(KEY_USER_PLAN, plan)
            putString(KEY_PROFILE_IMAGE_URL, profileImageUrl)
            putBoolean(KEY_IS_LOGGED_IN, true)
            apply()
        }
    }

    /** Update only the tokens (after a refresh call). */
    fun updateTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long) {
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            if (!refreshToken.isNullOrBlank()) putString(KEY_REFRESH_TOKEN, refreshToken)
            putLong(KEY_TOKEN_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000L)
            apply()
        }
    }

    /** Wipe the session on logout. */
    fun clear() {
        prefs.edit().apply {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_TOKEN_EXPIRES_AT)
            remove(KEY_USER_ID)
            remove(KEY_USER_NAME)
            remove(KEY_USER_EMAIL)
            remove(KEY_USER_PLAN)
            remove(KEY_PROFILE_IMAGE_URL)
            putBoolean(KEY_IS_LOGGED_IN, false)
            apply()
        }
    }

    companion object {
        const val PREFS_NAME = "IMI_PREFS"

        // Kept identical to the key SplashActivity / VerifyCodeActivity already use.
        const val KEY_IS_LOGGED_IN = "is_logged_in"

        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRES_AT = "access_token_expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_PLAN = "user_plan"
        private const val KEY_PROFILE_IMAGE_URL = "profile_image_url"

        /** Refresh slightly before the token actually dies. */
        private const val REFRESH_LEEWAY_MS = 60_000L
    }
}
