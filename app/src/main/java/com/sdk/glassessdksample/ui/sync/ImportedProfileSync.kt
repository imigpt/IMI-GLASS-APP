package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.SessionManager
import com.sdk.glassessdksample.ui.profile.ProfileSource
import com.sdk.glassessdksample.ui.profile.UserProfileStore
import java.util.concurrent.Executors

/**
 * Fire-and-forget pusher that mirrors the imported ChatGPT/Claude profile to
 * the backend via PUT /v1/profile/imported-summary, so it appears on the admin
 * panel's "Imported Profile" tab.
 *
 * The local EncryptedSharedPreferences copy is the source of truth and is
 * written first; this only mirrors it. A failure here is logged and never
 * surfaced — the profile still works locally, and [syncPending] picks it up on
 * the next login or app start.
 *
 * Follows the same single-thread-executor shape as [ChatSync] and
 * [ConversationSync].
 */
object ImportedProfileSync {

    private const val TAG = "ImportedProfileSync"

    private val io = Executors.newSingleThreadExecutor()

    private fun isLoggedIn(ctx: Context) = SessionManager(ctx).isLoggedIn

    /**
     * Mirrors a saved profile to the backend.
     *
     * [importedAt] should be the same timestamp the local store recorded, so
     * the panel shows when the user actually imported rather than when the
     * upload happened to succeed.
     */
    fun pushProfile(
        context: Context,
        body: String,
        source: ProfileSource,
        importedAt: Long = System.currentTimeMillis()
    ) {
        if (body.isBlank()) return
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) {
            // Imported while signed out of IMI (but signed in to ChatGPT or
            // Claude — the two are unrelated sessions, and the import flow
            // never asks for the IMI one). Kept locally; syncPending() pushes
            // it after the next login.
            Log.i(TAG, "Not signed in; deferring ${source.wireValue} upload")
            return
        }
        io.execute {
            try {
                when (val result = ImportedProfileApi(ctx).upload(body, source, importedAt)) {
                    is ImportedProfileApi.Result.Ok ->
                        Log.i(TAG, "Uploaded ${source.wireValue} profile")
                    is ImportedProfileApi.Result.Err ->
                        Log.w(TAG, "Upload failed for ${source.wireValue}: ${result.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "pushProfile failed: ${e.message}")
            }
        }
    }

    /** Mirrors a local delete. The endpoint 204s whether or not one existed. */
    fun pushDelete(context: Context, source: ProfileSource) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) return
        io.execute {
            try {
                when (val result = ImportedProfileApi(ctx).delete(source)) {
                    is ImportedProfileApi.Result.Ok ->
                        Log.i(TAG, "Deleted ${source.wireValue} profile")
                    is ImportedProfileApi.Result.Err ->
                        Log.w(TAG, "Delete failed for ${source.wireValue}: ${result.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "pushDelete failed: ${e.message}")
            }
        }
    }

    /**
     * Pushes anything held locally that the backend doesn't have yet.
     *
     * Covers the two ways a profile ends up unsynced: imported while signed
     * out, or its upload failed. Call after login and on app start — safe to
     * repeat, since the endpoint upserts.
     */
    fun syncPending(context: Context) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) return
        io.execute {
            try {
                val local = UserProfileStore(ctx).loadAll()
                if (local.isEmpty()) return@execute

                val api = ImportedProfileApi(ctx)
                val remote = when (val result = api.listAll()) {
                    is ImportedProfileApi.Result.Ok -> result.value
                    is ImportedProfileApi.Result.Err -> {
                        Log.w(TAG, "Backfill check failed: ${result.message}")
                        return@execute
                    }
                }
                val alreadyUp = remote.map { it.source.uppercase() }.toSet()

                local.filterNot { it.source.wireValue in alreadyUp }.forEach { profile ->
                    when (val result = api.upload(profile.body, profile.source, profile.importedAt)) {
                        is ImportedProfileApi.Result.Ok ->
                            Log.i(TAG, "Backfilled ${profile.source.wireValue} profile")
                        is ImportedProfileApi.Result.Err ->
                            Log.w(TAG, "Backfill failed for ${profile.source.wireValue}: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncPending failed: ${e.message}")
            }
        }
    }
}
