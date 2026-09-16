package com.sdk.glassessdksample.ui.web

import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Performs a validated [BrowserAction] against the WebView.
 *
 * Nothing reaches this class without passing [ActionValidator], but the
 * password rule is re-checked here anyway — an executor that can be tricked
 * into typing a secret is a bug no matter which caller does the tricking.
 *
 * Every WebView touch is confined to the main thread by [execute] itself.
 * WebView throws if it is driven from anywhere else, and the callers here are
 * suspend functions running on background dispatchers — so the confinement has
 * to live in this class rather than in a convention callers must remember.
 */
class ActionExecutor(private val webView: WebView) {

    /**
     * Runs one action, hopping to the main thread first.
     *
     * This used to work only because the tool dispatchers wrapped the whole call
     * in runBlocking from a main-thread-adjacent context. Once those became
     * proper suspend calls the work landed on a Dispatchers.Default worker and
     * every browse_web crashed with "A WebView method was called on thread
     * DefaultDispatcher-worker-N", which reached the user as "Something went
     * wrong in the browser." withContext(Dispatchers.Main) makes the thread
     * requirement structural: it holds no matter which dispatcher calls in.
     */
    suspend fun execute(action: BrowserAction): ActionResult =
        withContext(Dispatchers.Main) { executeOnMain(action) }

    private suspend fun executeOnMain(action: BrowserAction): ActionResult = when (action) {
        is BrowserAction.Open -> {
            webView.loadUrl(action.url)
            awaitPageSettle()
            ActionResult(action, true, "Opened ${action.url}")
        }

        is BrowserAction.Search -> {
            val url = searchUrl(action.query, action.engine)
            webView.loadUrl(url)
            awaitPageSettle()
            ActionResult(action, true, "Searched for \"${action.query}\"")
        }

        is BrowserAction.Click -> runClick(action)
        is BrowserAction.Type -> runType(action)
        is BrowserAction.Scroll -> runScroll(action)

        BrowserAction.Back -> {
            if (webView.canGoBack()) {
                webView.goBack()
                awaitPageSettle()
                ActionResult(action, true, "Went back")
            } else {
                // Worded as a fact about the history, not a refusal. "There is
                // no page to go back to" read to the model as a prohibition,
                // and it went on to tell the user it had no permission to
                // navigate between pages — which was never true.
                ActionResult(
                    action,
                    false,
                    "This browser has no earlier page in its history yet, so there is " +
                        "nowhere to go back to. Use open or search instead."
                )
            }
        }

        BrowserAction.Forward -> {
            if (webView.canGoForward()) {
                webView.goForward()
                awaitPageSettle()
                ActionResult(action, true, "Went forward")
            } else {
                ActionResult(
                    action,
                    false,
                    "Nothing has been navigated back from, so there is no forward page. " +
                        "Use open or search instead."
                )
            }
        }

        is BrowserAction.TapAt -> runTapAt(action)

        BrowserAction.Reload -> {
            webView.reload()
            awaitPageSettle()
            ActionResult(action, true, "Reloaded")
        }

        is BrowserAction.Wait -> {
            delay(PAGE_SETTLE_MS)
            ActionResult(action, true, "Waited")
        }

        // These are conversational, not mechanical — the session handles them.
        is BrowserAction.AskUser,
        is BrowserAction.HandoffToUser,
        is BrowserAction.Done,
        is BrowserAction.Failed -> ActionResult(action, true, action.describe())
    }

    // ------------------------------------------------------------------ click

    /**
     * Taps a point, for the vision fallback.
     *
     * Resolves the element under the coordinate with elementFromPoint and
     * dispatches to it, rather than synthesizing a raw touch: a real element
     * gives the same event sequence every other action uses, and lets the
     * result say WHAT was hit — a blind coordinate that lands on a background
     * div would otherwise report success having done nothing.
     */
    private suspend fun runTapAt(action: BrowserAction.TapAt): ActionResult {
        val js = """
        (function() {
          try {
            var x = ${action.x}, y = ${action.y};
            var el = document.elementFromPoint(x, y);
            if (!el) return JSON.stringify({ok:false, error:'nothing at that point'});

            // Prefer the nearest genuinely interactive ancestor: vision aims at
            // the middle of a control, which is often a label inside it.
            var target = el.closest(
              'a, button, input, select, textarea, [role="button"], [role="option"],' +
              ' [role="menuitem"], [role="tab"], [onclick], [tabindex]') || el;

            var desc = (target.innerText || target.textContent || target.tagName || '')
              .trim().replace(/\s+/g, ' ').slice(0, 60);

            target.scrollIntoView({block:'center'});
            ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t) {
              var Ctor = t.indexOf('pointer') === 0 ? (window.PointerEvent || MouseEvent) : MouseEvent;
              target.dispatchEvent(new Ctor(t, {
                bubbles:true, cancelable:true, view:window, clientX:x, clientY:y
              }));
            });
            return JSON.stringify({ok:true, hit:desc});
          } catch (e) {
            return JSON.stringify({ok:false, error:String(e)});
          }
        })();
        """.trimIndent()

        val res = evaluate(js)
        awaitPageSettle()
        return if (res.optBoolean("ok")) {
            val hit = res.optString("hit").takeIf { it.isNotBlank() }
            ActionResult(
                action,
                true,
                if (hit != null) "Tapped ${action.label} (hit \"$hit\")"
                else "Tapped ${action.label}"
            )
        } else {
            ActionResult(
                action,
                false,
                "Couldn't tap at ${action.x},${action.y}: ${res.optString("error")}"
            )
        }
    }

    private suspend fun runClick(action: BrowserAction.Click): ActionResult {
        val js = """
        (function() {
          try {
            var sel = ${action.selector.toJsString()};
            var el = null;

            // A real CSS selector is tried first. querySelector THROWS on a
            // string that isn't valid CSS, so this has to be guarded — the
            // planner regularly passes an element's visible text instead of a
            // selector, which is not a mistake it can avoid: options inside an
            // autocomplete dropdown are created by the typing that precedes
            // them, so they never appear in the page snapshot and the model has
            // no selector to quote. On MakeMyTrip that meant every attempt at
            // "To BOM, Chhatrapati Shivaji International Airport" was rejected
            // and the task died with the city fields still empty.
            try { el = document.querySelector(sel); } catch (e) { el = null; }

            // Fall back to matching what the user would actually SEE. Exact
            // match first, then a contains match, both over visible elements
            // only, preferring the smallest match so a click lands on the
            // option itself rather than the container wrapping it.
            if (!el) {
              var needle = (sel || '').trim().toLowerCase();
              var label = ${action.label.toJsString()}.trim().toLowerCase();
              var targets = [needle, label].filter(function(s) { return s.length > 1; });

              var candidates = document.querySelectorAll(
                'a, button, li, [role="option"], [role="button"], [role="menuitem"],' +
                ' div, span, p, td');
              var best = null, bestLen = Infinity, bestScore = 0;

              for (var i = 0; i < candidates.length; i++) {
                var c = candidates[i];
                var r = c.getBoundingClientRect();
                if (r.width <= 0 || r.height <= 0) continue;
                var style = window.getComputedStyle(c);
                if (style.visibility === 'hidden' || style.display === 'none') continue;
                if (parseFloat(style.opacity || '1') < 0.05) continue;

                var txt = (c.innerText || c.textContent || '').trim().toLowerCase();
                if (!txt || txt.length > 200) continue;

                for (var t = 0; t < targets.length; t++) {
                  var want = targets[t];
                  var score = 0;
                  if (txt === want) score = 3;
                  else if (txt.indexOf(want) !== -1) score = 2;
                  else if (want.indexOf(txt) !== -1 && txt.length > 3) score = 1;
                  if (score === 0) continue;
                  if (score > bestScore || (score === bestScore && txt.length < bestLen)) {
                    best = c; bestLen = txt.length; bestScore = score;
                  }
                }
              }
              el = best;
            }

            if (!el) return JSON.stringify({ok:false, error:'element not found'});
            el.scrollIntoView({block:'center'});
            // Fire a full pointer sequence: frameworks often listen for these
            // rather than for click alone.
            ['pointerdown','mousedown','pointerup','mouseup','click'].forEach(function(t) {
              var Ctor = t.indexOf('pointer') === 0 ? (window.PointerEvent || MouseEvent) : MouseEvent;
              el.dispatchEvent(new Ctor(t, {bubbles:true, cancelable:true, view:window}));
            });
            return JSON.stringify({ok:true});
          } catch (e) {
            return JSON.stringify({ok:false, error:String(e)});
          }
        })();
        """.trimIndent()

        val res = evaluate(js)
        awaitPageSettle()
        return if (res.optBoolean("ok")) {
            ActionResult(action, true, "Tapped ${action.label}")
        } else {
            ActionResult(action, false, "Couldn't tap ${action.label}: ${res.optString("error")}")
        }
    }

    // ------------------------------------------------------------------- type

    private suspend fun runType(action: BrowserAction.Type): ActionResult {
        val js = """
        (function() {
          try {
            var el = document.querySelector(${action.selector.toJsString()});
            if (!el) return JSON.stringify({ok:false, error:'field not found'});

            // Independent refusal: this executor never fills a password field,
            // whatever it was asked to do.
            var type = (el.getAttribute('type') || '').toLowerCase();
            if (type === 'password') {
              return JSON.stringify({ok:false, error:'refused: password field'});
            }

            el.scrollIntoView({block:'center'});
            el.focus();

            var value = ${action.text.toJsString()};

            if (el.isContentEditable) {
              el.textContent = value;
            } else {
              // Assign through the native setter so React/Vue see the change;
              // writing .value directly is swallowed by their value tracker.
              var proto = el.tagName === 'TEXTAREA'
                ? window.HTMLTextAreaElement.prototype
                : window.HTMLInputElement.prototype;
              var setter = Object.getOwnPropertyDescriptor(proto, 'value');
              if (setter && setter.set) { setter.set.call(el, value); }
              else { el.value = value; }
            }

            el.dispatchEvent(new Event('input', {bubbles:true}));
            el.dispatchEvent(new Event('change', {bubbles:true}));

            if (${action.submit}) {
              var form = el.form || el.closest('form');
              el.dispatchEvent(new KeyboardEvent('keydown',
                {bubbles:true, cancelable:true, key:'Enter', keyCode:13, which:13}));
              el.dispatchEvent(new KeyboardEvent('keyup',
                {bubbles:true, cancelable:true, key:'Enter', keyCode:13, which:13}));
              if (form && typeof form.requestSubmit === 'function') {
                form.requestSubmit();
              } else if (form) {
                form.submit();
              }
            }
            return JSON.stringify({ok:true});
          } catch (e) {
            return JSON.stringify({ok:false, error:String(e)});
          }
        })();
        """.trimIndent()

        val res = evaluate(js)
        // Typing into an autocomplete opens its dropdown asynchronously. The
        // old short pause routinely returned before those options existed, so
        // the next page snapshot missed them entirely and the agent had nothing
        // to click. Give a non-submitting type long enough for the suggestions
        // to render.
        if (action.submit) awaitPageSettle() else delay(AUTOCOMPLETE_SETTLE_MS)

        return if (res.optBoolean("ok")) {
            ActionResult(
                action,
                true,
                "Filled the field. If this opened a list of suggestions, the next " +
                    "page summary will show them — pick one from there."
            )
        } else {
            ActionResult(action, false, "Couldn't fill the field: ${res.optString("error")}")
        }
    }

    // ----------------------------------------------------------------- scroll

    private suspend fun runScroll(action: BrowserAction.Scroll): ActionResult {
        val js = """
        (function() {
          window.scrollBy({top: window.innerHeight * ${action.amount}, behavior: 'instant'});
          return JSON.stringify({ok:true});
        })();
        """.trimIndent()
        evaluate(js)
        delay(SHORT_SETTLE_MS)
        return ActionResult(action, true, action.describe())
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun evaluate(js: String): JSONObject = suspendCoroutine { cont ->
        webView.evaluateJavascript(js) { raw ->
            cont.resume(parseJsResult(raw))
        }
    }

    private fun parseJsResult(raw: String?): JSONObject {
        if (raw.isNullOrBlank() || raw == "null") {
            return JSONObject().put("ok", false).put("error", "no result")
        }
        return try {
            val unwrapped = if (raw.startsWith("\"")) {
                JSONObject("{\"v\":$raw}").getString("v")
            } else {
                raw
            }
            JSONObject(unwrapped)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "bad result")
        }
    }

    /**
     * Waits for the page to look settled. A fixed delay rather than an
     * onPageFinished hook, because most modern navigation is client-side and
     * never fires it; the readyState poll catches full loads early.
     */
    private suspend fun awaitPageSettle() {
        delay(SHORT_SETTLE_MS)
        var waited = SHORT_SETTLE_MS
        while (waited < PAGE_SETTLE_MS) {
            val state = evaluate(
                "(function(){return JSON.stringify({ok:true, state:document.readyState});})();"
            )
            if (state.optString("state") == "complete") break
            delay(POLL_MS)
            waited += POLL_MS
        }
        // Let client-side rendering paint before the next page read.
        delay(RENDER_MS)
    }

    private fun searchUrl(query: String, engine: String): String {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return when (engine.lowercase()) {
            "youtube" -> "https://m.youtube.com/results?search_query=$q"
            "bing" -> "https://www.bing.com/search?q=$q"
            "duckduckgo" -> "https://duckduckgo.com/?q=$q"
            else -> "https://www.google.com/search?q=$q"
        }
    }

    companion object {
        private const val SHORT_SETTLE_MS = 400L

        /**
         * Pause after typing into a field that did not submit.
         *
         * Longer than [SHORT_SETTLE_MS] because the thing being waited for is
         * an autocomplete dropdown, which a site fetches and renders after the
         * keystrokes land. At 400ms the next page snapshot was routinely taken
         * before the suggestions existed, so the agent could never see the
         * option it needed to pick.
         */
        private const val AUTOCOMPLETE_SETTLE_MS = 1500L

        private const val PAGE_SETTLE_MS = 6000L
        private const val POLL_MS = 300L
        private const val RENDER_MS = 700L

        /** Safely embeds a Kotlin string as a JS string literal. */
        private fun String.toJsString(): String =
            JSONObject.quote(this)
    }
}
