package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Runs the browser agent with nobody watching, for the glasses.
 *
 * Same read-plan-validate-execute loop as [WebAgentSession], but it cannot ask
 * a screen for anything, so every branch has to end in something speakable:
 *
 * - Needs an answer from the user → return the question. The live model asks it
 *   out loud, and the user's reply comes back as a fresh `browse_web` call.
 * - Needs the user to log in or clear a security check → park the goal, tell
 *   them to use the phone, resume on "continue".
 * - Needs confirmation to spend money or do something final → **refuse and hand
 *   over**. A spoken "yes" is too weak a gate for an irreversible action the
 *   user cannot see, so those steps stay on the phone where they can be read.
 */
class HeadlessAgentRunner(
    private val context: Context,
    /**
     * Called with a short line each time the agent does something.
     *
     * Nothing reported progress before: a run took a minute or more with the
     * screen frozen on one message, so there was no way to tell working from
     * stuck — which is exactly what it looked like when the agent was quietly
     * grinding through steps.
     */
    private val onProgress: ((String) -> Unit)? = null
) {

    data class Outcome(
        val success: Boolean,
        /** Exactly what the glasses should say. */
        val spokenResult: String
    )

    private val planner = WebAgentPlanner(context)

    suspend fun run(goal: String, resuming: Boolean = false): Outcome {
        val history = mutableListOf<ActionResult>()

        if (resuming) {
            history.add(
                ActionResult(
                    BrowserAction.Wait("user handled this step"),
                    true,
                    "The user completed the manual step. Continue from the current page."
                )
            )
        }

        var steps = 0
        while (steps < MAX_STEPS) {
            steps++

            onProgress?.invoke("Reading the page… (step $steps of $MAX_STEPS)")
            val page = GlassBrowserEngine.readPage()

            onProgress?.invoke("Working out the next step… ($steps of $MAX_STEPS)")

            // The DOM read is the normal path: instant, free, exact selectors.
            // Only when it comes back with genuinely nothing to act on — a site
            // whose controls are all unmarked <div>s — is it worth paying for a
            // screenshot. Roughly 2000 input tokens and a second or two per
            // step, against approximately nothing for this read, so it stays a
            // last resort rather than a default.
            // Never on the first step: nothing has been navigated to yet, so
            // there is nothing to photograph. Belt and braces alongside the
            // blank-page check in hasNothingActionable().
            val action = if (steps > 1 && page.hasNothingActionable()) {
                Log.d(TAG, "Page summary is empty — falling back to vision")
                onProgress?.invoke("Looking at the page…")
                planFromScreenshot(goal, history)
                    ?: planner.planNext(goal, page, history, emptyList())
            } else {
                planner.planNext(goal, page, history, emptyList())
            }
                ?: run {
                    // The planner giving up is exactly the moment the user can
                    // help, so ASK rather than report. This path used to return
                    // a flat outcome while every other failure parked and asked
                    // — so the one case where a human hint was most valuable was
                    // the one case that ended the task.
                    val question = if (history.isEmpty()) {
                        "I couldn't work out where to start with that one. " +
                            "How would you like me to go about it?"
                    } else {
                        "I got stuck after ${history.size} step(s) on " +
                            "${WebSessionManager.displayHost(page.url)} — I couldn't work " +
                            "out what to do next. What should I try?"
                    }
                    GlassBrowserEngine.requireUser(
                        question,
                        goal,
                        GlassBrowserEngine.WaitKind.NEEDS_GUIDANCE
                    )
                    return Outcome(false, question)
                }

            onProgress?.invoke(action.describe())

            when (action) {
                is BrowserAction.Done ->
                    return Outcome(true, action.summary)

                is BrowserAction.Failed ->
                    return Outcome(false, action.reason)

                is BrowserAction.AskUser -> {
                    // Parked as NEEDS_GUIDANCE, so whatever the user says back
                    // is folded into the goal rather than discarded — resuming
                    // on the unchanged goal walked straight back into the same
                    // dead end, which is what made a stuck agent unrecoverable.
                    GlassBrowserEngine.requireUser(
                        action.question,
                        goal,
                        GlassBrowserEngine.WaitKind.NEEDS_GUIDANCE
                    )
                    return Outcome(true, action.question)
                }

                is BrowserAction.HandoffToUser ->
                    return handOff(action.reason, goal)

                else -> Unit
            }

            when (val verdict = ActionValidator.validate(action, page)) {
                is ActionValidator.Verdict.Allow -> Unit

                is ActionValidator.Verdict.Handoff ->
                    return handOff(verdict.reason, goal)

                is ActionValidator.Verdict.Reject -> {
                    Log.d(TAG, "Rejected ${action.describe()}: ${verdict.reason}")
                    history.add(ActionResult(action, false, verdict.reason))
                    continue
                }

                is ActionValidator.Verdict.NeedsConfirmation -> {
                    // Deliberately not confirmable by voice. See the class note.
                    GlassBrowserEngine.requireUser(verdict.prompt, goal)
                    return Outcome(
                        false,
                        "This next step spends money or can't be undone, so I won't do it " +
                            "from here. I've put it on your phone screen — take a look."
                    )
                }
            }

            val executor = GlassBrowserEngine.executor()
            val result = executor.execute(action)
            history.add(result)

            delay(STEP_GAP_MS)
        }

        // Running out of steps is no longer the end of the task. Say what went
        // wrong and ASK what to try instead, keeping the goal parked so the
        // user's answer can carry it on. Giving up here meant a task died on
        // one bad guess when the user usually knows the way round it.
        val explanation = explainFailure(history)
        // Hitting the cap is a CHECK-IN, not a dead end — the run can carry on
        // from exactly here. Only word it as "what should I try instead" when
        // something was actually going wrong; otherwise it reads as a failure
        // when the task was simply long.
        val stalled = history.any { !it.success }
        val question = if (stalled) {
            "$explanation What should I try instead?"
        } else {
            "$explanation Shall I keep going?"
        }
        GlassBrowserEngine.requireUser(
            question,
            goal,
            GlassBrowserEngine.WaitKind.NEEDS_GUIDANCE
        )
        return Outcome(false, question)
    }

    /**
     * Says what actually went wrong, using the steps that were taken.
     *
     * This used to be a flat "I couldn't finish that one, you might have better
     * luck on the phone" — which told the user nothing about what stopped it or
     * whether trying again was worth their time, while the runner was holding a
     * full account of every action and every failure and throwing it away.
     */
    private fun explainFailure(history: List<ActionResult>): String {
        val failures = history.filter { !it.success }
        val lastFailure = failures.lastOrNull()

        val sb = StringBuilder("I've done $MAX_STEPS steps and I'm not finished yet. ")

        when {
            // The same action failing repeatedly is the agent stuck in a loop —
            // the most useful thing to name, because retrying won't help.
            failures.size >= 3 &&
                failures.takeLast(3).map { it.action.describe() }.distinct().size == 1 -> {
                sb.append("I kept trying to ")
                    .append(failures.last().action.describe().replaceFirstChar { it.lowercase() })
                    .append(" and it kept failing: ")
                    .append(failures.last().detail)
                    .append(". That one needs doing by hand.")
            }

            lastFailure != null -> {
                sb.append("The last thing that went wrong was ")
                    .append(lastFailure.action.describe().replaceFirstChar { it.lowercase() })
                    .append(" — ")
                    .append(lastFailure.detail)
                    .append(".")
            }

            // Nothing failed outright: it simply ran out of room. Say where it
            // got to, so the user can judge whether to carry on themselves.
            else -> {
                val lastStep = history.lastOrNull()?.action?.describe()
                if (lastStep != null) {
                    sb.append("Nothing went wrong exactly — the task just needed more steps ")
                        .append("than I'm allowed. I got as far as ")
                        .append(lastStep.replaceFirstChar { it.lowercase() })
                        .append(".")
                } else {
                    sb.append("I couldn't get anywhere with it at all.")
                }
            }
        }

        return sb.toString()
    }

    /**
     * Takes a screenshot and plans from it, scaling any coordinates back.
     *
     * The image is downscaled before sending to cap token cost, so a point the
     * model picks is in IMAGE pixels and has to be mapped back to the page's
     * own coordinate space before it can be tapped.
     */
    private suspend fun planFromScreenshot(
        goal: String,
        history: List<ActionResult>
    ): BrowserAction? {
        val shot = GlassBrowserEngine.screenshotBase64() ?: return null

        val action = VisionPlanner(context).planFromImage(goal, shot, history) ?: return null
        if (action !is BrowserAction.TapAt) return action

        // Map image pixels -> CSS pixels.
        val (viewWidth, viewHeight) = GlassBrowserEngine.viewportSize()
        val bounds = try {
            val bytes = android.util.Base64.decode(shot, android.util.Base64.NO_WRAP)
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts
        } catch (e: Exception) {
            null
        }

        val imgWidth = bounds?.outWidth ?: 0
        val imgHeight = bounds?.outHeight ?: 0
        if (imgWidth <= 0 || imgHeight <= 0) return action

        val scaledX = (action.x.toFloat() * viewWidth / imgWidth).toInt()
        val scaledY = (action.y.toFloat() * viewHeight / imgHeight).toInt()
        Log.d(TAG, "Vision tap ${action.x},${action.y} -> $scaledX,$scaledY")
        return action.copy(x = scaledX, y = scaledY)
    }

    /**
     * Parks the goal and tells the user, in speech, what only they can do.
     * This is the "this is your time to do it" moment.
     */
    private fun handOff(reason: String, goal: String): Outcome {
        GlassBrowserEngine.requireUser(reason, goal)
        // requireUser() pushes the blocked page onto whatever home screen is
        // open (BrowserHandoffOverlay), so the user no longer has to go and
        // find the Web section — it's already in front of them.
        return Outcome(
            false,
            "$reason I've put it on your phone screen — finish it there, " +
                "then say continue and I'll carry on."
        )
    }

    companion object {
        private const val TAG = "HeadlessAgentRunner"

        /**
         * How many steps one run may take before checking back in.
         *
         * This was 10, which was simply too few: a real task — search, open a
         * result, set a delivery pin code, go back, pick another, add to cart —
         * spends most of that budget on navigation and then died mid-task. The
         * number now reflects what tasks actually cost.
         *
         * It is deliberately still a number rather than "unlimited". An
         * unbounded loop means a confused agent browses forever on the user's
         * battery and data, and every step is a paid model call. But hitting it
         * is no longer the end of the task: the runner parks and ASKS whether
         * to carry on, and a resume continues from exactly where it stopped —
         * so a genuinely long task is finished across as many legs as it needs,
         * with the user deciding each time.
         */
        private const val MAX_STEPS = 40
        private const val STEP_GAP_MS = 300L
    }
}
