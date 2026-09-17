package com.sdk.glassessdksample.ui.swiggy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * OAuth 2.1 + PKCE against Swiggy's MCP gateway, for the Builders Club servers.
 *
 * WHY LOOPBACK AND NOT AN APP SCHEME (this is the whole design constraint):
 * Swiggy's /auth/register rejects custom schemes. Registering
 * "com.aselea.imiglass://swiggy/callback" comes back
 *   invalid_redirect_uri: protocol must be one of:
 *   http:, https:, cursor:, claude:, raycast:, vscode:, swiggyinternal:, ...
 * i.e. an allowlist of desktop AI clients plus a few named partners. Our package
 * is not on it, so the usual Android "Custom Tab -> app scheme" redirect is not
 * available to us at all.
 *
 * http://localhost IS accepted, so we use the loopback flow, which is what
 * RFC 8252 (OAuth for Native Apps) recommends for native clients anyway: open
 * the system browser, let it redirect to a one-shot local server we run for the
 * duration of the login, and read the authorization code off that request.
 *
 * The listener binds 127.0.0.1 only, lives for one request, and is closed in a
 * finally block. Nothing is left listening after login.
 *
 * Tokens land in [SwiggyTokenStore], mirroring how auth.SessionManager keeps the
 * IMI session.
 */
object SwiggyAuth { 

    private const val TAG = "SwiggyAuth"

    /**
     * Discovered from https://mcp.swiggy.com/.well-known/oauth-authorization-server.
     * Hardcoded rather than fetched so login has one less round trip to fail on;
     * [refreshMetadata] re-reads them if Swiggy ever moves the endpoints.
     */
    @Volatile private var authorizeEndpoint = "https://mcp.swiggy.com/auth/authorize"
    @Volatile private var tokenEndpoint = "https://mcp.swiggy.com/auth/token"
    private const val REGISTRATION_ENDPOINT = "https://mcp.swiggy.com/auth/register"
    private const val METADATA_URL = "https://mcp.swiggy.com/.well-known/oauth-authorization-server"

    private const val SCOPE = "mcp:tools"

    /**
     * The port the redirect comes back on. Fixed rather than ephemeral because
     * it has to match the redirect_uri registered with Swiggy exactly, and DCR
     * pins the URI at registration time.
     */
    private const val CALLBACK_PORT = 8765
    const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT/callback"

    /**
     * Swiggy's /auth/register currently returns the same client_id ("swiggy-mcp")
     * for every caller, so registration is really a formality today. We still
     * call it — that is the documented flow and the id is theirs to change — and
     * fall back to the known constant if registration is unreachable.
     */
    private const val FALLBACK_CLIENT_ID = "swiggy-mcp"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** PKCE verifier for the login currently in flight, held until the code comes back. */
    @Volatile private var pendingVerifier: String? = null

    /** Guards against two logins racing for the same port. */
    @Volatile private var loginInFlight = false

    // ---------------------------------------------------------------- login

    /**
     * Runs the whole login: registers, opens the browser, waits for the
     * redirect, exchanges the code. Returns a spoken-style result string.
     *
     * Suspends for as long as the user takes in the browser, bounded by
     * [timeoutMs]. Call it off the main thread (the tool dispatcher is already
     * suspend, so it is).
     */
    suspend fun login(context: Context, timeoutMs: Long = 180_000L): Result = withContext(Dispatchers.IO) {
        if (loginInFlight) {
            return@withContext Result(false, "A Swiggy sign-in is already open. Finish it in the browser first.")
        }
        loginInFlight = true
        var server: ServerSocket? = null
        try {
            refreshMetadata()
            val clientId = registerClient()

            val verifier = generateCodeVerifier()
            pendingVerifier = verifier
            val challenge = codeChallengeOf(verifier)
            val state = generateCodeVerifier().take(24)

            // Bind BEFORE opening the browser, so a fast user cannot beat the
            // listener to the redirect and get a connection refused page.
            server = ServerSocket(CALLBACK_PORT, 1, java.net.InetAddress.getByName("127.0.0.1"))
            server.soTimeout = timeoutMs.toInt()

            val authUrl = Uri.parse(authorizeEndpoint).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("state", state)
                .build()

            Log.d(TAG, "Opening Swiggy consent: $authUrl")
            val intent = Intent(Intent.ACTION_VIEW, authUrl).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                return@withContext Result(false, "Couldn't open a browser to sign in to Swiggy.")
            }

            val callback = awaitCallback(server, state)
                ?: return@withContext Result(false, "Swiggy sign-in timed out. Try again when you're ready.")

            if (callback.error != null) {
                return@withContext Result(false, "Swiggy sign-in was declined or failed: ${callback.error}.")
            }
            val code = callback.code
                ?: return@withContext Result(false, "Swiggy didn't return a sign-in code. Try again.")

            exchangeCode(context, clientId, code, verifier)
        } catch (e: Exception) {
            Log.e(TAG, "Swiggy login failed", e)
            Result(false, "Swiggy sign-in failed: ${e.message}")
        } finally {
            pendingVerifier = null
            loginInFlight = false
            try { server?.close() } catch (_: Exception) {}
        }
    }

    data class Result(val success: Boolean, val message: String)

    private data class Callback(val code: String?, val state: String?, val error: String?)

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
                        "<h2>Swiggy connected</h2><p>You can close this tab and go back to IMI.</p></body></html>"
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
            Log.w(TAG, "Timed out waiting for Swiggy redirect")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Callback listener failed", e)
            null
        }
    }

    private fun exchangeCode(context: Context, clientId: String, code: String, verifier: String): Result {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        val req = Request.Builder().url(tokenEndpoint).post(form).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "Token exchange failed ${resp.code}: $body")
                return Result(false, "Swiggy rejected the sign-in (${resp.code}).")
            }
            val json = JSONObject(body)
            val access = json.optString("access_token")
            if (access.isNullOrBlank()) return Result(false, "Swiggy didn't return an access token.")
            SwiggyTokenStore.save(
                context = context,
                accessToken = access,
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresInSeconds = json.optLong("expires_in", 3600L),
                clientId = clientId
            )
            Log.d(TAG, "Swiggy connected")
            return Result(true, "Swiggy is connected.")
        }
    }

    // -------------------------------------------------------------- refresh

    /**
     * Returns a usable access token, refreshing if it is near expiry.
     * Null means the user has to log in again.
     */
    suspend fun validAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        if (!SwiggyTokenStore.isConnected(context)) return@withContext null
        if (!SwiggyTokenStore.isExpired(context)) return@withContext SwiggyTokenStore.accessToken(context)

        val refresh = SwiggyTokenStore.refreshToken(context)
        if (refresh.isNullOrBlank()) {
            // Sessions expire after a few days and there is nothing to renew
            // with, so make the caller ask the user to reconnect.
            SwiggyTokenStore.clear(context)
            return@withContext null
        }
        try {
            val clientId = SwiggyTokenStore.clientId(context) ?: FALLBACK_CLIENT_ID
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", clientId)
                .build()
            val req = Request.Builder().url(tokenEndpoint).post(form).build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Refresh failed ${resp.code}: $body")
                    SwiggyTokenStore.clear(context)
                    return@withContext null
                }
                val json = JSONObject(body)
                val access = json.optString("access_token")
                if (access.isNullOrBlank()) {
                    SwiggyTokenStore.clear(context)
                    return@withContext null
                }
                SwiggyTokenStore.save(
                    context = context,
                    accessToken = access,
                    refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refresh,
                    expiresInSeconds = json.optLong("expires_in", 3600L),
                    clientId = clientId
                )
                return@withContext access
            }
        } catch (e: Exception) {
            Log.e(TAG, "Refresh threw", e)
            return@withContext null
        }
    }

    // ------------------------------------------------------------ discovery

    /** Re-reads the OAuth metadata so moved endpoints don't need an app update. */
    private fun refreshMetadata() {
        try {
            val req = Request.Builder().url(METADATA_URL).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optString("authorization_endpoint").takeIf { it.isNotBlank() }?.let { authorizeEndpoint = it }
                json.optString("token_endpoint").takeIf { it.isNotBlank() }?.let { tokenEndpoint = it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Metadata discovery failed, using defaults: ${e.message}")
        }
    }

    /** Dynamic Client Registration (RFC 7591). Falls back to the known id. */
    private fun registerClient(): String {
        try {
            val payload = JSONObject().apply {
                put("client_name", "IMI Glasses")
                put("redirect_uris", org.json.JSONArray().put(REDIRECT_URI))
                put("grant_types", org.json.JSONArray().put("authorization_code").put("refresh_token"))
                put("response_types", org.json.JSONArray().put("code"))
                put("token_endpoint_auth_method", "none")
                put("scope", SCOPE)
            }
            val req = Request.Builder()
                .url(REGISTRATION_ENDPOINT)
                .post(payload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val id = JSONObject(body).optString("client_id")
                    if (id.isNotBlank()) return id
                }
                Log.w(TAG, "DCR failed ${resp.code}: $body")
            }
        } catch (e: Exception) {
            Log.w(TAG, "DCR threw: ${e.message}")
        }
        return FALLBACK_CLIENT_ID
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
