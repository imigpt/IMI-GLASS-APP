package com.sdk.glassessdksample.ui.web

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * Owns the browser session for the in-app Web section.
 *
 * The whole point of this class is persistence: cookies and DOM storage are
 * kept across app restarts so the user logs into a site **once** and stays
 * logged in. Module 2's agent relies on that — it never handles credentials
 * itself, it just reuses the session the user established by hand.
 */
object WebSessionManager {

    /** Desktop UA — many sites expose a richer, easier-to-drive DOM to it. */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** Applies the browser configuration this feature depends on. */
    fun configure(webView: WebView, desktopMode: Boolean = false) {
        // Lets `chrome://inspect` attach to these WebViews from a dev machine.
        // Debug builds only: this exposes page contents to anything that can
        // reach adb, which is not something a shipped app should offer.
        if (com.sdk.glassessdksample.BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            // useWideViewPort must stay ON. It makes the WebView honour the
            // page's own <meta name="viewport">, which is how a responsive site
            // lays out at the real screen width. Turning it off does NOT give a
            // wider layout — the WebView falls back to a fixed ~360px CSS
            // viewport, so the page renders cramped and, on ChatGPT, never
            // finished loading at all.
            //
            // loadWithOverviewMode is the one that zooms out to fit, so that is
            // the setting to leave off for mobile: the page then lays out at
            // phone width and fills the view at 1:1 instead of being shrunk.
            useWideViewPort = true
            loadWithOverviewMode = desktopMode
            builtInZoomControls = true
            displayZoomControls = false

            javaScriptCanOpenWindowsAutomatically = true
            // Google Sign-In ("Continue with Google") and similar OAuth flows
            // open in a popup window (window.open), not a normal navigation.
            // With this false and no WebChromeClient.onCreateWindow handler,
            // that popup request is silently dropped - the page just sits
            // there forever, looking frozen, because it's still waiting for a
            // popup that never opens. True here + onCreateWindow in
            // WebBrowserActivity/GlassBrowserEngine routes the popup's
            // navigation back into the same WebView instead of actually
            // spawning a second window.
            setSupportMultipleWindows(true)

            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT

            userAgentString = if (desktopMode) DESKTOP_UA else userAgentString
        }

        // Force the WebView to composite opaquely.
        //
        // The app theme's window background is a gradient WITH ALPHA, so the
        // window is TRANSLUCENT. A WebView in a translucent window can end up
        // composited as fully transparent — Chrome's own devtools reported the
        // page as `empty:false, width:1080, height:1665, visible:false`: real
        // content, correct size, never drawn. On a dark app background that is
        // an entirely black rectangle, which reads as "the page didn't load"
        // when in fact it loaded perfectly.
        //
        // setBackgroundColor on its own is not enough; the layer type must be
        // hardware for the opaque path to be taken.
        webView.setBackgroundColor(android.graphics.Color.WHITE)
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        // Third-party cookies are required by most SSO / login flows.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // Dark mode. forceDark is a NO-OP once the app targets Android 13+ —
        // the log says so outright ("setForceDark() is a no-op in an app with
        // targetSdkVersion>=T"), and this app targets 36. With it silently
        // doing nothing, the WebView rendered in light mode while the sites
        // themselves followed the system dark theme and painted light text.
        // Light text on an undarkened background, inside this app's black
        // chrome, is an entirely blank-looking page — which is exactly how
        // chatgpt.com/auth/login presented.
        //
        // isAlgorithmicDarkeningAllowed is the replacement: it lets the page's
        // own prefers-color-scheme decide, and only applies automatic darkening
        // where the site has no dark styling of its own.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webView.settings.isAlgorithmicDarkeningAllowed = true
        } else {
            @Suppress("DEPRECATION")
            webView.settings.forceDark = WebSettings.FORCE_DARK_AUTO
        }
    }

    /** Flushes cookies to disk so the session survives process death. */
    fun persist() {
        CookieManager.getInstance().flush()
    }

    /**
     * Wipes every trace of the browsing session: cookies, DOM storage, cache
     * and history. Exposed to the user as "Clear browsing data".
     */
    fun clearSession(context: Context, webView: WebView?) {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
        webView?.apply {
            clearCache(true)
            clearFormData()
            clearHistory()
        }
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("org.chromium") }
            ?.forEach { it.deleteRecursively() }
    }

    /**
     * Turns whatever the user typed in the address bar into a URL.
     * Anything that doesn't look like a host becomes a Google search.
     */
    fun toUrlOrSearch(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return "about:blank"

        if (text.startsWith("http://") || text.startsWith("https://")) return text
        if (text.startsWith("about:") || text.startsWith("file://")) return text

        val looksLikeDomain = !text.contains(' ') &&
            text.contains('.') &&
            text.substringAfterLast('.').isNotEmpty()

        return if (looksLikeDomain) {
            "https://$text"
        } else {
            "https://www.google.com/search?q=" + java.net.URLEncoder.encode(text, "UTF-8")
        }
    }

    /** Short host label for the address bar, e.g. "google.com". */
    fun displayHost(url: String?): String {
        if (url.isNullOrBlank() || url == "about:blank") return ""
        return try {
            java.net.URI(url).host?.removePrefix("www.") ?: url
        } catch (_: Exception) {
            url
        }
    }
}
