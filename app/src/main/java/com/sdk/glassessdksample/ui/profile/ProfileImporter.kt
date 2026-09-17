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

            onProgress("Asking ${source.displayName} what it knows about you…")
            // The composer mounts after the rest of the page on both sites, so
            // looking once and giving up reports "the site may have changed"
            // for a box that simply had not appeared yet.
            if (!awaitComposer(webView)) {
                return Result.Failed(
                    "Couldn't find the message box on ${source.displayName}. " +
                        "The site may have changed."
                )
            }
            if (!sendPrompt(webView, source.extractionPrompt)) {
                return Result.Failed(
                    "Couldn't find the message box on ${source.displayName}. " +
                        "The site may have changed."
                )
            }

            onProgress("Waiting for the reply…")
            val reply = awaitReply(webView) ?: return Result.Failed(
                "${source.displayName} didn't reply in time."
            )

            if (reply.contains(ProfileSource.NO_MEMORY_MARKER)) Result.NoMemory
            else Result.Success(reply)
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
          var nodes = document.querySelectorAll(
            'textarea, div[contenteditable], [role="textbox"]');
          var best = null, bestArea = 0;
          for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (el.disabled || el.readOnly) continue;
            if (el.tagName !== 'TEXTAREA' &&
                el.getAttribute('contenteditable') === 'false') continue;
            var r = el.getBoundingClientRect();
            if (r.width < 80 || r.height < 20) continue;
            var st = window.getComputedStyle(el);
            if (st.display === 'none' || st.visibility === 'hidden' ||
                parseFloat(st.opacity) < 0.1) continue;
            var area = r.width * r.height;
            if (area > bestArea) { bestArea = area; best = el; }
          }
          return best;
        }
    """.trimIndent()

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
                // Confirm the text really landed before claiming success.
                var now = box.isContentEditable ? box.textContent : box.value;
                if (!now || now.length < 10) return 'notyped';
                return 'typed';
              } catch(e){ return 'err:'+e; }
            })()
            """.trimIndent()
        )
        if (!result.contains("typed") || result.contains("notyped")) return false

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
                var btn = document.querySelector(
                  'button[data-testid="send-button"], button[aria-label*="Send" i]');
                if (btn && !btn.disabled) btn.click();
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
        val marker = "NO_MEMORY_AVAILABLE"
        if (pageText.contains(marker)) return marker

        // The prompt's last distinctive line, as the boundary.
        val promptTail = "Reply with exactly this instead:"
        val idx = pageText.lastIndexOf(promptTail)
        val after = if (idx >= 0) {
            pageText.substring(idx + promptTail.length)
                .substringAfter(marker, "")
                .ifBlank { pageText.substring(idx + promptTail.length) }
        } else {
            pageText
        }
        return after.trim().lines()
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(MAX_PROFILE_CHARS)
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

    private companion object {
        const val TAG = "ProfileImporter"

        const val PAGE_TIMEOUT_MS = 20_000L

        /** How long to wait for the composer to mount after the page loads. */
        const val COMPOSER_TIMEOUT_MS = 15_000L
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
