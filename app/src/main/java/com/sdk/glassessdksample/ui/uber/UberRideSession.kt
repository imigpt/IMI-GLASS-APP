package com.sdk.glassessdksample.ui.uber

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * One ride, gathered a question at a time.
 *
 * Same job as SwiggyOrderSession and the same reasoning: a spoken request is
 * never complete in one breath. "Book me an Uber to the airport" does not say
 * which airport, from where, or which ride type — and Uber refuses all three as
 * guesses, since product_id is location-specific and the fare has to be quoted
 * before the user can agree to it.
 *
 * WHAT IS DIFFERENT FROM THE SWIGGY SESSION, and why:
 *
 *  - PICKUP IS NOT ASKED FOR. Swiggy picks from a fixed list of saved
 *    addresses, so asking is the only option. Here the phone already knows
 *    where the user is, and on glasses the whole point is to say fewer words —
 *    so pickup comes from GPS and is only asked about if GPS fails. The user
 *    still HEARS the pickup in the summary, so a wrong GPS fix is catchable
 *    before any money moves.
 *
 *  - THE PRICE CAN GO STALE. A Swiggy cart total holds. An Uber fare_id
 *    expires, and surge can appear between the quote and the booking. So
 *    [Phase.AWAITING_CONFIRMATION] is not a promise the price still stands, and
 *    UberTools re-quotes rather than assuming.
 *
 *  - THERE IS NO PAYMENT QUESTION. Uber charges whatever card is already on the
 *    rider's account. Nothing to collect, and nothing we could collect safely
 *    out loud anyway.
 */
object UberRideSession {

    private const val TAG = "UberRideSession"

    enum class Phase {
        /** Nothing in flight. */
        IDLE,

        /** Collecting what the ride needs — destination, then ride type. */
        GATHERING,

        /** The ride has been read out with a price and is waiting on yes or no. */
        AWAITING_CONFIRMATION
    }

    /** One ride type offered to the user, kept so "the first one" can resolve. */
    data class RideChoice(
        val productId: String,
        val label: String,
        val displayPrice: String,
        val etaMinutes: Int,
        val fareId: String?
    )

    data class Place(
        val latitude: Double,
        val longitude: Double,
        val label: String
    )

    @Volatile var phase: Phase = Phase.IDLE
        private set

    /** What the user asked for, in their own words ("to the airport"). */
    @Volatile var request: String = ""
        private set

    @Volatile var pickup: Place? = null
        private set

    @Volatile var destination: Place? = null
        private set

    @Volatile var chosenRide: RideChoice? = null
        private set

    /** Ride types last read out, so "the second one" resolves next turn. */
    @Volatile var lastChoices: List<RideChoice> = emptyList()
        private set

    /**
     * Set only after the user has been TOLD about surge and agreed to it.
     * Passing this to the API without that conversation would defeat the point
     * of Uber raising the 409 at all.
     */
    @Volatile var surgeConfirmationId: String? = null
        private set

    @Volatile private var lastActivityAt: Long = 0L

    /** A ride left untouched this long is abandoned, not in progress. */
    private const val STALE_AFTER_MS = 10 * 60 * 1000L

    val isActive: Boolean
        get() {
            if (phase == Phase.IDLE) return false
            if (System.currentTimeMillis() - lastActivityAt > STALE_AFTER_MS) {
                Log.d(TAG, "Session went stale, resetting")
                reset()
                return false
            }
            return true
        }

    // ------------------------------------------------------------- mutation

    fun start(request: String) {
        reset()
        this.request = request
        phase = Phase.GATHERING
        touch()
    }

    fun setPickup(place: Place) {
        pickup = place
        touch()
    }

    fun setDestination(place: Place) {
        destination = place
        touch()
    }

    fun setChoices(choices: List<RideChoice>) {
        lastChoices = choices
        touch()
    }

    fun setChosenRide(choice: RideChoice) {
        chosenRide = choice
        touch()
    }

    /** Re-quoted fares replace the old ones; the product stays chosen. */
    fun updateChosenFare(displayPrice: String, fareId: String?, etaMinutes: Int) {
        chosenRide = chosenRide?.copy(
            displayPrice = displayPrice,
            fareId = fareId,
            etaMinutes = etaMinutes
        )
        touch()
    }

    fun setSurgeConfirmation(id: String?) {
        surgeConfirmationId = id
        touch()
    }

    fun awaitConfirmation() {
        phase = Phase.AWAITING_CONFIRMATION
        touch()
    }

    /** Back to gathering, e.g. the user wants a different ride type after the summary. */
    fun backToGathering() {
        phase = Phase.GATHERING
        touch()
    }

    fun reset() {
        phase = Phase.IDLE
        request = ""
        pickup = null
        destination = null
        chosenRide = null
        lastChoices = emptyList()
        surgeConfirmationId = null
        lastActivityAt = 0L
    }

    private fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }

    // -------------------------------------------------------------- resolve

    /**
     * Maps what the user said onto one of the ride types just read out.
     *
     * Same problem SwiggyOrderSession.resolveChoice solves: the user answers
     * "the first one" or "Go" rather than repeating a product name, and
     * treating that as a fresh request sends nonsense to the API.
     */
    fun resolveChoice(answer: String, choices: List<RideChoice>): RideChoice? {
        if (choices.isEmpty()) return null
        val said = answer.lowercase(Locale.ROOT).trim()

        // Ordinals, in English and the Hindi a user is likely to speak.
        val ordinals = listOf(
            listOf("first", "1st", "one", "pehla", "pehle", "ek"),
            listOf("second", "2nd", "two", "dusra", "dusre", "do"),
            listOf("third", "3rd", "three", "teesra", "teesre", "teen"),
            listOf("fourth", "4th", "four", "chautha", "char")
        )
        ordinals.forEachIndexed { index, words ->
            if (words.any { said == it || said.startsWith("$it ") || said.contains(" $it ") }) {
                choices.getOrNull(index)?.let { return it }
            }
        }

        // "the cheapest" / "sasta" — the list is already price-ordered by the
        // caller, but re-deriving is safer than trusting that.
        if (said.contains("cheap") || said.contains("sasta") || said.contains("kam")) {
            return choices.minByOrNull { priceValue(it.displayPrice) ?: Double.MAX_VALUE }
        }

        // Exact or contained label match: "UberX", "Go", "premier".
        choices.firstOrNull { it.label.equals(said, ignoreCase = true) }?.let { return it }
        choices.firstOrNull { said.contains(it.label.lowercase(Locale.ROOT)) }?.let { return it }

        // The distinguishing word of a label — "Go" out of "Uber Go".
        choices.firstOrNull { choice ->
            choice.label.split(" ")
                .filter { it.length > 1 && !it.equals("uber", ignoreCase = true) }
                .any { said.contains(it.lowercase(Locale.ROOT)) }
        }?.let { return it }

        return null
    }

    /** Pulls a number out of "₹340.50" so prices can be compared. */
    private fun priceValue(display: String): Double? =
        Regex("[0-9]+(\\.[0-9]+)?").find(display.replace(",", ""))?.value?.toDoubleOrNull()

    /** True when the user clearly agreed. Mirrors the Swiggy confirm wording. */
    fun isAffirmative(answer: String): Boolean {
        val said = answer.lowercase(Locale.ROOT).trim()
        return listOf(
            "yes", "yeah", "yep", "sure", "ok", "okay", "go ahead", "book it",
            "do it", "confirm", "haan", "han", "ha", "theek hai", "thik hai",
            "kar do", "karo", "book karo", "bilkul"
        ).any { said == it || said.startsWith("$it ") || said.contains(it) }
    }

    // ------------------------------------------------------------- location

    /**
     * The phone's current position, as the ride's pickup.
     *
     * getLastKnownLocation rather than requesting a fresh fix: a fresh fix can
     * take 30 seconds outdoors and forever indoors, and the user is waiting
     * mid-sentence. A last-known fix is nearly always good enough for a pickup
     * pin, and the user hears the address in the summary before anything is
     * booked, so a stale one is catchable.
     *
     * Returns null when permission is missing or no provider has a fix — the
     * caller then ASKS for the pickup instead of failing.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(context: Context): Place? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "No location permission")
            return@withContext null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null

        val best: Location? = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { provider ->
                    try { manager.getLastKnownLocation(provider) } catch (e: Exception) { null }
                }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }

        if (best == null) {
            Log.w(TAG, "No last-known location from any provider")
            return@withContext null
        }

        Place(
            latitude = best.latitude,
            longitude = best.longitude,
            label = reverseGeocode(context, best.latitude, best.longitude) ?: "your current location"
        )
    }

    fun hasLocationPermission(context: Context): Boolean {
        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Turns what the user SAID into coordinates.
     *
     * Biased toward the pickup point when there is one: "the airport" means the
     * airport in this city, and an unbiased geocode happily returns one three
     * countries away. Geocoder's bounded overload takes that box.
     */
    @Suppress("DEPRECATION")
    suspend fun geocode(context: Context, spoken: String, near: Place?): Place? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "No geocoder on this device")
            return@withContext null
        }
        // Geocoder does network I/O with no timeout of its own and can hang.
        withTimeoutOrNull(12_000L) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val results = if (near != null) {
                    // Roughly a 100km box around the pickup.
                    val d = 0.9
                    geocoder.getFromLocationName(
                        spoken, 5,
                        near.latitude - d, near.longitude - d,
                        near.latitude + d, near.longitude + d
                    )
                } else {
                    geocoder.getFromLocationName(spoken, 5)
                }

                val hit = results?.firstOrNull() ?: return@withTimeoutOrNull null
                Place(
                    latitude = hit.latitude,
                    longitude = hit.longitude,
                    // featureName is usually the place's actual name ("Kempegowda
                    // International Airport"); the address line is the fallback.
                    label = hit.featureName?.takeIf { it.length > 3 }
                        ?: hit.getAddressLine(0)
                        ?: spoken
                )
            } catch (e: Exception) {
                Log.w(TAG, "Geocode failed for '$spoken': ${e.message}")
                null
            }
        }
    }

    /** Coordinates to something speakable, for the summary. */
    @Suppress("DEPRECATION")
    private fun reverseGeocode(context: Context, lat: Double, lng: Double): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val results = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lng, 1)
            val hit = results?.firstOrNull() ?: return null
            // Thoroughfare is the street, which is what a pickup actually needs.
            hit.thoroughfare?.let { street ->
                hit.subLocality?.let { "$street, $it" } ?: street
            } ?: hit.getAddressLine(0)
        } catch (e: Exception) {
            null
        }
    }
}
