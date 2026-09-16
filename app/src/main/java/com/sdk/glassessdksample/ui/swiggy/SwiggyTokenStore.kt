package com.sdk.glassessdksample.ui.swiggy

import android.content.Context

/**
 * Where the user's Swiggy MCP tokens live.
 *
 * Kept in its own prefs file rather than the IMI_PREFS one that
 * auth.SessionManager owns: signing out of IMI should not silently drop the
 * Swiggy grant, and clearing Swiggy should not touch the IMI session. Same
 * shape as SessionManager otherwise.
 */
object SwiggyTokenStore {

    private const val PREFS_NAME = "SWIGGY_MCP_PREFS"
    private const val KEY_ACCESS = "swiggy_access_token"
    private const val KEY_REFRESH = "swiggy_refresh_token"
    private const val KEY_EXPIRES_AT = "swiggy_expires_at"
    private const val KEY_CLIENT_ID = "swiggy_client_id"

    /** Refresh this far ahead of expiry rather than racing it. */
    private const val REFRESH_LEEWAY_MS = 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long,
        clientId: String
    ) {
        prefs(context).edit().apply {
            putString(KEY_ACCESS, accessToken)
            if (!refreshToken.isNullOrBlank()) putString(KEY_REFRESH, refreshToken)
            putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000L)
            putString(KEY_CLIENT_ID, clientId)
            apply()
        }
    }

    fun accessToken(context: Context): String? = prefs(context).getString(KEY_ACCESS, null)

    fun refreshToken(context: Context): String? = prefs(context).getString(KEY_REFRESH, null)

    fun clientId(context: Context): String? = prefs(context).getString(KEY_CLIENT_ID, null)

    fun isConnected(context: Context): Boolean = !accessToken(context).isNullOrBlank()

    fun isExpired(context: Context): Boolean {
        if (accessToken(context).isNullOrBlank()) return true
        return System.currentTimeMillis() >= prefs(context).getLong(KEY_EXPIRES_AT, 0L) - REFRESH_LEEWAY_MS
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
