package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One task, from "do a task for me" through to a finished run.
 *
 * A voice turn is stateless: every tool call arrives on its own, with nothing
 * carried over from the last one. Gathering requirements takes several turns,
 * so the half-built task has to live somewhere between them. That is this.
 *
 * The flow, each arrow being a separate spoken turn:
 *
 *   start_task ─▶ question ─▶ task_answer ─▶ question ─▶ … ─▶ PLAN
 *                                                              │
 *                            approved ◀── task_approve ────────┘
 *                                │
 *                                ▼
 *                        execution (HeadlessAgentRunner)
 *
 * Process-wide singleton for the same reason [GlassBrowserEngine] is one: the
 * task belongs to the user, not to whichever Activity happens to be alive.
 */
object TaskSession {

    private const val TAG = "TaskSession"

    enum class Phase {
        /** Nothing in flight. */
        IDLE,

        /** Asking the user what the task needs. */
        GATHERING,

        /** A plan has been spoken and is waiting for yes or no. */
        AWAITING_APPROVAL,

        /** Approved and running in the browser. */
        EXECUTING
    }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** What the user originally asked for, in their own words. */
    @Volatile
    var request: String = ""
        private set

    /** The plan awaiting approval, or the one currently executing. */
    @Volatile
    var plan: TaskPlan? = null
        private set

    /** Questions asked and answers given, in order. */
    private val answers = mutableListOf<Pair<String, String>>()

    /** The question the user is currently being asked. */
    @Volatile
    private var openQuestion: String? = null

    /** When the task last did anything, for the abandonment check below. */
    @Volatile
    private var lastActivityAt: Long = 0L

    val questionCount: Int get() = answers.size

    // ------------------------------------------------------------- observers

    /**
     * Lets the phone screen mirror the conversation — the plan has to appear
     * there as well as being spoken, and the user may approve by tapping.
     */
    fun interface Listener {
        fun onPhaseChanged(phase: Phase, plan: TaskPlan?)

        /**
         * A line of progress from a running task.
         *
         * Defaulted so existing listeners need not care. It exists because the
         * two approval paths were asymmetric: tapping "Go ahead" passed a
         * progress callback and showed each step, while approving by VOICE went
         * through the tool handler, which passed none — so the screen sat on one
         * frozen message and there was no way to tell working from stuck.
         */
        fun onProgress(message: String) {}

        /**
         * The run ended — [message] is what was said out loud.
         *
         * Same asymmetry as [onProgress], one step worse: approving by voice
         * surfaced NOTHING at the end. The spoken outcome went back through the
         * glasses ("I couldn't finish that, you might have better luck on the
         * phone") and the screen was left on a stale progress line, so the user
         * was told it failed but never shown where it got to or what stopped
         * it. The page it ended on is the evidence, and it belongs on screen
         * for a success just as much as a failure.
         */
        fun onFinished(message: String, success: Boolean) {}
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        val p = phase
        val pl = plan
        main.post { listener.onPhaseChanged(p, pl) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val p = phase
        val pl = plan
        main.post { listeners.forEach { it.onPhaseChanged(p, pl) } }
    }

    /** Pushes one progress line to every screen watching the task. */
    private fun notifyProgress(message: String) {
        main.post { listeners.forEach { it.onProgress(message) } }
    }

    /**
     * Same channel, for runs driven from outside this object — a resumed task
     * carried on by GlassBrowserTools after the user answered a stuck agent.
     */
    fun reportProgress(message: String) = notifyProgress(message)

    /** Pushes the final outcome to every screen watching the task. */
    private fun notifyFinished(message: String, success: Boolean) {
        main.post { listeners.forEach { it.onFinished(message, success) } }
    }

    // ----------------------------------------------------------- transitions

    /** Begins a new task, discarding any half-finished one. */
    fun begin(userRequest: String) {
        request = userRequest
        answers.clear()
        openQuestion = null
        plan = null
        phase = Phase.GATHERING
        lastActivityAt = System.currentTimeMillis()
        // A new task starts its step budget from zero.
        GlassBrowserEngine.resetStepsSpent()
        Log.d(TAG, "Task started: $userRequest")
        notifyListeners()
    }

    /** Records the question just put to the user. */
    fun askedQuestion(question: String) {
        openQuestion = question
        lastActivityAt = System.currentTimeMillis()
    }

    /**
     * Files the user's answer against whatever was last asked.
     *
     * When nothing was asked — the user volunteering something mid-gather — the
     * answer is still kept, under a placeholder, rather than dropped: extra
     * detail is never unwelcome and losing it means asking for it again.
     */
    fun recordAnswer(answer: String) {
        val question = openQuestion ?: "(the user added)"
        answers.add(question to answer)
        openQuestion = null
        lastActivityAt = System.currentTimeMillis()
    }

    /** Everything gathered so far, for the planner prompt. */
    fun answersSoFar(): List<Pair<String, String>> = answers.toList()

    /** A plan has been produced and spoken; now it needs a yes. */
    fun awaitApproval(newPlan: TaskPlan) {
        plan = newPlan
        phase = Phase.AWAITING_APPROVAL
        lastActivityAt = System.currentTimeMillis()
        Log.d(TAG, "Plan awaiting approval: ${newPlan.summary}")
        notifyListeners()
    }

    /** Approved. Returns the goal string execution should run, or null. */
    fun approve(): String? {
        val approved = plan ?: return null
        if (phase != Phase.AWAITING_APPROVAL) return null
        phase = Phase.EXECUTING
        Log.d(TAG, "Plan approved")
        notifyListeners()
        return approved.resolvedGoal
    }

    /**
     * The user wants changes rather than a yes or no. Drops back to gathering
     * with their words kept, so the next plan reflects them.
     */
    fun requestChanges(what: String) {
        answers.add("(the user wanted a change)" to what)
        plan = null
        phase = Phase.GATHERING
        notifyListeners()
    }

    /** Ends the task, whether it finished, failed, or was abandoned. */
    fun finish() {
        phase = Phase.IDLE
        request = ""
        plan = null
        answers.clear()
        openQuestion = null
        notifyListeners()
    }

    /**
     * True while a task is genuinely in progress.
     *
     * The voice session checks this to decide whether to stay open for the
     * user's answer instead of dropping back to the wake word. That makes a
     * task that is never finished dangerous in a way it wasn't before: the
     * phase only leaves GATHERING when the model calls another task tool, and a
     * user who simply walks away mid-question never triggers that — which would
     * hold the microphone open indefinitely.
     *
     * So an abandoned task stops counting as active. The state is cleared
     * lazily here rather than on a timer: nothing needs to happen at the moment
     * it expires, only the next time someone asks.
     */
    val isActive: Boolean
        get() {
            if (phase == Phase.IDLE) return false
            // Execution is exempt: browsing legitimately takes minutes, and it
            // ends by its own path rather than by the user saying anything.
            if (phase == Phase.EXECUTING) return true
            if (System.currentTimeMillis() - lastActivityAt > ABANDON_AFTER_MS) {
                Log.d(TAG, "Task abandoned after $ABANDON_AFTER_MS ms — releasing the session")
                finish()
                return false
            }
            return true
        }

    /** How long a task can sit unanswered before it stops holding the session. */
    private const val ABANDON_AFTER_MS = 3 * 60 * 1000L

    /**
     * Re-plans after the user edited something, and parks the new plan for
     * approval exactly as the spoken route does.
     *
     * Lives here rather than in GlassBrowserTools because the on-screen Edit
     * button needs it and that object's planning helpers are private to it.
     * Returns what to say if the re-plan produced no plan.
     */
    suspend fun replan(context: Context): String {
        val turn = TaskPlanner(context).next(request, answersSoFar())
        return when (turn) {
            is TaskTurn.Ready -> {
                awaitApproval(turn.plan)
                turn.plan.toSpoken()
            }
            is TaskTurn.Question -> {
                // The change left something unclear. Keep gathering rather than
                // planning around a gap the user just told us mattered.
                askedQuestion(turn.text)
                turn.text
            }
            is TaskTurn.Refused -> {
                finish()
                turn.reason
            }
            null -> {
                finish()
                "I couldn't rework that plan. Try telling me again?"
            }
        }
    }

    // ------------------------------------------------------------- execution

    /**
     * Runs the approved plan, reusing the same agent loop the rest of the
     * browser uses. Returns the sentence to speak.
     *
     * The plan is not re-planned here: [HeadlessAgentRunner] still decides each
     * action against the live page. What the plan changed is the *goal* it gets
     * — one resolved sentence with every gathered detail in it, instead of a
     * vague phrase it would have had to guess its way through.
     */
    suspend fun execute(context: Context, goal: String): String {
        GlassBrowserEngine.markBusy(true)
        return try {
            // Progress always goes through the listeners, so it reaches the
            // screen no matter which path approved the plan — voice or tap.
            val outcome = HeadlessAgentRunner(context) { notifyProgress(it) }.run(goal)
            // A handoff leaves the engine waiting on the user, and the task is
            // not over — the overlay is showing them the step to complete.
            if (!GlassBrowserEngine.awaitingUser) {
                // Tell the screen BEFORE finish(): finish() moves the phase to
                // IDLE, and a listener reacting to that would tear the overlay
                // down before the outcome had been shown.
                notifyFinished(outcome.spokenResult, outcome.success)
                finish()
            }
            outcome.spokenResult
        } catch (e: Exception) {
            Log.e(TAG, "Task execution failed", e)
            notifyFinished("Something went wrong running that task.", false)
            finish()
            "Something went wrong running that task."
        } finally {
            GlassBrowserEngine.markBusy(false)
        }
    }
}
