package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.auth.AuthApi
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
 * A single vision record as returned by the `/v1/gallery/photos` and
 * `/v1/vision/descriptions` endpoints. A photo and its AI description are the
 * same underlying record: uploading a photo creates one with null query/desc;
 * posting a description fills those fields in (optionally on the same [id]).
 */
data class VisionRecord(
    val id: String,
    val deviceType: String,
    val source: String,
    val imageUrl: String?,
    val thumbnailUrl: String?,
    val userQuery: String?,
    val aiDescription: String?,
    val sessionId: String?,
    val capturedAt: Long,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Networking layer for the IMI Glass Vision API (Part 1 of the backend spec):
 * photo uploads (`/v1/gallery/photos`) and AI descriptions
 * (`/v1/vision/descriptions`) — both halves of one unified vision record.
 *
 * Mirrors [NotesMeetingsApi]'s conventions: JWT bearer auth, the shared base URL,
 * the `{ "error": { "message": ... } }` envelope, and blocking calls that MUST be
 * invoked off the main thread (the [VisionSync] orchestrator handles that).
 */
class VisionApi(context: Context) {

    private val appContext = context.applicationContext
    private val authApi = AuthApi(appContext)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val message: String, val auth: Boolean = false, val code: Int = 0) : Result<Nothing>()
    }

    // ---------------------------------------------------------------------
    // Photos (/v1/gallery/photos)
    // ---------------------------------------------------------------------

    /**
     * Upload a captured photo. [source] is "BLE"/"WiFi"; [deviceType] "MARK1"/"MARK2".
     * Returns the created [VisionRecord] (with id + imageUrl, null query/description).
     */
    fun uploadPhoto(
        imageFile: File,
        source: String = "BLE",
        deviceType: String = "MARK2",
        capturedAt: Long = System.currentTimeMillis()
    ): Result<VisionRecord> {
        val token = authApi.ensureValidAccessToken()
            ?: return Result.Err("Not signed in", auth = true)
        val mime = if (imageFile.extension.equals("png", true)) "image/png" else "image/jpeg"
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", imageFile.name, imageFile.asRequestBody(mime.toMediaType()))
            .addFormDataPart("source", source)
            .addFormDataPart("deviceType", deviceType)
            .addFormDataPart("capturedAt", capturedAt.toString())
            .build()
        val req = Request.Builder()
            .url("${AuthApi.BASE_URL}/v1/gallery/photos")
            .post(multipart)
            .addHeader("Authorization", "Bearer $token")
            .build()
        return execute(req) { parseRecord(it) }
    }

    /** GET /v1/gallery/photos — paged list of the user's vision records. */
    fun listPhotos(): Result<List<VisionRecord>> = listAll("/v1/gallery/photos")

    fun getPhoto(id: String): Result<VisionRecord> =
        request("GET", "/v1/gallery/photos/$id", null) { parseRecord(it) }

    fun deletePhoto(id: String): Result<Unit> =
        request("DELETE", "/v1/gallery/photos/$id", null) { }

    /** DELETE /v1/gallery/photos — clears every photo; returns the count removed. */
    fun deleteAllPhotos(): Result<Int> =
        request("DELETE", "/v1/gallery/photos", null) { it.optInt("deleted", 0) }

    // ---------------------------------------------------------------------
    // Descriptions (/v1/vision/descriptions)
    // ---------------------------------------------------------------------

    /**
     * Create or update the AI description for a photo. Omit [id] to create a fresh
     * record; pass an existing record's id to attach the description to it.
     */
    fun saveDescription(
        id: String? = null,
        deviceType: String = "MARK2",
        source: String = "BLE",
        imageUrl: String? = null,
        userQuery: String? = null,
        aiDescription: String? = null,
        sessionId: String? = null,
        capturedAt: Long = System.currentTimeMillis()
    ): Result<VisionRecord> {
        val body = JSONObject().apply {
            id?.let { put("id", it) }
            put("deviceType", deviceType)
            put("source", source)
            imageUrl?.let { put("imageUrl", it) }
            userQuery?.let { put("userQuery", it) }
            aiDescription?.let { put("aiDescription", it) }
            sessionId?.let { put("sessionId", it) }
            put("capturedAt", capturedAt)
        }
        return request("POST", "/v1/vision/descriptions", body) { parseRecord(it) }
    }

    fun listDescriptions(): Result<List<VisionRecord>> = listAll("/v1/vision/descriptions")

    /** GET /v1/vision/descriptions/last — most recent record (404 → null). */
    fun getLastDescription(): Result<VisionRecord?> =
        when (val r = request("GET", "/v1/vision/descriptions/last", null) { parseRecord(it) }) {
            is Result.Ok -> Result.Ok(r.value)
            is Result.Err -> if (r.code == 404) Result.Ok(null) else r
        }

    fun deleteDescription(id: String): Result<Unit> =
        request("DELETE", "/v1/vision/descriptions/$id", null) { }

    fun deleteAllDescriptions(): Result<Int> =
        request("DELETE", "/v1/vision/descriptions", null) { it.optInt("deleted", 0) }

    // ---------------------------------------------------------------------
    // JSON mapping
    // ---------------------------------------------------------------------

    private fun parseRecord(json: JSONObject): VisionRecord = VisionRecord(
        id = json.optString("id"),
        deviceType = json.optString("deviceType"),
        source = json.optString("source"),
        imageUrl = json.optString("imageUrl").takeIf { it.isNotBlank() && it != "null" },
        thumbnailUrl = json.optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
        userQuery = json.optString("userQuery").takeIf { it.isNotBlank() && it != "null" },
        aiDescription = json.optString("aiDescription").takeIf { it.isNotBlank() && it != "null" },
        sessionId = json.optString("sessionId").takeIf { it.isNotBlank() && it != "null" },
        capturedAt = json.optLong("capturedAt", System.currentTimeMillis()),
        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
    )

    // ---------------------------------------------------------------------
    // HTTP plumbing (shared shape with NotesMeetingsApi)
    // ---------------------------------------------------------------------

    /** Pages through a list endpoint (pageSize=100) until `hasMore` is false. */
    private fun listAll(path: String): Result<List<VisionRecord>> {
        val out = mutableListOf<VisionRecord>()
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
                        items.optJSONObject(i)?.let { out.add(parseRecord(it)) }
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
        private const val TAG = "VisionApi"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = "".toRequestBody(null)
    }
}
