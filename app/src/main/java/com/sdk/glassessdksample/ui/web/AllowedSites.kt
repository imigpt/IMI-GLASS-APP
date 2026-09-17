package com.sdk.glassessdksample.ui.web

import java.util.Locale

/**
 * The only sites this app's browser may visit.
 *
 * Full web access is not free: every agent step is a paid model call, and an
 * open-ended browser invites long runs on sites the agent is bad at anyway
 * (see the MakeMyTrip note in doc/WEB_AGENT.md). Restricting the surface to two
 * known-good, text-heavy sites bounds that cost.
 *
 * This is the single source of truth. Every path that can put a URL into a
 * WebView — the address bar, the start-screen chips, the agent's Open and
 * Search actions, and the WebViewClient that catches in-page links — asks this
 * object first, so there is no route to an arbitrary page that bypasses it.
 */
object AllowedSites {

    /**
     * Allowed hosts, matched as the host itself or any subdomain of it.
     *
     * Both OpenAI hostnames are listed because they redirect into each other:
     * chatgpt.com is the current home, chat.openai.com is the older address
     * still in links and bookmarks. Blocking the one that redirects would
     * present as "ChatGPT is broken" rather than as a policy.
     */
    private val ALLOWED_HOSTS = listOf(
        "chatgpt.com",
        "chat.openai.com",
        "claude.ai"
    )

    /**
     * Hosts the allowed sites themselves navigate to during sign-in.
     *
     * Both sites offer "Continue with Google" and send the user through an
     * identity provider before coming back. Without these, logging in — the one
     * thing the agent explicitly hands to the user — would hit the block screen
     * halfway through, leaving them unable to reach either site at all.
     */
    private val ALLOWED_AUTH_HOSTS = listOf(
        "accounts.google.com",
        "accounts.googleapis.com",
        "auth.openai.com",
        "auth0.openai.com",
        "appleid.apple.com",
        "login.microsoftonline.com"
    )

    /** What the user is told, in speech and on screen, when a site is blocked. */
    const val BLOCKED_MESSAGE =
        "I can only browse ChatGPT and Claude right now. Other sites are turned off."

    /** Where an out-of-scope request lands if something needs a fallback page. */
    val DEFAULT_URL: String get() = AiService.CHATGPT.homeUrl

    /** True if [url] may be loaded. */
    fun isAllowed(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        // about:blank is how the start screen and the parked engine idle.
        if (url == "about:blank") return true

        val host = hostOf(url) ?: return false
        return (ALLOWED_HOSTS + ALLOWED_AUTH_HOSTS).any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }

    /**
     * True if [url] is one of the two products themselves, rather than an
     * identity provider we merely pass through. Used where a *destination*
     * matters — the agent's own Open action should aim at a real site, not at
     * a login screen it is not allowed to fill in anyway.
     */
    fun isPrimarySite(url: String?): Boolean {
        val host = hostOf(url) ?: return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            java.net.URI(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
    }
}
