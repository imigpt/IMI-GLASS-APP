package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.SessionManager
import com.sdk.glassessdksample.ui.MeetingMinute
import com.sdk.glassessdksample.ui.MeetingMinutesManager
import com.sdk.glassessdksample.ui.QuickNote
import com.sdk.glassessdksample.ui.QuickNotesManager
import java.io.File
import java.util.concurrent.Executors

/**
 * Offline-first sync orchestrator for Quick Notes and Meeting Minutes.
 *
 * The local [QuickNotesManager] / [MeetingMinutesManager] remain the source of
 * truth the UI reads synchronously. Every local mutation also calls one of the
 * `push*` helpers here, which run the matching [NotesMeetingsApi] call on a
 * background thread — fire-and-forget, so the UI never blocks on the network.
 *
 * On launch (after login) [syncOnLaunch] runs a one-time bulk migration of any
 * pre-existing local data, then pulls the server copy down into the local cache
 * so the data follows the user across devices/reinstalls.
 */
object BackendSync {

    private const val TAG = "BackendSync"
    private const val PREFS = "backend_sync_prefs"
    private const val KEY_MIGRATED = "migrated_v1"
    private const val KEY_LAST_PULL = "last_pull_at"

    private val io = Executors.newSingleThreadExecutor()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isLoggedIn(ctx: Context) = SessionManager(ctx).isLoggedIn

    // ---------------------------------------------------------------------
    // Launch sync: one-time migration + pull
    // ---------------------------------------------------------------------

    /**
     * Call once when the app launches with a logged-in user (e.g. from Splash).
     * Safe to call repeatedly: migration only runs until it succeeds once.
     */
    fun syncOnLaunch(context: Context) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) return
        io.execute {
            try {
                migrateIfNeeded(ctx)
                pullIntoCache(ctx)
                prefs(ctx).edit().putLong(KEY_LAST_PULL, System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                Log.w(TAG, "syncOnLaunch failed: ${e.message}")
            }
        }
    }

    /** Push all existing local notes/meetings once via the bulk endpoints. */
    private fun migrateIfNeeded(ctx: Context) {
        if (prefs(ctx).getBoolean(KEY_MIGRATED, false)) return
        val api = NotesMeetingsApi(ctx)

        val localNotes = QuickNotesManager(ctx).getAllNotes()
        val notesOk = localNotes.isEmpty() || run {
            localNotes.chunked(200).all { batch ->
                api.bulkImportNotes(batch) is NotesMeetingsApi.Result.Ok
            }
        }

        val localMeetings = MeetingMinutesManager(ctx).getAllMeetings()
        val meetingsOk = localMeetings.isEmpty() || run {
            localMeetings.chunked(200).all { batch ->
                api.bulkImportMeetings(batch) is NotesMeetingsApi.Result.Ok
            }
        }

        if (notesOk && meetingsOk) {
            prefs(ctx).edit().putBoolean(KEY_MIGRATED, true).apply()
            Log.d(TAG, "Migration complete: ${localNotes.size} notes, ${localMeetings.size} meetings")
        } else {
            Log.w(TAG, "Migration incomplete; will retry next launch")
        }
    }

    /** Replace the local cache with the server's copy (server is source of truth post-migration). */
    private fun pullIntoCache(ctx: Context) {
        val api = NotesMeetingsApi(ctx)

        (api.listAllNotes() as? NotesMeetingsApi.Result.Ok)?.let { result ->
            QuickNotesManager(ctx).replaceAllFromServer(result.value)
            Log.d(TAG, "Pulled ${result.value.size} notes")
        }
        (api.listAllMeetings() as? NotesMeetingsApi.Result.Ok)?.let { result ->
            MeetingMinutesManager(ctx).replaceAllFromServer(result.value)
            Log.d(TAG, "Pulled ${result.value.size} meetings")
        }
    }

    // ---------------------------------------------------------------------
    // Fire-and-forget pushes (called from the managers on each mutation)
    // ---------------------------------------------------------------------

    fun pushCreateNote(ctx: Context, note: QuickNote) = run(ctx) { api ->
        api.createNote(note).logIfErr("createNote")
    }

    fun pushUpdateNote(ctx: Context, note: QuickNote) = run(ctx) { api ->
        api.updateNote(note).logIfErr("updateNote")
    }

    fun pushDeleteNote(ctx: Context, noteId: String) = run(ctx) { api ->
        api.deleteNote(noteId).logIfErr("deleteNote")
    }

    /** Upload an image file for a note (used by AI photo notes). */
    fun pushNoteImage(ctx: Context, noteId: String, imagePath: String) = run(ctx) { api ->
        val file = File(imagePath)
        if (file.exists()) api.uploadNoteImage(noteId, file).logIfErr("uploadNoteImage")
    }

    fun pushStartMeeting(ctx: Context, meeting: MeetingMinute) = run(ctx) { api ->
        api.startMeeting(meeting).logIfErr("startMeeting")
    }

    fun pushAppendTranscript(ctx: Context, meetingId: String, text: String) = run(ctx) { api ->
        if (text.isNotBlank()) api.appendTranscript(meetingId, text).logIfErr("appendTranscript")
    }

    fun pushUpdateMeeting(ctx: Context, meeting: MeetingMinute) = run(ctx) { api ->
        api.updateMeeting(meeting).logIfErr("updateMeeting")
    }

    fun pushCancelMeeting(ctx: Context, meetingId: String) = run(ctx) { api ->
        api.cancelMeeting(meetingId).logIfErr("cancelMeeting")
    }

    fun pushDeleteMeeting(ctx: Context, meetingId: String) = run(ctx) { api ->
        api.deleteMeeting(meetingId).logIfErr("deleteMeeting")
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private inline fun run(context: Context, crossinline block: (NotesMeetingsApi) -> Unit) {
        val ctx = context.applicationContext
        if (!isLoggedIn(ctx)) return
        io.execute {
            try {
                block(NotesMeetingsApi(ctx))
            } catch (e: Exception) {
                Log.w(TAG, "push failed: ${e.message}")
            }
        }
    }

    private fun NotesMeetingsApi.Result<*>.logIfErr(op: String) {
        if (this is NotesMeetingsApi.Result.Err) Log.w(TAG, "$op -> $message")
    }
}
