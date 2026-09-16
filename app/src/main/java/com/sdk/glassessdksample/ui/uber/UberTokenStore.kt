package com.sdk.glassessdksample.ui.uber

import android.content.Context

/**
 * Where the user's Uber OAuth tokens live.
 *
 * Its own prefs file, for the same reason SwiggyTokenStore has one: signing out
 * of IMI should not silently drop the Uber grant, and disconnecting Uber should
 * not touch the IMI session or the Swiggy one.
 *
 * Unlike Swiggy there is no client_id to remember — Uber issues one static
 * client_id per registered app, so it is a constant in [UberAuth] rather than
 * something discovered at runtime.
 */
object UberTokenStore {

    private const val PREFS_NAME = "UBER_PREFS"
    private const val KEY_ACCESS = "uber_access_token"
    private const val KEY_REFRESH = "uber_refresh_token"
    private const val KEY_EXPIRES_AT = "uber_expires_at"

    /** Refresh this far ahead of expiry rather than racing it. */
    private const val REFRESH_LEEWAY_MS = 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        expiresInSeconds: Long
    ) {
        prefs(context).edit().apply {
            putString(KEY_ACCESS, accessToken)
            if (!refreshToken.isNullOrBlank()) putString(KEY_REFRESH, refreshToken)
            putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000L)
            apply()
        }
    }

    fun accessToken(context: Context): String? = prefs(context).getString(KEY_ACCESS, null)

    fun refreshToken(context: Context): String? = prefs(context).getString(KEY_REFRESH, null)

    fun isConnected(context: Context): Boolean = !accessToken(context).isNullOrBlank()

    fun isExpired(context: Context): Boolean {
        if (accessToken(context).isNullOrBlank()) return true
        return System.currentTimeMillis() >= prefs(context).getLong(KEY_EXPIRES_AT, 0L) - REFRESH_LEEWAY_MS
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
