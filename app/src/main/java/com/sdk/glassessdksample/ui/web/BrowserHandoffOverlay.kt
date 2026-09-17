package com.sdk.glassessdksample.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The blocked page, brought to the user instead of the user going to find it.
 *
 * When the glasses drive [GlassBrowserEngine] and it reaches something only a
 * person may do — a login, a CAPTCHA, an OTP — the engine parks and the glasses
 * say so. Previously the *only* place that block could be resolved was
 * WebBrowserActivity.onResume(): the user had to already understand what had
 * happened and navigate More → Web to find it. Anyone who didn't just heard the
 * glasses give up.
 *
 * This overlay puts that step on the home screen. It hosts its own WebView, and
 * because [WebSessionManager] shares one process-wide cookie jar, a login or
 * CAPTCHA solved *here* genuinely clears the block on the engine's off-screen
 * WebView — the same trick WebBrowserActivity.showGlassHandoffIfWaiting() uses.
 *
 * Drop it in as the last child of a home layout and call [attach] once; it
 * subscribes to the engine itself and shows/hides on its own.
 */
class BrowserHandoffOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Notified when the user clears or abandons the step, for a spoken reply. */
    var onResolved: ((resumed: Boolean) -> Unit)? = null

    private val webView: WebView
    private val reasonText: TextView
    private val continueButton: Button
    private val cancelButton: Button
    private val editButton: Button

    /**
     * Scope for the one suspend call this view makes ([GlassBrowserEngine.currentUrl]).
     * Owned by the view and cancelled in [detach] — a fresh MainScope() per show()
     * would leak a scope every time the agent got stuck.
     */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    /** The URL the engine was stuck on, so we only reload when it changes. */
    private var loadedUrl: String? = null

    /**
     * Whether [webView] has ever actually loaded a page.
     *
     * [loadedUrl] alone cannot answer this: it is reset to null on hide(), and
     * on the voice-approved path it is never set at all — so "loadedUrl is
     * null" does not distinguish "nothing loaded yet" from "loaded, then
     * cleared". Showing the WebView in the first case is a black rectangle.
     */
    private var hasLoadedPage = false

    /** True once a resumed run has finished and its result is on screen. */
    private var isRunFinished = false

    /** True while the overlay is showing a plan to approve, not a stuck page. */
    private var isShowingPlan = false

    /** The plan's full text. Replaces the WebView, which has nothing to show yet. */
    private val planText: TextView

    /** Scrolls the plan — a long itinerary won't fit a phone screen. */
    private val planScroll: android.widget.ScrollView

    /** The in-flight resumed run, so "Stop" can actually stop it. */
    private var runJob: kotlinx.coroutines.Job? = null

    private val listener = GlassBrowserEngine.WaitListener { reason ->
        if (reason != null) show(reason) else hide()
    }

    /**
     * Mirrors the spoken task conversation onto the phone. The plan is read out
     * through the glasses, but a multi-step plan is not something anyone can
     * hold in their head from audio alone — so the full text appears here, and
     * the user can approve by tapping instead of speaking.
     */
    /**
     * Mirrors the spoken task conversation onto the phone. The plan is read out
     * through the glasses, but a multi-step plan is not something anyone can
     * hold in their head from audio alone — so the full text appears here, and
     * the user can approve by tapping instead of speaking.
     *
     * Declared as a method rather than a property initialiser: it reads
     * [reasonText], and a property here would be initialised before the views
     * below it exist.
     */
    private fun createTaskListener() = object : TaskSession.Listener {

        override fun onProgress(message: String) {
            // Only while this overlay is the thing showing the run — never over
            // a plan awaiting approval or a finished result.
            if (visibility == View.VISIBLE && !isShowingPlan && !isRunFinished) {
                reasonText.text = message
            }
        }

        override fun onFinished(message: String, success: Boolean) {
            // Reaches the screen however the plan was approved. A handoff is
            // the one case to leave alone: the engine is waiting on the user
            // and the wait listener is already showing them the blocked page.
            if (GlassBrowserEngine.awaitingUser) return
            showResult(message)
        }

        override fun onPhaseChanged(phase: TaskSession.Phase, plan: TaskPlan?) {
            when (phase) {
                TaskSession.Phase.AWAITING_APPROVAL ->
                    if (plan != null) showPlan(plan)

                // Approval can happen by voice OR by tapping here, and the
                // screen has to show the run either way: approving out loud and
                // then looking at the phone used to show nothing at all,
                // because this only reacted when the plan was already up.
                TaskSession.Phase.EXECUTING -> showExecuting()

                // Only tear the overlay down if it is showing this task. A
                // stuck page (a handoff mid-run) must survive the task ending.
                TaskSession.Phase.IDLE ->
                    if (isShowingPlan && !GlassBrowserEngine.awaitingUser) hidePlan()

                TaskSession.Phase.GATHERING -> Unit
            }
        }
    }

    /** Built in [attach], once the views above are all constructed. */
    private var taskListener: TaskSession.Listener? = null

    init {
        // The home screen keeps running underneath; this sits over it opaquely
        // so a half-visible dashboard doesn't read as "the page is broken".
        setBackgroundColor(Color.parseColor("#0D0B09"))
        visibility = View.GONE
        isClickable = true

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        }

        reasonText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(
                com.sdk.glassessdksample.R.drawable.bg_web_agent_strip
            )
        }
        column.addView(
            reasonText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(14), dp(14), dp(14), dp(8)) }
        )

        webView = WebView(context).apply {
            WebSessionManager.configure(this, desktopMode = true)
            // The allow-list holds here too. This overlay is where the user
            // finishes a sign-in by hand, so it must reach the identity
            // providers — which AllowedSites permits — without becoming a way
            // to browse anywhere else.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return !AllowedSites.isAllowed(url)
                }
            }
            // Same OAuth-popup routing as the engine and the Web screen: without
            // it, "Continue with Google" waits forever on a popup that never
            // opens — and sign-in is one of the main reasons we're here at all.
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean = PopupWindowRouter.routeInto(this@apply, resultMsg)
            }
        }
        column.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // Shown in the WebView's place while a plan is awaiting approval.
        planText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        planScroll = android.widget.ScrollView(context).apply {
            visibility = View.GONE
            addView(planText)
        }
        column.addView(
            planScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(14), dp(10), dp(14), dp(16))
        }

        // Shown only on a plan: lets the user change a detail instead of being
        // forced to choose between running a plan that is wrong and throwing
        // the whole task away.
        editButton = Button(context).apply {
            text = "Edit"
            setTextColor(Color.parseColor("#FF7F2E"))
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { promptForEdit() }
        }
        buttonRow.addView(editButton)

        // Cancel first (left), so the destructive-ish option isn't under the
        // thumb that's reaching for Continue.
        cancelButton = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#ADADAD"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                when {
                    // After a finished run this button is just "Close" — the goal
                    // is already done, so cancelling the engine is meaningless.
                    isRunFinished -> dismissResult()
                    // Mid-run it reads "Stop", and must genuinely abort: without
                    // cancelling the job the agent kept browsing and then painted
                    // its result over the screen the user had just stopped.
                    runJob?.isActive == true -> stopRun()
                    // Declining a plan ends the task rather than the engine's
                    // wait — nothing is parked in the browser yet.
                    isShowingPlan -> {
                        TaskSession.finish()
                        hidePlan()
                    }
                    else -> resolve(resumed = false)
                }
            }
        }
        buttonRow.addView(cancelButton)

        continueButton = Button(context).apply {
            text = "Continue"
            setTextColor(Color.WHITE)
            setBackgroundResource(
                com.sdk.glassessdksample.R.drawable.bg_web_agent_cta
            )
            setPadding(dp(28), 0, dp(28), 0)
            setOnClickListener {
                if (isShowingPlan) approvePlan() else resolve(resumed = true)
            }
        }
        buttonRow.addView(
            continueButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44)
            ).apply { marginStart = dp(8) }
        )

        column.addView(buttonRow)
        addView(column)
    }

    /**
     * Starts listening. Call from the host's onCreate; the engine replays any
     * block that is already pending, so a home screen opened *after* the agent
     * got stuck still shows it.
     */
    fun attach() {
        GlassBrowserEngine.addWaitListener(listener)
        val task = taskListener ?: createTaskListener().also { taskListener = it }
        TaskSession.addListener(task)
    }

    /** Stops listening. Call from the host's onDestroy. */
    fun detach() {
        GlassBrowserEngine.removeWaitListener(listener)
        taskListener?.let { TaskSession.removeListener(it) }
        scope.cancel()
        try {
            webView.stopLoading()
            webView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "WebView teardown failed", e)
        }
    }

    /** True while the user is being asked to resolve a step. */
    val isShowing: Boolean get() = visibility == View.VISIBLE

    // ------------------------------------------------------------------ state

    @SuppressLint("SetJavaScriptEnabled")
    private fun show(reason: String) {
        // A previous run may have left the buttons reading Done/Close. This is
        // a fresh block, so put the handoff wiring back before showing it.
        resetButtons()

        // Put the PAGE back. A handoff during a task arrives after showPlan()
        // has hidden the WebView and shown the plan text in its place, and
        // nothing here used to undo that — so hitting a sign-in or CAPTCHA
        // mid-task left the old plan on screen with a Continue button under it,
        // and the page the user actually had to deal with was invisible.
        isShowingPlan = false
        planScroll.visibility = View.GONE

        // The reason already ends in its own instruction ("…then tap Continue."),
        // so appending another one produced the same sentence twice on screen.
        reasonText.text = reason
        visibility = View.VISIBLE

        // Load whatever the engine is actually stuck on. Cookies are shared, so
        // solving it in this WebView unblocks the engine's off-screen one.
        scope.launch {
            val stuckUrl = GlassBrowserEngine.currentUrl()
            if (!stuckUrl.isNullOrBlank() && stuckUrl != loadedUrl) {
                loadedUrl = stuckUrl
                hasLoadedPage = true
                webView.loadUrl(stuckUrl)
            }
            // Same reasoning as showResult(): only show the WebView once there
            // is something in it, or the user gets a black rectangle and no
            // sign of what they are meant to be unblocking.
            webView.visibility = if (hasLoadedPage) View.VISIBLE else View.GONE
        }
    }

    private fun hide() {
        visibility = View.GONE
        loadedUrl = null
    }

    /**
     * The user is done. Continue carries the parked goal on from here; Cancel
     * drops it so the glasses aren't left blocked on a step the user has
     * decided against.
     *
     * Continue deliberately runs the goal **itself** rather than only clearing
     * the wait flag. Clearing the flag was all this did at first, on the
     * assumption that GlassBrowserTools.browser_continue would pick the goal up
     * — but that only fires when the user says "continue" to a *live* voice
     * session. If the session had already ended (the common case: the user put
     * the glasses down to deal with the login), the parked goal was silently
     * dropped. Tapping Continue appeared to do nothing at all.
     */
    private fun resolve(resumed: Boolean) {
        // Flush first: the login that just happened is only useful to the
        // off-screen engine once its cookies are actually written.
        WebSessionManager.persist()

        if (!resumed) {
            GlassBrowserEngine.cancel()
            hide()
            onResolved?.invoke(false)
            return
        }

        // resume() is one-shot: it hands back the goal and clears the flag, so
        // a later spoken "continue" gets "nothing waiting" rather than running
        // this same goal a second time.
        val goal = GlassBrowserEngine.resume()
        if (goal == null) {
            hide()
            onResolved?.invoke(true)
            return
        }

        if (GlassBrowserEngine.isBusy) {
            reasonText.text = "Still finishing the last step — give me a moment."
            return
        }

        // Stay on screen through the run. Closing here would drop the user back
        // to the dashboard with no way to see whether the task ever finished,
        // which is the other half of the same bug.
        showRunning()

        runJob = scope.launch {
            GlassBrowserEngine.markBusy(true)
            val outcome = try {
                HeadlessAgentRunner(context).run(goal, resuming = true)
            } catch (e: Exception) {
                Log.w(TAG, "Resumed run failed", e)
                HeadlessAgentRunner.Outcome(false, "Something went wrong finishing that.")
            } finally {
                GlassBrowserEngine.markBusy(false)
            }

            // A second block (another login, a CAPTCHA on the next page) will
            // have re-armed the engine via requireUser(), and the listener
            // repaints this overlay for that new step — so don't clobber it.
            if (GlassBrowserEngine.awaitingUser) return@launch

            showResult(outcome.spokenResult)
            onResolved?.invoke(true)
        }
    }

    /** Continue tapped: the agent is working again, so say so and hide the buttons. */
    private fun showRunning() {
        reasonText.text = "Carrying on…"
        continueButton.visibility = View.GONE
        cancelButton.text = "Stop"
    }

    /**
     * The run ended. The outcome stays on screen until the user dismisses it —
     * they stepped away to do a login, so they may not be watching when it
     * lands, and an auto-dismiss would lose the answer entirely.
     */
    private fun showResult(message: String) {
        isRunFinished = true
        reasonText.text = message
        continueButton.visibility = View.VISIBLE
        continueButton.text = "Done"
        cancelButton.text = "Close"

        // Show the page the run actually ended on — whether it succeeded or
        // gave up. A spoken "I couldn't finish that, you might have better luck
        // on the phone" told the user nothing about WHERE it got to or what
        // stopped it, and a success was just as blind: the result was read out
        // while the screen still showed about:blank. The page is the evidence,
        // so put it up and let them carry on from there by hand.
        isShowingPlan = false
        planScroll.visibility = View.GONE

        // Deliberately NOT made visible yet. A task approved by voice never put
        // a page on this overlay, so the WebView has loaded nothing and showing
        // it here is a full-screen black rectangle under the result text —
        // which reads as the task having broken, when it had in fact just
        // succeeded. It becomes visible below, once there is something in it.
        scope.launch {
            val endedOn = GlassBrowserEngine.currentUrl()
            when {
                // Nothing to show: the engine was already cleared, or it never
                // left about:blank. Keep the WebView hidden so the result text
                // stands on its own instead of floating above a black void.
                endedOn.isNullOrBlank() -> webView.visibility = View.GONE

                // Already displaying this exact page — just reveal it. Skipping
                // the reload keeps the user's scroll position.
                endedOn == loadedUrl && hasLoadedPage ->
                    webView.visibility = View.VISIBLE

                else -> {
                    loadedUrl = endedOn
                    hasLoadedPage = true
                    webView.loadUrl(endedOn)
                    webView.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * "Stop" during a resumed run. Cancels the loop and drops the goal, so the
     * engine isn't left thinking a task is still in flight.
     */
    private fun stopRun() {
        runJob?.cancel()
        runJob = null
        GlassBrowserEngine.markBusy(false)
        GlassBrowserEngine.cancel()
        showResult("Stopped.")
    }

    // -------------------------------------------------------------- task plan

    /**
     * Shows the plan for approval. The glasses speak a short version; this is
     * the full thing, because a multi-step plan read aloud is not something
     * anyone can actually hold on to.
     */
    private fun showPlan(plan: TaskPlan) {
        isShowingPlan = true
        isRunFinished = false
        reasonText.text = "Here's my plan — shall I go ahead?"
        planText.text = plan.toDisplayText()

        // No page to show at approval time; the plan takes the WebView's place.
        webView.visibility = View.GONE
        planScroll.visibility = View.VISIBLE
        planScroll.scrollTo(0, 0)

        continueButton.visibility = View.VISIBLE
        continueButton.text = "Go ahead"
        cancelButton.text = "Cancel"
        editButton.visibility = View.VISIBLE
        visibility = View.VISIBLE
    }

    /**
     * "Edit" on a plan. Takes a plain-language change and re-plans around it,
     * rather than making the user cancel and dictate the whole task again.
     */
    private fun promptForEdit() {
        val plan = TaskSession.plan ?: return
        val input = android.widget.EditText(context).apply {
            hint = "What should I change?"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7A7A7A"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        android.app.AlertDialog.Builder(context)
            .setTitle("Change the plan")
            .setMessage(plan.summary)
            .setView(input)
            .setNegativeButton("Back", null)
            .setPositiveButton("Update") { _, _ ->
                val change = input.text?.toString()?.trim().orEmpty()
                if (change.isEmpty()) return@setPositiveButton
                applyEdit(change)
            }
            .show()
    }

    /** Feeds the requested change back through the planner for a fresh plan. */
    private fun applyEdit(change: String) {
        reasonText.text = "Reworking the plan…"
        editButton.visibility = View.GONE
        continueButton.visibility = View.GONE

        scope.launch {
            // requestChanges() drops the old plan and returns to GATHERING with
            // the user's words kept, which is exactly what the voice route does
            // when they ask for a change instead of saying yes.
            TaskSession.requestChanges(change)
            val spoken = TaskSession.replan(context)
            // A new plan arrives through the task listener, which repaints this
            // overlay. Only speak up here if planning gave us nothing.
            if (TaskSession.phase != TaskSession.Phase.AWAITING_APPROVAL) {
                showResult(spoken)
            }
        }
    }

    /**
     * A task has started running, whoever started it.
     *
     * When the tap path started it, [approvePlan] has already painted this and
     * owns the result. When the *voice* path started it, this is the only thing
     * that puts anything on screen — the spoken result goes back through the
     * glasses, so the overlay just shows that work is happening and gets out of
     * the way when the task ends.
     */
    private fun showExecuting() {
        isShowingPlan = false
        planScroll.visibility = View.GONE
        webView.visibility = View.VISIBLE
        // Don't stomp on a run this overlay is already driving and tracking.
        if (runJob?.isActive == true) return
        showRunning()
        visibility = View.VISIBLE
    }

    /**
     * "Go ahead" tapped on the phone rather than said out loud.
     *
     * Approval has to work from here as well as by voice: the plan is on screen
     * precisely because the user may be reading it rather than listening, and
     * by then the voice session may well be over.
     */
    private fun approvePlan() {
        // Null means the voice path got there first (the user said yes and
        // tapped). That run is already going, so leave it alone and let
        // showExecuting() keep the screen honest rather than blanking it.
        val goal = TaskSession.approve() ?: return

        // Swap the plan out for the page as soon as work starts — a handoff
        // mid-run needs the WebView back.
        isShowingPlan = false
        planScroll.visibility = View.GONE
        webView.visibility = View.VISIBLE
        showRunning()

        runJob = scope.launch {
            // Progress AND the final outcome both arrive through the task
            // listener above, which every approval path feeds. Calling
            // showResult() here as well would run it twice for one task —
            // reloading the page and resetting the buttons a second time.
            TaskSession.execute(context, goal)
        }
    }

    /** Puts the plan away and restores the page view for normal handoffs. */
    private fun hidePlan() {
        isShowingPlan = false
        planScroll.visibility = View.GONE
        webView.visibility = View.VISIBLE
        resetButtons()
        hide()
    }

    /** Clears a finished run's result and puts the overlay away. */
    private fun dismissResult() {
        resetButtons()
        hide()
    }

    /** Puts both buttons back to their handoff (pre-run) state. */
    private fun resetButtons() {
        isRunFinished = false
        editButton.visibility = View.GONE
        continueButton.visibility = View.VISIBLE
        continueButton.text = "Continue"
        cancelButton.text = "Cancel"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "BrowserHandoffOverlay"
    }
}
