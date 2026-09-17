package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.util.Log
import com.sdk.glassessdksample.ui.QuickNote
import com.sdk.glassessdksample.ui.QuickNotesManager
import kotlinx.coroutines.sync.withLock

/**
 * The browser, exposed to the glasses voice session as tools.
 *
 * The user speaks; the live model decides a tool is needed; these functions run
 * and return a **spoken-style sentence** that the model reads back. So the
 * whole browser is operated by talking, with the phone in a pocket.
 *
 * Every string returned here is going to be said out loud, so they are written
 * as speech — short, no markup, no URLs read character by character.
 */
object GlassBrowserTools {

    private const val TAG = "GlassBrowserTools"

    /**
     * Serialises the task tools against each other.
     *
     * Every other browser tool guards itself with GlassBrowserEngine.isBusy,
     * but the task tools plan rather than browse, so that flag never covered
     * them — two deliveries of one call could run the planner concurrently and
     * both append to the answer list.
     */
    private val taskMutex = kotlinx.coroutines.sync.Mutex()

    /** Last answer handled, to recognise a redelivery of the same call. */
    @Volatile
    private var lastAnswerHandled: String? = null

    @Volatile
    private var lastAnswerAt: Long = 0L

    /** What was said for that answer, replayed if the call arrives again. */
    @Volatile
    private var lastTurnSpoken: String = ""

    /** Two identical answers closer together than this are one call, twice. */
    private const val DUPLICATE_WINDOW_MS = 8_000L

    /** Tool names this object handles, for the dispatcher to check against. */
    val TOOL_NAMES = setOf(
        "start_task",
        "task_answer",
        "task_approve",
        "browse_web",
        "browser_continue",
        "browser_cancel",
        "read_current_page",
        "catch_up_on_ai",
        "browser_scroll",
        "browser_click",
        "browser_type",
        "browser_back",
        "browser_forward"
    )

    /**
     * Declarations in the same shape as the other tools in `GeminiLiveService`.
     * The descriptions carry their weight: they are what makes the model pick
     * the browser instead of answering from memory.
     */
    fun declarations(): List<Map<String, Any>> = listOf(
        mapOf(
            "type" to "function",
            "name" to "start_task",
            "description" to
                "Begin a multi-step task that the browser will carry out on the user's " +
                "phone. Call this ONLY when the user explicitly asks for a task in those " +
                "words - 'do a task for me', 'start a task', 'I have a task', 'ek task " +
                "karna hai'. NEVER call it for an ordinary question, however much it " +
                "sounds like something on the web: 'what are flights to Jaipur', " +
                "'how much is this', 'what's the score' are all answered by you directly " +
                "with your own search, not by this. The browser can ONLY reach two sites: " +
                "ChatGPT (chatgpt.com) and Claude (claude.ai). If the task needs any other " +
                "site - shopping, booking, email, YouTube - do NOT call this: say you can " +
                "only do tasks on ChatGPT and Claude at the moment. This tool starts a " +
                "conversation: it " +
                "returns a QUESTION for you to ask the user out loud, and you pass their " +
                "reply to task_answer. It does not browse anything yet.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "request" to mapOf(
                        "type" to "string",
                        "description" to
                            "What the user wants done, in their own words. If they only " +
                            "said 'do a task for me' with no detail, pass that as-is."
                    )
                ),
                "required" to listOf("request")
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "task_answer",
            "description" to
                "Pass the user's reply to the question the task asked. Call this every " +
                "time the user answers something during a task conversation. It returns " +
                "either the NEXT question to ask out loud, or the finished PLAN to read " +
                "back to them. When it returns a plan, read it out and ask whether to go " +
                "ahead - do NOT call task_approve in the same turn.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "answer" to mapOf(
                        "type" to "string",
                        "description" to "What the user just said, in their own words"
                    )
                ),
                "required" to listOf("answer")
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "task_approve",
            "description" to
                "The user has agreed to the plan you read back to them - 'yes', 'go " +
                "ahead', 'do it', 'haan', 'theek hai'. This starts the actual browsing " +
                "and may take a while. Call it ONLY in a turn AFTER the plan was read " +
                "out and the user said yes. If they say no or cancel, set approved to " +
                "false. If instead they want part of the plan CHANGED - 'no, make it " +
                "the 20th', 'change the pin code', 'two people not one', 'edit that' - " +
                "do NOT call this at all: call task_answer with exactly what they want " +
                "changed, and a fresh plan comes back for them to approve.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "approved" to mapOf(
                        "type" to "boolean",
                        "description" to "True if the user agreed, false if they cancelled"
                    )
                ),
                "required" to listOf("approved")
            )
        ),
        // browse_web is deliberately NOT declared to the voice model.
        //
        // It used to be, and the model reliably chose it for ordinary questions
        // ("flights to Jaipur", "restaurants nearby") because it was simply
        // there. That opened a real WebView, which hit sign-in walls and
        // CAPTCHAs it is not allowed to solve, so the user got "I couldn't get
        // past the security check" instead of an answer — and no prompt wording
        // stopped the model reaching for a tool sitting in front of it.
        //
        // Live questions are answered by Gemini's own google_search grounding,
        // server-side, in the same reply. The browser is now reached ONLY
        // through start_task, which the user triggers by name ("do a task for
        // me"), so the model never picks it unprompted. The handler below still
        // exists for the Web section of the app, which calls it directly.
        mapOf(
            "type" to "function",
            "name" to "browser_continue",
            "description" to
                "Resume a browsing task that stopped. Two cases. (1) It stopped " +
                "because the user had to do something themselves — signing in, a " +
                "security check: call this when they say 'I've logged in', 'done', " +
                "'carry on', 'continue', 'ho gaya', with no instruction. (2) It got " +
                "STUCK and asked them what to try instead: pass whatever they answer " +
                "as 'instruction', in their own words — 'sort by rating', 'try the " +
                "second one', 'use Flipkart instead', 'skip that bit'. Always pass " +
                "the instruction when they gave one; without it the task just walks " +
                "back into the same dead end.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "instruction" to mapOf(
                        "type" to "string",
                        "description" to
                            "What the user said to try instead, if they gave guidance. " +
                            "Leave empty when they only said they had finished a login."
                    )
                )
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_cancel",
            "description" to
                "Give up on the current browsing task instead of continuing it. Use " +
                "when the user says 'never mind', 'cancel that', 'forget it', 'stop', " +
                "or 'give up' about something the browser was doing or waiting on.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_scroll",
            "description" to
                "Scroll the page currently open in the browser, without re-planning " +
                "the whole task. Use for 'scroll down', 'scroll up', 'go down more', " +
                "'page down', 'scroll to the top'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "direction" to mapOf(
                        "type" to "string",
                        "description" to "'down' or 'up'. Defaults to down."
                    ),
                    "amount" to mapOf(
                        "type" to "string",
                        "description" to
                            "How far: 'a bit', 'a lot'/'page', or 'top'/'bottom' to jump " +
                            "to the very start or end of the page. Defaults to 'a bit'."
                    )
                )
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_click",
            "description" to
                "Tap a button or link on the page currently open in the browser, by " +
                "what it says, without re-planning the whole task. Use for 'click " +
                "sign up', 'tap the second result', 'open the first link', 'press " +
                "search'. Won't tap a sign-in/login control — that still needs the " +
                "user's own tap.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "description" to mapOf(
                        "type" to "string",
                        "description" to "What the button or link says or looks like, in the user's words"
                    )
                ),
                "required" to listOf("description")
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_type",
            "description" to
                "Type text into a field on the page currently open in the browser, by " +
                "what the field is for, without re-planning the whole task. Use for " +
                "'type headphones in the search box', 'put my name in the name field'. " +
                "Never used for passwords, OTPs or card numbers — those are always the " +
                "user's own step.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "field" to mapOf(
                        "type" to "string",
                        "description" to "Which field, by its label or placeholder, in the user's words"
                    ),
                    "text" to mapOf(
                        "type" to "string",
                        "description" to "The text to type"
                    ),
                    "submit" to mapOf(
                        "type" to "boolean",
                        "description" to "True if this should also submit the field (press Enter)"
                    )
                ),
                "required" to listOf("field", "text")
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_back",
            "description" to
                "Go back to the previous page in the browser. Use for 'go back', " +
                "'previous page'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "browser_forward",
            "description" to
                "Go forward to the next page in the browser, after having gone back. " +
                "Use for 'go forward'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to emptyMap<String, Any>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "read_current_page",
            "description" to
                "Read back what is on the page the browser is currently showing. Use " +
                "when the user asks 'what does it say', 'read that', 'summarise this " +
                "page', or asks a question about the page just opened.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "question" to mapOf(
                        "type" to "string",
                        "description" to "What the user wants to know, if they asked something specific"
                    )
                )
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "catch_up_on_ai",
            "description" to
                "Tell the user where their work with an AI assistant has got to, by " +
                "opening that service in the browser and reading their recent " +
                "conversations. Use for 'how far is my Claude project', 'what was I " +
                "doing in ChatGPT', 'catch me up on my Gemini chats'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "service" to mapOf(
                        "type" to "string",
                        "description" to "Which assistant: claude, chatgpt or gemini"
                    ),
                    "question" to mapOf(
                        "type" to "string",
                        "description" to "What the user actually asked"
                    )
                ),
                "required" to listOf("service")
            )
        )
    )

    /**
     * `handleBlocking` used to live here as a runBlocking bridge for the
     * `onToolCall` dispatchers back when they were plain functions. They are
     * suspend now and call `handle` directly, so the wrapper — and the thread
     * it used to pin — is gone.
     */

    /**
     * Runs one browser tool. Returns the sentence the glasses should say.
     *
     * Never throws: a voice turn that dies silently is worse than one that
     * says it went wrong.
     */
    suspend fun handle(
        context: Context,
        toolName: String,
        args: Map<String, Any>
    ): String {
        GlassBrowserEngine.init(context)

        return try {
            when (toolName) {
                "start_task" -> startTask(context, args["request"]?.toString().orEmpty())
                "task_answer" -> taskAnswer(context, args["answer"]?.toString().orEmpty())
                "task_approve" -> taskApprove(
                    context,
                    args["approved"]?.toString()?.toBooleanStrictOrNull() ?: true
                )
                "browse_web" -> browse(context, args["goal"]?.toString().orEmpty())
                "browser_continue" -> resume(context, args["instruction"]?.toString())
                "browser_cancel" -> cancel()
                "read_current_page" -> readPage(context, args["question"]?.toString())
                "catch_up_on_ai" -> catchUp(
                    context,
                    args["service"]?.toString().orEmpty(),
                    args["question"]?.toString()
                )
                "browser_scroll" -> scroll(
                    args["direction"]?.toString(),
                    args["amount"]?.toString()
                )
                "browser_click" -> click(args["description"]?.toString().orEmpty())
                "browser_type" -> type(
                    args["field"]?.toString().orEmpty(),
                    args["text"]?.toString().orEmpty(),
                    args["submit"]?.toString()?.toBooleanStrictOrNull() ?: false
                )
                "browser_back" -> backOrForward(forward = false)
                "browser_forward" -> backOrForward(forward = true)
                else -> "I don't know how to do that in the browser."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool $toolName failed", e)
            GlassBrowserEngine.markBusy(false)
            "Something went wrong in the browser."
        }
    }

    // ------------------------------------------------------- task conversation

    /**
     * Starts the gather-plan-approve flow. Returns the first question, which the
     * live model reads out; the user's reply comes back through [taskAnswer].
     */
    private suspend fun startTask(context: Context, request: String): String {
        if (GlassBrowserEngine.isBusy) {
            return "I'm still working on the last thing. Give me a moment."
        }
        if (request.isBlank()) {
            return "Sure — what would you like me to do?"
        }

        return taskMutex.withLock {
            TaskSession.begin(request)
            nextTurn(context)
        }
    }

    /** Files an answer and returns either the next question or the plan. */
    private suspend fun taskAnswer(context: Context, answer: String): String {
        if (!TaskSession.isActive) {
            return "There's no task on the go. Say 'start a task' to begin one."
        }
        if (answer.isBlank()) return "Sorry, I didn't catch that."

        return taskMutex.withLock {
            // The SAME answer arriving twice is a duplicate delivery, not the
            // user repeating themselves: the live socket has several dispatch
            // paths (Gemini's toolCall plus OpenAI's two function-call events)
            // and one call can come down more than one of them. Without this,
            // the planner ran twice, the answer was filed twice, and the user
            // got the next question — or the whole plan — read out twice.
            if (answer.trim().equals(lastAnswerHandled, ignoreCase = true) &&
                System.currentTimeMillis() - lastAnswerAt < DUPLICATE_WINDOW_MS
            ) {
                Log.d(TAG, "Ignoring duplicate answer: $answer")
                return@withLock lastTurnSpoken
            }
            lastAnswerHandled = answer.trim()
            lastAnswerAt = System.currentTimeMillis()

            // A reply during approval is a change request, not an answer to a
            // question — the model should have called task_approve for a plain yes.
            if (TaskSession.phase == TaskSession.Phase.AWAITING_APPROVAL) {
                TaskSession.requestChanges(answer)
            } else {
                TaskSession.recordAnswer(answer)
            }
            nextTurn(context).also { lastTurnSpoken = it }
        }
    }

    /**
     * Asks the planner what comes next and turns it into something speakable.
     * Shared by [startTask] and [taskAnswer] because both need the same thing.
     */
    private suspend fun nextTurn(context: Context): String {
        val atCap = TaskSession.questionCount >= TaskPlanner.MAX_QUESTIONS
        if (atCap) Log.d(TAG, "Question cap reached — a question this turn will be refused")

        val turn = TaskPlanner(context).next(
            TaskSession.request,
            TaskSession.answersSoFar()
        )

        // The cap is enforced HERE, not just asked for in the prompt. This
        // block used to log "forcing a plan" and then force nothing, so a model
        // that kept finding one more thing to ask could question the user
        // indefinitely — and the prompt now explicitly allows up to six
        // questions, which makes running over more likely rather than less.
        if (atCap && turn is TaskTurn.Question) {
            Log.w(TAG, "Planner asked past the cap; requesting a plan instead")
            val forced = TaskPlanner(context).next(
                TaskSession.request,
                TaskSession.answersSoFar() +
                    ("(no more questions allowed)" to
                        "Do not ask anything else. Write the plan now and list " +
                        "anything you still had to assume.")
            )
            // If it still will not plan, say so rather than looping.
            if (forced !is TaskTurn.Question && forced != null) {
                return renderTurn(forced)
            }
            TaskSession.finish()
            return "I couldn't pin that down well enough to plan it. Try telling me again?"
        }

        return renderTurn(turn)
    }

    /** Turns a planner turn into the sentence the glasses should say. */
    private fun renderTurn(turn: TaskTurn?): String {
        return when (turn) {
            null -> {
                TaskSession.finish()
                "I couldn't work that one out. Try telling me again?"
            }

            is TaskTurn.Question -> {
                TaskSession.askedQuestion(turn.text)
                turn.text
            }

            is TaskTurn.Ready -> {
                TaskSession.awaitApproval(turn.plan)
                // Spoken short; the phone shows the whole thing via the
                // TaskSession listener.
                turn.plan.toSpoken()
            }

            is TaskTurn.Refused -> {
                TaskSession.finish()
                turn.reason
            }
        }
    }

    /** Runs the approved plan, or drops it. */
    private suspend fun taskApprove(context: Context, approved: Boolean): String {
        if (TaskSession.phase != TaskSession.Phase.AWAITING_APPROVAL) {
            return "There's no plan waiting for a yes right now."
        }

        if (!approved) {
            TaskSession.finish()
            return "Okay, I've dropped it."
        }

        val goal = TaskSession.approve()
            ?: return "Something went wrong with that plan. Shall we start again?"

        return TaskSession.execute(context, goal)
    }

    // ------------------------------------------------------------------ tools

    private suspend fun browse(context: Context, goal: String): String {
        if (goal.isBlank()) return "Tell me what you'd like me to do on the web."

        if (GlassBrowserEngine.isBusy) {
            return "I'm still working on the last thing. Give me a moment."
        }

        // A pending manual step blocks everything: doing a new task would leave
        // the old one silently abandoned.
        GlassBrowserEngine.pendingReason?.let { reason ->
            return "$reason Say continue when you're done."
        }

        GlassBrowserEngine.markBusy(true)
        return try {
            val runner = HeadlessAgentRunner(context)
            val outcome = runner.run(goal)
            outcome.spokenResult
        } finally {
            GlassBrowserEngine.markBusy(false)
        }
    }

    /**
     * Carries a parked task on.
     *
     * [instruction] is what the user said when the agent was stuck and asked
     * what to try instead. It is folded into the goal by the engine, so the
     * planner sees it on the next turn — previously the answer was discarded
     * and the agent resumed on the unchanged goal, walking straight back into
     * whatever had stopped it.
     */
    private suspend fun resume(context: Context, instruction: String?): String {
        val goal = GlassBrowserEngine.resume(instruction)
            ?: return "There's nothing waiting to continue."

        GlassBrowserEngine.markBusy(true)
        return try {
            // Progress goes to any screen watching, the same as a first run.
            HeadlessAgentRunner(context) { TaskSession.reportProgress(it) }
                .run(goal, resuming = true)
                .spokenResult
        } finally {
            GlassBrowserEngine.markBusy(false)
        }
    }

    private fun cancel(): String {
        if (!GlassBrowserEngine.awaitingUser && !GlassBrowserEngine.isBusy) {
            return "There's nothing to cancel."
        }
        GlassBrowserEngine.cancel()
        return "Okay, I've dropped that."
    }

    /**
     * A quick, direct action against whatever page is already open — no LLM
     * planning turn, no page snapshot round-trip through a planner prompt.
     * These exist so ordinary mid-browsing commands ("scroll down", "click
     * sign up") answer immediately instead of re-running the whole [browse]
     * goal loop, which was the only way to act on the page before.
     */
    private suspend fun scroll(direction: String?, amount: String?): String {
        if (GlassBrowserEngine.isBusy) return "Hang on, I'm still doing the last thing."
        if (GlassBrowserEngine.currentUrl() == null) return "There's no page open yet."

        val amountText = amount?.lowercase().orEmpty()
        val dir = if (direction?.lowercase()?.contains("up") == true) -1.0 else 1.0
        val magnitude = when {
            // No absolute "jump to edge" primitive exists in BrowserAction (by
            // design, the executor only performs the closed action set below),
            // so "top"/"bottom" is approximated with a large relative scroll —
            // comfortably more than any single page's height.
            "top" in amountText -> return runDirectAction(BrowserAction.Scroll(-25.0))
            "bottom" in amountText -> return runDirectAction(BrowserAction.Scroll(25.0))
            "lot" in amountText || "page" in amountText -> 1.6
            "bit" in amountText || "little" in amountText -> 0.4
            else -> 0.9
        }

        val action = BrowserAction.Scroll(dir * magnitude)
        return runDirectAction(action)
    }

    private suspend fun click(description: String): String {
        if (description.isBlank()) return "What should I click?"
        if (GlassBrowserEngine.isBusy) return "Hang on, I'm still doing the last thing."
        if (GlassBrowserEngine.currentUrl() == null) return "There's no page open yet."

        val page = GlassBrowserEngine.readPage()
        val target = findByLabel(page, description, includeInputs = false)
            ?: return "I can't find \"$description\" on this page."

        val action = BrowserAction.Click(target.selector, target.label)
        return runDirectAction(action, page)
    }

    private suspend fun type(field: String, text: String, submit: Boolean): String {
        if (field.isBlank() || text.isBlank()) return "What should I type, and into which field?"
        if (GlassBrowserEngine.isBusy) return "Hang on, I'm still doing the last thing."
        if (GlassBrowserEngine.currentUrl() == null) return "There's no page open yet."

        val page = GlassBrowserEngine.readPage()
        val target = findByLabel(page, field, includeInputs = true)
            ?: return "I can't find a \"$field\" field on this page."

        val action = BrowserAction.Type(target.selector, text, submit)
        return runDirectAction(action, page)
    }

    private suspend fun backOrForward(forward: Boolean): String {
        if (GlassBrowserEngine.isBusy) return "Hang on, I'm still doing the last thing."
        val action = if (forward) BrowserAction.Forward else BrowserAction.Back
        return runDirectAction(action)
    }

    /** One labelled, clickable/typable element found on the page. */
    private data class LabelledTarget(val selector: String, val label: String)

    /**
     * Best-effort match of a spoken description against the page's buttons,
     * links, and (optionally) input labels — same data [WebAgentPlanner] would
     * reason over, but matched directly instead of via an LLM call, so this
     * stays fast. Exact label match wins; otherwise the element whose label
     * contains the most words from the description wins.
     */
    private fun findByLabel(
        page: PageReader.PageSnapshot,
        description: String,
        includeInputs: Boolean
    ): LabelledTarget? {
        val query = description.lowercase().trim()
        val candidates = mutableListOf<LabelledTarget>()

        fun collect(key: String) {
            val arr = page.raw.optJSONArray(key) ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label")
                if (label.isBlank()) continue
                if (includeInputs && key == "inputs" && o.optBoolean(PageReader.SENSITIVE_FLAG)) continue
                candidates.add(LabelledTarget(o.optString("selector"), label))
            }
        }
        collect("buttons")
        collect("links")
        if (includeInputs) collect("inputs")

        if (candidates.isEmpty()) return null

        candidates.firstOrNull { it.label.equals(query, ignoreCase = true) }?.let { return it }
        candidates.firstOrNull { it.label.lowercase().contains(query) }?.let { return it }
        candidates.firstOrNull { query.contains(it.label.lowercase()) }?.let { return it }

        val queryWords = query.split(" ").filter { it.length > 2 }
        if (queryWords.isEmpty()) return null
        return candidates
            .map { it to queryWords.count { w -> it.label.lowercase().contains(w) } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    /** Validates then executes one action directly, outside the planner loop. */
    private suspend fun runDirectAction(
        action: BrowserAction,
        page: PageReader.PageSnapshot? = null
    ): String {
        val snapshot = page ?: GlassBrowserEngine.readPage()
        when (val verdict = ActionValidator.validate(action, snapshot)) {
            is ActionValidator.Verdict.Handoff -> {
                GlassBrowserEngine.requireUser(verdict.reason, null)
                return "${verdict.reason} Say continue when you're done."
            }
            is ActionValidator.Verdict.NeedsConfirmation -> {
                // A spoken "yes" is too weak a gate for anything the planner
                // itself refuses to do without an on-screen tap — send the
                // user to the Web screen the same way the full agent loop does.
                return "${verdict.prompt} Please do that step on the Web screen."
            }
            is ActionValidator.Verdict.Reject -> return "I couldn't do that: ${verdict.reason}"
            ActionValidator.Verdict.Allow -> Unit
        }

        GlassBrowserEngine.markBusy(true)
        return try {
            val executor = GlassBrowserEngine.executor()
            val result = withContextMain { executor.execute(action) }
            result.detail
        } finally {
            GlassBrowserEngine.markBusy(false)
        }
    }

    private suspend fun <T> withContextMain(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }

    private suspend fun readPage(context: Context, question: String?): String {
        val url = GlassBrowserEngine.currentUrl()
            ?: return "There's no page open yet. Tell me what to look up."

        val content = GlassBrowserEngine.readContent()
        if (!content.ok || content.isEmpty) {
            return "There's nothing readable on that page."
        }

        val summary = PageSummarizer(context).summarize(
            content,
            PageSummarizer.Style.GENERAL,
            question
        ) ?: return "I couldn't read that page."

        Log.d(TAG, "Summarised $url")
        return summary.body
    }

    private suspend fun catchUp(
        context: Context,
        serviceName: String,
        question: String?
    ): String {
        val service = AiService.match(serviceName)
            ?: return "I can check Claude or ChatGPT. Which one?"

        GlassBrowserEngine.markBusy(true)
        return try {
            GlassBrowserEngine.open(service.homeUrl)
            val content = GlassBrowserEngine.readContent()

            // A sign-in wall reads as an almost-empty page. Say what's actually
            // needed rather than summarising a login screen.
            if (!content.ok || content.text.length < MIN_CONTENT_CHARS) {
                GlassBrowserEngine.requireUser(
                    "I need you signed in to ${service.displayName}. " +
                        "Open the Web screen on your phone and log in.",
                    null
                )
                return "I need you signed in to ${service.displayName}. " +
                    "Open the Web section on your phone and log in — you only have to do it once. " +
                    "Then say continue."
            }

            val summary = PageSummarizer(context).summarize(
                content,
                PageSummarizer.Style.PROJECT_STATUS,
                question
            ) ?: return "I couldn't read your ${service.displayName} history."

            saveNote(context, "${service.displayName} catch-up", summary)
            summary.body
        } finally {
            GlassBrowserEngine.markBusy(false)
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Files a summary in Quick Notes so it survives the conversation — the user
     * heard it once, in a pocket, and will want it later.
     */
    private fun saveNote(
        context: Context,
        title: String,
        summary: PageSummarizer.Summary
    ) {
        try {
            QuickNotesManager(context).createNote(
                title = title,
                content = buildString {
                    append(summary.body)
                    append("\n\nSource: ").append(summary.sourceUrl)
                },
                createdBy = QuickNote.CreatedBy.AI
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save catch-up note", e)
        }
    }

    private const val MIN_CONTENT_CHARS = 400
}
