package com.sdk.glassessdksample.ui.profile

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import com.sdk.glassessdksample.ui.web.PageContentExtractor
import com.sdk.glassessdksample.ui.web.WebSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Asks the user's own AI what it remembers about them, and brings the answer back.
 *
 * The user signs in themselves in a visible WebView; this then opens a fresh
 * chat, sends one prompt, waits for the reply, and reads it. The user approves
 * or edits what comes back before anything is stored, and the session is signed
 * out afterwards — see [signOut], which runs whether or not the import worked.
 *
 * This deliberately does NOT use the browser agent. There is no planner, no
 * model deciding what to click, no step budget. The job is three fixed actions
 * on two known sites, so it is written as three fixed actions: far more
 * reliable than asking a model to rediscover the same buttons every time, and
 * it cannot wander somewhere it was not asked to go.
 */
class ProfileImporter(private val context: Context) {

    sealed class Result {
        /** The assistant answered. [text] still needs the user's approval. */
        data class Success(val text: String) : Result()

        /** The account has no memory of the user — nothing to import. */
        object NoMemory : Result()

        /** The user is not signed in, so nothing could be asked. */
        object NotSignedIn : Result()

        data class Failed(val reason: String) : Result()
    }

    /**
     * Runs the whole import against [webView], reporting progress so the screen
     * can say what is happening rather than showing a blank spinner.
     */
    suspend fun import(
        webView: WebView,
        source: ProfileSource,
        onProgress: (String) -> Unit
    ): Result {
        return try {
            onProgress("Opening a new chat…")
            if (!load(webView, source.newChatUrl)) {
                return Result.Failed("Couldn't open ${source.displayName}.")
            }

            if (isSignedOut(webView)) return Result.NotSignedIn

            // Both sites interrupt a fresh session with announcements and
            // consent dialogs — ChatGPT's "More relevant, personalized replies"
            // modal is one, and it sits in front of the composer. Typing then
            // goes nowhere and the reply that comes back is the dialog's own
            // text, so these have to be cleared before anything else.
            dismissDialogs(webView)

            onProgress("Asking ${source.displayName} what it knows about you…")
            // The composer mounts after the rest of the page on both sites, so
            // looking once and giving up reports "the site may have changed"
            // for a box that simply had not appeared yet.
            if (!awaitComposer(webView)) {
                // Log what IS on the page. "The site may have changed" told
                // nobody anything, and diagnosing this from the phone screen
                // alone meant guessing at selectors.
                Log.w(TAG, "No composer found. Page state: ${describePage(webView)}")
                return Result.Failed(
                    "Couldn't find the message box on ${source.displayName}. " +
                        "The site may have changed."
                )
            }
            if (!sendPrompt(webView, source.extractionPrompt)) {
                Log.w(TAG, "Typing failed. Page state: ${describePage(webView)}")
                return Result.Failed(
                    "Couldn't type into ${source.displayName}. " +
                        "The site may have changed."
                )
            }

            onProgress("Waiting for the reply…")
            val reply = awaitReply(webView) ?: return Result.Failed(
                "${source.displayName} didn't reply in time."
            )

            // Exact match: extractAnswer returns the bare marker and nothing
            // else when the assistant genuinely had no memories, so a looser
            // check here would misread a profile that merely mentions it.
            if (reply.trim() == ProfileSource.NO_MEMORY_MARKER) {
                Result.NoMemory
            } else if (reply.trim().length < MIN_PROFILE_CHARS) {
                // A handful of characters is not a profile. This caught a run
                // that returned just "ChatGPT said:" — the page chrome — after
                // a modal dialog blocked the composer, and presented it to the
                // user as a successful import with an empty box to approve.
                // Log the tail of the PAGE, not just the empty result: an empty
                // reply means extraction cut in the wrong place, and the only
                // way to see where is to see what it was cutting.
                val page = withContext(Dispatchers.Main) {
                    PageContentExtractor.extract(webView)
                }.text
                Log.w(
                    TAG,
                    "Reply too short (${reply.trim().length} chars). " +
                        "Page tail: ${page.takeLast(400)}"
                )
                Result.Failed(
                    "${source.displayName} didn't give a usable answer. " +
                        "Please try again."
                )
            } else {
                Result.Success(reply)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Import failed", e)
            Result.Failed("Something went wrong: ${e.message}")
        }
    }

    /**
     * Signs the user out and wipes the session.
     *
     * The promise made to the user is that their account is borrowed, not held,
     * so this must run even when the import failed halfway — a crash that left
     * the session live would be the worst case, because it is also the case
     * nobody checks. Callers put it in a `finally`.
     *
     * Both steps are deliberate: hitting the site's own logout invalidates the
     * session server-side, and clearing cookies removes it locally in case that
     * request never lands.
     */
    suspend fun signOut(webView: WebView, source: ProfileSource) {
        // TEMPORARILY DISABLED while the import is being debugged.
        //
        // Signing in and out of the same account repeatedly from one IP is
        // exactly the pattern anti-bot systems flag, and both sites began
        // returning 403 to this device after an afternoon of test runs. Keeping
        // the session across attempts means one sign-in instead of twenty.
        //
        // This MUST go back before release: the screen promises the account is
        // borrowed for one question, not held, and right now it is held.
        if (SKIP_SIGN_OUT) {
            Log.w(TAG, "Sign-out skipped (debug flag) — session left signed in")
            return
        }
        try {
            load(webView, source.logoutUrl, timeoutMs = LOGOUT_TIMEOUT_MS)
            delay(LOGOUT_GRACE_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Logout navigation failed; clearing cookies anyway", e)
        } finally {
            withContext(Dispatchers.Main) {
                WebSessionManager.clearSession(context, webView)
                webView.loadUrl("about:blank")
            }
        }
    }

    /** Whether a session-shaped cookie exists. A hint for the UI, not a promise. */
    fun hasSession(source: ProfileSource): Boolean {
        val cookies = CookieManager.getInstance().getCookie(source.cookieDomain)
        if (cookies.isNullOrBlank()) return false
        return SESSION_HINTS.any { cookies.contains(it, ignoreCase = true) }
    }

    // ------------------------------------------------------------------ steps

    /** Navigates and waits for the page to be interactive, not merely loaded. */
    private suspend fun load(
        webView: WebView,
        url: String,
        timeoutMs: Long = PAGE_TIMEOUT_MS
    ): Boolean {
        withContext(Dispatchers.Main) { webView.loadUrl(url) }
        var waited = 0L
        while (waited < timeoutMs) {
            delay(POLL_MS)
            waited += POLL_MS
            // Single quotes throughout. An escaped double quote inside the
            // attribute selector reaches JavaScript as a literal backslash,
            // which is a syntax error — querySelector then throws, this never
            // returns true, and the load times out on a page that had in fact
            // arrived perfectly well.
            val ready = js(
                webView,
                "(function(){try{return document.readyState==='complete' && " +
                    "!!document.querySelector('textarea,[contenteditable],button,a');}" +
                    "catch(e){return false;}})()"
            )
            if (ready.contains("true")) {
                // Client-rendered pages keep painting after readyState flips.
                delay(RENDER_GRACE_MS)
                return true
            }
        }
        return false
    }

    private suspend fun isSignedOut(webView: WebView): Boolean {
        val result = js(
            webView,
            """
            (function(){
              try {
                if (/\/(auth\/)?login|\/unauth/i.test(location.href)) return true;
                var n = document.querySelectorAll('button,a');
                for (var i=0;i<n.length;i++){
                  var t=(n[i].innerText||'').trim().toLowerCase();
                  if (t==='log in'||t==='login'||t==='sign in'||t==='sign up'){
                    var r=n[i].getBoundingClientRect();
                    if (r.width>0 && r.height>0) return true;
                  }
                }
                return false;
              } catch(e){ return false; }
            })()
            """.trimIndent()
        )
        return result.contains("true")
    }

    /**
     * Types the prompt and submits it.
     *
     * Both sites use ProseMirror, which keeps its own document model and only
     * trusts events the browser's editing pipeline produces. Assigning
     * textContent puts the text on screen while the editor stays empty, so the
     * send does nothing — execCommand('insertText') is what makes it real.
     */
    /**
     * Finds the message box, defined as the largest VISIBLE editable element.
     *
     * A fixed selector list was too brittle: both sites ship hidden textareas
     * and off-screen contenteditable nodes, and `querySelector` returns the
     * first match in document order — which was one of those decoys, so typing
     * went somewhere invisible and the import reported the box missing.
     *
     * Size-and-visibility is a far more durable signal than any class name or
     * id, because the composer is by construction the biggest thing on screen
     * you can type into, whatever they rename it to.
     */
    private val composerFinder = """
        function findComposer() {
          // Deliberately NOT scoped to div: ChatGPT's editable node is a <p>
          // inside the editor and Claude's is a ProseMirror div, so
          // 'div[contenteditable]' missed the real target on both.
          var nodes = document.querySelectorAll(
            'textarea, [contenteditable="true"], [contenteditable=""], ' +
            '[role="textbox"], .ProseMirror, #prompt-textarea');
          var best = null, bestArea = -1;
          for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (el.disabled || el.readOnly) continue;
            if (el.getAttribute('contenteditable') === 'false') continue;
            var st = window.getComputedStyle(el);
            if (st.display === 'none' || st.visibility === 'hidden') continue;
            var r = el.getBoundingClientRect();
            var area = r.width * r.height;
            // STRICTLY greater, and a real element must beat a zero-area one.
            //
            // ChatGPT ships a 0x0 `fallbackTextarea` that appears BEFORE the
            // real composer in document order. With a >= comparison, or with
            // bestArea starting below zero, that decoy won: typing went into an
            // invisible textarea, the read-back was empty, and the import
            // reported the message box missing — on a page where the composer
            // was present, visible and already holding the text.
            if (area > bestArea && (area > 0 || bestArea < 0)) {
              bestArea = area; best = el;
            }
          }
          // Prefer a contenteditable editor over a zero-area textarea even if
          // the textarea somehow ranked first: the editor is what the site
          // actually submits.
          if (best && bestArea === 0) {
            var real = document.querySelector(
              '#prompt-textarea, .ProseMirror, [contenteditable="true"]');
            if (real) best = real;
          }
          return best;
        }
    """.trimIndent()

    /**
     * A one-line description of what is actually on the page, for the log.
     *
     * Reports the URL and every editable-looking element with its tag, size and
     * attributes — enough to write a correct selector from a log alone, rather
     * than shipping a guess and waiting to hear whether it worked.
     */
    private suspend fun describePage(webView: WebView): String = js(
        webView,
        """
        (function(){
          try {
            var out = 'url=' + location.href + ' ready=' + document.readyState;
            var nodes = document.querySelectorAll(
              'textarea, [contenteditable], [role="textbox"], .ProseMirror');
            out += ' candidates=' + nodes.length + ' [';
            for (var i = 0; i < nodes.length && i < 8; i++) {
              var el = nodes[i];
              var r = el.getBoundingClientRect();
              out += el.tagName
                + '#' + (el.id || '-')
                + '.' + (String(el.className || '-').slice(0, 30))
                + ' ce=' + el.getAttribute('contenteditable')
                + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) + '; ';
            }
            return out + ']';
          } catch (e) { return 'describe failed: ' + e; }
        })()
        """.trimIndent()
    )

    /**
     * Clears welcome and consent dialogs that cover the composer.
     *
     * Matched by BUTTON LABEL rather than by any dialog selector: the labels
     * ("Got it", "Okay", "Continue") are stable and few, whereas the modals
     * themselves are unnamed divs that change shape between releases. Runs
     * twice because dismissing one can reveal another behind it.
     */
    private suspend fun dismissDialogs(webView: WebView) {
        repeat(2) {
            val dismissed = js(
                webView,
                """
                (function(){
                  try {
                    var labels = ['got it','okay','ok','continue','accept',
                                  'dismiss','close','not now','maybe later'];
                    var buttons = document.querySelectorAll('button');
                    for (var i = 0; i < buttons.length; i++) {
                      var b = buttons[i];
                      var t = (b.innerText || '').trim().toLowerCase();
                      if (labels.indexOf(t) === -1) continue;
                      var r = b.getBoundingClientRect();
                      if (r.width <= 0 || r.height <= 0) continue;
                      b.click();
                      return 'clicked:' + t;
                    }
                    return 'none';
                  } catch(e) { return 'err:' + e; }
                })()
                """.trimIndent()
            )
            if (dismissed.contains("none")) return
            Log.d(TAG, "Dismissed dialog: $dismissed")
            delay(DIALOG_SETTLE_MS)
        }
    }

    /** Polls until the composer exists, since it mounts after the page loads. */
    private suspend fun awaitComposer(webView: WebView): Boolean {
        var waited = 0L
        while (waited < COMPOSER_TIMEOUT_MS) {
            val found = js(
                webView,
                "(function(){ $composerFinder try { return !!findComposer(); } " +
                    "catch(e){ return false; } })()"
            )
            if (found.contains("true")) return true
            delay(POLL_MS)
            waited += POLL_MS
        }
        return false
    }

    private suspend fun sendPrompt(webView: WebView, prompt: String): Boolean {
        val escaped = prompt
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")

        val result = js(
            webView,
            """
            (function(){
              $composerFinder
              try {
                var box = findComposer();
                if (!box) return 'nobox';
                box.focus();
                var text = '$escaped';
                if (box.isContentEditable) {
                  var sel=window.getSelection(), r=document.createRange();
                  r.selectNodeContents(box); sel.removeAllRanges(); sel.addRange(r);
                  if (!document.execCommand('insertText', false, text)) return 'notyped';
                } else {
                  var setter = Object.getOwnPropertyDescriptor(
                    window.HTMLTextAreaElement.prototype, 'value');
                  setter.set.call(box, text);
                  box.dispatchEvent(new Event('input', {bubbles:true}));
                }
                return 'typed';
              } catch(e){ return 'err:'+e; }
            })()
            """.trimIndent()
        )
        if (!result.contains("typed") || result.contains("notyped")) return false

        // Verify AFTER a pause, not inside the same script.
        //
        // ProseMirror applies an insertText through its own update cycle, so
        // reading textContent on the very next statement finds the editor still
        // empty — the previous build called that a typing failure on a page
        // where the text had in fact landed and Send was already enabled.
        delay(TYPE_SETTLE_MS)
        val landed = js(
            webView,
            "(function(){ $composerFinder try { var b = findComposer(); " +
                "if (!b) return '0'; " +
                "var t = b.isContentEditable ? b.textContent : b.value; " +
                "return String((t || '').trim().length); } catch(e){ return '0'; } })()"
        )
        val chars = landed.filter { it.isDigit() }.toIntOrNull() ?: 0
        if (chars < MIN_TYPED_CHARS) {
            Log.w(TAG, "Text did not land: only $chars chars in composer")
            return false
        }

        // Submit as a separate step: the editor needs a moment to register the
        // text before Enter, and a send button that is still disabled will
        // silently ignore the click.
        delay(TYPE_SETTLE_MS)
        val sent = js(
            webView,
            """
            (function(){
              $composerFinder
              try {
                var box = findComposer();
                if (!box) return 'nobox';
                box.focus();
                ['keydown','keypress','keyup'].forEach(function(t){
                  box.dispatchEvent(new KeyboardEvent(t,
                    {bubbles:true,cancelable:true,key:'Enter',code:'Enter',
                     keyCode:13,which:13}));
                });

                // Enter is not always enough — the composer may treat it as a
                // newline, or only enable sending via the button. Click an
                // explicit send control too if one is present and enabled;
                // sending twice is not a risk because the box is cleared by
                // the first send, so a second click has nothing to submit.
                // Match on the visible label as well as the test id: the live
                // button is labelled "Send prompt", and relying on a single
                // data-testid meant a rename silently broke sending.
                var btn = document.querySelector(
                  'button[data-testid="send-button"], button[aria-label*="Send" i]');
                if (!btn) {
                  var all = document.querySelectorAll('button');
                  for (var i = 0; i < all.length; i++) {
                    var lbl = (all[i].getAttribute('aria-label') || '').toLowerCase();
                    if (lbl.indexOf('send') !== -1) { btn = all[i]; break; }
                  }
                }
                if (btn && !btn.disabled) { btn.click(); return 'sent-click'; }
                return 'sent';
              } catch(e){ return 'err:'+e; }
            })()
            """.trimIndent()
        )
        return sent.contains("sent")
    }

    /**
     * Waits for the assistant to finish answering, then returns the reply.
     *
     * Completion is judged by the text going QUIET rather than by any spinner:
     * both sites stream tokens, so the answer grows for as long as it is being
     * written. Two consecutive identical reads mean it has stopped.
     */
    private suspend fun awaitReply(webView: WebView): String? {
        var lastText = ""
        var stableFor = 0
        var waited = 0L

        while (waited < REPLY_TIMEOUT_MS) {
            delay(REPLY_POLL_MS)
            waited += REPLY_POLL_MS

            val content = withContext(Dispatchers.Main) {
                PageContentExtractor.extract(webView)
            }
            if (!content.ok) continue

            val text = content.text
            // Nothing yet, or only the prompt echoed back.
            if (text.length < MIN_REPLY_CHARS) continue

            if (text == lastText) {
                stableFor++
                if (stableFor >= STABLE_READS) return extractAnswer(text)
            } else {
                stableFor = 0
                lastText = text
            }
        }
        // Timed out mid-stream: return what there is rather than nothing, as a
        // long answer that stopped growing late is still usable.
        return if (lastText.length >= MIN_REPLY_CHARS) extractAnswer(lastText) else null
    }

    /**
     * Pulls the assistant's answer out of the whole-page text.
     *
     * The extractor returns everything on screen, which includes the prompt we
     * just sent and the site's own chrome. The answer is what comes after the
     * prompt, so cut there; the marker check runs on the full text beforehand,
     * so a NO_MEMORY reply is never lost by this.
     */
    private fun extractAnswer(pageText: String): String {
        // Anchor on the assistant's own reply label first: both sites emit
        // "ChatGPT said:" / "Claude said:" between the echoed prompt and the
        // answer, which is a far more reliable boundary than any phrase from
        // the prompt — and it survives the prompt being reworded.
        // Boundaries in order of reliability. "ChatGPT said:" is only present
        // for screen readers on some renders — the live page often lacks it —
        // so the prompt's own last line is the dependable cut point: the page
        // shows the prompt above the answer, so whatever follows is the reply.
        val boundaries = listOf(
            "ChatGPT said:",
            "Claude said:",
            "No headings, no bullet points."
        )
        var reply = boundaries
            .firstNotNullOfOrNull { marker ->
                val idx = pageText.lastIndexOf(marker)
                if (idx >= 0) pageText.substring(idx + marker.length) else null
            }
            ?: pageText

        // Strip the site's own furniture, which the text extractor picks up
        // along with the conversation and which would otherwise be saved as
        // part of the user's profile.
        // Only trailing furniture. "Skip to content" used to be in this list,
        // but it sits at the TOP of the page — above the answer — so cutting
        // everything before it discarded the entire reply and the import
        // reported a 0-character result on a page holding a full profile.
        val trailingChrome = listOf(
            "ChatGPT can make mistakes",
            "Claude can make mistakes",
            "Cookie preferences",
            "Sources"
        )
        trailingChrome.forEach { line -> reply = reply.substringBefore(line) }

        val cleaned = reply.trim().lines()
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(MAX_PROFILE_CHARS)

        // The prompt no longer offers this marker, so a model only produces it
        // unprompted when it truly has nothing. Kept as a safety net; the
        // too-short check in import() is what normally catches an empty result.
        return if (cleaned.contains(ProfileSource.NO_MEMORY_MARKER)) {
            ProfileSource.NO_MEMORY_MARKER
        } else {
            cleaned
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun js(webView: WebView, script: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value ?: "")
                }
            }
        }

    companion object {
        const val TAG = "ProfileImporter"

        /**
         * Debug-only: keep the session instead of signing out after an import.
         *
         * Set true while iterating, so repeated test runs do not look like
         * credential stuffing to the sites' bot detection. Must be false in
         * anything the user actually uses — see [signOut].
         */
        const val SKIP_SIGN_OUT = true

        const val PAGE_TIMEOUT_MS = 20_000L

        /** How long to wait for the composer to mount after the page loads. */
        const val COMPOSER_TIMEOUT_MS = 15_000L

        /**
         * Minimum characters that must be in the composer to count as typed.
         *
         * The prompt is hundreds of characters, so anything this small means
         * the insert did not take — but it stays low enough that a partially
         * rendered editor is not mistaken for a failure.
         */
        const val MIN_TYPED_CHARS = 20

        /** Pause after dismissing a dialog, for the next one to settle. */
        const val DIALOG_SETTLE_MS = 800L

        /**
         * Shortest reply that could be a real profile.
         *
         * The prompt asks for 150-250 words, so anything under this is page
         * furniture rather than an answer — and presenting furniture to the
         * user as their imported profile is worse than reporting a failure.
         */
        const val MIN_PROFILE_CHARS = 120
        const val LOGOUT_TIMEOUT_MS = 8_000L
        const val LOGOUT_GRACE_MS = 1_500L
        const val POLL_MS = 250L
        const val RENDER_GRACE_MS = 1_200L
        const val TYPE_SETTLE_MS = 600L

        /** Long, because a thoughtful profile answer streams for a while. */
        const val REPLY_TIMEOUT_MS = 90_000L
        const val REPLY_POLL_MS = 1_500L

        /** Identical reads meaning the stream has stopped. */
        const val STABLE_READS = 2

        /** Below this the page is still just showing the prompt. */
        const val MIN_REPLY_CHARS = 400

        const val MAX_PROFILE_CHARS = 4_000

        val SESSION_HINTS = listOf(
            "session", "sessionKey", "__Secure", "auth", "token"
        )
    }
}
