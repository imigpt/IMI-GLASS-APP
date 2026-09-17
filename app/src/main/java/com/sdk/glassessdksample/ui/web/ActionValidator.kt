package com.sdk.glassessdksample.ui.web

import android.util.Log
import java.util.Locale

/**
 * The security boundary between the planner and the browser.
 *
 * Every action crosses this before it runs. The rules here are code, not prompt
 * text, because a prompt is a request and this needs to be a guarantee: a model
 * that is confused, or a page that tries to talk the model into something,
 * still cannot get a password typed or a CAPTCHA answered.
 */
object ActionValidator {

    private const val TAG = "ActionValidator"

    sealed class Verdict {
        object Allow : Verdict()

        /** Not permitted, but the user can do it themselves. */
        data class Handoff(val reason: String) : Verdict()

        /** Malformed or nonsensical — the planner is told and retries. */
        data class Reject(val reason: String) : Verdict()

        /** Permitted only after the user explicitly confirms. */
        data class NeedsConfirmation(val prompt: String) : Verdict()
    }

    /** Words that mean "this click spends money or is otherwise final". */
    private val CONFIRM_KEYWORDS = listOf(
        "pay", "payment", "checkout", "place order", "buy now", "confirm booking",
        "book now", "purchase", "subscribe", "delete", "remove account",
        "send money", "transfer", "authorize", "authorise"
    )

    /** Words that mean "this is the login step the user must do". */
    private val LOGIN_KEYWORDS = listOf(
        "sign in", "signin", "log in", "login", "continue with google",
        "continue with apple", "sign in with", "verify otp", "verify code"
    )

    fun validate(action: BrowserAction, page: PageReader.PageSnapshot?): Verdict {
        // A CAPTCHA stops everything that would interact with THIS page —
        // typing, clicking, scrolling. It must NOT stop navigating away
        // (Open/Search): the whole point of those actions is leaving the
        // stuck page, so they need no interaction with what's blocking it.
        // Without this exemption, a goal as simple as "open YouTube" could
        // never proceed if the browser merely happened to be sitting on some
        // other, unrelated page that has a CAPTCHA/login wall on it — the
        // agent would report itself stuck before it ever got a chance to
        // navigate to the site the user actually asked for.
        if (page?.hasCaptcha == true &&
            action !is BrowserAction.HandoffToUser &&
            action !is BrowserAction.Done &&
            action !is BrowserAction.Failed &&
            action !is BrowserAction.Open &&
            action !is BrowserAction.Search
        ) {
            return Verdict.Handoff(
                "This page is asking for a CAPTCHA. Please solve it, then tap Continue."
            )
        }

        return when (action) {
            is BrowserAction.Type -> validateType(action, page)
            is BrowserAction.Click -> validateClick(action, page)
            is BrowserAction.Open -> validateOpen(action, page)
            // A web search is by definition a route to an arbitrary site, so
            // there is no version of it that stays inside the allowed set.
            // Rejected rather than handed off: the planner can act on this and
            // go to one of the two sites directly, whereas a handoff would put
            // a page in front of the user that they are equally not allowed to
            // browse from.
            is BrowserAction.Search ->
                Verdict.Reject(
                    "Web search is turned off. " + AllowedSites.BLOCKED_MESSAGE +
                        " Use Open with one of those sites instead."
                )

            is BrowserAction.Scroll ->
                if (action.amount == 0.0) Verdict.Reject("Scroll amount was zero.")
                else Verdict.Allow

            // A coordinate tap comes from the vision fallback, which is blind to
            // markup — so the label is the only signal about what it will hit.
            // Hold it to the SAME rules as a normal click: a screenshot must not
            // become a way around the login and payment guards.
            is BrowserAction.TapAt -> validateTapAt(action)

            else -> Verdict.Allow
        }
    }

    private fun validateType(
        action: BrowserAction.Type,
        page: PageReader.PageSnapshot?
    ): Verdict {
        // Rule 1: never type into a credential field. Checked against what the
        // page actually reported, not against what the model claims.
        if (page?.isSensitive(action.selector) == true) {
            return Verdict.Handoff(
                "That field is a password or security code. Please type it yourself, then tap Continue."
            )
        }

        // Typing into a signed-out page is worse than useless: the site accepts
        // the text and then silently refuses to act on it, so the run reports
        // success for something that never happened. Observed on a logged-out
        // ChatGPT, which took "hello" into its composer and would not send it.
        // Hand off instead — signing in is the user's step anyway.
        if (page?.signedOut == true) {
            return Verdict.Handoff(
                "You're not signed in to this site, so I can't send anything. " +
                    "Please log in, then tap Continue and I'll carry on."
            )
        }

        // Rule 2: never type text that looks like a credential, wherever it
        // is going — this catches a model trying to route around rule 1.
        if (looksLikeCredential(action.text)) {
            return Verdict.Handoff(
                "This step needs your own login details. Please enter them, then tap Continue."
            )
        }

        if (action.selector.isBlank()) return Verdict.Reject("No field selector given.")

        if (page != null && !page.hasSelector(action.selector)) {
            return Verdict.Reject(
                "Selector ${action.selector} is not on this page. Pick one from the list."
            )
        }
        return Verdict.Allow
    }

    private fun validateClick(
        action: BrowserAction.Click,
        page: PageReader.PageSnapshot?
    ): Verdict {
        if (action.selector.isBlank()) return Verdict.Reject("No selector given.")

        // A selector that isn't in the snapshot used to be rejected outright.
        // That is wrong for anything the page creates in response to the
        // agent's own typing — autocomplete options, dropdown entries — which
        // cannot be in a snapshot taken before the typing happened. The
        // executor can now find an element by its visible text, so let the
        // click through and let it try; a genuine miss comes back as a failed
        // action, which the planner can learn from, instead of a rejection
        // loop that repeats the same doomed attempt until the task dies.
        if (page != null && !page.hasSelector(action.selector)) {
            Log.d(
                TAG,
                "Selector not in snapshot, allowing text-match fallback: ${action.selector}"
            )
        }

        val label = action.label.lowercase(Locale.ROOT)

        // Login buttons go to the user: the agent gets them to the door, the
        // user opens it. That keeps credentials out of this app entirely.
        if (LOGIN_KEYWORDS.any { label.contains(it) }) {
            return Verdict.Handoff(
                "Signing in is your step. Please log in, then tap Continue and I'll carry on."
            )
        }

        // Anything that spends money or is irreversible needs an explicit tap.
        if (CONFIRM_KEYWORDS.any { label.contains(it) }) {
            return Verdict.NeedsConfirmation(
                "I'm about to tap \"${action.label}\". This may be final or cost money. Continue?"
            )
        }

        return Verdict.Allow
    }

    /** Same login/payment gates as [validateClick], keyed off the label. */
    private fun validateTapAt(action: BrowserAction.TapAt): Verdict {
        val label = action.label.lowercase(Locale.ROOT)

        if (LOGIN_KEYWORDS.any { label.contains(it) }) {
            return Verdict.Handoff(
                "Signing in is your step. Please log in, then tap Continue and I'll carry on."
            )
        }

        if (CONFIRM_KEYWORDS.any { label.contains(it) }) {
            return Verdict.NeedsConfirmation(
                "I'm about to tap \"${action.label}\". This may be final or cost money. Continue?"
            )
        }

        return Verdict.Allow
    }

    private fun validateOpen(
        action: BrowserAction.Open,
        page: PageReader.PageSnapshot?
    ): Verdict {
        val url = action.url.trim()

        // Re-opening the page you are already on discards everything that has
        // loaded and returns you to the same state, so the next turn makes the
        // same decision — a loop that reports "ok" every step while going
        // nowhere. Observed on ChatGPT: three consecutive "Opening chatgpt.com"
        // steps against a page that was already open and merely slow to render.
        // The prompt says not to; this makes it so.
        if (page != null && sameLocation(page.url, url)) {
            return Verdict.Reject(
                "You are already on $url. Do not re-open it — act on the page, " +
                    "or wait for it to finish loading."
            )
        }
        // Only real web pages. javascript: and data: URLs are how a page would
        // try to get arbitrary code executed through the agent.
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return Verdict.Reject("Only http and https URLs can be opened.")
        }
        // The allow-list is enforced here, at the same boundary as the
        // credential and payment rules, so it is a property of the code rather
        // than a request in a prompt. Reject, not Handoff: the planner is told
        // why and can pick an allowed site on its next turn.
        if (!AllowedSites.isPrimarySite(url)) {
            return Verdict.Reject(
                AllowedSites.BLOCKED_MESSAGE +
                    " Open chatgpt.com or claude.ai instead."
            )
        }
        return Verdict.Allow
    }

    /**
     * Whether two URLs point at the same page.
     *
     * Host + path only: the query and fragment are ignored deliberately, since
     * a re-open that differs solely by "?" or "#" lands on the same place.
     * Trailing slashes are normalised so "chatgpt.com" and "chatgpt.com/" match,
     * while "chatgpt.com/c/123" correctly does not match the root.
     */
    private fun sameLocation(current: String?, target: String): Boolean {
        if (current.isNullOrBlank()) return false
        return try {
            val a = java.net.URI(current)
            val b = java.net.URI(target)
            val hostA = a.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return false
            val hostB = b.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return false
            hostA == hostB &&
                a.path.orEmpty().trimEnd('/') == b.path.orEmpty().trimEnd('/')
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Heuristic for text that shouldn't be typed by an automation.
     *
     * This deliberately no longer judges a bare short number by its shape. The
     * old rule was "4-8 digits means OTP or PIN", which also matches every
     * Indian postcode — so "deliver to pin code 302020" was unachievable by
     * construction: the agent had to type the one string the validator forbade,
     * handed off saying "this step needs your own login details" on a page
     * where the user was already signed in, and looped there forever.
     *
     * Where the text is GOING is the reliable signal, and rule 1 of
     * [validateType] already checks it against what the page reported. This is
     * the backstop for text that is unmistakably a secret wherever it lands.
     */
    private fun looksLikeCredential(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        // Long card-like digit runs. A postcode is never this long.
        if (t.filter { it.isDigit() }.length >= 12 && t.none { it.isLetter() }) return true
        val lower = t.lowercase(Locale.ROOT)
        return lower.contains("password") || lower.contains("otp code")
    }
}
