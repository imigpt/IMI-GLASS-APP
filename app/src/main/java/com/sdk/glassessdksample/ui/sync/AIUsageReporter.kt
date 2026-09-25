package com.sdk.glassessdksample.ui.sync

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.type.UsageMetadata
import com.sdk.glassessdksample.auth.AuthApi
import com.sdk.glassessdksample.auth.SessionManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reports per-call Gemini token usage to the backend (`POST /v1/ai/usage`) so
 * admin can attribute AI cost to individual users. Android port of the iOS
 * `AIUsageReporter.swift`; keep the two in step.
 *
 * Why this exists: the Gemini key lives on-device and every call goes straight
 * from the app to Google, so the backend never sees that traffic. A call that
 * isn't reported here is invisible in the admin cost dashboard.
 *
 * Two rules from the backend contract:
 *  - Never send a cost. `costUsd` is computed server-side from the token counts
 *    (the request schema has no cost field), which keeps pricing consistent
 *    across old app builds.
 *  - `model` must be the EXACT Gemini model id that was called — pricing is
 *    looked up by that string and an unknown id prices at $0.
 *
 * Fire-and-forget: a missed report is a gap in an admin dashboard, not a
 * user-facing failure, so errors are swallowed and nothing is retried.
 */
object AIUsageReporter {

    private const val TAG = "AIUsageReporter"
    private const val PATH = "/v1/ai/usage"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** Matches the backend enum exactly — any other value is rejected with a 400. */
    enum class Feature(val wire: String) {
        /** Real-time speech-to-speech (Gemini Live). */
        LIVE("live"),
        /** Turn-based text chat. */
        CHAT("chat"),
        /** Image analysis. */
        VISION("vision"),
        /** Transcription / summary generation. */
        SUMMARIZE("summarize"),
        OTHER("other")
    }

    // Single thread keeps reports in order and off the caller's thread.
    private val io = Executors.newSingleThreadExecutor()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Convenience for SDK `generateContent` callers. No-op when [usage] is null. */
    fun report(context: Context?, model: String, usage: UsageMetadata?, feature: Feature) {
        if (usage == null) return
        val prompt = usage.promptTokenCount.coerceAtLeast(0)
        // For 2.5 models totalTokenCount also includes thinking tokens, which are
        // billed as output but not included in candidatesTokenCount. Deriving
        // output from the total keeps them in; otherwise we'd under-report.
        val output = maxOf(
            usage.candidatesTokenCount,
            usage.totalTokenCount - prompt
        ).coerceAtLeast(0)
        report(context, model, prompt, output, feature)
    }

    /**
     * Reports one Gemini Live `usageMetadata` frame (parsed by Gson, so numbers
     * arrive as Double).
     *
     * Report every frame VERBATIM — do not diff consecutive readings. Live's
     * `promptTokenCount` grows each turn because the session re-sends the whole
     * conversation as context, and Google really does re-bill that context on
     * every turn. The growth is a repeated charge, not a running meter, so
     * de-duplicating it would under-bill the app's most expensive feature.
     * (Same decision as iOS; see backend doc §4.)
     */
    fun reportLiveFrame(context: Context?, model: String, usage: Map<*, *>) {
        fun count(key: String) = (usage[key] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0

        val prompt = count("promptTokenCount")
        // Live labels the generated side responseTokenCount; REST uses
        // candidatesTokenCount. Only one is present.
        val response = maxOf(count("responseTokenCount"), count("candidatesTokenCount"))
        // Both are billed as output, and Live incurs tool-use tokens constantly
        // (every session declares function tools + Search grounding).
        val thoughts = count("thoughtsTokenCount")
        val toolUse = count("toolUsePromptTokenCount")

        if (prompt == 0 && response == 0 && thoughts == 0 && toolUse == 0) {
            // Only a total with no split: attribute it to output, the more
            // expensive side, rather than guess low.
            report(context, model, 0, count("totalTokenCount"), Feature.LIVE)
            return
        }
        report(context, model, prompt, response + thoughts + toolUse, Feature.LIVE)
    }

    /** Report one Gemini call. Returns immediately; the POST runs on a background thread. */
    fun report(context: Context?, model: String, inputTokens: Int, outputTokens: Int, feature: Feature) {
        if (context == null || model.isBlank()) return
        // A call that consumed nothing costs nothing; skip the round trip.
        if (inputTokens <= 0 && outputTokens <= 0) return
        val ctx = context.applicationContext
        // No session → no user to bill it to; the endpoint would just 401.
        if (!SessionManager(ctx).isLoggedIn) return

        val body = JSONObject().apply {
            put("model", model)
            put("inputTokens", inputTokens.coerceAtLeast(0))
            put("outputTokens", outputTokens.coerceAtLeast(0))
            put("feature", feature.wire)
            put("occurredAt", System.currentTimeMillis())
        }

        io.execute {
            try {
                val token = AuthApi(ctx).ensureValidAccessToken() ?: return@execute
                val request = Request.Builder()
                    .url("${AuthApi.BASE_URL}$PATH")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "📊 AI usage reported: $model ${inputTokens}in/${outputTokens}out [${feature.wire}]")
                    } else {
                        Log.w(TAG, "AI usage report HTTP ${response.code} (ignored): ${response.body?.string()?.take(300)}")
                    }
                }
            } catch (e: Exception) {
                // Best-effort by design — never surfaced, never retried.
                Log.w(TAG, "AI usage report failed (ignored): ${e.message}")
            }
        }
    }
}
