package com.sdk.glassessdksample.ui.swiggy

import android.util.Log
import org.json.JSONObject

/**
 * One order, gathered a question at a time.
 *
 * A spoken order is never complete in one breath. "Order some milk" leaves
 * open which milk, to which address, and paid how — and Swiggy refuses every
 * one of those as a guess: search needs an addressId, and checkout "REJECTS a
 * call with no payment method". So this holds the half-built order between
 * turns while the model asks for the missing pieces one at a time, the same
 * shape as TaskSession does for browser tasks.
 *
 * Deliberately NOT a copy of TaskSession: that one plans arbitrary web work
 * and needs an LLM planner. Here the required fields are fixed and known from
 * Swiggy's schemas, so the "planner" is just: which field is still empty?
 */
object SwiggyOrderSession {

    private const val TAG = "SwiggyOrderSession"

    enum class Phase {
        /** Nothing in flight. */
        IDLE,

        /** Collecting what the order needs — address, item, quantity, payment. */
        GATHERING,

        /** The full order has been read out and is waiting on yes or no. */
        AWAITING_CONFIRMATION
    }

    /** One choice offered to the user, kept so "the first one" can resolve. */
    data class Choice(
        val id: String,
        val secondaryId: String?,
        val label: String,
        val price: String?
    )

    @Volatile var phase: Phase = Phase.IDLE
        private set

    @Volatile var server: SwiggyMcpClient.Server? = null
        private set

    /** What the user asked for, in their own words ("some milk"). */
    @Volatile var request: String = ""
        private set

    @Volatile var addressId: String? = null
        private set

    @Volatile var addressLabel: String? = null
        private set

    /** The chosen product/dish, once the user has picked from [lastChoices]. */
    @Volatile var chosenItem: Choice? = null
        private set

    @Volatile var quantity: Int = 1
        private set

    /** "Cash" or "UPI" — Swiggy rejects checkout without one. */
    @Volatile var paymentMethod: String? = null
        private set

    /**
     * For UPI: the exact app id from get_payment_options.
     *
     * Swiggy's own words: each method id "MUST be echoed byte-for-byte into
     * the place-order tool's intentApp argument". So the user picking "UPI" is
     * not enough — they have to pick WHICH app, and we must send back Swiggy's
     * id for it rather than the name the user said.
     */
    @Volatile var upiApp: String? = null
        private set

    @Volatile var upiAppLabel: String? = null
        private set

    /** UPI apps last offered, resolved like any other spoken choice. */
    @Volatile var lastUpiApps: List<Choice> = emptyList()
        private set

    /** The cart total, read back before the user confirms. */
    @Volatile var cartTotal: String? = null
        private set

    /** For Food: the restaurant the dish belongs to. */
    @Volatile var restaurantId: String? = null
        private set

    @Volatile var restaurantName: String? = null
        private set

    /** What was last read out, so "the second one" can be resolved next turn. */
    @Volatile var lastChoices: List<Choice> = emptyList()
        private set

    /** Addresses last read out, resolved the same way as [lastChoices]. */
    @Volatile var lastAddresses: List<Choice> = emptyList()
        private set

    @Volatile private var lastActivityAt: Long = 0L

    /** An order left untouched this long is abandoned, not in progress. */
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

    fun begin(what: String, on: SwiggyMcpClient.Server) {
        reset()
        request = searchTermFrom(what)
        server = on
        phase = Phase.GATHERING
        touch()
    }

    /**
     * Replaces what is being searched for, keeping the address already settled.
     *
     * The user changes their mind mid-order far more often than they start
     * over ("actually, Maggi"), and re-asking which address every time would
     * be maddening. Clearing the item sends them back through the product
     * question on the next turn with the new term.
     */
    fun changeRequest(what: String) {
        request = searchTermFrom(what)
        chosenItem = null
        quantity = 1
        lastChoices = emptyList()
        restaurantId = null
        restaurantName = null
        // The total belonged to the old item, so it must be recomputed rather
        // than read back against a product the user never chose.
        cartTotal = null
        ambiguousPick = null
        phase = Phase.GATHERING
        touch()
    }

    /**
     * Reduces what the user said to the thing to search for.
     *
     * Spoken orders are wrapped in words that are not part of the product:
     * "order SOME MILK", "search for milk", "get me some bread please". Passed
     * through as-is, Swiggy searches the literal string "some milk" and finds
     * nothing — which is exactly what happened before this existed.
     */
    fun searchTermFrom(spoken: String): String {
        var s = spoken.lowercase().trim()

        // Leading command verbs, longest first so "search for" goes before "search".
        val leading = listOf(
            "i want to order", "i would like to order", "i want to buy", "i want",
            "can you order", "can you get me", "please order", "please get me",
            "order me", "order some", "order a", "order",
            "search for some", "search for", "search some", "search",
            "buy me some", "buy some", "buy me", "buy",
            "get me some", "get me", "get some", "get",
            "add some", "add"
        )

        // Hindi puts the verb at the END ("doodh mangao", "pizza chahiye"), so
        // those are stripped as suffixes rather than prefixes.
        val trailing = listOf(
            "mangwa do", "manga do", "mangao", "mangwao", "order karo", "order kar do",
            "chahiye", "la do", "lao", "de do", "dila do"
        )
        var changed = true
        while (changed) {
            changed = false
            for (p in leading) {
                if (s.startsWith("$p ")) {
                    s = s.removePrefix("$p ").trim()
                    changed = true
                    break
                }
            }
            for (p in trailing) {
                if (s.endsWith(" $p") || s == p) {
                    s = s.removeSuffix(p).trim()
                    changed = true
                    break
                }
            }
        }

        // Quantity and filler words anywhere in what is left.
        // "on instamart", "from swiggy" say WHERE to look, not WHAT to look
        // for. Left in, they became part of the query and Swiggy searched for
        // a product literally named "milk instamart", which finds nothing.
        val whereClauses = listOf(
            "on instamart", "from instamart", "in instamart", "on insta mart",
            "from insta mart", "on swiggy instamart", "from swiggy instamart",
            "on swiggy", "from swiggy", "in swiggy", "on dineout", "from dineout",
            "se", "par", "pe"
        )
        for (w in whereClauses) {
            if (s.endsWith(" $w")) s = s.removeSuffix(w).trim()
            if (s.startsWith("$w ")) s = s.removePrefix(w).trim()
        }

        val filler = setOf(
            "some", "a", "an", "the", "any", "please", "pls", "me", "my",
            "for", "of", "kuch", "thoda", "zara", "please.",
            // Prepositions left behind once a "where" clause is removed.
            "on", "from", "in", "at", "se", "par", "pe",
            // Words that stand in for a product without naming one, so they
            // must not become the query: "order SOMETHING from Instamart".
            "something", "anything", "stuff", "things", "item", "items",
            // Service names on their own are never the product.
            "instamart", "swiggy", "dineout", "insta", "mart"
        )
        s = s.split(" ")
            .filter { it.isNotBlank() && it.lowercase() !in filler }
            .joinToString(" ")
            .trim()
            .trim('.', ',', '!', '?')

        // Everything was filler or a service name ("order something from
        // Instamart"), so there is no product here at all. Empty is the honest
        // answer; the caller asks the user what they want rather than running
        // a search that cannot match.
        return s
    }

    /** True when [searchTermFrom] found no product to search for. */
    fun hasSearchTerm(): Boolean = request.isNotBlank()

    fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }

    fun setAddress(id: String, label: String) {
        addressId = id
        addressLabel = label
        touch()
    }

    fun setChoices(choices: List<Choice>) {
        lastChoices = choices
        ambiguousPick = null
        touch()
    }

    /**
     * A short answer that matched none of the options on offer.
     *
     * Kept so the question can be re-asked mentioning what they said, rather
     * than either guessing a product or silently repeating the same list.
     */
    @Volatile var ambiguousPick: String? = null
        private set

    fun setAmbiguousPick(spoken: String) {
        // Blank clears it, so the re-ask happens exactly once.
        ambiguousPick = spoken.takeIf { it.isNotBlank() }
        touch()
    }

    fun setAddresses(choices: List<Choice>) {
        lastAddresses = choices
        touch()
    }

    fun setItem(choice: Choice, qty: Int) {
        chosenItem = choice
        quantity = qty.coerceAtLeast(1)
        touch()
    }

    fun setRestaurant(id: String, name: String) {
        restaurantId = id
        restaurantName = name
        touch()
    }

    fun setPayment(method: String) {
        paymentMethod = method
        // Switching away from UPI drops the app that was chosen for it.
        if (method != "UPI") {
            upiApp = null
            upiAppLabel = null
        }
        touch()
    }

    fun setUpiApps(apps: List<Choice>) {
        lastUpiApps = apps
        touch()
    }

    fun setUpiApp(id: String, label: String) {
        upiApp = id
        upiAppLabel = label
        touch()
    }

    fun setCartTotal(total: String?) {
        cartTotal = total
        touch()
    }

    /** True when payment is fully settled: cash, or UPI with an app chosen. */
    fun paymentComplete(): Boolean = when (paymentMethod) {
        null -> false
        "UPI" -> upiApp != null
        else -> true
    }

    /**
     * Clearing one field sends the user back through the question for it on
     * the next turn, leaving everything else they already settled alone.
     */
    fun clearAddress() {
        addressId = null
        addressLabel = null
        lastAddresses = emptyList()
        touch()
    }

    fun clearItem() {
        chosenItem = null
        quantity = 1
        // Same reason as changeRequest: the total was for the old item.
        cartTotal = null
        touch()
    }

    fun clearPayment() {
        paymentMethod = null
        touch()
    }

    fun awaitConfirmation() {
        phase = Phase.AWAITING_CONFIRMATION
        touch()
    }

    fun backToGathering() {
        phase = Phase.GATHERING
        touch()
    }

    fun reset() {
        phase = Phase.IDLE
        server = null
        request = ""
        addressId = null
        addressLabel = null
        chosenItem = null
        quantity = 1
        paymentMethod = null
        restaurantId = null
        restaurantName = null
        lastChoices = emptyList()
        lastAddresses = emptyList()
        // Payment details are per-order: a UPI app or a total carried into the
        // next order would be read back as fact while belonging to the last one.
        upiApp = null
        upiAppLabel = null
        lastUpiApps = emptyList()
        cartTotal = null
        ambiguousPick = null
        lastActivityAt = 0L
    }

    /**
     * The next thing still missing, or null when the order is complete.
     *
     * The order of these checks is the order the user gets asked, and it is
     * not arbitrary: Swiggy's search itself needs an addressId, so the address
     * has to be settled before anything can even be looked up.
     */
    fun missingField(): Field? = when {
        addressId == null -> Field.ADDRESS
        chosenItem == null -> Field.ITEM
        paymentMethod == null -> Field.PAYMENT
        // "UPI" alone cannot be paid with: Swiggy needs the specific app.
        !paymentComplete() -> Field.UPI_APP
        else -> null
    }

    enum class Field { ADDRESS, ITEM, PAYMENT, UPI_APP }

    /**
     * Resolves what the user said against the last list read out to them.
     *
     * People answer with a position ("the first one", "number two", "pehla"),
     * or with words from the label ("the full cream one"), so both are tried.
     */
    fun resolveChoice(spoken: String, from: List<Choice>): Choice? {
        if (from.isEmpty()) return null
        val said = spoken.lowercase().trim()

        val byPosition = when {
            said.contains("first") || said.contains("1st") || said.contains("pehl") ||
                said.contains("one") && said.length < 12 -> 0
            said.contains("second") || said.contains("2nd") || said.contains("dusr") ||
                said.contains("doosr") || said.contains("two") -> 1
            said.contains("third") || said.contains("3rd") || said.contains("teesr") ||
                said.contains("three") -> 2
            said.contains("last") -> from.size - 1
            else -> -1
        }
        if (byPosition in from.indices) return from[byPosition]

        // A bare number: "number 2", "2".
        Regex("\\b(\\d+)\\b").find(said)?.let { m ->
            val idx = m.groupValues[1].toIntOrNull()?.minus(1)
            if (idx != null && idx in from.indices) return from[idx]
        }

        // Otherwise match on the words of the label — but by SCORE, not by
        // first hit. Every milk product contains the word "milk", so a
        // first-match-wins rule handed back whatever happened to be at the top
        // of the list: asking for "Country Delight Cow Milk" silently ordered
        // "Saras Pasteurised Toned Milk". The best overlap has to win, and a
        // single generic word in common is not enough to count as a choice.
        val words = said.split(" ", ",").filter { it.length > 3 }
        if (words.isNotEmpty()) {
            val scored = from.map { c ->
                val label = c.label.lowercase()
                c to words.count { label.contains(it) }
            }.filter { it.second > 0 }.sortedByDescending { it.second }

            val best = scored.firstOrNull()
            if (best != null) {
                val runnerUp = scored.getOrNull(1)
                // Accept only a clear winner: either it matched more words than
                // anything else, or it is the only candidate at all.
                if (runnerUp == null || best.second > runnerUp.second) return best.first
            }
        }
        return null
    }

    /** Reads "cash"/"upi" out of whatever the user said. */
    fun resolvePayment(spoken: String): String? {
        val said = spoken.lowercase()
        return when {
            said.contains("cash") || said.contains("cod") || said.contains("delivery par") ||
                said.contains("nakad") -> "Cash"
            said.contains("upi") || said.contains("gpay") || said.contains("google pay") ||
                said.contains("phonepe") || said.contains("paytm") || said.contains("online") -> "UPI"
            else -> null
        }
    }

    /**
     * A spoken-style summary of the order, read back before confirming.
     *
     * The TOTAL matters more than any other part: it is the number the user is
     * agreeing to spend, and Swiggy orders cannot be cancelled from here. The
     * cart total is preferred over the item's own price because it includes
     * delivery and fees, which the shelf price does not.
     */
    fun summary(): String {
        val item = chosenItem
        val parts = mutableListOf<String>()
        if (item != null) {
            val qty = if (quantity > 1) "$quantity x " else ""
            parts.add("$qty${item.label}${item.price?.let { " at $it each" } ?: ""}")
        }
        restaurantName?.let { parts.add("from $it") }
        addressLabel?.let { parts.add("to $it") }

        // Total last but one, right before how it is being paid, so the two
        // money facts are spoken together.
        cartTotal?.let { parts.add("total $it") }

        paymentMethod?.let {
            val how = when {
                it == "Cash" -> "cash on delivery"
                upiAppLabel != null -> "UPI using $upiAppLabel"
                else -> "UPI"
            }
            parts.add("paying by $how")
        }
        return parts.joinToString(", ")
    }

    /**
     * A short spoken label for an address.
     *
     * These get read out loud as a numbered list, so the tag ("HOME", "Work")
     * is what actually distinguishes them to the ear — a full street address
     * read out four times is unusable. The locality is added only when it
     * helps tell two addresses apart, and nothing is cut mid-word.
     */
    fun labelForAddress(obj: JSONObject): String {
        val tag = obj.optString("addressTag").takeIf { it.isNotBlank() }
            ?: obj.optString("addressCategory").takeIf { it.isNotBlank() }
        val area = obj.optString("locality").takeIf { it.isNotBlank() }
            ?: obj.optString("city").takeIf { it.isNotBlank() }
            ?: obj.optString("addressLine").takeIf { it.isNotBlank() }?.let { trimToWords(it) }
        return listOfNotNull(tag, area).joinToString(", ").ifBlank { "Saved address" }
    }

    /** Trims to roughly [max] characters without cutting a word in half. */
    private fun trimToWords(text: String, max: Int = 34): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace > 10) cut.take(lastSpace) else cut
    }
}
