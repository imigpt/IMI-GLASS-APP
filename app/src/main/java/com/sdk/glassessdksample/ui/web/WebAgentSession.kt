package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The agent loop: read the page, ask Gemini for one action, check it, run it,
 * repeat.
 *
 * The loop is capped and interruptible. Every path that involves the user's
 * credentials, a CAPTCHA, or spending money exits the loop and waits for the
 * user — the session resumes only when they say so.
 */
class WebAgentSession(
    private val context: Context,
    private val webView: WebView,
    private val scope: CoroutineScope,
    private val listener: Listener
) {

    /** How the session talks to the screen. All calls are on the UI thread. */
    interface Listener {
        /** Progress line for the status strip. */
        fun onStatus(message: String)

        /** The agent needs an answer before it can continue. */
        fun onQuestion(question: String)

        /** The user must drive the WebView themselves for this step. */
        fun onHandoff(reason: String)

        /** A destructive or paid action needs an explicit yes. */
        fun onConfirmationNeeded(prompt: String)

        /** The run ended. [success] false means it gave up or was stopped. */
        fun onFinished(success: Boolean, summary: String)
    }

    private val planner = WebAgentPlanner(context)
    private val executor = ActionExecutor(webView)

    private val history = mutableListOf<ActionResult>()
    private val answers = mutableListOf<Pair<String, String>>()

    private var job: Job? = null
    private var goal: String = ""

    /** Set while the loop is parked waiting for the user. */
    private var pendingQuestion: String? = null
    private var resumeSignal: ((String?) -> Unit)? = null

    val isRunning: Boolean get() = job?.isActive == true

    // ------------------------------------------------------------------ start

    /** Starts a fresh run. Any previous run is cancelled first. */
    fun start(userGoal: String) {
        stop(notify = false)
        goal = userGoal
        history.clear()
        answers.clear()

        job = scope.launch {
            try {
                runLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop error", e)
                listener.onFinished(false, "Something went wrong: ${e.message}")
            }
        }
    }

    /** Cancels the run. Called by the Stop button and on screen teardown. */
    fun stop(notify: Boolean = true) {
        resumeSignal = null
        pendingQuestion = null
        job?.cancel()
        job = null
        if (notify) listener.onFinished(false, "Stopped.")
    }

    // ----------------------------------------------------- user interactions

    /** Feeds back the answer to an [Listener.onQuestion]. */
    fun provideAnswer(answer: String) {
        val question = pendingQuestion
        if (question != null) {
            answers.add(question to answer)
            pendingQuestion = null
        }
        resumeSignal?.invoke(answer)
        resumeSignal = null
    }

    /** The user finished their manual step (login, CAPTCHA) and tapped Continue. */
    fun resumeAfterHandoff() {
        history.add(
            ActionResult(
                BrowserAction.Wait("user handled this step"),
                true,
                "The user completed the step manually. Continue from the current page."
            )
        )
        resumeSignal?.invoke(null)
        resumeSignal = null
    }

    /** Result of a confirmation prompt. */
    fun provideConfirmation(approved: Boolean) {
        resumeSignal?.invoke(if (approved) CONFIRM_YES else CONFIRM_NO)
        resumeSignal = null
    }

    // ------------------------------------------------------------- the loop

    private suspend fun runLoop() {
        listener.onStatus("Reading the page…")

        var steps = 0
        // The loop's OWN job, not the enclosing lifecycleScope: Stop cancels
        // this job alone, and the scope would still report itself active.
        // Extends itself while progress is being made, like the headless
        // runner: the user is watching this one and can hit Stop, so nagging
        // them for permission to continue is pure friction.
        var budget = MAX_STEPS
        while (currentCoroutineContext().isActive && steps < budget) {
            steps++

            if (steps == budget && steps < HARD_MAX_STEPS) {
                val progressing = history.takeLast(PROGRESS_WINDOW).let { recent ->
                    recent.isEmpty() || recent.any { it.success }
                }
                if (progressing) {
                    budget += STEP_EXTENSION
                    Log.d(TAG, "Extending budget to $budget")
                }
            }

            val page = PageReader.read(webView)

            listener.onStatus("Thinking… (step $steps)")
            // Same hybrid rule as the headless runner: read the page code
            // normally, and only look at a screenshot when that code is
            // clearly not describing the page — nothing actionable in it, or
            // a run of failures meaning the selectors it offers do not work.
            // This screen had no vision path at all, so a site whose controls
            // the DOM read cannot see was unusable here even though the
            // voice-driven agent could manage it.
            val failureRun = recentFailureRun()
            val needsEyes = steps > 1 &&
                (page.hasNothingActionable() || failureRun >= VISION_AFTER_FAILURES)

            val action = if (needsEyes) {
                listener.onStatus("Taking a proper look at the page…")
                planFromScreenshot(page) ?: planner.planNext(goal, page, history, answers)
            } else {
                planner.planNext(goal, page, history, answers)
            }

            if (action == null) {
                listener.onFinished(false, "I couldn't work out what to do next.")
                return
            }

            // Terminal actions end the run before anything is executed.
            when (action) {
                is BrowserAction.Done -> {
                    listener.onFinished(true, action.summary)
                    return
                }
                is BrowserAction.Failed -> {
                    listener.onFinished(false, action.reason)
                    return
                }
                is BrowserAction.AskUser -> {
                    val answer = askUser(action.question) ?: return
                    history.add(ActionResult(action, true, "User answered: $answer"))
                    continue
                }
                is BrowserAction.HandoffToUser -> {
                    if (!handOff(action.reason)) return
                    continue
                }
                else -> Unit
            }

            when (val verdict = ActionValidator.validate(action, page)) {
                is ActionValidator.Verdict.Allow -> Unit

                is ActionValidator.Verdict.Handoff -> {
                    if (!handOff(verdict.reason)) return
                    continue
                }

                is ActionValidator.Verdict.Reject -> {
                    // Not fatal: tell the planner why and let it try again.
                    Log.d(TAG, "Rejected ${action.describe()}: ${verdict.reason}")
                    history.add(ActionResult(action, false, verdict.reason))
                    continue
                }

                is ActionValidator.Verdict.NeedsConfirmation -> {
                    val approved = confirm(verdict.prompt) ?: return
                    if (!approved) {
                        listener.onFinished(false, "Cancelled — I didn't tap it.")
                        return
                    }
                }
            }

            listener.onStatus(action.describe())
            val result = executor.execute(action)
            history.add(result)

            if (!result.success) {
                Log.d(TAG, "Action failed: ${result.detail}")
            }

            // Breathing room so a client-side render finishes before the read.
            delay(STEP_GAP_MS)
        }

        if (steps >= budget) {
            listener.onFinished(
                false,
                "I've done $steps steps on this. Pausing here rather than running on " +
                    "indefinitely — tell me what to do next and I'll carry on."
            )
        }
    }

    /** Unbroken run of failures at the end of the history. */
    private fun recentFailureRun(): Int {
        var count = 0
        for (result in history.asReversed()) {
            if (result.success) break
            count++
        }
        return count
    }

    /**
     * Plans from a screenshot of the VISIBLE WebView, scaling the coordinates
     * the model returns back into page pixels.
     */
    private suspend fun planFromScreenshot(page: PageReader.PageSnapshot): BrowserAction? {
        val shot = captureBase64() ?: return null
        val action = VisionPlanner(context).planFromImage(goal, shot, history) ?: return null
        if (action !is BrowserAction.TapAt) return action

        val bounds = try {
            val bytes = android.util.Base64.decode(shot, android.util.Base64.NO_WRAP)
            android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, this)
            }
        } catch (e: Exception) {
            null
        }

        val imgW = bounds?.outWidth ?: 0
        val imgH = bounds?.outHeight ?: 0
        if (imgW <= 0 || imgH <= 0 || webView.width <= 0 || webView.height <= 0) return action

        return action.copy(
            x = (action.x.toFloat() * webView.width / imgW).toInt(),
            y = (action.y.toFloat() * webView.height / imgH).toInt()
        )
    }

    /** JPEG of the visible WebView, base64-encoded. */
    private fun captureBase64(): String? = try {
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) null else {
            val bitmap = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.RGB_565
            )
            webView.draw(android.graphics.Canvas(bitmap))
            val scaled = if (h > 1536) {
                val ratio = 1536f / h
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap, (w * ratio).toInt(), 1536, true
                ).also { bitmap.recycle() }
            } else bitmap
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            scaled.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Screenshot failed", e)
        null
    }

    // -------------------------------------------------------------- suspends

    /** Parks the loop until [provideAnswer]. Null means the run was cancelled. */
    private suspend fun askUser(question: String): String? {
        pendingQuestion = question
        listener.onQuestion(question)
        return awaitUser()
    }

    /** Parks until [resumeAfterHandoff]. False means the run was cancelled. */
    private suspend fun handOff(reason: String): Boolean {
        listener.onHandoff(reason)
        return awaitUser() != null
    }

    /** Parks until [provideConfirmation]. Null means cancelled. */
    private suspend fun confirm(prompt: String): Boolean? {
        listener.onConfirmationNeeded(prompt)
        val reply = awaitUser() ?: return null
        return reply == CONFIRM_YES
    }

    private suspend fun awaitUser(): String? =
        suspendCancellableCoroutine { cont ->
            resumeSignal = { value ->
                if (cont.isActive) cont.resume(value ?: RESUMED)
            }
            cont.invokeOnCancellation { resumeSignal = null }
        }

    companion object {
        private const val TAG = "WebAgentSession"

        /**
         * Hard cap so a confused agent can't browse forever on the user's data.
         *
         * Raised from 15 for the same reason as the headless runner's: an
         * ordinary shopping or booking task spends most of its budget just
         * navigating, and stopping there ended tasks that were going fine.
         * Higher here than headless because the user is watching this one and
         * can hit Stop the moment it looks wrong.
         */
        private const val MAX_STEPS = 50

        /** Granted each time the budget is reached and the run is still progressing. */
        private const val STEP_EXTENSION = 25

        /** Ceiling that self-extension never crosses. */
        private const val HARD_MAX_STEPS = 250

        /** Recent steps examined to decide whether the run is still going well. */
        private const val PROGRESS_WINDOW = 6

        /** Consecutive failures before trusting a screenshot over the page code. */
        private const val VISION_AFTER_FAILURES = 2
        private const val STEP_GAP_MS = 350L

        private const val RESUMED = "__resumed__"
        private const val CONFIRM_YES = "__yes__"
        private const val CONFIRM_NO = "__no__"
    }
}
