package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.sdk.glassessdksample.RemoteConfigManager
import com.sdk.glassessdksample.ui.TokenUsageTracker
import com.sdk.glassessdksample.ui.UsageLimitManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Decides the agent's next single action, using the Gemini key this app
 * already ships with.
 *
 * Design notes:
 * - **One action per call.** The model never plans a whole sequence up front;
 *   it sees the page as it actually is and picks the next move. Web pages
 *   change under you, so a plan made three steps ago is usually wrong.
 * - **JSON response mode.** `responseMimeType = application/json` removes the
 *   markdown-fence parsing that otherwise breaks these loops.
 * - The model returns an action *name*, never code. Executing it is
 *   [ActionExecutor]'s job and permitting it is [ActionValidator]'s.
 */
class WebAgentPlanner(private val context: Context?) {

    /** Reuses the AI_CHAT budget rather than adding a metering mode. */
    private val usageMode = TokenUsageTracker.Mode.AI_CHAT

    private val systemPrompt = """
        You are the browser agent inside the Imi Glass app. You control a real
        Android WebView on the user's phone to accomplish the user's goal.

        You are given the user's goal, what you have done so far, and a summary
        of the CURRENT page. Reply with exactly ONE next action as JSON.

        Allowed actions (JSON shapes):
        {"action":"open","url":"https://..."}
        {"action":"click","selector":"<selector from the page summary>","label":"what it is"}
        {"action":"type","selector":"<selector>","text":"...","submit":true|false}
        {"action":"scroll","amount":0.8}
        {"action":"back"} {"action":"forward"} {"action":"reload"}
        {"action":"wait","reason":"..."}
        {"action":"ask_user","question":"..."}
        {"action":"handoff","reason":"..."}
        {"action":"done","summary":"..."}
        {"action":"failed","reason":"..."}

        Hard rules:
        1. Use selectors that appear verbatim in the page summary wherever one
           exists. Never invent a CSS selector.
           ONE exception, for "click" only: when you need to pick something the
           page has just created in response to your own typing — an option in
           an autocomplete or suggestion dropdown, which cannot be in a summary
           taken before you typed — put the option's EXACT VISIBLE TEXT in
           "selector" instead, and what it is in "label". The browser will match
           it by what is on screen. Use this only for that case; for anything
           already listed in the summary, quote its real selector.
           After typing into a city, airport, station or address field, expect a
           dropdown: read the next summary, and if the option you want is listed
           there, click it by its real selector.
        2. NEVER type into a field marked [SENSITIVE]. Never type passwords,
           OTPs, card numbers or PINs anywhere. For those, use "handoff".
        3. If the CURRENT page shows a login screen or a CAPTCHA and the goal
           actually requires using THIS page (reading it, clicking something on
           it, submitting a form on it), use "handoff" and explain what the
           user should do. But if the goal is to go somewhere else entirely
           (e.g. the goal is about Claude but the current page happens to be
           some other login/CAPTCHA screen), just "open" the site the goal
           actually asks for - leaving an unrelated blocked page needs no
           handoff, since you are not interacting with it.
        4. If you need information only the user has (an address, a date, a
           choice between options), use "ask_user" with one clear question.
        5a. NEVER "open" a URL you are ALREADY on. Look at the CURRENT PAGE url
           first: if you are on chatgpt.com and the goal is a ChatGPT task, the
           page is already there — act on it, do not reload it. Re-opening the
           same page throws away everything that has loaded and puts you back
           where you started, which is an infinite loop, not progress. If the
           page looks empty, prefer "wait" once to let it finish rendering.
        5. You can ONLY reach two sites: https://chatgpt.com and
           https://claude.ai. "open" any other URL and it will be refused.
           There is NO web search — no Google, no Bing. If the goal needs a
           different site, do not try to navigate there and do not look for a
           way around it: use "failed" and say the browser is limited to
           ChatGPT and Claude.
           To search WITHIN one of these two sites, use that site's own search
           box: type into the field whose label or placeholder looks like
           search, with submit true. A search box is an input — look under
           INPUTS in the summary, not BUTTONS. If the summary was truncated and
           you cannot find it, scroll or reload and read again; do not invent a
           selector.
        6. When the goal is met, use "done" with a short summary that answers
           the user's actual question. If you have read what the user asked
           for, put the answer in the summary itself.
        7. If you are stuck or the site blocks automation, use "failed" with a
           plain explanation. Do not loop.
        8. Do not repeat an action that has just failed. Try something else.
        8a. NEVER type the same text into the same field twice. If the history
           shows you already typed it, the text IS in the field — typing it
           again achieves nothing. A whole run has been lost to exactly this.
           After typing a search term, the next action is ALWAYS one of:
           re-issue "type" with submit true to press Enter, or "click" the
           search button or a suggestion. If the page still looks unchanged
           after that, the field may not be the real search box — look for a
           different one, or open the site's search URL directly.
        8b. Check the INPUTS list before typing. If a field already shows
           current="your text", it is filled — move on to submitting it.
        9. You CAN navigate this browser's history. "back" and "forward" are
           yours to use and need no permission from anyone — going back to a
           search results page to try a different result is a normal, expected
           move. The page summary tells you CAN_GO_BACK and CAN_GO_FORWARD; use
           "back" whenever that says true and stepping back is useful.
           If CAN_GO_BACK is false there is simply no earlier page in this
           browser yet — that is a fact about the history, NOT a restriction on
           you. In that case use "open" to get where you need to be.
           NEVER tell the user you are not allowed to navigate between pages, or
           that you lack permission to go back. That is untrue.

        Reply with JSON only.
    """.trimIndent()

    /**
     * Plans one step. Returns null when the model or the key is unavailable,
     * or when the reply couldn't be read as a known action.
     */
    suspend fun planNext(
        goal: String,
        page: PageReader.PageSnapshot?,
        history: List<ActionResult>,
        userAnswers: List<Pair<String, String>>
    ): BrowserAction? {
        if (!UsageLimitManager.tryConsume(context, usageMode)) {
            UsageLimitManager.promptUpgradeIfPossible(context, usageMode)
            return BrowserAction.Failed(UsageLimitManager.limitReachedMessage(usageMode))
        }

        val prompt = buildPrompt(goal, page, history, userAnswers)

        return try {
            withContext(Dispatchers.IO) {
                var lastError: Exception? = null
                for (modelName in CANDIDATE_MODELS) {
                    try {
                        val response = model(modelName).generateContent(prompt)
                        TokenUsageTracker.track(context, usageMode, response.usageMetadata)

                        val text = response.text?.trim().orEmpty()
                        if (text.isBlank()) {
                            lastError = IllegalStateException("empty response")
                            continue
                        }
                        return@withContext parseAction(text)
                    } catch (e: Exception) {
                        lastError = e
                        // Same fallback ladder GeminiAIClient uses: walk down
                        // to a model this key can actually reach.
                        if (isModelNotFound(e)) {
                            Log.w(TAG, "Model $modelName unavailable, trying next")
                            continue
                        }
                        throw e
                    }
                }
                Log.e(TAG, "Planning failed", lastError)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Planner error: ${e.message}", e)
            // Running out of output budget is a DIFFERENT thing from not
            // understanding the page, and collapsing both into null told the
            // user the agent was confused when it had actually been cut off
            // mid-sentence. Say what happened instead of guessing.
            if (isMaxTokens(e)) {
                BrowserAction.Failed(
                    "That page was too big for me to work through. Try a simpler " +
                        "page, or narrow down what you're after."
                )
            } else {
                null
            }
        }
    }

    /** True when generation stopped because it hit the output cap. */
    private fun isMaxTokens(e: Exception): Boolean =
        e.message.orEmpty().contains("MAX_TOKENS", ignoreCase = true) ||
            e is com.google.ai.client.generativeai.type.ResponseStoppedException

    private fun model(modelName: String) = GenerativeModel(
        modelName = modelName,
        apiKey = RemoteConfigManager.geminiApiKey,
        systemInstruction = com.google.ai.client.generativeai.type.content {
            text(systemPrompt)
        },
        generationConfig = generationConfig {
            temperature = 0.1f          // planning wants determinism, not flair
            responseMimeType = "application/json"
            // 400 was too tight and failed in a way that looked like the whole
            // feature was broken. One action is small, but "done" and "failed"
            // carry a written summary, and a reasoning model spends tokens
            // before it emits any JSON at all — so on a big page (an Amazon
            // search result) generation stopped at the cap, the SDK threw
            // ResponseStoppedException(MAX_TOKENS), and the catch below turned
            // that into a null the user heard as "I couldn't work out how to do
            // that." Output tokens are cheap; a dead task is not.
            maxOutputTokens = 2048
        }
    )

    private fun buildPrompt(
        goal: String,
        page: PageReader.PageSnapshot?,
        history: List<ActionResult>,
        userAnswers: List<Pair<String, String>>
    ): String {
        val sb = StringBuilder()
        sb.append("GOAL: ").append(goal).append("\n\n")

        if (userAnswers.isNotEmpty()) {
            sb.append("ANSWERS THE USER GAVE YOU:\n")
            userAnswers.takeLast(MAX_ANSWERS).forEach { (q, a) ->
                sb.append("- Q: ").append(q).append("\n  A: ").append(a).append('\n')
            }
            sb.append('\n')
        }

        if (history.isNotEmpty()) {
            sb.append("STEPS SO FAR (oldest first):\n")
            history.takeLast(MAX_HISTORY).forEach { r ->
                val mark = if (r.success) "ok" else "FAILED"
                sb.append("- ").append(r.action.describe())
                    .append(" -> ").append(mark).append(": ").append(r.detail).append('\n')
            }
            sb.append('\n')
        }

        if (page != null && page.ok) {
            sb.append("CURRENT PAGE:\n").append(trim(page.toPromptText()))
        } else {
            sb.append("CURRENT PAGE: blank — nothing is loaded yet.\n")
        }

        sb.append("\nWhat is your next single action? JSON only.")
        return sb.toString()
    }

    private fun parseAction(text: String): BrowserAction? {
        // JSON mode makes fences rare but not impossible; strip them if present.
        val cleaned = text
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        // Take the outermost JSON object, ignoring any prose around it.
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            Log.w(TAG, "No JSON object in plan: $text")
            return null
        }
        val jsonText = cleaned.substring(start, end + 1)

        return try {
            BrowserAction.fromJson(JSONObject(jsonText))
        } catch (e: Exception) {
            Log.w(TAG, "Unparseable plan: $text")
            null
        }
    }

    private fun trim(text: String): String =
        if (text.length <= MAX_PAGE_CHARS) text
        else text.take(MAX_PAGE_CHARS) +
            "\n[... page summary truncated — if what you need isn't listed, " +
            "scroll or use a different approach rather than inventing a selector]"

    private fun isModelNotFound(e: Exception): Boolean {
        val message = e.message.orEmpty()
        return message.contains("404") ||
            message.contains("Not Found", ignoreCase = true) ||
            e.stackTraceToString().contains("404")
    }

    companion object {
        private const val TAG = "WebAgentPlanner"

        private val CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite"
        )

        /**
         * How much of the page summary reaches the model.
         *
         * Raised from 6000. The widened element selector legitimately finds far
         * more controls on a modern site, and at 6000 the summary was being
         * chopped mid-list — so the planner quoted a selector from a truncated
         * view and got "field not found" for a search box that was plainly on
         * screen. A Flipkart search burned 200 steps on exactly that. Input
         * tokens are cheap next to a task that cannot finish.
         */
        private const val MAX_PAGE_CHARS = 24000
        /**
         * How many past steps the planner sees.
         *
         * Raised from 8. A loop longer than the window is invisible: the model
         * could type the same thing into the same box a dozen times and never
         * see enough history to notice it was repeating itself.
         */
        private const val MAX_HISTORY = 20
        private const val MAX_ANSWERS = 5
    }
}
