package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.AuthApi
import com.sdk.glassessdksample.auth.SessionManager
import com.sdk.glassessdksample.ui.MeetingMinute
import com.sdk.glassessdksample.ui.QuickNote
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Networking layer for the Quick Notes and Meeting Minutes backend
 * (`/v1/notes` and `/v1/meetings`), per the Frontend Integration Guide.
 *
 * Mirrors [AuthApi]'s conventions: JWT bearer auth, the same base URL, and the
 * `{ "error": { "message": ... } }` error envelope. Every call is **blocking**
 * and MUST be invoked off the main thread (the higher-level [BackendSync]
 * scheduler runs these on a background executor).
 *
 * Each public method returns a typed [Result] rather than throwing, so callers
 * can decide whether a failure should be retried or surfaced.
 */
class NotesMeetingsApi(context: Context) {

    private val appContext = context.applicationContext
    private val authApi = AuthApi(appContext)
    private val session = SessionManager(appContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        /** [auth] is true when the failure was a missing/expired session (caller should stop, not retry). */
        data class Err(val message: String, val auth: Boolean = false) : Result<Nothing>()
    }

    // ---------------------------------------------------------------------
    // Quick Notes
    // ---------------------------------------------------------------------

    fun createNote(note: QuickNote): Result<QuickNote> =
        request("POST", "/v1/notes", noteToJson(note)) { parseNote(it) }

    fun updateNote(note: QuickNote): Result<QuickNote> {
        val body = JSONObject()
            .put("title", note.title)
            .put("content", note.content)
        val result = request("PUT", "/v1/notes/${note.id}", body) { parseNote(it) }
        // If the note doesn't exist on the server yet, create it so data isn't lost.
        if (result is Result.Err && !result.auth) {
            Log.d(TAG, "updateNote PUT failed (${result.message}); attempting upsert via POST")
            return createNote(note)
        }
        return result
    }

    fun deleteNote(noteId: String): Result<Unit> =
        request("DELETE", "/v1/notes/$noteId", null) { }

    /** Pull every note for the current user, paging until exhausted. */
    fun listAllNotes(): Result<List<QuickNote>> =
        listAll("/v1/notes") { parseNote(it) }

    fun bulkImportNotes(notes: List<QuickNote>): Result<Int> {
        val items = JSONArray()
        notes.forEach { items.put(noteToJson(it)) }
        val body = JSONObject().put("items", items)
        return request("POST", "/v1/notes/bulk", body) { it.optInt("imported", 0) }
    }

    fun uploadNoteImage(noteId: String, imageFile: File): Result<String> {
        val token = authApi.ensureValidAccessToken()
            ?: return Result.Err("Not signed in", auth = true)
        val mime = if (imageFile.extension.equals("png", true)) "image/png" else "image/jpeg"
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", imageFile.name, imageFile.asRequestBody(mime.toMediaType()))
            .build()
        val req = Request.Builder()
            .url("${AuthApi.BASE_URL}/v1/notes/$noteId/image")
            .post(multipart)
            .addHeader("Authorization", "Bearer $token")
            .build()
        return execute(req) { it.optString("imageUrl") }
    }

    // ---------------------------------------------------------------------
    // Meeting Minutes
    // ---------------------------------------------------------------------

    fun startMeeting(meeting: MeetingMinute): Result<MeetingMinute> {
        val body = JSONObject()
            .put("id", meeting.id)
            .put("title", meeting.title)
            .put("startTime", meeting.startTime)
        return request("POST", "/v1/meetings", body) { parseMeeting(it) }
    }

    fun appendTranscript(meetingId: String, text: String): Result<MeetingMinute> {
        val body = JSONObject().put("text", text)
        return request("PATCH", "/v1/meetings/$meetingId/transcript", body) { parseMeeting(it) }
    }

    /** Used to end a meeting (set endTime/summary/isActive=false) or otherwise update it. */
    fun updateMeeting(meeting: MeetingMinute): Result<MeetingMinute> {
        val body = JSONObject()
            .put("endTime", meeting.endTime)
            .put("summary", meeting.summary)
            .put("transcript", meeting.transcript)
            .put("speakerTranscript", meeting.speakerTranscript)
            .put("speakerCount", meeting.speakerCount)
            .put("participants", meeting.participants)
            // Backend uses "active" (not "isActive") — send both to cover any inconsistency.
            .put("active", meeting.isActive)
            .put("isActive", meeting.isActive)
        val result = request("PUT", "/v1/meetings/${meeting.id}", body) { parseMeeting(it) }
        // If the meeting doesn't exist on the server yet (e.g. startMeeting push failed due to
        // network), create it now so the data isn't lost.
        if (result is Result.Err && !result.auth) {
            Log.d(TAG, "updateMeeting PUT failed (${result.message}); attempting upsert via POST")
            return upsertMeeting(meeting)
        }
        return result
    }

    /** Create a meeting on the server including all fields (upsert fallback). */
    private fun upsertMeeting(meeting: MeetingMinute): Result<MeetingMinute> {
        val body = JSONObject()
            .put("id", meeting.id)
            .put("title", meeting.title)
            .put("startTime", meeting.startTime)
            .put("endTime", meeting.endTime)
            .put("transcript", meeting.transcript)
            .put("summary", meeting.summary)
            .put("participants", meeting.participants)
            .put("speakerCount", meeting.speakerCount)
            .put("speakerTranscript", meeting.speakerTranscript)
            .put("active", meeting.isActive)
            .put("isActive", meeting.isActive)
        return request("POST", "/v1/meetings", body) { parseMeeting(it) }
    }

    fun cancelMeeting(meetingId: String): Result<Unit> =
        request("POST", "/v1/meetings/$meetingId/cancel", JSONObject()) { }

    fun deleteMeeting(meetingId: String): Result<Unit> =
        request("DELETE", "/v1/meetings/$meetingId", null) { }

    fun listAllMeetings(): Result<List<MeetingMinute>> =
        listAll("/v1/meetings") { parseMeeting(it) }

    fun bulkImportMeetings(meetings: List<MeetingMinute>): Result<Int> {
        val items = JSONArray()
        meetings.forEach { m ->
            items.put(
                JSONObject()
                    .put("id", m.id)
                    .put("title", m.title)
                    .put("startTime", m.startTime)
                    .put("endTime", m.endTime)
                    .put("transcript", m.transcript)
                    .put("summary", m.summary)
                    .put("participants", m.participants)
                    .put("speakerCount", m.speakerCount)
                    .put("speakerTranscript", m.speakerTranscript)
            )
        }
        val body = JSONObject().put("items", items)
        return request("POST", "/v1/meetings/bulk", body) { it.optInt("imported", 0) }
    }

    // ---------------------------------------------------------------------
    // JSON <-> model mapping
    // ---------------------------------------------------------------------

    private fun noteToJson(note: QuickNote): JSONObject = JSONObject()
        .put("id", note.id)
        .put("title", note.title)
        .put("content", note.content)
        .put("origin", note.createdBy.name)
        .put("noteTime", note.timestamp)

    private fun parseNote(json: JSONObject): QuickNote = QuickNote(
        id = json.optString("id"),
        title = json.optString("title"),
        content = json.optString("content"),
        imagePath = json.optString("imageUrl").takeIf { it.isNotBlank() && it != "null" },
        timestamp = json.optLong("noteTime", System.currentTimeMillis()),
        createdBy = if (json.optString("origin").equals("AI", true)) {
            QuickNote.CreatedBy.AI
        } else {
            QuickNote.CreatedBy.USER
        }
    )

    private fun parseMeeting(json: JSONObject): MeetingMinute = MeetingMinute(
        id = json.optString("id"),
        title = json.optString("title"),
        startTime = json.optLong("startTime", System.currentTimeMillis()),
        endTime = if (json.isNull("endTime")) json.optLong("startTime") else json.optLong("endTime"),
        transcript = json.optString("transcript"),
        summary = json.optString("summary"),
        participants = json.optString("participants"),
        // Guide returns `active`; tolerate `isActive` too.
        isActive = json.optBoolean("active", json.optBoolean("isActive", false)),
        speakerCount = json.optInt("speakerCount", 0),
        speakerTranscript = json.optString("speakerTranscript")
    )

    // ---------------------------------------------------------------------
    // HTTP plumbing
    // ---------------------------------------------------------------------

    /** Pages through a list endpoint (pageSize=100) until `hasMore` is false. */
    private fun <T> listAll(path: String, map: (JSONObject) -> T): Result<List<T>> {
        val out = mutableListOf<T>()
        var page = 1
        while (true) {
            val token = authApi.ensureValidAccessToken()
                ?: return Result.Err("Not signed in", auth = true)
            val url = "${AuthApi.BASE_URL}$path".toHttpUrlOrNull()?.newBuilder()
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
                        items.optJSONObject(i)?.let { out.add(map(it)) }
                    }
                    if (!r.value.optBoolean("hasMore", false) || items.length() == 0) {
                        return Result.Ok(out)
                    }
                    page++
                }
            }
        }
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
                } else {
                    if (response.code == 401) {
                        Result.Err("Session expired", auth = true)
                    } else {
                        Result.Err(parseError(raw, response.code))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request ${req.method} ${req.url} failed: ${e.message}")
            Result.Err("Network error")
        }
    }

    private fun parseError(raw: String, code: Int): String =
        runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: "Request failed (HTTP $code)"

    companion object {
        private const val TAG = "NotesMeetingsApi"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = "".toRequestBody(null)
    }
}
