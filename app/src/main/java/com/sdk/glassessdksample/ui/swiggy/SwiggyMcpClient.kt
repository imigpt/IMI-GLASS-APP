package com.sdk.glassessdksample.ui.swiggy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal MCP client for Swiggy's three Builders Club servers.
 *
 * The transport is streamable HTTP: one POST per server, JSON-RPC 2.0 in the
 * body. There is no socket to hold open and no session to keep alive between
 * calls beyond the Mcp-Session-Id the server hands back on initialize, so this
 * stays a plain request/response client rather than a long-lived connection.
 *
 * Responses come back as application/json today, but the spec allows the server
 * to answer a POST with text/event-stream, so [parseBody] handles both.
 */
object SwiggyMcpClient {

    private const val TAG = "SwiggyMcpClient"

    /** The three servers. See mcp.swiggy.com/builders/docs. */
    enum class Server(val slug: String, val label: String) {
        FOOD("food", "Swiggy Food"),
        INSTAMART("im", "Instamart"),
        DINEOUT("dineout", "Dineout")
    }

    private const val BASE_URL = "https://mcp.swiggy.com"
    private const val PROTOCOL_VERSION = "2025-06-18"

    private val JSON = "application/json".toMediaTypeOrNull()

    /**
     * Order placement can be slow, so the read timeout is generous. It still has
     * to return: this runs on the Gemini Live tool-call callback, and a hung
     * call means the model never gets a tool response and the user hears only
     * the thinking tone — the same reasoning as LocalToolHandlers' timeouts.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val requestId = AtomicInteger(1)

    /** Session id per server, returned by initialize and echoed on later calls. */
    private val sessions = mutableMapOf<Server, String>()

    /** Servers we have already initialized on this token. */
    private val initialized = mutableSetOf<Server>()

    /** Raised when the token is gone or rejected, so callers can prompt a reconnect. */
    class NotConnectedException(message: String) : Exception(message)

    /**
     * Calls one tool and returns its result as text.
     *
     * A 401 clears the stored token and throws [NotConnectedException]: Swiggy
     * sessions expire after a few days and the only cure is a fresh consent.
     */
    suspend fun callTool(
        context: Context,
        server: Server,
        toolName: String,
        arguments: Map<String, Any?> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        val token = SwiggyAuth.validAccessToken(context)
            ?: throw NotConnectedException("Swiggy isn't connected.")

        ensureInitialized(context, server, token)

        val params = JSONObject().apply {
            put("name", toolName)
            put("arguments", JSONObject(arguments.filterValues { it != null }))
        }
        val response = rpc(context, server, token, "tools/call", params)
        renderToolResult(response)
    }

    /**
     * Like [callTool], but returns the structured payload instead of text.
     *
     * The conversational flow has to pick ids out of a result — a product's
     * spinId, an address's addressId — and those live in structuredContent,
     * not in the human-readable text block. Falls back to parsing the text
     * block as JSON when a tool returns only that.
     */
    suspend fun callToolRaw(
        context: Context,
        server: Server,
        toolName: String,
        arguments: Map<String, Any?> = emptyMap()
    ): JSONObject? = withContext(Dispatchers.IO) {
        val token = SwiggyAuth.validAccessToken(context)
            ?: throw NotConnectedException("Swiggy isn't connected.")
        ensureInitialized(context, server, token)

        val params = JSONObject().apply {
            put("name", toolName)
            put("arguments", JSONObject(arguments.filterValues { it != null }))
        }
        val result = rpc(context, server, token, "tools/call", params)

        if (result.optBoolean("isError", false)) {
            throw Exception(extractText(result.optJSONArray("content")).ifBlank { "tool error" })
        }
        val structured = result.optJSONObject("structuredContent")
        val text = extractText(result.optJSONArray("content"))

        // Prefer structuredContent, but keep the text alongside it: some tools
        // return an envelope there ({"pagination":…}) while the rows the caller
        // actually needs are in the text block as JSON, and returning only the
        // envelope reads as "nothing found".
        val fromText = try {
            val t = text.trimStart()
            when {
                t.startsWith("{") -> JSONObject(text)
                // A bare array is wrapped so the caller always gets an object.
                t.startsWith("[") -> JSONObject().put("items", org.json.JSONArray(text))
                else -> null
            }
        } catch (e: Exception) {
            null
        }

        return@withContext when {
            structured != null && fromText != null -> {
                // Merge, with structuredContent winning on conflict.
                val merged = JSONObject(fromText.toString())
                structured.keys().forEach { k -> merged.put(k, structured.get(k)) }
                merged
            }
            structured != null -> structured
            fromText != null -> fromText
            else -> {
                // Nothing machine-readable at all; hand back the text so the
                // caller can at least log or speak it.
                if (text.isNotBlank()) JSONObject().put("text", text) else null
            }
        }
    }

    /** Lists a server's tools. Used by the tool-discovery debug path. */
    suspend fun listTools(context: Context, server: Server): List<String> = withContext(Dispatchers.IO) {
        val token = SwiggyAuth.validAccessToken(context)
            ?: throw NotConnectedException("Swiggy isn't connected.")
        ensureInitialized(context, server, token)
        val result = rpc(context, server, token, "tools/list", JSONObject())
        val tools = result.optJSONArray("tools") ?: return@withContext emptyList()
        (0 until tools.length()).mapNotNull { i ->
            tools.optJSONObject(i)?.let { t ->
                val name = t.optString("name")
                val desc = t.optString("description").take(120)
                if (name.isBlank()) null else "$name - $desc"
            }
        }
    }

    /**
     * Dumps every server's full tool schema to logcat under the tag
     * [SCHEMA_TAG].
     *
     * Swiggy publishes no catalogue of tool names or required arguments, so the
     * only way to learn the real contract is to ask a live server with a real
     * token. Kept in the shipping code rather than thrown away after the first
     * run, because the contract is Swiggy's to change and a 6-month deprecation
     * window means it will.
     *
     *   adb logcat -s SwiggySchema
     */
    suspend fun dumpAllSchemas(context: Context) = withContext(Dispatchers.IO) {
        val token = SwiggyAuth.validAccessToken(context)
        if (token == null) {
            Log.w(SCHEMA_TAG, "Not connected - connect Swiggy first")
            return@withContext
        }
        for (server in Server.values()) {
            try {
                ensureInitialized(context, server, token)
                val result = rpc(context, server, token, "tools/list", JSONObject())
                val tools = result.optJSONArray("tools")
                Log.i(SCHEMA_TAG, "===== ${server.label} (${server.slug}): ${tools?.length() ?: 0} tools =====")
                if (tools == null) continue
                for (i in 0 until tools.length()) {
                    val t = tools.optJSONObject(i) ?: continue
                    // Logcat truncates a long line, so name/description and the
                    // schema go out as separate entries.
                    Log.i(SCHEMA_TAG, "TOOL ${t.optString("name")} :: ${t.optString("description")}")
                    val schema = t.optJSONObject("inputSchema")
                    if (schema != null) {
                        Log.i(SCHEMA_TAG, "  required=${schema.optJSONArray("required")}")
                        schema.optJSONObject("properties")?.let { props ->
                            props.keys().forEach { key ->
                                Log.i(SCHEMA_TAG, "  arg $key = ${props.optJSONObject(key)}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(SCHEMA_TAG, "${server.slug} failed: ${e.message}")
            }
        }
        Log.i(SCHEMA_TAG, "===== schema dump complete =====")
    }

    private const val SCHEMA_TAG = "SwiggySchema"

    /**
     * MCP requires initialize before any tools/call. Done once per server per
     * process; a 401 later resets it so the next call re-initializes on the
     * refreshed token.
     */
    private fun ensureInitialized(context: Context, server: Server, token: String) {
        if (server in initialized) return
        synchronized(initialized) {
            if (server in initialized) return
            val params = JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "IMI Glasses")
                    put("version", "1.0")
                })
            }
            rpc(context, server, token, "initialize", params)
            // notifications/initialized is a notification: no id, no response.
            try {
                postRaw(
                    context, server, token,
                    JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("method", "notifications/initialized")
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "initialized notification failed (continuing): ${e.message}")
            }
            initialized.add(server)
        }
    }

    /** One JSON-RPC round trip. Returns the `result` object. */
    private fun rpc(
        context: Context,
        server: Server,
        token: String,
        method: String,
        params: JSONObject
    ): JSONObject {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestId.getAndIncrement())
            put("method", method)
            put("params", params)
        }
        val bodyText = postRaw(context, server, token, payload)
        val json = parseBody(bodyText)
            ?: throw Exception("Swiggy returned an unreadable response.")

        json.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "unknown error")
            throw Exception(msg)
        }
        return json.optJSONObject("result") ?: JSONObject()
    }

    /** POSTs a JSON-RPC payload and returns the raw body. */
    private fun postRaw(
        context: Context,
        server: Server,
        token: String,
        payload: JSONObject
    ): String {
        val req = Request.Builder()
            .url("$BASE_URL/${server.slug}")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .addHeader("MCP-Protocol-Version", PROTOCOL_VERSION)
            .apply { sessions[server]?.let { addHeader("Mcp-Session-Id", it) } }
            .post(payload.toString().toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()

            if (resp.code == 401) {
                // Token dead. Drop it so the next call asks for a fresh consent
                // rather than retrying a credential that cannot work.
                SwiggyTokenStore.clear(context)
                synchronized(initialized) {
                    initialized.remove(server)
                    sessions.remove(server)
                }
                throw NotConnectedException("Swiggy sign-in has expired.")
            }
            if (!resp.isSuccessful) {
                Log.e(TAG, "${server.slug} ${resp.code}: ${body.take(300)}")
                throw Exception("Swiggy ${server.label} returned an error (${resp.code}).")
            }
            resp.header("Mcp-Session-Id")?.let { sessions[server] = it }
            return body
        }
    }

    /**
     * Reads a JSON-RPC message out of either a plain JSON body or an SSE
     * stream. For SSE we take the last `data:` frame, which carries the
     * response for the request we just sent.
     */
    private fun parseBody(body: String): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("{")) {
            return try { JSONObject(trimmed) } catch (e: Exception) { null }
        }
        var last: JSONObject? = null
        trimmed.lineSequence().forEach { line ->
            if (line.startsWith("data:")) {
                val chunk = line.removePrefix("data:").trim()
                if (chunk.isNotEmpty() && chunk != "[DONE]") {
                    try { last = JSONObject(chunk) } catch (_: Exception) {}
                }
            }
        }
        return last
    }

    /**
     * Flattens an MCP tool result into text for the voice model.
     *
     * The content array holds typed blocks; we keep the text ones. Anything
     * else (images, resource links) has no spoken equivalent, so it is skipped
     * rather than described as a placeholder.
     */
    private fun renderToolResult(result: JSONObject): String {
        if (result.optBoolean("isError", false)) {
            val text = extractText(result.optJSONArray("content"))
            throw Exception(text.ifBlank { "the tool reported an error" })
        }
        val text = extractText(result.optJSONArray("content"))
        if (text.isNotBlank()) return text

        // Some tools answer with structuredContent and no text block.
        result.optJSONObject("structuredContent")?.let { return it.toString() }
        return "Done."
    }

    private fun extractText(content: JSONArray?): String {
        if (content == null) return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                val t = block.optString("text")
                if (t.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(t)
                }
            }
        }
        return sb.toString().trim()
    }

    /** Called on disconnect so a later login starts clean. */
    fun resetSessions() {
        synchronized(initialized) {
            initialized.clear()
            sessions.clear()
        }
    }
}
