package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.sdk.glassessdksample.RemoteConfigManager
import com.sdk.glassessdksample.ui.TokenUsageTracker
import com.sdk.glassessdksample.ui.UsageLimitManager
import com.sdk.glassessdksample.ui.sync.AIUsageReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Plans the next action from a PICTURE of the page, when the DOM tells us
 * nothing.
 *
 * [WebAgentPlanner] reads a structured summary of the page and is the right
 * tool almost always: it is instant, free, and gives exact selectors. But a
 * site that draws its controls as plain `<div>`s — MakeMyTrip's entire flight
 * form, for one — produces a summary with no origin, no destination and no
 * search button, and the agent correctly reports that there is nothing it can
 * see to interact with. No amount of widening the DOM query finds a control
 * that was never marked up as one.
 *
 * So this exists as the last resort, and is deliberately expensive to reach:
 * a screenshot costs roughly 2000 input tokens and a second or two per step,
 * against approximately nothing for a DOM read. [HeadlessAgentRunner] calls it
 * only when the text summary comes back with no actionable elements at all.
 *
 * It returns coordinates rather than selectors, because coordinates are what a
 * picture can honestly provide.
 */
class VisionPlanner(private val context: Context?) {

    private val usageMode = TokenUsageTracker.Mode.AI_CHAT

    private val systemPrompt = """
        You are looking at a SCREENSHOT of a web page in a phone browser, and
        deciding the single next action towards the user's goal.

        You are seeing a picture because this page's controls could not be read
        from its code — they are custom widgets. So work entirely from what is
        visible in the image.

        Reply with exactly ONE JSON object, one of:
        {"action":"tap_at","x":<int>,"y":<int>,"label":"what you are tapping"}
        {"action":"scroll","amount":0.8}
        {"action":"open","url":"https://..."}
        {"action":"back"}
        {"action":"ask_user","question":"..."}
        {"action":"done","summary":"..."}
        {"action":"failed","reason":"..."}

        COORDINATES: x and y are pixels in the image you were given, with 0,0 at
        the TOP-LEFT. Aim for the CENTRE of the thing you want to tap. Be precise
        — a coordinate that misses lands on empty background and does nothing.

        Rules:
        - One action only. The page changes after every action.
        - To fill a field, tap it first; typing is handled elsewhere.
        - NEVER tap anything that submits a payment, and never try to fill a
          password, OTP or card field. Use "ask_user" for those.
        - If the page is a sign-in wall or a CAPTCHA, use "failed" and say so
          plainly — a person has to do that part.
        - If you cannot see what the goal needs, "scroll" to look further down
          before giving up.
        - "open" can only reach https://chatgpt.com and https://claude.ai. Any
          other URL is refused, and there is no web search.

        JSON only.
    """.trimIndent()

    /**
     * Plans one action from [imageBase64].
     *
     * [imageWidth]/[imageHeight] are the pixel size of the image as sent, so
     * the caller can scale returned coordinates back to CSS pixels.
     */
    suspend fun planFromImage(
        goal: String,
        imageBase64: String,
        history: List<ActionResult>
    ): BrowserAction? {
        if (!UsageLimitManager.tryConsume(context, usageMode)) {
            return BrowserAction.Failed(UsageLimitManager.limitReachedMessage(usageMode))
        }

        val bytes = try {
            android.util.Base64.decode(imageBase64, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Bad screenshot payload", e)
            return null
        }

        val bitmap = try {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "Could not decode screenshot", e)
            return null
        }

        val prompt = buildString {
            append("GOAL: ").append(goal).append("\n\n")
            if (history.isNotEmpty()) {
                append("WHAT YOU HAVE ALREADY DONE:\n")
                history.takeLast(MAX_HISTORY).forEach { r ->
                    append("- ").append(r.action.describe())
                        .append(if (r.success) " -> ok" else " -> FAILED: ${r.detail}")
                        .append('\n')
                }
                append('\n')
            }
            append("The image is ").append(bitmap.width).append(" by ")
                .append(bitmap.height).append(" pixels.\n")
            append("What is your next single action? JSON only.")
        }

        return try {
            withContext(Dispatchers.IO) {
                var lastError: Exception? = null
                for (modelName in CANDIDATE_MODELS) {
                    try {
                        val response = model(modelName).generateContent(
                            content {
                                image(bitmap)
                                text(prompt)
                            }
                        )
                        TokenUsageTracker.track(context, usageMode, response.usageMetadata, modelName, AIUsageReporter.Feature.VISION)
                        val text = response.text?.trim().orEmpty()
                        if (text.isBlank()) {
                            lastError = IllegalStateException("empty response")
                            continue
                        }
                        return@withContext parse(text)
                    } catch (e: Exception) {
                        lastError = e
                        if (isModelNotFound(e)) continue
                        throw e
                    }
                }
                Log.e(TAG, "Vision planning failed", lastError)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vision planner error: ${e.message}", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun model(modelName: String) = GenerativeModel(
        modelName = modelName,
        apiKey = RemoteConfigManager.geminiApiKey,
        systemInstruction = content { text(systemPrompt) },
        generationConfig = generationConfig {
            temperature = 0.1f
            responseMimeType = "application/json"
            maxOutputTokens = 1024
        }
    )

    private fun parse(text: String): BrowserAction? {
        val cleaned = text.removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            BrowserAction.fromJson(JSONObject(cleaned.substring(start, end + 1)))
        } catch (e: Exception) {
            Log.w(TAG, "Unparseable vision plan: $text")
            null
        }
    }

    private fun isModelNotFound(e: Exception): Boolean {
        val m = e.message.orEmpty()
        return m.contains("404") || m.contains("Not Found", ignoreCase = true)
    }

    companion object {
        private const val TAG = "VisionPlanner"

        /** Vision-capable, cheapest first. */
        private val CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash"
        )

        private const val MAX_HISTORY = 6
    }
}
