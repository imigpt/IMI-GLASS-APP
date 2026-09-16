package com.sdk.glassessdksample.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * A browser the glasses can drive without anyone looking at the phone.
 *
 * This is the same WebView machinery as [WebBrowserActivity], but off-screen
 * and process-wide, so a voice session can browse while the app is in the
 * background or the phone is in a pocket.
 *
 * Two things make that safe rather than reckless:
 * - It shares its cookie jar with the visible browser, so a site the user
 *   logged into on screen is already logged in here. Credentials are never
 *   handled by the agent, only reused.
 * - When it reaches something only a person should do — a login, a CAPTCHA —
 *   it stops and says so, rather than trying to get past it. The user opens
 *   the Web screen, does that step, and tells the glasses to carry on.
 *
 * Held as a singleton because the browsing session is conceptually one thing:
 * the user's, not any one screen's.
 */
object GlassBrowserEngine {

    private const val TAG = "GlassBrowserEngine"

    /** Off-screen WebView. Created on the main thread, on first use. */
    private var webView: WebView? = null

    private val main = Handler(Looper.getMainLooper())

    /** True once the user has been told to do a manual step and hasn't finished. */
    @Volatile
    var awaitingUser: Boolean = false
        private set

    /** Why the engine is waiting, in words the glasses can speak. */
    @Volatile
    var pendingReason: String? = null
        private set

    /** The goal that was interrupted, resumed when the user says to continue. */
    @Volatile
    private var interruptedGoal: String? = null

    /**
     * Why the engine parked, which decides what resuming means.
     *
     * A login or CAPTCHA is the user DOING something the agent may not — it
     * resumes on the same goal, unchanged. Being stuck is different: the agent
     * has run out of ideas and needs to be TOLD something, and resuming on the
     * unchanged goal just walks back into the same wall. They were the same
     * state before, which is why a stuck agent could only ever repeat itself.
     */
    enum class WaitKind {
        /** Sign-in, CAPTCHA, payment — the user acts, the goal is untouched. */
        USER_ACTION,

        /** The agent is stuck and asked the user what to do instead. */
        NEEDS_GUIDANCE
    }

    @Volatile
    var waitKind: WaitKind = WaitKind.USER_ACTION
        private set

    /** Guidance the user gave while the agent was stuck, folded into the goal. */
    @Volatile
    private var guidance: MutableList<String> = mutableListOf()

    /** Guards against two voice turns driving the browser at once. */
    @Volatile
    var isBusy: Boolean = false
        private set

    // ------------------------------------------------------------------ setup

    /** Creates the off-screen WebView if it doesn't exist yet. Main thread only. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(context: Context): WebView {
        webView?.let { return it }

        val view = WebView(context.applicationContext)
        WebSessionManager.configure(view, desktopMode = true)
        view.webViewClient = WebViewClient()
        // Same OAuth-popup fix as WebBrowserActivity (see PopupWindowRouter):
        // without this, "Continue with Google" during a voice-driven sign-in
        // would leave this WebView waiting on a popup that never opens.
        view.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onCreateWindow(
                webView: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = PopupWindowRouter.routeInto(view, resultMsg)
        }
        // Never attached to a window: this browser has no viewer. Give it a
        // real size anyway, or layout-dependent scripts and visibility checks
        // see a 0x0 page and report nothing.
        view.layout(0, 0, VIRTUAL_WIDTH, VIRTUAL_HEIGHT)

        webView = view
        Log.d(TAG, "Off-screen browser created")
        return view
    }

    /** Runs [block] on the main thread, where every WebView call must happen. */
    private suspend fun <T> onMain(block: (WebView) -> T): T =
        withContext(Dispatchers.Main) {
            block(ensureWebView(appContext!!))
        }

    /** Set once from the Application/Service so tools don't need a Context. */
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // ------------------------------------------------------------- state gates

    /**
     * Notified whenever [awaitingUser] flips, so a screen can put the blocked
     * page in front of the user the moment it happens.
     *
     * Without this the wait-state was pollable but not observable, and the only
     * thing that ever looked at it was WebBrowserActivity.onResume() — so the
     * user had to already know something was stuck and walk to More → Web to
     * find out. The home screen subscribes instead, and the handoff comes to
     * them. Listeners are called on the main thread.
     */
    fun interface WaitListener {
        /** [reason] is null when the engine stopped waiting (resumed/cancelled). */
        fun onWaitChanged(reason: String?)
    }

    private val waitListeners = java.util.concurrent.CopyOnWriteArrayList<WaitListener>()

    /**
     * Subscribes [listener] and immediately replays the current state, so a
     * screen that comes up *after* the block happened still shows it — the
     * common case, since the user is usually looking at the glasses, not the
     * phone, when the agent gets stuck.
     */
    fun addWaitListener(listener: WaitListener) {
        waitListeners.addIfAbsent(listener)
        val reason = pendingReason
        if (reason != null) main.post { listener.onWaitChanged(reason) }
    }

    fun removeWaitListener(listener: WaitListener) {
        waitListeners.remove(listener)
    }

    private fun notifyWaitChanged() {
        val reason = pendingReason
        main.post { waitListeners.forEach { it.onWaitChanged(reason) } }
    }

    /**
     * Marks the engine as needing the user. The glasses speak [reason] and the
     * browser stays put until [resume] or [cancel].
     */
    fun requireUser(
        reason: String,
        goal: String?,
        kind: WaitKind = WaitKind.USER_ACTION
    ) {
        awaitingUser = true
        pendingReason = reason
        interruptedGoal = goal
        waitKind = kind
        Log.d(TAG, "Waiting on user ($kind): $reason")
        notifyWaitChanged()
    }

    /**
     * The user says they've done their part. Returns the goal to resume.
     *
     * [instruction] is what they said, when they were answering a stuck agent
     * rather than just finishing a login. It is appended to the goal so the
     * planner actually sees it next time round — without this the agent resumed
     * on the identical goal and got stuck in the identical place, which is what
     * made being stuck unrecoverable.
     */
    fun resume(instruction: String? = null): String? {
        awaitingUser = false
        pendingReason = null

        if (!instruction.isNullOrBlank()) guidance.add(instruction.trim())

        val goal = interruptedGoal
        interruptedGoal = null
        waitKind = WaitKind.USER_ACTION
        notifyWaitChanged()

        if (goal == null) return null
        if (guidance.isEmpty()) return goal

        return buildString {
            append(goal)
            append("\n\nTHE USER HAS SINCE TOLD YOU:")
            guidance.forEach { append("\n- ").append(it) }
            append("\nFollow that. Do not go back to an approach they have ruled out.")
        }
    }

    fun cancel() {
        awaitingUser = false
        pendingReason = null
        interruptedGoal = null
        waitKind = WaitKind.USER_ACTION
        // Guidance belongs to the abandoned task, not the next one.
        guidance.clear()
        isBusy = false
        notifyWaitChanged()
    }

    fun markBusy(busy: Boolean) {
        isBusy = busy
    }

    // -------------------------------------------------------------- browsing

    /** The URL currently loaded, or null when nothing has been opened. */
    suspend fun currentUrl(): String? = onMain { it.url?.takeIf { u -> u != "about:blank" } }

    /** Navigates and waits for the page to settle. */
    suspend fun open(url: String): Boolean = onMain { view ->
        view.loadUrl(url)
        true
    }.also { settle() }

    /** Runs a web search. */
    suspend fun search(query: String, engine: String = "google"): Boolean {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = when (engine.lowercase()) {
            "youtube" -> "https://m.youtube.com/results?search_query=$q"
            "bing" -> "https://www.bing.com/search?q=$q"
            else -> "https://www.google.com/search?q=$q"
        }
        return open(url)
    }

    /** Reads the page's interactive summary, for the agent loop. */
    suspend fun readPage(): PageReader.PageSnapshot =
        withContext(Dispatchers.Main) {
            PageReader.read(ensureWebView(appContext!!))
        }

    /**
     * A JPEG of the current page, base64-encoded, or null if it can't be taken.
     *
     * The LAST resort for understanding a page. Everything else here reads the
     * DOM, which is cheap, instant and exact — but a site that draws its
     * controls as plain <div>s can leave that summary genuinely empty, and no
     * amount of DOM widening finds a control that isn't marked up as one. A
     * picture is what a person would use in that situation.
     *
     * Deliberately not called unless the text summary comes back with nothing
     * to act on: an image costs ~2000 input tokens per step and adds a second
     * or two, against ~0 for the DOM read.
     */
    suspend fun screenshotBase64(): String? = withContext(Dispatchers.Main) {
        try {
            val view = ensureWebView(appContext!!)
            val width = view.width.takeIf { it > 0 } ?: VIRTUAL_WIDTH
            val height = view.height.takeIf { it > 0 } ?: VIRTUAL_HEIGHT

            // This WebView is never attached to a window, so there is no
            // surface to capture — draw it into a bitmap by hand instead.
            val bitmap = android.graphics.Bitmap.createBitmap(
                width, height, android.graphics.Bitmap.Config.RGB_565
            )
            view.draw(android.graphics.Canvas(bitmap))

            // Downscale before encoding. Gemini tiles an image into 768px
            // squares at 258 tokens each, so height is what costs money here.
            val scaled = if (height > MAX_SHOT_HEIGHT) {
                val ratio = MAX_SHOT_HEIGHT.toFloat() / height
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap, (width * ratio).toInt(), MAX_SHOT_HEIGHT, true
                ).also { bitmap.recycle() }
            } else {
                bitmap
            }

            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, SHOT_QUALITY, out)
            scaled.recycle()

            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot failed", e)
            null
        }
    }

    /** The size the screenshot was taken at, so tap coordinates can be scaled back. */
    suspend fun viewportSize(): Pair<Int, Int> = withContext(Dispatchers.Main) {
        val view = ensureWebView(appContext!!)
        Pair(
            view.width.takeIf { it > 0 } ?: VIRTUAL_WIDTH,
            view.height.takeIf { it > 0 } ?: VIRTUAL_HEIGHT
        )
    }

    /** Reads the page's prose, for summarising. */
    suspend fun readContent(): PageContentExtractor.Content =
        withContext(Dispatchers.Main) {
            PageContentExtractor.extract(ensureWebView(appContext!!))
        }

    /** An executor bound to the off-screen WebView. */
    suspend fun executor(): ActionExecutor =
        withContext(Dispatchers.Main) { ActionExecutor(ensureWebView(appContext!!)) }

    /**
     * Gives a freshly loaded page time to render before it is read.
     *
     * A fixed pause rather than an onPageFinished hook: most navigation on the
     * sites this is used with is client-side and never fires it.
     */
    private suspend fun settle() {
        kotlinx.coroutines.delay(SETTLE_MS)
    }

    /** Releases the WebView. Called when the app tears the voice session down. */
    fun release() {
        main.post {
            try {
                webView?.apply {
                    stopLoading()
                    destroy()
                }
            } catch (e: Exception) {
                Log.w(TAG, "release failed", e)
            }
            webView = null
            cancel()
        }
    }

    private const val VIRTUAL_WIDTH = 1280
    private const val VIRTUAL_HEIGHT = 2000

    /** Screenshots are scaled to this height before encoding, to cap tokens. */
    private const val MAX_SHOT_HEIGHT = 1536
    private const val SHOT_QUALITY = 70
    private const val SETTLE_MS = 2500L
}
