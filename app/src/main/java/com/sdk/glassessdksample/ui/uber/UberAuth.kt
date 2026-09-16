package com.sdk.glassessdksample.ui.uber

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * OAuth 2.0 + PKCE against Uber's rider API.
 *
 * HOW THIS DIFFERS FROM SwiggyAuth, and why the differences exist:
 *
 * 1. NO DYNAMIC CLIENT REGISTRATION. Swiggy's /auth/register hands the same
 *    "swiggy-mcp" id to every caller, so registration there is a formality.
 *    Uber has no DCR endpoint at all: you create an app by hand on
 *    developer.uber.com/dashboard and it gives YOU a private client_id and
 *    client_secret. Hence [CLIENT_ID] / [CLIENT_SECRET] below are constants
 *    that have to be filled in, not something discovered at runtime.
 *
 * 2. THE TOKEN EXCHANGE NEEDS THE SECRET. Swiggy registers us with
 *    token_endpoint_auth_method "none", so PKCE alone authenticates the
 *    exchange. Uber's /oauth/v2/token requires client_secret even when a PKCE
 *    verifier is sent. We still send the verifier — it protects the code in
 *    transit — but the secret is what Uber actually checks.
 *
 *    A secret inside an APK is extractable, so this is a DEMO-GRADE
 *    arrangement. Before this ships to real users the exchange should move
 *    behind the IMI backend: the app sends the code, the server holds the
 *    secret and returns the token. Nothing else in this file changes when that
 *    happens — only [exchangeCode] and [refresh] point somewhere else.
 *
 * 3. THE REDIRECT IS REGISTERED BY HAND. Swiggy rejected our app scheme and
 *    forced the loopback flow (see SwiggyAuth's header). Uber accepts whatever
 *    redirect you type into the dashboard, so loopback is a CHOICE here rather
 *    than a workaround — it is what RFC 8252 recommends for native clients, and
 *    reusing the shape we already debugged for Swiggy is worth more than a
 *    marginally prettier app-scheme redirect.
 *
 *    Port 8766, NOT 8765: Swiggy already owns 8765, and a user who starts an
 *    Uber login while a Swiggy login is still open would otherwise collide on
 *    the bind.
 *
 * SCOPES. "request" is a PRIVILEGED scope. Until Uber grants this app Full
 * Access it works ONLY for your own Uber account and the developer accounts
 * listed on the dashboard — everyone else gets a consent screen that refuses
 * the scope. That is expected and is not a bug in this code.
 */
object UberAuth {

    private const val TAG = "UberAuth"

    // ------------------------------------------------------------ credentials

    /**
     * FILL THESE IN from developer.uber.com/dashboard -> your app -> Auth.
     *
     * While they are left at the placeholder values every Uber tool reports
     * "Uber isn't set up yet" rather than failing with a confusing 401, so the
     * app is safe to build and run before the keys exist.
     */
    const val CLIENT_ID = "PASTE_UBER_CLIENT_ID_HERE"
    private const val CLIENT_SECRET = "PASTE_UBER_CLIENT_SECRET_HERE"

    /** True once real credentials are present. */
    val isConfigured: Boolean
        get() = !CLIENT_ID.startsWith("PASTE_") && !CLIENT_SECRET.startsWith("PASTE_")

    // -------------------------------------------------------------- endpoints

    private const val AUTHORIZE_ENDPOINT = "https://auth.uber.com/oauth/v2/authorize"
    private const val TOKEN_ENDPOINT = "https://auth.uber.com/oauth/v2/token"

    /**
     * profile      - who the rider is, used to greet them after connecting
     * places       - saved home/work addresses, so "take me home" resolves
     * request      - PRIVILEGED. booking a ride. needs Full Access for production
     * offline_access - issues a refresh_token, so the user is not re-prompted daily
     */
    private const val SCOPE = "profile places request offline_access"

    /**
     * Must match the redirect URI typed into the Uber dashboard EXACTLY,
     * including the trailing path. Uber compares it character for character on
     * both the authorize call and the token exchange.
     */
    private const val CALLBACK_PORT = 8766
    const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT/callback"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Guards against two logins racing for the same port. */
    @Volatile private var loginInFlight = false

    data class Result(val success: Boolean, val message: String)

    private data class Callback(val code: String?, val state: String?, val error: String?)

    // ---------------------------------------------------------------- login

    /**
     * Runs the whole login: opens the browser, waits for the redirect, exchanges
     * the code. Returns a spoken-style result string.
     *
     * Suspends for as long as the user takes in the browser, bounded by
     * [timeoutMs]. Call it off the main thread (the tool dispatcher is already
     * suspend, so it is).
     */
    suspend fun login(context: Context, timeoutMs: Long = 180_000L): Result = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            return@withContext Result(false, "Uber isn't set up in this app yet.")
        }
        if (loginInFlight) {
            return@withContext Result(false, "An Uber sign-in is already open. Finish it in the browser first.")
        }
        loginInFlight = true
        var server: ServerSocket? = null
        try {
            val verifier = generateCodeVerifier()
            val challenge = codeChallengeOf(verifier)
            val state = generateCodeVerifier().take(24)

            // Bind BEFORE opening the browser, so a fast user cannot beat the
            // listener to the redirect and get a connection refused page.
            server = ServerSocket(CALLBACK_PORT, 1, java.net.InetAddress.getByName("127.0.0.1"))
            server.soTimeout = timeoutMs.toInt()

            val authUrl = Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state)
                .build()

            Log.d(TAG, "Opening Uber consent: $authUrl")
            val intent = Intent(Intent.ACTION_VIEW, authUrl).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                return@withContext Result(false, "Couldn't open a browser to sign in to Uber.")
            }

            val callback = awaitCallback(server, state)
                ?: return@withContext Result(false, "Uber sign-in timed out. Try again when you're ready.")

            if (callback.error != null) {
                return@withContext Result(false, "Uber sign-in was declined or failed: ${callback.error}.")
            }
            val code = callback.code
                ?: return@withContext Result(false, "Uber didn't return a sign-in code. Try again.")

            exchangeCode(context, code, verifier)
        } catch (e: Exception) {
            Log.e(TAG, "Uber login failed", e)
            Result(false, "Uber sign-in failed: ${e.message}")
        } finally {
            loginInFlight = false
            try { server?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Accepts exactly one HTTP request on [server], parses the query off the
     * request line, and writes back a small page so the user sees that it
     * worked instead of a blank tab.
     */
    private fun awaitCallback(server: ServerSocket, expectedState: String): Callback? {
        return try {
            server.accept().use { socket: Socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return null
                // "GET /callback?code=...&state=... HTTP/1.1"
                val path = requestLine.split(" ").getOrNull(1) ?: return null
                val uri = Uri.parse("http://localhost$path")

                val code = uri.getQueryParameter("code")
                val state = uri.getQueryParameter("state")
                val error = uri.getQueryParameter("error")

                // state mismatch means this redirect is not the one we started.
                val mismatched = state != null && state != expectedState
                val ok = error == null && !mismatched && code != null

                val body = if (ok) {
                    "<html><body style=\"font-family:sans-serif;text-align:center;padding-top:60px\">" +
                        "<h2>Uber connected</h2><p>You can close this tab and go back to IMI.</p></body></html>"
                } else {
                    "<html><body style=\"font-family:sans-serif;text-align:center;padding-top:60px\">" +
                        "<h2>Sign-in failed</h2><p>Go back to IMI and try again.</p></body></html>"
                }
                socket.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body")
                        .toByteArray()
                )
                socket.getOutputStream().flush()

                when {
                    error != null -> Callback(null, state, error)
                    mismatched -> Callback(null, state, "state_mismatch")
                    else -> Callback(code, state, null)
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Timed out waiting for Uber redirect")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Callback listener failed", e)
            null
        }
    }

    private fun exchangeCode(context: Context, code: String, verifier: String): Result {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("code_verifier", verifier)
            .build()
        val req = Request.Builder().url(TOKEN_ENDPOINT).post(form).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Token exchange failed ${resp.code}: $body")
                return Result(false, "Uber rejected the sign-in (${resp.code}).")
            }
            val json = JSONObject(body)
            val access = json.optString("access_token")
            if (access.isNullOrBlank()) return Result(false, "Uber didn't return an access token.")

            // Uber echoes the scopes actually granted. If "request" is missing
            // the account is not on the dashboard allowlist, and booking will
            // fail LATER with a confusing 403 — so say so now, while the user
            // is still thinking about Uber.
            val granted = json.optString("scope").orEmpty()
            val canBook = granted.contains("request")

            UberTokenStore.save(
                context = context,
                accessToken = access,
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresInSeconds = json.optLong("expires_in", 2592000L)
            )
            Log.d(TAG, "Uber connected, scopes: $granted")
            return if (canBook) {
                Result(true, "Uber is connected.")
            } else {
                Result(true, "Uber is connected, but this account can't book rides yet - it needs to be added to the Uber developer dashboard.")
            }
        }
    }

    // -------------------------------------------------------------- refresh

    /**
     * Returns a usable access token, refreshing if it is near expiry.
     * Null means the user has to log in again.
     */
    suspend fun validAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext null
        if (!UberTokenStore.isConnected(context)) return@withContext null
        if (!UberTokenStore.isExpired(context)) return@withContext UberTokenStore.accessToken(context)

        val refresh = UberTokenStore.refreshToken(context)
        if (refresh.isNullOrBlank()) {
            // No offline_access grant, so there is nothing to renew with. Make
            // the caller ask the user to reconnect.
            UberTokenStore.clear(context)
            return@withContext null
        }
        try {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build()
            val req = Request.Builder().url(TOKEN_ENDPOINT).post(form).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Refresh failed ${resp.code}: $body")
                    UberTokenStore.clear(context)
                    return@withContext null
                }
                val json = JSONObject(body)
                val access = json.optString("access_token")
                if (access.isNullOrBlank()) {
                    UberTokenStore.clear(context)
                    return@withContext null
                }
                UberTokenStore.save(
                    context = context,
                    accessToken = access,
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refresh,
                    expiresInSeconds = json.optLong("expires_in", 2592000L)
                )
                return@withContext access
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh threw", e)
            return@withContext null
        }
    }

    // ----------------------------------------------------------------- PKCE

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun codeChallengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
