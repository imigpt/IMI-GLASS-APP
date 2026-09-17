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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a spoken request into a plan the user approves before anything runs.
 *
 * This is the front half of a task: it asks for what it does not know, one
 * question at a time, and only then writes the plan. [WebAgentPlanner] remains
 * the back half — it decides each individual browser action once the plan has
 * been approved and execution starts.
 *
 * Why the split: the action planner sees one page at a time and cannot ask
 * anything, so anything it was not told it had to invent. A request like "book
 * me a flight to Jaipur" has at least four missing facts (date, time, return,
 * passengers), and inventing them is how the agent ended up deep inside a
 * booking flow for the wrong journey.
 */
class TaskPlanner(private val context: Context?) {

    /** Reuses the AI_CHAT budget rather than adding a metering mode. */
    private val usageMode = TokenUsageTracker.Mode.AI_CHAT

    private val systemPrompt = """
        You plan tasks that a browser agent will carry out on the user's Android
        phone, in a real WebView, on the live web.

        You are talking to someone wearing smart glasses. They speak; you reply
        out loud. Your job has two phases.

        PHASE 1 - GATHER. Ask for what you genuinely need and do not know.
        - ONE question per reply. Short, spoken, natural.
        - Never ask for a password, OTP, card number or any other credential.
          The user enters those themselves, on the phone, at the time.
        - If the user already said something, do not ask it again.

        DO NOT PLAN UNTIL YOU KNOW EVERYTHING THE TASK ACTUALLY NEEDS. Stopping
        after one question and planning on guesses is the most common way this
        goes wrong: the plan looks fine, then execution fills a form with
        invented values and books the wrong thing. Work out what a person doing
        this by hand would have to know, and ask for each missing piece.

        For a flight, that means ALL of: departure city, destination, date,
        one-way or return (and the return date if so), how many people, and any
        time-of-day preference. Asking only about time and planning the rest is
        NOT acceptable.
        For a hotel: city, check-in and check-out dates, how many guests, rooms.
        For a purchase: exactly which item, which variant or size, quantity.
        For a booking or reservation: date, time, how many people.

        Apply the same standard to anything else: list what the form on the
        other end will demand, and ask for whatever the user has not told you.
        Ask up to six questions. Only plan when nothing essential is missing, or
        when you have asked six — whichever comes first.

        PHASE 2 - PLAN. When you know enough, write the plan.
        - Steps in plain language, in order, as the user would describe them.
        - State anything you assumed rather than asked, so they can correct it.
        - Name every point where you will hand the phone over. You ALWAYS hand
          over for: signing in, CAPTCHAs, OTPs, and PAYMENT. You never pay for
          anything and never enter card details. Say so in the plan.
        - resolved_goal must restate the whole task with every answer folded in,
          because that string is the only thing the execution agent receives.

        PHASE 0 - CHECK IT IS POSSIBLE. Do this FIRST, before asking anything.
        Work out whether a browser on the user's phone could really carry this
        out. Things it CANNOT do:
        - anything on a site other than ChatGPT (chatgpt.com) and Claude
          (claude.ai). Those are the ONLY two sites reachable, and there is no
          web search. Shopping, booking, email, YouTube, maps and every other
          site are all out of scope — refuse those in PHASE 0 rather than
          planning a task that cannot run.
        - anything that needs a phone app rather than a website (WhatsApp, UPI
          apps, native banking apps)
        - anything needing the user's card details or a payment to complete —
          it can reach the payment page, never past it
        - anything on a site that requires a CAPTCHA on every action
        - anything physical, or anything outside a web browser entirely
        - anything illegal, or impersonating the user in a way they have not
          asked for

        If the task cannot be done, DO NOT just refuse. Say plainly that you
        cannot do that one, say WHY in a single clear sentence, and then offer
        the nearest thing you CAN do. Put the whole lot in "reason", written to
        be spoken aloud. For example: "I can't pay for it — I never handle card
        details. I can find it, put it in your basket and get you to the payment
        page, and you take it from there. Want me to do that?"
        Always offer an alternative when there is an honest one. A bare "I can't
        do that" is not acceptable.

        Reply with exactly ONE JSON object, in one of these shapes:
        {"phase":"question","question":"..."}
        {"phase":"plan","summary":"one line","steps":["...","..."],
         "assumptions":["..."],"handoff_points":["..."],"resolved_goal":"..."}
        {"phase":"refuse","reason":"..."}

        A site that merely MIGHT block automation is NOT a reason to refuse:
        plan it anyway and let execution report back honestly. Refuse only for
        the genuine impossibilities listed above.

        JSON only.
    """.trimIndent()

    /**
     * Works out the next conversational turn.
     *
     * [answers] is every question already asked and what the user said back, so
     * the model can see what it still needs.
     */
    suspend fun next(
        request: String,
        answers: List<Pair<String, String>>
    ): TaskTurn? {
        if (!UsageLimitManager.tryConsume(context, usageMode)) {
            UsageLimitManager.promptUpgradeIfPossible(context, usageMode)
            return TaskTurn.Refused(UsageLimitManager.limitReachedMessage(usageMode))
        }

        val prompt = buildPrompt(request, answers)

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
                        return@withContext parse(text)
                    } catch (e: Exception) {
                        lastError = e
                        if (isModelNotFound(e)) {
                            Log.w(TAG, "Model $modelName unavailable, trying next")
                            continue
                        }
                        throw e
                    }
                }
                Log.e(TAG, "Task planning failed", lastError)
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Task planner error: ${e.message}", e)
            // Same distinction WebAgentPlanner makes: being cut off is not the
            // same as not understanding, and the user should be told which.
            if (e.message.orEmpty().contains("MAX_TOKENS", ignoreCase = true) ||
                e is com.google.ai.client.generativeai.type.ResponseStoppedException
            ) {
                TaskTurn.Refused(
                    "That's a bigger task than I can plan in one go. Try breaking " +
                        "it into a smaller one."
                )
            } else {
                null
            }
        }
    }

    private fun model(modelName: String) = GenerativeModel(
        modelName = modelName,
        apiKey = RemoteConfigManager.geminiApiKey,
        systemInstruction = com.google.ai.client.generativeai.type.content {
            text(systemPrompt)
        },
        generationConfig = generationConfig {
            // Slightly above the action planner's 0.1: the questions should
            // sound like a person, not a form.
            temperature = 0.3f
            responseMimeType = "application/json"
            // A plan is several steps plus assumptions and handoff points, and
            // the model reasons before emitting any of it. 900 left very little
            // headroom — the same cap that broke WebAgentPlanner outright.
            maxOutputTokens = 2048
        }
    )

    private fun buildPrompt(
        request: String,
        answers: List<Pair<String, String>>
    ): String {
        val sb = StringBuilder()
        // Dates only mean something relative to today, and "next Friday" is a
        // very common way to book things.
        sb.append("TODAY: ")
            .append(SimpleDateFormat("EEEE d MMMM yyyy", Locale.UK).format(Date()))
            .append("\n\n")
        sb.append("WHAT THE USER ASKED FOR: ").append(request).append("\n\n")

        if (answers.isEmpty()) {
            sb.append("You have not asked anything yet.\n")
        } else {
            sb.append("WHAT YOU HAVE ASKED AND BEEN TOLD:\n")
            answers.forEach { (q, a) ->
                sb.append("- Q: ").append(q).append("\n  A: ").append(a).append('\n')
            }
            sb.append('\n')
            // This line used to read "Plan now unless something essential is
            // still missing", which pushed the model to plan after a single
            // question — the per-turn prompt carries more weight than the
            // system prompt, so it quietly overrode the gathering rules there.
            sb.append("You have asked ").append(answers.size)
                .append(" of ").append(TaskPlanner.MAX_QUESTIONS).append(" allowed questions.\n")
            if (answers.size >= MAX_QUESTIONS) {
                sb.append("That is the limit — write the plan now, and list anything ")
                    .append("you still had to assume.\n")
            } else {
                sb.append("Check the list of things this kind of task needs. If ANY of ")
                    .append("them is still unknown, ask about the next one. Only plan ")
                    .append("when nothing essential is missing.\n")
            }
        }

        sb.append("\nNext turn? JSON only.")
        return sb.toString()
    }

    private fun parse(text: String): TaskTurn? {
        val cleaned = text
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            Log.w(TAG, "No JSON object in task plan: $text")
            return null
        }

        return try {
            TaskTurn.fromJson(JSONObject(cleaned.substring(start, end + 1)))
        } catch (e: Exception) {
            Log.w(TAG, "Unparseable task plan: $text")
            null
        }
    }

    private fun isModelNotFound(e: Exception): Boolean {
        val message = e.message.orEmpty()
        return message.contains("404") ||
            message.contains("Not Found", ignoreCase = true) ||
            e.stackTraceToString().contains("404")
    }

    companion object {
        private const val TAG = "TaskPlanner"

        private val CANDIDATE_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite"
        )

        /** Hard stop on the interview, whatever the model thinks it still needs. */
        const val MAX_QUESTIONS = 6
    }
}
