package com.sdk.glassessdksample.ui.uber

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Uber, exposed to the glasses voice session as a CONVERSATION.
 *
 * Same contract as SwiggyTools and GlassBrowserTools: the user speaks, the live
 * model picks a tool, these functions run, and the string returned here is read
 * out loud. So every return value is written as speech — short, no markup, no
 * JSON, no coordinates.
 *
 * THE SHAPE OF A RIDE, and why it needs more than one call:
 *   1. uber_start   - destination from what they said, pickup from GPS, then
 *                     the ride types with real prices.
 *   2. uber_reply   - which ride type. Re-quotes and reads back the summary.
 *   3. uber_confirm - books it. SPENDS MONEY.
 *
 * Shorter than a Swiggy order because Uber needs less: no address list (GPS),
 * and no payment question (Uber charges the card already on the account).
 *
 * WHY THE FARE IS RE-QUOTED AT CONFIRM TIME. A Swiggy cart total holds while
 * the user thinks. An Uber fare_id expires, and surge can appear in the gap
 * between the quote and the yes. Booking on a stale quote means the user is
 * charged a price nobody said out loud — so [confirm] re-estimates first, and
 * if the price MOVED it asks again rather than booking.
 */
object UberTools {

    private const val TAG = "UberTools"

    /**
     * Serialises the ride conversation against itself.
     *
     * Gemini Live can deliver one tool call twice, and two concurrent confirms
     * would book two rides. Same problem SwiggyTools.orderMutex solves, with a
     * worse failure mode — a duplicate Uber is a car that actually arrives.
     */
    private val rideMutex = Mutex()

    /** Last reply handled, to recognise a redelivery of the same call. */
    @Volatile private var lastReplyHandled: String? = null
    @Volatile private var lastReplyAt: Long = 0L
    @Volatile private var lastSpoken: String = ""

    /** Two identical replies closer together than this are one call, twice. */
    private const val DUPLICATE_WINDOW_MS = 8_000L

    /** A booking this recent is a redelivery, not a second ride the user wants. */
    @Volatile private var lastBookingAt: Long = 0L
    private const val BOOKING_DEDUPE_MS = 30_000L

    val TOOL_NAMES = setOf(
        "uber_connect",
        "uber_start",
        "uber_reply",
        "uber_confirm",
        "uber_cancel",
        "uber_track"
    )

    // -------------------------------------------------------- declarations

    fun declarations(): List<Map<String, Any>> = listOf(
        mapOf(
            "type" to "function",
            "name" to "uber_start",
            "description" to
                "Begin booking an Uber ride. Call this the moment the user asks for a " +
                "ride - 'book me an Uber', 'get me a cab to the airport', 'I need a " +
                "ride home', 'uber bulao', 'cab chahiye', 'airport chalna hai'. You do " +
                "NOT need any details first: this tool starts a conversation and " +
                "returns the NEXT QUESTION to ask the user out loud, usually which ride " +
                "type they want with the real prices. Ask that question exactly as " +
                "given, ONE at a time, then pass their answer to uber_reply. Pass " +
                "whatever destination the user mentioned in 'destination'; leave it " +
                "empty if they did not say one.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "destination" to mapOf(
                        "type" to "string",
                        "description" to "Where the user wants to go, in their own words - " +
                            "'the airport', 'home', 'Koramangala'. Empty if they did not say."
                    )
                ),
                "required" to listOf<String>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "uber_reply",
            "description" to
                "Pass the user's answer to the question the ride booking just asked. " +
                "Call this every time the user answers something while a ride is being " +
                "set up - 'the first one', 'UberX', 'the cheapest', 'go', 'dusra wala', " +
                "or naming a destination. It returns either the NEXT question to ask out " +
                "loud, or the finished ride summary to read back. When it returns a " +
                "summary, read it out and ask whether to book it - do NOT call " +
                "uber_confirm in the same turn.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "answer" to mapOf(
                        "type" to "string",
                        "description" to "What the user just said, in their own words"
                    )
                ),
                "required" to listOf("answer")
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "uber_confirm",
            "description" to
                "Book the ride that was read back to the user. THIS SPENDS THE USER'S " +
                "MONEY and a real car is dispatched - cancelling afterwards may cost a " +
                "cancellation fee. Call it ONLY in a turn AFTER you read the full ride " +
                "summary including THE PRICE out loud and the user explicitly agreed - " +
                "'yes', 'book it', 'go ahead', 'haan', 'kar do'. Never call it in the " +
                "same turn as the summary, and never on a guess. If they want something " +
                "changed, do not call this - call uber_reply with what they want changed.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "confirmed" to mapOf(
                        "type" to "boolean",
                        "description" to "True only if the user clearly said yes. False cancels."
                    )
                ),
                "required" to listOf("confirmed")
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "uber_cancel",
            "description" to
                "Call this when the user wants to stop. If a ride is still being set up " +
                "and NOT yet booked, nothing is booked and no money is spent. If a ride " +
                "has ALREADY been booked, this cancels the real ride, which may cost a " +
                "cancellation fee - the tool will tell you which happened, so read its " +
                "answer out. Triggers: 'forget it', 'cancel', 'stop', 'rehne do', " +
                "'cancel kar do'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "uber_track",
            "description" to
                "Check where an already-booked Uber has got to - the driver, the car, " +
                "and how far away it is. Use for 'where is my Uber', 'has the driver " +
                "arrived', 'kitni der lagegi', 'track my ride', 'driver kahan hai'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "uber_connect",
            "description" to
                "Connect the user's Uber account. Call this ONLY when another Uber tool " +
                "says the account is not connected, or the user asks to sign in to Uber. " +
                "It opens a sign-in page in the phone's browser which the user finishes " +
                "themselves - never ask for a password, an OTP or card details out loud. " +
                "Tell them to check their phone, then wait.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>()
            )
        )
    )

    // -------------------------------------------------------------- dispatch

    suspend fun handle(
        context: Context,
        toolName: String,
        args: Map<String, Any>
    ): String {
        return try {
            when (toolName) {
                "uber_connect" -> connect(context)
                "uber_start" -> rideMutex.withLock {
                    start(context, args["destination"]?.toString().orEmpty())
                }
                "uber_reply" -> rideMutex.withLock {
                    reply(context, args["answer"]?.toString().orEmpty())
                }
                "uber_confirm" -> rideMutex.withLock {
                    confirm(
                        context,
                        args["confirmed"]?.toString()?.toBooleanStrictOrNull() ?: false
                    )
                }
                "uber_cancel" -> rideMutex.withLock { cancel(context) }
                "uber_track" -> track(context)
                else -> "That Uber action isn't available."
            }
        } catch (e: UberApiClient.NotConnectedException) {
            // An instruction rather than an error, so the model asks the user
            // to connect instead of announcing a failure.
            "Uber isn't connected yet. Tell the user you need to connect their " +
                "Uber account, then call uber_connect."
        } catch (e: Exception) {
            Log.e(TAG, "Uber tool '$toolName' failed", e)
            "Uber couldn't do that: ${e.message ?: "something went wrong"}."
        }
    }

    // ----------------------------------------------------------- connect

    private suspend fun connect(context: Context): String {
        if (!UberAuth.isConfigured) {
            return "Uber isn't set up in this app yet. Tell the user Uber booking " +
                "hasn't been configured, and do not call any more Uber tools."
        }
        if (UberTokenStore.isConnected(context) && !UberTokenStore.isExpired(context)) {
            return "Uber is already connected."
        }
        val result = UberAuth.login(context)
        return if (result.success) {
            "${result.message} Ask the user where they want to go."
        } else {
            "${result.message} Tell the user to finish signing in on their phone, " +
                "then say done when they have."
        }
    }

    // ------------------------------------------------------------- start

    private suspend fun start(context: Context, destination: String): String {
        if (!UberAuth.isConfigured) {
            return "Uber isn't set up in this app yet. Tell the user Uber booking " +
                "hasn't been configured, and do not call any more Uber tools."
        }
        if (!UberTokenStore.isConnected(context)) {
            return "Uber isn't connected yet. Tell the user you need to connect their " +
                "Uber account, then call uber_connect."
        }

        // A ride already booked is not something to quietly start over on top of.
        UberApiClient.currentRide(context)?.let { existing ->
            if (existing.status !in setOf("completed", "rider_canceled", "driver_canceled")) {
                return "The user already has an Uber on the way - ${describeRide(existing)}. " +
                    "Ask if they want to cancel that one first."
            }
        }

        UberRideSession.start(destination)

        val pickup = UberRideSession.currentLocation(context)
        if (pickup == null) {
            // GPS is unavailable, so fall back to asking. The ride is still in
            // GATHERING, and the next uber_reply is read as the pickup because
            // pickup is still null.
            return "Ask the user: where should the driver pick you up?"
        }
        UberRideSession.setPickup(pickup)

        if (destination.isBlank()) {
            return "Ask the user: where would you like to go?"
        }
        return resolveDestinationAndQuote(context, destination)
    }

    /**
     * Geocodes what the user said and reads back the ride types with prices.
     * Shared by [start] and [reply] because either can be the turn that finally
     * supplies a destination.
     */
    private suspend fun resolveDestinationAndQuote(context: Context, spoken: String): String {
        val pickup = UberRideSession.pickup
        val place = UberRideSession.geocode(context, spoken, pickup)
            ?: return "Ask the user: I couldn't find $spoken - can you give me the area or a landmark?"

        UberRideSession.setDestination(place)
        return quoteRideTypes(context)
    }

    /**
     * Prices every ride type at this pickup and reads the cheapest few out.
     *
     * Only the first three are offered: a spoken list longer than that is
     * unusable through glasses — the user cannot scroll back — which is the
     * same reason the system prompt caps spoken lists at about four items.
     */
    private suspend fun quoteRideTypes(context: Context): String {
        val pickup = UberRideSession.pickup ?: return "Ask the user where to pick them up."
        val destination = UberRideSession.destination ?: return "Ask the user where they want to go."

        val products = UberApiClient.products(context, pickup.latitude, pickup.longitude)
        if (products.isEmpty()) {
            UberRideSession.reset()
            return "Tell the user there are no Ubers available at their location right now."
        }

        // Estimating is one call per product, so cap the fan-out. Shared rides
        // are dropped: they need a seat count and a different consent
        // conversation, which is not worth the extra turns here.
        val candidates = products.filterNot { it.shared }.take(4)

        val choices = candidates.mapNotNull { product ->
            try {
                val est = UberApiClient.estimate(
                    context, product.productId,
                    pickup.latitude, pickup.longitude,
                    destination.latitude, destination.longitude
                )
                UberRideSession.RideChoice(
                    productId = product.productId,
                    label = product.displayName,
                    displayPrice = est.displayPrice,
                    etaMinutes = est.pickupEtaMinutes,
                    fareId = est.fareId
                )
            } catch (e: Exception) {
                // One unavailable product should not sink the whole quote.
                Log.w(TAG, "Estimate failed for ${product.displayName}: ${e.message}")
                null
            }
        }.take(3)

        if (choices.isEmpty()) {
            UberRideSession.reset()
            return "Tell the user Uber couldn't price a ride to ${destination.label} right now."
        }

        UberRideSession.setChoices(choices)

        // One option is not a choice — quote it and go straight to the summary.
        if (choices.size == 1) {
            UberRideSession.setChosenRide(choices.first())
            return summaryFor(choices.first())
        }

        val spoken = choices.joinToString(", ") { choice ->
            val eta = if (choice.etaMinutes > 0) ", ${choice.etaMinutes} minutes away" else ""
            "${choice.label} at ${choice.displayPrice}$eta"
        }
        return "Ask the user which they want, reading these exactly: $spoken. " +
            "Then pass their choice to uber_reply."
    }

    // ------------------------------------------------------------- reply

    private suspend fun reply(context: Context, answer: String): String {
        if (answer.isBlank()) return "Ask the user to say that again."

        if (!UberRideSession.isActive) {
            return "There's no ride being set up. If the user wants a ride, call uber_start."
        }

        // The SAME answer arriving twice is a duplicate delivery, not the user
        // repeating themselves — the live socket has several dispatch paths and
        // one call can arrive down more than one. Without this, a ride type
        // could be chosen and then re-quoted off a single "the first one".
        val now = System.currentTimeMillis()
        if (answer == lastReplyHandled && now - lastReplyAt < DUPLICATE_WINDOW_MS) {
            Log.d(TAG, "Duplicate reply '$answer' ignored")
            return lastSpoken
        }
        lastReplyHandled = answer
        lastReplyAt = now

        val spoken = when {
            // No pickup yet means GPS failed and we asked for one out loud.
            UberRideSession.pickup == null -> {
                val place = UberRideSession.geocode(context, answer, null)
                if (place == null) {
                    "Ask the user: I couldn't find that - can you give me a landmark nearby?"
                } else {
                    UberRideSession.setPickup(place)
                    val destination = UberRideSession.request
                    if (destination.isBlank()) {
                        "Ask the user: where would you like to go?"
                    } else {
                        resolveDestinationAndQuote(context, destination)
                    }
                }
            }

            // Pickup known but no destination: this answer is the destination.
            UberRideSession.destination == null -> resolveDestinationAndQuote(context, answer)

            // A reply while the summary is on the table is a CHANGE, not a yes:
            // a plain yes would have come through uber_confirm.
            UberRideSession.phase == UberRideSession.Phase.AWAITING_CONFIRMATION ->
                handleChange(context, answer)

            // Otherwise they are picking a ride type.
            else -> {
                val choice = UberRideSession.resolveChoice(answer, UberRideSession.lastChoices)
                if (choice == null) {
                    // A short answer with no match is the user trying to PICK
                    // one, not naming a new destination. Treating it as a fresh
                    // geocode is how "Go" becomes a street in another city, so
                    // ask again instead.
                    val options = UberRideSession.lastChoices.joinToString(", ") {
                        "${it.label} at ${it.displayPrice}"
                    }
                    "Ask the user again which one, reading these exactly: $options."
                } else {
                    UberRideSession.setChosenRide(choice)
                    summaryFor(choice)
                }
            }
        }

        lastSpoken = spoken
        return spoken
    }

    /**
     * A reply that arrives while the summary is waiting. Either they want a
     * different ride type from the same list, or a different destination.
     */
    private suspend fun handleChange(context: Context, answer: String): String {
        // Check the list already read out FIRST — "make it the cheaper one"
        // refers to that list, and geocoding it would throw the list away.
        val fromSameList = UberRideSession.resolveChoice(answer, UberRideSession.lastChoices)
        if (fromSameList != null) {
            UberRideSession.setChosenRide(fromSameList)
            UberRideSession.backToGathering()
            return summaryFor(fromSameList)
        }

        // Anything longer is a new destination.
        UberRideSession.backToGathering()
        return resolveDestinationAndQuote(context, answer)
    }

    /** The summary read back before any money moves. */
    private fun summaryFor(choice: UberRideSession.RideChoice): String {
        val pickup = UberRideSession.pickup?.label ?: "your location"
        val destination = UberRideSession.destination?.label ?: "your destination"
        UberRideSession.awaitConfirmation()

        val eta = if (choice.etaMinutes > 0) ", arriving in about ${choice.etaMinutes} minutes" else ""
        return "Read this whole summary out loud to the user, INCLUDING THE PRICE, then " +
            "ask whether to book it. Do NOT call uber_confirm in this turn. Summary: " +
            "${choice.label} from $pickup to $destination, ${choice.displayPrice}$eta."
    }

    // ----------------------------------------------------------- confirm

    private suspend fun confirm(context: Context, confirmed: Boolean): String {
        if (!confirmed) {
            UberRideSession.reset()
            return "Ride dropped. Nothing was booked and no money was spent."
        }
        if (!UberRideSession.isActive) {
            return "There's no ride waiting to be booked. If the user wants one, call uber_start."
        }
        if (UberRideSession.phase != UberRideSession.Phase.AWAITING_CONFIRMATION) {
            return "The ride isn't ready yet. Keep answering with uber_reply until you get a summary."
        }

        // A confirm arriving moments after a booking is the same call twice.
        // Booking twice sends two real cars, so this guard is not optional.
        if (System.currentTimeMillis() - lastBookingAt < BOOKING_DEDUPE_MS) {
            Log.w(TAG, "Duplicate confirm ignored")
            return lastSpoken.ifBlank { "The ride is already booked." }
        }

        val choice = UberRideSession.chosenRide
            ?: return "No ride type is chosen. Ask the user which one with uber_reply."
        val pickup = UberRideSession.pickup ?: return "No pickup location. Call uber_start again."
        val destination = UberRideSession.destination ?: return "No destination. Call uber_start again."

        // RE-QUOTE BEFORE BOOKING. The fare_id may have expired and surge may
        // have appeared while the user was deciding. If the price moved, the
        // user agreed to a number that is no longer true, so ask again rather
        // than charging them the new one.
        val fresh = try {
            UberApiClient.estimate(
                context, choice.productId,
                pickup.latitude, pickup.longitude,
                destination.latitude, destination.longitude
            )
        } catch (e: Exception) {
            Log.w(TAG, "Re-quote before booking failed: ${e.message}")
            null
        }

        if (fresh != null && fresh.displayPrice != choice.displayPrice) {
            UberRideSession.updateChosenFare(fresh.displayPrice, fresh.fareId, fresh.pickupEtaMinutes)
            UberRideSession.awaitConfirmation()
            val spoken = "The price changed to ${fresh.displayPrice}. Tell the user the new " +
                "price out loud and ask whether to still book it. Do NOT call uber_confirm " +
                "in this turn."
            lastSpoken = spoken
            return spoken
        }
        if (fresh != null) {
            UberRideSession.updateChosenFare(fresh.displayPrice, fresh.fareId, fresh.pickupEtaMinutes)
        }

        val fareId = UberRideSession.chosenRide?.fareId ?: choice.fareId

        val spoken = try {
            val ride = UberApiClient.requestRide(
                context = context,
                productId = choice.productId,
                fareId = fareId,
                startLat = pickup.latitude,
                startLng = pickup.longitude,
                endLat = destination.latitude,
                endLng = destination.longitude,
                startAddress = pickup.label,
                endAddress = destination.label,
                surgeConfirmationId = UberRideSession.surgeConfirmationId
            )
            lastBookingAt = System.currentTimeMillis()
            UberRideSession.reset()
            "Booked. Tell the user: ${describeRide(ride)}"
        } catch (e: UberApiClient.SurgeException) {
            // Surge appeared. The user agreed to the OLD price, so this needs a
            // fresh yes at the new one — storing the id without asking would
            // charge them the multiplier silently.
            UberRideSession.setSurgeConfirmation(e.surgeConfirmationId)
            UberRideSession.awaitConfirmation()
            "Uber is surge pricing right now at ${e.multiplier} times the normal fare. " +
                "Tell the user that out loud and ask whether to book anyway. Do NOT call " +
                "uber_confirm in this turn."
        } catch (e: UberApiClient.FareExpiredException) {
            UberRideSession.awaitConfirmation()
            "The fare quote expired. Tell the user you're re-checking the price, then " +
                "call uber_confirm again."
        }

        lastSpoken = spoken
        return spoken
    }

    // ------------------------------------------------------------ cancel

    /**
     * Cancels whichever thing is in flight. A half-built ride costs nothing; a
     * booked one may cost a cancellation fee, so the two are reported
     * differently rather than with one vague "cancelled".
     */
    private suspend fun cancel(context: Context): String {
        val booked = UberApiClient.currentRide(context)
        if (booked != null && booked.status !in setOf("completed", "rider_canceled", "driver_canceled")) {
            return try {
                UberApiClient.cancelCurrentRide(context)
                UberRideSession.reset()
                "Tell the user their Uber has been cancelled. Uber may charge a " +
                    "cancellation fee for this."
            } catch (e: Exception) {
                "Tell the user the ride couldn't be cancelled: ${e.message ?: "something went wrong"}."
            }
        }

        UberRideSession.reset()
        return "Ride dropped. Nothing was booked and no money was spent."
    }

    // ------------------------------------------------------------- track

    private suspend fun track(context: Context): String {
        if (!UberTokenStore.isConnected(context)) {
            return "Uber isn't connected yet. Tell the user you need to connect their " +
                "Uber account, then call uber_connect."
        }
        val ride = UberApiClient.currentRide(context)
            ?: return "Tell the user they don't have an Uber on the way right now."
        return "Tell the user: ${describeRide(ride)}"
    }

    /** One ride, as a spoken sentence. */
    private fun describeRide(ride: UberApiClient.Ride): String {
        val parts = mutableListOf<String>()

        parts += when (ride.status) {
            "processing" -> "Uber is still finding a driver"
            "accepted" -> {
                val who = ride.driverName?.let { "$it is on the way" } ?: "a driver is on the way"
                if (ride.etaMinutes > 0) "$who, about ${ride.etaMinutes} minutes out" else who
            }
            "arriving" -> "the driver is arriving now"
            "in_progress" -> "the ride is under way"
            "driver_canceled" -> "the driver cancelled"
            "rider_canceled" -> "the ride was cancelled"
            "completed" -> "the ride is finished"
            "no_drivers_available" -> "no drivers are available right now"
            else -> "the ride status is ${ride.status}"
        }

        ride.vehicleDescription?.let { car ->
            parts += ride.licensePlate?.let { "$car, plate $it" } ?: car
        }

        return parts.joinToString(". ").replaceFirstChar { it.uppercase() } + "."
    }
}
