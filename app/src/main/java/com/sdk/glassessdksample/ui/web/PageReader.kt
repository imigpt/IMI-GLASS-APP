package com.sdk.glassessdksample.ui.web

import android.webkit.WebView
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Turns the live page into a compact JSON summary the planner can reason about.
 *
 * The whole loop's cost and reliability hinge on this. Sending raw HTML would
 * blow the token budget and bury the useful parts, so the injected script keeps
 * only what an agent needs to decide its next move: visible interactive
 * elements, each with a **stable selector** we can hand straight back to
 * [ActionExecutor], plus a trimmed slice of the visible text.
 */
object PageReader {

    /** Marks fields the agent must never touch. Enforced again in the executor. */
    const val SENSITIVE_FLAG = "sensitive"

    // Raised alongside the widened button selector below: a React site can
    // legitimately have far more than 40 interactive elements on screen, and
    // truncating at 40 hid the ones further down the page.
    private const val MAX_ELEMENTS = 60
    private const val MAX_TEXT_CHARS = 2500

    /**
     * Injected script. Assigns every candidate element a `data-imi-ref`
     * attribute, so the selector we report back stays valid even on pages
     * where classes are hashed and regenerated between renders.
     */
    private val SCRIPT = """
    (function() {
      try {
        var MAX_ELEMENTS = $MAX_ELEMENTS;
        var MAX_TEXT = $MAX_TEXT_CHARS;

        function visible(el) {
          var r = el.getBoundingClientRect();
          if (r.width < 4 || r.height < 4) return false;
          var s = window.getComputedStyle(el);
          if (s.visibility === 'hidden' || s.display === 'none') return false;
          if (parseFloat(s.opacity || '1') < 0.05) return false;
          // On-screen or just below the fold — the agent can scroll to it.
          return r.top < window.innerHeight * 2 && r.bottom > -window.innerHeight;
        }

        function label(el) {
          var t = (el.getAttribute('aria-label') || el.getAttribute('placeholder') ||
                   el.getAttribute('title') || el.getAttribute('name') ||
                   el.value || el.innerText || el.textContent || '').trim();
          return t.replace(/\s+/g, ' ').slice(0, 80);
        }

        // A field is sensitive if it is a password input, or if anything about
        // it mentions a password/OTP/card. Reported so the planner sees it and
        // enforced independently in Kotlin.
        function sensitive(el) {
          var type = (el.getAttribute('type') || '').toLowerCase();
          if (type === 'password') return true;
          var hay = ((el.getAttribute('name') || '') + ' ' +
                     (el.getAttribute('id') || '') + ' ' +
                     (el.getAttribute('autocomplete') || '') + ' ' +
                     (el.getAttribute('aria-label') || '') + ' ' +
                     (el.getAttribute('placeholder') || '')).toLowerCase();
          // "pin" alone also matched Amazon's "Enter pin code" delivery field,
          // which is a postcode, not a secret — so the agent was blocked from
          // the one thing the task required. Match the PIN senses only.
          if (/pin\s*code|postal\s*code|postcode|\bzip\b/.test(hay)) return false;
          return /pass|pwd|otp|cvv|cvc|card|credit|secret|\bpin\b/.test(hay);
        }

        var refCounter = 0;
        function selectorFor(el) {
          if (el.id && /^[A-Za-z][\w-]*$/.test(el.id)) return '#' + el.id;
          var existing = el.getAttribute('data-imi-ref');
          if (existing) return '[data-imi-ref="' + existing + '"]';
          var ref = 'r' + (++refCounter) + '_' + Date.now().toString(36);
          el.setAttribute('data-imi-ref', ref);
          return '[data-imi-ref="' + ref + '"]';
        }

        var inputs = [], buttons = [], links = [];

        var inputEls = document.querySelectorAll(
          'input, textarea, select, [contenteditable="true"]');
        for (var i = 0; i < inputEls.length && inputs.length < MAX_ELEMENTS; i++) {
          var el = inputEls[i];
          var type = (el.getAttribute('type') || '').toLowerCase();
          if (type === 'hidden') continue;
          if (!visible(el)) continue;
          inputs.push({
            selector: selectorFor(el),
            label: label(el),
            type: type || el.tagName.toLowerCase(),
            value: (el.value || '').slice(0, 40),
            $SENSITIVE_FLAG: sensitive(el)
          });
        }

        // Anything that BEHAVES like a button, not just <button>.
        //
        // The narrow list missed entire sites: MakeMyTrip's whole flight form —
        // origin, destination, and the SEARCH FLIGHTS bar — is <div>s with
        // click handlers, so the summary came back with no origin, no
        // destination and no search button, and the agent correctly reported
        // that it could not see anything to interact with. Modern React sites
        // draw custom widgets this way as a matter of course.
        var btnEls = document.querySelectorAll(
          'button, [role="button"], input[type="submit"], input[type="button"],' +
          ' [role="option"], [role="menuitem"], [role="tab"], [role="link"],' +
          ' [role="checkbox"], [role="radio"], [role="switch"], [role="combobox"],' +
          ' [onclick], [data-testid], [tabindex]:not([tabindex="-1"]),' +
          ' label, summary,' +
          ' [class*="btn"], [class*="Btn"], [class*="button"], [class*="Button"]');
        // A widened net catches containers as well as the real control, so
        // filter: skip anything that merely WRAPS another candidate, and skip
        // duplicates of the same element picked up by two selectors.
        var seenBtn = [];
        for (var j = 0; j < btnEls.length && buttons.length < MAX_ELEMENTS; j++) {
          var b = btnEls[j];
          if (seenBtn.indexOf(b) !== -1) continue;
          seenBtn.push(b);
          if (!visible(b)) continue;

          // A wrapper holding another clickable is not itself the target —
          // clicking it often hits nothing. Keep the innermost one.
          if (b.querySelector(
                'button, [role="button"], input[type="submit"], [onclick]')) continue;

          // Huge elements are layout, not controls.
          var br = b.getBoundingClientRect();
          if (br.width > window.innerWidth * 0.98 && br.height > 220) continue;

          var bl = label(b);
          if (!bl) continue;
          // Pure-layout divs often carry the whole page's text.
          if (bl.length > 120) continue;

          buttons.push({ selector: selectorFor(b), label: bl });
        }

        var linkEls = document.querySelectorAll('a[href]');
        for (var k = 0; k < linkEls.length && links.length < MAX_ELEMENTS; k++) {
          var a = linkEls[k];
          if (!visible(a)) continue;
          var al = label(a);
          if (!al) continue;
          links.push({
            selector: selectorFor(a),
            label: al,
            href: (a.href || '').slice(0, 200)
          });
        }

        var text = (document.body ? (document.body.innerText || '') : '')
          .replace(/\s+/g, ' ').trim().slice(0, MAX_TEXT);

        // Heuristics that tell the agent to stop and hand control to the user.
        //
        // CAPTCHA detection deliberately looks for a challenge that is actually
        // RENDERED, not for a string in the source. Testing the raw HTML against
        // /captcha/ matched almost every large site — Google results, a 404 page,
        // any page bundling reCAPTCHA for a login form it isn't showing — so the
        // agent was constantly told to hand off on pages with no challenge on
        // them at all, and the user got asked to solve a CAPTCHA that wasn't
        // there. A real challenge is a visible, sized iframe or widget container.
        var hasCaptcha = (function () {
          var SEL = [
            'iframe[src*="recaptcha/api2/anchor"]',
            'iframe[src*="recaptcha/enterprise/anchor"]',
            'iframe[src*="hcaptcha.com/captcha"]',
            'iframe[src*="challenges.cloudflare.com"]',
            'div.g-recaptcha', 'div.h-captcha', 'div.cf-turnstile',
            '#challenge-form', '#captcha', '.captcha-container'
          ];
          for (var s = 0; s < SEL.length; s++) {
            var els = document.querySelectorAll(SEL[s]);
            for (var e = 0; e < els.length; e++) {
              var el = els[e];
              var r = el.getBoundingClientRect();
              // An invisible/zero-sized recaptcha node is the score-based v3
              // variety, which needs nothing from the user. Only a challenge
              // big enough to interact with counts.
              if (r.width >= 80 && r.height >= 60 && visible(el)) return true;
            }
          }
          return false;
        })();
        var hasPassword = document.querySelectorAll('input[type="password"]').length > 0;

        return JSON.stringify({
          ok: true,
          url: location.href,
          title: document.title || '',
          scrollY: Math.round(window.scrollY),
          pageHeight: Math.round(document.body ? document.body.scrollHeight : 0),
          viewportHeight: Math.round(window.innerHeight),
          atBottom: (window.innerHeight + window.scrollY) >=
                    ((document.body ? document.body.scrollHeight : 0) - 40),
          hasCaptcha: hasCaptcha,
          hasPasswordField: hasPassword,
          inputs: inputs,
          buttons: buttons,
          links: links,
          text: text
        });
      } catch (e) {
        return JSON.stringify({ ok: false, error: String(e) });
      }
    })();
    """.trimIndent()

    /** A parsed page snapshot. */
    data class PageSnapshot(
        val ok: Boolean,
        val url: String,
        val title: String,
        val hasCaptcha: Boolean,
        val hasPasswordField: Boolean,
        val atBottom: Boolean,
        /** Whether this browser has somewhere to go back to / forward to. */
        val canGoBack: Boolean = false,
        val canGoForward: Boolean = false,
        val raw: JSONObject
    ) {
        /**
         * The page rendered for the prompt. Kept terse on purpose — this is
         * sent on every planning turn, so every character is paid for.
         */
        fun toPromptText(): String {
            val sb = StringBuilder()
            sb.append("URL: ").append(url).append('\n')
            sb.append("TITLE: ").append(title).append('\n')
            if (hasCaptcha) sb.append("WARNING: a CAPTCHA is present on this page.\n")
            if (hasPasswordField) sb.append("WARNING: a password field is present.\n")
            sb.append("AT_BOTTOM: ").append(atBottom).append('\n')
            // Stated every turn so the planner never has to guess whether
            // navigating through history is available to it.
            sb.append("CAN_GO_BACK: ").append(canGoBack)
                .append("  CAN_GO_FORWARD: ").append(canGoForward).append('\n')

            appendList(sb, "INPUTS", "inputs") { o ->
                val flag = if (o.optBoolean(SENSITIVE_FLAG)) " [SENSITIVE - DO NOT TYPE]" else ""
                val value = o.optString("value").takeIf { it.isNotBlank() }
                    ?.let { " current=\"$it\"" }.orEmpty()
                "${o.optString("selector")} | ${o.optString("type")} | " +
                    "\"${o.optString("label")}\"$value$flag"
            }
            appendList(sb, "BUTTONS", "buttons") { o ->
                "${o.optString("selector")} | \"${o.optString("label")}\""
            }
            appendList(sb, "LINKS", "links") { o ->
                "${o.optString("selector")} | \"${o.optString("label")}\""
            }

            val text = raw.optString("text")
            if (text.isNotBlank()) {
                sb.append("\nVISIBLE TEXT:\n").append(text).append('\n')
            }
            return sb.toString()
        }

        private fun appendList(
            sb: StringBuilder,
            heading: String,
            key: String,
            render: (JSONObject) -> String
        ) {
            val arr = raw.optJSONArray(key) ?: return
            if (arr.length() == 0) return
            sb.append('\n').append(heading).append(":\n")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                sb.append("- ").append(render(o)).append('\n')
            }
        }

        /**
         * True when this snapshot gives the planner nothing it could act on.
         *
         * The trigger for the vision fallback. A page that renders every
         * control as a plain <div> produces a summary with no inputs, no
         * buttons and no links — the DOM read has honestly failed, and a
         * picture is the only way left to see the page.
         */
        fun hasNothingActionable(): Boolean {
            // A browser that has not navigated anywhere yet is not an
            // unreadable page — it is a blank tab, and the right response is to
            // open something, which plain planning handles. Treating it as
            // unreadable sent a screenshot of about:blank to the vision model
            // on the very first step of every run, before any site had loaded:
            // an empty image, asked "what next?". The whole vision path was
            // being spent on a blank page and never reached the real site.
            if (isBlank) return false

            if (!ok) return true
            val counts = listOf("inputs", "buttons", "links").sumOf { key ->
                raw.optJSONArray(key)?.length() ?: 0
            }
            return counts == 0
        }

        /** True before the browser has navigated anywhere. */
        val isBlank: Boolean
            get() = url.isBlank() || url == "about:blank" || url.startsWith("data:")

        /** Whether a selector was actually reported by this snapshot. */
        fun hasSelector(selector: String): Boolean =
            listOf("inputs", "buttons", "links").any { key ->
                val arr = raw.optJSONArray(key) ?: return@any false
                (0 until arr.length()).any {
                    arr.optJSONObject(it)?.optString("selector") == selector
                }
            }

        /** Whether the field behind [selector] was flagged sensitive. */
        fun isSensitive(selector: String): Boolean {
            val arr = raw.optJSONArray("inputs") ?: return false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("selector") == selector) {
                    return o.optBoolean(SENSITIVE_FLAG)
                }
            }
            return false
        }
    }

    /** Reads the current page. Must be called on the UI thread. */
    suspend fun read(webView: WebView): PageSnapshot = suspendCoroutine { cont ->
        // History state comes from the WebView, not from the page's JS: a page
        // cannot see how it was reached. Without it the planner was blind to
        // its own history — it could only discover that going back was possible
        // by trying, and a Back that came back "there is no page to go back to"
        // read to the model as a refusal, which is how the agent ended up
        // telling the user it was not allowed to navigate between pages.
        val canBack = webView.canGoBack()
        val canForward = webView.canGoForward()
        webView.evaluateJavascript(SCRIPT) { result ->
            cont.resume(parse(result, canBack, canForward))
        }
    }

    private fun parse(
        evaluateResult: String?,
        canGoBack: Boolean = false,
        canGoForward: Boolean = false
    ): PageSnapshot {
        val fallback = PageSnapshot(
            ok = false,
            url = "",
            title = "",
            hasCaptcha = false,
            hasPasswordField = false,
            atBottom = false,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            raw = JSONObject()
        )
        if (evaluateResult.isNullOrBlank() || evaluateResult == "null") return fallback

        return try {
            // evaluateJavascript hands back a JSON-encoded *string*, so the
            // payload is double-encoded and has to be unwrapped first.
            val unwrapped = if (evaluateResult.startsWith("\"")) {
                JSONObject("{\"v\":$evaluateResult}").getString("v")
            } else {
                evaluateResult
            }
            val json = JSONObject(unwrapped)
            if (!json.optBoolean("ok")) return fallback

            PageSnapshot(
                ok = true,
                url = json.optString("url"),
                title = json.optString("title"),
                hasCaptcha = json.optBoolean("hasCaptcha"),
                hasPasswordField = json.optBoolean("hasPasswordField"),
                atBottom = json.optBoolean("atBottom"),
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                raw = json
            )
        } catch (_: Exception) {
            fallback
        }
    }
}
