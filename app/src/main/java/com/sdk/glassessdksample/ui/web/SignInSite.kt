package com.sdk.glassessdksample.ui.web

import android.webkit.CookieManager

/**
 * A site the user can sign into ahead of time, so a task never stops to ask.
 *
 * The agent already inherits whatever sessions the in-app browser holds — they
 * share one cookie jar through [WebSessionManager]. What was missing was a
 * deliberate way to establish those sessions BEFORE a task needs them: the
 * first a user knew about a login wall was the agent parking halfway through a
 * booking. Signing in here once removes that interruption entirely.
 *
 * Credentials never touch this app. Each entry opens the site's own sign-in
 * page in the browser, and the user types into the site exactly as they would
 * in Chrome.
 */
enum class SignInSite(
    val displayName: String,
    /** Where to send the user to sign in. */
    val signInUrl: String,
    /** Cookie domain to check for an existing session. */
    val cookieDomain: String,
    /** What the agent can do here once signed in, shown as the row subtitle. */
    val purpose: String
) {
    // Only the sites the browser can actually open appear here. The list used
    // to carry Amazon, Flipkart, MakeMyTrip, IRCTC, Swiggy, Zomato, Gmail and
    // YouTube; with the allow-list in place, signing into those bought the user
    // nothing, because no task could ever navigate to them. See AllowedSites.
    CHATGPT(
        displayName = "ChatGPT",
        signInUrl = "https://chatgpt.com/auth/login",
        cookieDomain = "https://chatgpt.com",
        purpose = "Asking and reading your chats"
    ),
    CLAUDE(
        displayName = "Claude",
        signInUrl = "https://claude.ai/login",
        cookieDomain = "https://claude.ai",
        purpose = "Asking and reading your chats"
    );

    /**
     * A best guess at whether a session already exists.
     *
     * Deliberately described as a guess, not a fact. All this can see is
     * whether the domain has cookies that look like a session — it cannot know
     * whether the site still considers them valid, and sessions expire on
     * wildly different schedules (Amazon can hold for months, a bank for
     * minutes). The UI says "signed in earlier", never a confident tick, and
     * the agent still handles a login wall it runs into anyway.
     */
    fun hasSession(): Boolean {
        val cookies = CookieManager.getInstance().getCookie(cookieDomain) ?: return false
        if (cookies.isBlank()) return false
        return SESSION_HINTS.any { cookies.contains(it, ignoreCase = true) }
    }

    /** Forgets this site's session, so the user can sign in as someone else. */
    fun clearSession() {
        val manager = CookieManager.getInstance()
        val existing = manager.getCookie(cookieDomain) ?: return
        // There is no per-domain delete, so expire each cookie by name.
        existing.split(";").forEach { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isNotEmpty()) {
                manager.setCookie(cookieDomain, "$name=; Max-Age=0; Path=/")
            }
        }
        manager.flush()
    }

    companion object {
        /**
         * Cookie names that usually mean "this browser is logged in".
         *
         * Broad on purpose: a false positive shows "signed in earlier" on a
         * site the user is not signed into, which costs them one wasted tap.
         * A false negative nags them to sign in when they already have, which
         * is the more annoying failure.
         */
        private val SESSION_HINTS = listOf(
            "session", "sess", "sid", "auth", "token", "login",
            "at-main", "x-main", "ubid",           // Amazon
            "SAPISID", "SSID", "LOGIN_INFO",       // Google
            "mmt-auth", "csrf"
        )
    }
}
