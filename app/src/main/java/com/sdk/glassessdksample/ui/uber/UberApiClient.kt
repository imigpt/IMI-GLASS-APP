package com.sdk.glassessdksample.ui.uber

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Uber's rider REST API, as much of it as booking a ride needs.
 *
 * Where SwiggyMcpClient speaks JSON-RPC to an MCP server that already models
 * "tools", this is plain REST and there is no tool layer to borrow — so the
 * shape of an order is decided here and in [UberRideSession] rather than by the
 * far end. The client itself stays deliberately thin: authenticate, call, hand
 * back JSON, and translate Uber's error codes into something speakable.
 *
 * ENDPOINTS USED, and why each one is needed rather than skipped:
 *   GET  /v1.2/products          which ride types exist AT THIS PICKUP POINT.
 *                                product_id is location-specific, so it cannot
 *                                be cached across cities.
 *   POST /v1.2/requests/estimate the upfront fare_id and the price to read out.
 *                                Booking without this is possible but then the
 *                                user agrees to a price nobody quoted them.
 *   POST /v1.2/requests          the actual booking. Spends money.
 *   GET  /v1.2/requests/current  tracking an in-flight ride.
 *   DELETE /v1.2/requests/current cancelling one.
 *
 * THE TWO FAILURE MODES THAT MATTER, both handled as typed exceptions rather
 * than strings, because the session has to DO something different for each:
 *   409 surge        -> the fare needs explicit re-confirmation at the higher
 *                       multiplier. [SurgeException] carries the id to echo back.
 *   422 fare_expired -> the quoted fare went stale while the user was deciding.
 *                       [FareExpiredException] makes the session re-estimate
 *                       instead of booking at an unknown price.
 */
object UberApiClient {

    private const val TAG = "UberApiClient"

    private const val BASE_URL = "https://api.uber.com"

    private val JSON = "application/json".toMediaTypeOrNull()

    /**
     * Booking can be slow, so the read timeout is generous. It still has to
     * return: this runs on the Gemini Live tool-call callback, and a hung call
     * means the model never gets a tool response and the user hears only the
     * thinking tone — same reasoning as SwiggyMcpClient's timeouts.
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Raised when the token is gone or rejected, so callers can prompt a reconnect. */
    class NotConnectedException(message: String) : Exception(message)

    /**
     * Raised on 409 surge. [surgeConfirmationId] must be passed back to
     * [requestRide] to book at the surged price, and [multiplier] is what the
     * user has to be told before that happens.
     */
    class SurgeException(
        val surgeConfirmationId: String?,
        val multiplier: Double
    ) : Exception("Surge pricing in effect (${multiplier}x)")

    /** Raised on 422 fare_expired. The session re-estimates and re-quotes. */
    class FareExpiredException : Exception("The fare quote expired")

    /** Any other Uber error, already turned into something speakable. */
    class UberException(message: String) : Exception(message)

    // ------------------------------------------------------------- transport

    private suspend fun call(
        context: Context,
        method: String,
        path: String,
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val token = UberAuth.validAccessToken(context)
            ?: throw NotConnectedException("Uber isn't connected.")

        val builder = Request.Builder()
            .url("$BASE_URL$path")
            .header("Authorization", "Bearer $token")
            .header("Accept-Language", "en_IN")
            .header("Content-Type", "application/json")

        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(JSON))
            "DELETE" -> builder.delete()
            else -> throw IllegalArgumentException("bad method $method")
        }

        client.newCall(builder.build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            Log.d(TAG, "$method $path -> ${resp.code}")

            if (resp.isSuccessful) {
                // DELETE /requests/current answers 204 with no body.
                return@withContext if (raw.isBlank()) JSONObject() else JSONObject(raw)
            }

            if (resp.code == 401) {
                // The grant is gone, not merely expired — validAccessToken
                // already tried refreshing. Only a fresh consent fixes it.
                UberTokenStore.clear(context)
                throw NotConnectedException("Uber isn't connected.")
            }

            throw translateError(resp.code, raw)
        }
    }

    /**
     * Turns Uber's error envelope into the right exception type.
     *
     * Uber nests errors two different ways depending on endpoint — a top-level
     * {code, message} or {errors:[{code, title}]} — so both are checked before
     * falling back to the status code.
     */
    private fun translateError(status: Int, raw: String): Exception {
        val json = try { JSONObject(raw) } catch (e: Exception) { JSONObject() }

        val nested = json.optJSONArray("errors")?.optJSONObject(0)
        val code = json.optString("code").ifBlank { nested?.optString("code").orEmpty() }
        val message = json.optString("message")
            .ifBlank { nested?.optString("title").orEmpty() }

        if (status == 409 && code == "surge") {
            val meta = json.optJSONObject("meta")?.optJSONObject("surge_confirmation")
            return SurgeException(
                surgeConfirmationId = meta?.optString("surge_confirmation_id")?.takeIf { it.isNotBlank() },
                multiplier = meta?.optDouble("multiplier", 1.0) ?: 1.0
            )
        }
        if (status == 422 && code == "fare_expired") return FareExpiredException()

        return when {
            code == "current_trip_exists" ->
                UberException("You're already on an Uber trip right now.")
            code == "product_not_allowed" || status == 403 ->
                // Overwhelmingly the privileged-scope wall during development.
                UberException("This Uber account isn't allowed to book rides yet.")
            status == 404 ->
                UberException("Uber couldn't find that.")
            message.isNotBlank() -> UberException(message)
            else -> UberException("Uber returned an error ($status).")
        }
    }

    // -------------------------------------------------------------- products

    data class Product(
        val productId: String,
        val displayName: String,
        val capacity: Int,
        val shared: Boolean
    )

    /**
     * Ride types available at this pickup point, nearest-first as Uber returns
     * them. Shared products are kept — some users want the cheap option — but
     * flagged so the session can mention it.
     */
    suspend fun products(context: Context, lat: Double, lng: Double): List<Product> {
        val json = call(context, "GET", "/v1.2/products?latitude=$lat&longitude=$lng")
        val arr = json.optJSONArray("products") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("product_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Product(
                productId = id,
                displayName = o.optString("display_name").ifBlank { "Uber" },
                capacity = o.optInt("capacity", 4),
                shared = o.optBoolean("shared", false)
            )
        }
    }

    // -------------------------------------------------------------- estimate

    data class Estimate(
        val fareId: String?,
        val displayPrice: String,
        val pickupEtaMinutes: Int,
        val durationSeconds: Int,
        val surgeMultiplier: Double
    )

    /**
     * The upfront fare for one product between two points.
     *
     * [fareId] is null for products Uber prices by surge rather than upfront.
     * That is not an error: [requestRide] simply books without one, and the
     * surge branch takes over if a multiplier applies.
     */
    suspend fun estimate(
        context: Context,
        productId: String,
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): Estimate {
        val body = JSONObject().apply {
            put("product_id", productId)
            put("start_latitude", startLat)
            put("start_longitude", startLng)
            put("end_latitude", endLat)
            put("end_longitude", endLng)
        }
        val json = call(context, "POST", "/v1.2/requests/estimate", body)

        // Two shapes: upfront pricing puts the money under "fare", surge
        // pricing puts it under "estimate". Whichever is present wins.
        val fare = json.optJSONObject("fare")
        val est = json.optJSONObject("estimate")
        val trip = json.optJSONObject("trip")

        val display = fare?.optString("display")?.takeIf { it.isNotBlank() }
            ?: est?.optString("display")?.takeIf { it.isNotBlank() }
            ?: "price unavailable"

        return Estimate(
            fareId = fare?.optString("fare_id")?.takeIf { it.isNotBlank() },
            displayPrice = display,
            pickupEtaMinutes = json.optInt("pickup_estimate", 0),
            durationSeconds = trip?.optInt("duration_estimate", 0) ?: 0,
            surgeMultiplier = est?.optDouble("surge_multiplier", 1.0) ?: 1.0
        )
    }

    // --------------------------------------------------------------- booking

    data class Ride(
        val requestId: String,
        val status: String,
        val etaMinutes: Int,
        val driverName: String?,
        val driverPhone: String?,
        val vehicleDescription: String?,
        val licensePlate: String?
    )

    /**
     * Books the ride. THIS SPENDS THE USER'S MONEY.
     *
     * [surgeConfirmationId] is only passed on the second attempt, after a
     * [SurgeException] was reported to the user and they agreed to the higher
     * price. Passing it without asking would defeat the entire point of Uber
     * raising the 409 in the first place.
     */
    suspend fun requestRide(
        context: Context,
        productId: String,
        fareId: String?,
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double,
        startAddress: String? = null,
        endAddress: String? = null,
        surgeConfirmationId: String? = null
    ): Ride {
        val body = JSONObject().apply {
            put("product_id", productId)
            if (!fareId.isNullOrBlank()) put("fare_id", fareId)
            put("start_latitude", startLat)
            put("start_longitude", startLng)
            put("end_latitude", endLat)
            put("end_longitude", endLng)
            if (!startAddress.isNullOrBlank()) put("start_address", startAddress)
            if (!endAddress.isNullOrBlank()) put("end_address", endAddress)
            if (!surgeConfirmationId.isNullOrBlank()) {
                put("surge_confirmation_id", surgeConfirmationId)
            }
        }
        return parseRide(call(context, "POST", "/v1.2/requests", body))
    }

    /** The ride in progress, or null if there isn't one. */
    suspend fun currentRide(context: Context): Ride? {
        return try {
            val json = call(context, "GET", "/v1.2/requests/current")
            if (json.optString("request_id").isBlank()) null else parseRide(json)
        } catch (e: UberException) {
            // 404 here means "no active trip", which is an answer, not a fault.
            null
        }
    }

    /** Cancels the ride in progress. Uber may still charge a cancellation fee. */
    suspend fun cancelCurrentRide(context: Context) {
        call(context, "DELETE", "/v1.2/requests/current")
    }

    private fun parseRide(json: JSONObject): Ride {
        val driver = json.optJSONObject("driver")
        val vehicle = json.optJSONObject("vehicle")
        return Ride(
            requestId = json.optString("request_id"),
            status = json.optString("status").ifBlank { "processing" },
            etaMinutes = json.optInt("eta", 0),
            driverName = driver?.optString("name")?.takeIf { it.isNotBlank() },
            driverPhone = driver?.optString("phone_number")?.takeIf { it.isNotBlank() },
            vehicleDescription = vehicle?.let {
                val make = it.optString("make")
                val model = it.optString("model")
                "$make $model".trim().takeIf { s -> s.isNotBlank() }
            },
            licensePlate = vehicle?.optString("license_plate")?.takeIf { it.isNotBlank() }
        )
    }
}
