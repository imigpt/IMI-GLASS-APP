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
            // Everything the agent had already done, carried across the pause.
            // Without this a resumed run began blank: it re-planned from
            // scratch against the current page, could not see what it had
            // already tried or achieved, and concluded within a step or two
            // that it was done or stuck — so saying "continue" looked like it
            // did nothing at all.
            history.addAll(GlassBrowserEngine.takeParkedHistory())
            history.add(
                ActionResult(
                    BrowserAction.Wait("user handled this step"),
                    true,
                    "The user completed the manual step. Continue from the current page."
                )
            )
        }

        var steps = 0
        // The page as it was, to notice a run going nowhere. Action results
        // cannot be trusted for this: a click on a dead element reports
        // success, so a task can spend forty "successful" steps without the
        // page ever changing — which is exactly what a Flipkart run did.
        var lastSignature = ""
        var unchangedFor = 0
        // The budget EXTENDS ITSELF while the task is going well. A long job
        // stopping every 40 steps to ask "shall I keep going?" was the single
        // most annoying thing about it — nothing had gone wrong, the agent was
        // mid-flow, and the user had to say continue purely to grant steps it
        // could have taken itself. Now it only stops when it is genuinely
        // stuck, or when the hard ceiling makes it irresponsible to carry on
        // unsupervised.
        var budget = MAX_STEPS
        while (steps < budget) {
            steps++
            GlassBrowserEngine.addStepsSpent(1)

            // Reached the current budget? Decide whether to grant more.
            if (steps == budget && GlassBrowserEngine.stepsSpent < HARD_MAX_STEPS) {
                val progressing = history.takeLast(PROGRESS_WINDOW).let { recent ->
                    recent.isEmpty() || recent.any { it.success }
                }
                if (progressing) {
                    budget += STEP_EXTENSION
                    Log.d(
                        TAG,
                        "Extending budget to $budget (total ${GlassBrowserEngine.stepsSpent})"
                    )
                    onProgress?.invoke("Still working — this one is taking a while…")
                }
            }

            onProgress?.invoke("Reading the page… (step $steps)")
            val page = GlassBrowserEngine.readPage()

            onProgress?.invoke("Working out the next step… (step $steps)")

            // Has the page actually MOVED? Action results cannot answer this:
            // clicking a dead element and typing into an already-filled field
            // both report success, so a run can spend forty "successful" steps
            // with the page never changing — which is precisely what happened
            // when a Flipkart task did nothing but retype the same search term.
            val signature = page.url + "|" + page.title + "|" +
                (page.raw.optJSONArray("inputs")?.length() ?: 0) + "," +
                (page.raw.optJSONArray("buttons")?.length() ?: 0)
            if (signature == lastSignature) unchangedFor++ else unchangedFor = 0
            lastSignature = signature

            // The DOM read is the normal path: instant, free, exact selectors.
            // Only when it comes back with genuinely nothing to act on — a site
            // whose controls are all unmarked <div>s — is it worth paying for a
            // screenshot. Roughly 2000 input tokens and a second or two per
            // step, against approximately nothing for this read, so it stays a
            // last resort rather than a default.
            // Never on the first step: nothing has been navigated to yet, so
            // there is nothing to photograph. Belt and braces alongside the
            // blank-page check in hasNothingActionable().
            //
            // Two triggers, both meaning "the page code is not telling me what
            // I need to know":
            //
            //  - the summary is empty, so there is nothing to act on at all;
            //  - the last few steps kept FAILING, which in practice means the
            //    planner is quoting selectors that do not work. A Flipkart
            //    search burned 200 steps repeating "field not found" against a
            //    search box that was plainly on screen — the DOM read was
            //    misleading it, and no number of retries against the same bad
            //    summary was ever going to fix that. A picture breaks the loop.
            val failureRun = recentFailureRun(history)
            val stuckOnDom = steps > 1 && failureRun >= VISION_AFTER_FAILURES
            // Counting failures only catches the LOUD kind of stuck. A page
            // that never changes while every action claims success is the
            // quiet kind, and it slipped straight past the failure counter.
            val goingNowhere = unchangedFor >= VISION_AFTER_UNCHANGED
            val needsEyes = steps > 1 &&
                (page.hasNothingActionable() || stuckOnDom || goingNowhere)

            val action = if (needsEyes) {
                Log.d(
                    TAG,
                    when {
                        stuckOnDom -> "Stuck after $failureRun failures — using vision"
                        goingNowhere -> "Page unchanged for $unchangedFor steps — using vision"
                        else -> "Page summary is empty — falling back to vision"
                    }
                )
                onProgress?.invoke("Taking a proper look at the page…")
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
                    GlassBrowserEngine.parkHistory(history)
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
                    GlassBrowserEngine.parkHistory(history)
                    GlassBrowserEngine.requireUser(
                        action.question,
                        goal,
                        GlassBrowserEngine.WaitKind.NEEDS_GUIDANCE
                    )
                    return Outcome(true, action.question)
                }

                is BrowserAction.HandoffToUser ->
                    return handOff(action.reason, goal, history)

                else -> Unit
            }

            when (val verdict = ActionValidator.validate(action, page)) {
                is ActionValidator.Verdict.Allow -> Unit

                is ActionValidator.Verdict.Handoff ->
                    return handOff(verdict.reason, goal, history)

                is ActionValidator.Verdict.Reject -> {
                    Log.d(TAG, "Rejected ${action.describe()}: ${verdict.reason}")
                    history.add(ActionResult(action, false, verdict.reason))
                    continue
                }

                is ActionValidator.Verdict.NeedsConfirmation -> {
                    // Deliberately not confirmable by voice. See the class note.
                    GlassBrowserEngine.parkHistory(history)
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

            // Every action, logged. Diagnosing a stuck run from the budget
            // counter alone was guesswork — the log showed 40 steps passing
            // and not a single thing the agent had tried.
            Log.d(
                TAG,
                "step $steps: ${action.describe()} -> " +
                    (if (result.success) "ok" else "FAILED") + ": ${result.detail}"
            )

            delay(STEP_GAP_MS)
        }

        // The loop only ends here for one of two reasons, and they read very
        // differently to the user.
        //
        // Either the agent stopped making progress — nothing in the recent
        // window succeeded — in which case more steps would just repeat the
        // same failure and the user's guidance is genuinely worth having.
        //
        // Or it hit the hard ceiling, which exists because an agent that
        // extends itself indefinitely could browse for a very long time on
        // someone's data with nobody watching. That is not a failure, and
        // saying so matters: the task can simply be told to carry on.
        val explanation = explainFailure(history)
        val stalled = history.takeLast(PROGRESS_WINDOW).none { it.success }
        val question = if (stalled) {
            "$explanation What should I try instead?"
        } else {
            "$explanation That's as far as I'll go without checking — say " +
                "continue and I'll carry on."
        }
        GlassBrowserEngine.parkHistory(history)
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

        val total = GlassBrowserEngine.stepsSpent.coerceAtLeast(MAX_STEPS)
        val sb = StringBuilder("I've done $total steps on this. ")

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
                    sb.append("It's a long one and I'm still going. I've got as far as ")
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
     * How many of the most recent actions failed, counting back from the end.
     *
     * A run of failures is the signal that the page summary is misleading the
     * planner — it keeps choosing selectors that are not really there. Counting
     * only the unbroken tail means one bad step among successes does not
     * trigger an expensive screenshot.
     */
    private fun recentFailureRun(history: List<ActionResult>): Int {
        var count = 0
        for (result in history.asReversed()) {
            if (result.success) break
            count++
        }
        return count
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
    private fun handOff(
        reason: String,
        goal: String,
        history: List<ActionResult> = emptyList()
    ): Outcome {
        // Keep what has been done, so resuming after the login carries on
        // rather than starting the task over from nothing.
        GlassBrowserEngine.parkHistory(history)
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
        /** Steps in the first leg, before the budget starts extending itself. */
        private const val MAX_STEPS = 40

        /** Granted each time the agent reaches its budget and is still progressing. */
        private const val STEP_EXTENSION = 20

        /**
         * The ceiling no amount of self-extension crosses.
         *
         * Self-extension exists so a long task finishes without nagging; this
         * exists so a confused one cannot run all day on the user's data and
         * usage budget while nobody is watching. At this point it stops and
         * checks in — which the user can wave through.
         */
        private const val HARD_MAX_STEPS = 200

        /**
         * How many recent steps are examined to decide "is this going well?".
         *
         * All-failures across this window means more steps would only repeat
         * the same mistake, so the user's help is worth more than the budget.
         */
        private const val PROGRESS_WINDOW = 6

        /**
         * Consecutive failures before the agent stops trusting the page code
         * and looks at a screenshot instead.
         *
         * Two is deliberate: one failure is ordinary — a stale selector, a
         * mistimed click — and retrying is the right response. Two in a row
         * means the summary itself is wrong, and further retries against it
         * are wasted steps.
         */
        private const val VISION_AFTER_FAILURES = 2

        /**
         * Identical page snapshots before the agent stops believing its own
         * action results and looks at a screenshot.
         *
         * Three, not two: a page can legitimately stay the same across a
         * scroll that reveals nothing new, or a click that opens an overlay
         * the summary happens to describe identically.
         */
        private const val VISION_AFTER_UNCHANGED = 3

        private const val STEP_GAP_MS = 300L
    }
}
