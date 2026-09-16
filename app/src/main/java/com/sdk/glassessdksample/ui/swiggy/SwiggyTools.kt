package com.sdk.glassessdksample.ui.swiggy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Swiggy, exposed to the glasses voice session as a CONVERSATION.
 *
 * Same contract as GlassBrowserTools: the user speaks, the live model picks a
 * tool, these functions run, and the string returned here is read out loud. So
 * every return value is written as speech — short, no markup, no JSON.
 *
 * WHY THIS IS A CONVERSATION AND NOT ONE CALL
 * Nobody says a complete order in one breath. "Order some milk" does not say
 * which milk, to which address, or paid how — and Swiggy refuses all three as
 * guesses: search itself requires an addressId, and checkout in Swiggy's own
 * words "REJECTS a call with no payment method". So instead of one big tool,
 * this is [swiggy_start] followed by [swiggy_reply] as many times as it takes,
 * with [SwiggyOrderSession] holding the half-built order between turns. Same
 * shape as start_task / task_answer next door.
 *
 * The tool names below are Swiggy's real ones, read off a live server with
 * SwiggyMcpClient.dumpAllSchemas. They are not guessable — "add to cart" is
 * update_cart, "place order" is checkout on Instamart but place_food_order on
 * Food — so when they change, re-run the dump rather than guessing again.
 */
object SwiggyTools {

    private const val TAG = "SwiggyTools"

    /**
     * Serialises the order conversation against itself.
     *
     * Gemini Live can deliver one tool call twice, and two concurrent replies
     * would advance the order twice — picking an item, then paying for it, off
     * a single "yes". The browser tools hit this exact problem; see
     * GlassBrowserTools.taskMutex.
     */
    private val orderMutex = Mutex()

    /** Last reply handled, to recognise a redelivery of the same call. */
    @Volatile private var lastReplyHandled: String? = null
    @Volatile private var lastReplyAt: Long = 0L
    @Volatile private var lastSpoken: String = ""

    /** Two identical replies closer together than this are one call, twice. */
    private const val DUPLICATE_WINDOW_MS = 8_000L

    val TOOL_NAMES = setOf(
        "swiggy_connect",
        "swiggy_start",
        "swiggy_reply",
        "swiggy_confirm",
        "swiggy_cancel",
        "swiggy_track",
        "swiggy_dump_schemas"
    )

    // -------------------------------------------------------- declarations

    fun declarations(): List<Map<String, Any>> = listOf(
        mapOf(
            "type" to "function",
            "name" to "swiggy_start",
            "description" to
                "Begin ordering something from Swiggy. Call this the moment the user " +
                "asks to order food or groceries - 'order some milk', 'I want biryani', " +
                "'get me groceries', 'doodh mangao', 'khana order karo', 'book a table'. " +
                "You do NOT need any details first: this tool starts a conversation and " +
                "returns the NEXT QUESTION to ask the user out loud, such as which " +
                "address to deliver to or which of several products they meant. Ask that " +
                "question exactly as given, ONE at a time, then pass their answer to " +
                "swiggy_reply. Set service to 'instamart' for groceries and household " +
                "items, 'food' for restaurant meals, 'dineout' to book a table.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "request" to mapOf(
                        "type" to "string",
                        "description" to "What the user wants, in their own words - 'some milk', 'chicken biryani'"
                    ),
                    "service" to mapOf(
                        "type" to "string",
                        "description" to "One of: instamart, food, dineout"
                    )
                ),
                "required" to listOf("request", "service")
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "swiggy_reply",
            "description" to
                "Pass the user's answer to the question the order just asked. Call this " +
                "every time the user answers something while an order is being put " +
                "together - 'the first one', 'two packets', 'home', 'cash', 'dusra " +
                "wala'. It returns either the NEXT question to ask out loud, or the " +
                "finished order summary to read back. When it returns a summary, read it " +
                "out and ask whether to place it - do NOT call swiggy_confirm in the " +
                "same turn.",
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
            "name" to "swiggy_confirm",
            "description" to
                "Place the order that was read back to the user. THIS SPENDS THE USER'S " +
                "MONEY AND CANNOT BE UNDONE - Swiggy does not allow cancelling through " +
                "this app. Call it ONLY in a turn AFTER you read the full order summary " +
                "out loud and the user explicitly agreed - 'yes', 'place it', 'go " +
                "ahead', 'haan', 'kar do'. Never call it in the same turn as the " +
                "summary, and never on a guess. If they want something changed, do not " +
                "call this - call swiggy_reply with what they want changed.",
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
            "name" to "swiggy_cancel",
            "description" to
                "Abandon the order being put together. Call this when the user says to " +
                "forget it, stop, cancel, 'rehne do', 'chodo'. Nothing is ordered and no " +
                "money is spent. This does NOT cancel an order already placed - for " +
                "that the user must call Swiggy on 080-67466729.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "swiggy_track",
            "description" to
                "Check where an already-placed Swiggy order has got to. Use for 'where " +
                "is my order', 'has it left', 'kitni der lagegi', 'track my order'.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "service" to mapOf(
                        "type" to "string",
                        "description" to "One of: instamart, food"
                    )
                ),
                "required" to listOf("service")
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "swiggy_connect",
            "description" to
                "Connect the user's Swiggy account. Call this ONLY when another Swiggy " +
                "tool says the account is not connected, or the user asks to sign in to " +
                "Swiggy. It opens a sign-in page in the phone's browser which the user " +
                "finishes themselves - never ask for a password, an OTP or card details " +
                "out loud. Tell them to check their phone, then wait.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>()
            )
        ),
        mapOf(
            "type" to "function",
            "name" to "swiggy_dump_schemas",
            "description" to
                "Developer diagnostic. Call ONLY if the user literally says 'dump swiggy " +
                "schemas' or 'debug swiggy tools'. Writes Swiggy's real tool list to the " +
                "phone log and orders nothing.",
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
                "swiggy_connect" -> connect(context)
                "swiggy_start" -> start(
                    context,
                    args["request"]?.toString().orEmpty(),
                    args["service"]?.toString().orEmpty()
                )
                "swiggy_reply" -> reply(context, args["answer"]?.toString().orEmpty())
                "swiggy_confirm" -> confirm(
                    context,
                    args["confirmed"]?.toString()?.toBooleanStrictOrNull() ?: false
                )
                "swiggy_cancel" -> {
                    SwiggyOrderSession.reset()
                    "Order dropped. Nothing was placed and no money was spent."
                }
                "swiggy_track" -> track(context, args["service"]?.toString().orEmpty())
                "swiggy_dump_schemas" -> {
                    SwiggyMcpClient.dumpAllSchemas(context)
                    "Wrote Swiggy's tool list to the log."
                }
                else -> "That Swiggy action isn't available."
            }
        } catch (e: SwiggyMcpClient.NotConnectedException) {
            // An instruction rather than an error, so the model asks the user
            // to connect instead of announcing a failure.
            "Swiggy isn't connected yet. Tell the user you need to connect their " +
                "Swiggy account, then call swiggy_connect."
        } catch (e: Exception) {
            Log.e(TAG, "Swiggy tool '$toolName' failed", e)
            "Swiggy couldn't do that: ${e.message ?: "something went wrong"}."
        }
    }

    // ----------------------------------------------------------- connect

    private suspend fun connect(context: Context): String {
        if (SwiggyTokenStore.isConnected(context) && !SwiggyTokenStore.isExpired(context)) {
            return "Swiggy is already connected."
        }
        SwiggyMcpClient.resetSessions()
        val result = SwiggyAuth.login(context)
        return if (result.success) {
            "Swiggy is connected now. Ask the user what they would like to order."
        } else {
            "${result.message} Tell the user to finish signing in on their phone, " +
                "then say done when they have."
        }
    }

    // ------------------------------------------------------- conversation

    private suspend fun start(context: Context, request: String, service: String): String {
        if (request.isBlank()) return "Sure - ask the user what they would like to order."
        val server = serverFor(service)
            ?: return "Ask the user whether they mean groceries, a restaurant meal, or a table booking."

        return orderMutex.withLock {
            SwiggyOrderSession.begin(request, server)
            nextTurn(context)
        }
    }

    private suspend fun reply(context: Context, answer: String): String {
        if (!SwiggyOrderSession.isActive) {
            return "There's no order on the go. Ask the user what they'd like to order."
        }
        if (answer.isBlank()) return "Sorry, I didn't catch that."

        return orderMutex.withLock {
            // The SAME answer arriving twice is a duplicate delivery, not the
            // user repeating themselves — the live socket has several dispatch
            // paths and one call can arrive down more than one. Without this,
            // an item could be chosen and then paid for off a single "yes".
            if (answer.trim().equals(lastReplyHandled, ignoreCase = true) &&
                System.currentTimeMillis() - lastReplyAt < DUPLICATE_WINDOW_MS
            ) {
                Log.d(TAG, "Ignoring duplicate reply: $answer")
                return@withLock lastSpoken
            }
            lastReplyHandled = answer.trim()
            lastReplyAt = System.currentTimeMillis()

            // A reply while the summary is on the table is a CHANGE, not a yes:
            // a plain yes would have come through swiggy_confirm.
            if (SwiggyOrderSession.phase == SwiggyOrderSession.Phase.AWAITING_CONFIRMATION) {
                applyChange(answer)
            } else {
                applyAnswer(answer)
            }
            nextTurn(context).also { lastSpoken = it }
        }
    }

    /**
     * Files the user's answer against whatever was being asked.
     *
     * An answer that does not resolve to one of the choices offered is NOT
     * noise to be dropped: at the item question it is the user naming
     * something else to look for ("Maggi", "search for milk"). Dropping it
     * left the session re-running the original search forever, so an
     * unresolved answer becomes the new search term instead.
     */
    private fun applyAnswer(answer: String) {
        val session = SwiggyOrderSession
        when (session.missingField()) {
            SwiggyOrderSession.Field.ADDRESS -> {
                session.resolveChoice(answer, session.lastAddresses)?.let {
                    session.setAddress(it.id, it.label)
                }
                // An unresolved address answer is left alone: the addresses are
                // a fixed list the user must pick from, so the question simply
                // gets asked again rather than being treated as a new one.
            }
            SwiggyOrderSession.Field.ITEM -> {
                val choice = session.resolveChoice(answer, session.lastChoices)
                if (choice != null) {
                    val qty = Regex("\\b(\\d+)\\b").find(answer)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    session.setItem(choice, qty)
                } else if (session.lastChoices.isNotEmpty() &&
                    answer.trim().split(" ").size <= 2
                ) {
                    // A one or two word answer while options are on the table is
                    // the user trying to PICK one ("Gold" meaning Amul Gold), not
                    // naming a new product. Treating it as a fresh search sent
                    // "Gold" to Swiggy and came back with gold jewellery. Ask
                    // again instead of searching for the wrong thing.
                    Log.d(TAG, "Ambiguous pick '$answer' - re-asking rather than searching")
                    session.setAmbiguousPick(answer)
                } else {
                    // A longer phrase with no match is a different product.
                    Log.d(TAG, "Unmatched item answer '$answer' - treating as a new search")
                    session.changeRequest(answer)
                }
            }
            SwiggyOrderSession.Field.UPI_APP -> {
                session.resolveChoice(answer, session.lastUpiApps)?.let {
                    session.setUpiApp(it.id, it.label)
                } ?: run {
                    // "cash" here is the user changing their mind away from UPI.
                    if (session.resolvePayment(answer) == "Cash") session.setPayment("Cash")
                }
            }
            SwiggyOrderSession.Field.PAYMENT -> {
                val method = session.resolvePayment(answer)
                if (method != null) {
                    session.setPayment(method)
                } else if (session.resolveChoice(answer, session.lastChoices) == null &&
                    answer.trim().split(" ").size <= 4
                ) {
                    // Neither a payment method nor one of the items: the user is
                    // switching product late ("actually, make it Maggi").
                    Log.d(TAG, "Unmatched payment answer '$answer' - treating as a new search")
                    session.changeRequest(answer)
                }
            }
            null -> Unit
        }
    }

    /**
     * The user wants something different after hearing the summary.
     *
     * Clearing the field they named sends them back through the question for
     * it on the next turn, with everything else they already settled intact.
     */
    private fun applyChange(answer: String) {
        val session = SwiggyOrderSession
        session.backToGathering()
        val said = answer.lowercase()
        when {
            // A payment word is unambiguous, so take it as the new method.
            session.resolvePayment(answer) != null ->
                session.setPayment(session.resolvePayment(answer)!!)

            said.contains("address") || said.contains("pata") || said.contains("deliver") ->
                session.clearAddress()

            said.contains("payment") || said.contains("pay") ->
                session.clearPayment()

            // Anything else is a correction to the item. Check it against the
            // options already read out BEFORE clearing them — "make it the
            // second one" is picking from that same list, and clearing first
            // would throw away the list the answer refers to.
            else -> {
                val fromSameList = session.resolveChoice(answer, session.lastChoices)
                if (fromSameList != null) {
                    val qty = Regex("\\b(\\d+)\\b").find(answer)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    session.setItem(fromSameList, qty)
                } else {
                    session.changeRequest(answer)
                }
            }
        }
    }

    /**
     * Works out what is still missing and returns the question to ask — or,
     * when nothing is missing, the summary to read back.
     */
    private suspend fun nextTurn(context: Context): String {
        val session = SwiggyOrderSession
        val server = session.server ?: return "Ask the user what they'd like to order."

        return when (session.missingField()) {
            SwiggyOrderSession.Field.ADDRESS -> askForAddress(context, server)
            SwiggyOrderSession.Field.ITEM -> askForItem(context, server)
            SwiggyOrderSession.Field.PAYMENT -> askForPayment(context, server)
            SwiggyOrderSession.Field.UPI_APP -> askForUpiApp(context, server)
            null -> {
                // Build the cart NOW, before confirming, for two reasons: the
                // user must hear the REAL total (delivery and fees included)
                // before agreeing to spend it, and a cart that Swiggy rejects
                // should fail here — where it can be fixed by talking — rather
                // than at the moment of payment.
                val total = buildCartAndGetTotal(context, server)
                session.setCartTotal(total)
                session.awaitConfirmation()
                "Ready to order: ${session.summary()}. Read ALL of this back to the " +
                    "user, including the total, and ask whether to place it. Do NOT " +
                    "place it in this turn."
            }
        }
    }

    /**
     * Puts the chosen item in the Swiggy cart and returns the payable total.
     *
     * Returns null rather than throwing when the total cannot be read: a
     * missing total is worth mentioning, but it should not block an order the
     * user has otherwise fully specified.
     */
    private suspend fun buildCartAndGetTotal(
        context: Context,
        server: SwiggyMcpClient.Server
    ): String? {
        val session = SwiggyOrderSession
        val item = session.chosenItem ?: return null
        val addressId = session.addressId ?: return null

        lastCartError = null
        return try {
            when (server) {
                SwiggyMcpClient.Server.INSTAMART -> {
                    // update_cart REPLACES the cart, so the whole order goes in
                    // one call — adding items one at a time would drop the rest.
                    val items = JSONArray().put(JSONObject().apply {
                        put("spinId", item.id)
                        item.secondaryId?.let { put("skuId", it) }
                        put("quantity", session.quantity)
                    })
                    val cart = SwiggyMcpClient.callToolRaw(
                        context, server, "update_cart",
                        mapOf("items" to items, "selectedAddressId" to addressId)
                    )
                    extractTotal(cart)
                }
                SwiggyMcpClient.Server.FOOD -> {
                    val restaurantId = session.restaurantId ?: return null
                    val cartItems = JSONArray().put(JSONObject().apply {
                        put("menu_item_id", item.id)
                        put("quantity", session.quantity)
                    })
                    val cart = SwiggyMcpClient.callToolRaw(
                        context, server, "update_food_cart",
                        mapOf(
                            "restaurantId" to restaurantId,
                            "cartItems" to cartItems,
                            "addressId" to addressId
                        )
                    )
                    extractTotal(cart)
                }
                SwiggyMcpClient.Server.DINEOUT -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not build cart for total: ${e.message}")
            // Keep the reason: the caller turns it into something the user can
            // act on, instead of it surfacing later as a wrong diagnosis.
            lastCartError = e.message?.substringBefore("Report ID")?.trim()?.take(120)
                ?: "the cart was rejected"
            null
        }
    }

    /** Digs the payable amount out of a cart reply. */
    private fun extractTotal(cart: JSONObject?): String? {
        if (cart == null) return null
        val keys = listOf(
            "total", "grandTotal", "payableAmount", "toPay", "finalAmount",
            "billTotal", "totalAmount", "cartTotal"
        )
        fun search(node: Any?, depth: Int): String? {
            if (depth > 6) return null
            when (node) {
                is JSONObject -> {
                    for (k in keys) {
                        val v = node.opt(k)
                        if (v != null && v != JSONObject.NULL) {
                            val s = v.toString()
                            if (s.isNotBlank() && s != "0" && s != "null") return s
                        }
                    }
                    node.keys().forEach { k -> search(node.opt(k), depth + 1)?.let { return it } }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        search(node.opt(i), depth + 1)?.let { return it }
                    }
                }
            }
            return null
        }
        return search(cart, 0)?.let { raw ->
            // Swiggy returns paise in some fields and rupees in others; a
            // four-figure "amount" for one milk packet is paise.
            val n = raw.toDoubleOrNull()
            when {
                n == null -> raw
                n > 1000 && n % 100.0 == 0.0 -> "₹${(n / 100).toInt()}"
                else -> "₹${n.toInt()}"
            }
        }
    }

    /**
     * Swiggy will not search without an addressId, so this is always first.
     * One saved address is used without asking; several means a question.
     */
    private suspend fun askForAddress(context: Context, server: SwiggyMcpClient.Server): String {
        val tool = if (server == SwiggyMcpClient.Server.DINEOUT) "get_saved_locations" else "get_addresses"
        val raw = SwiggyMcpClient.callToolRaw(context, server, tool)
        val addresses = parseAddresses(raw)

        if (addresses.isEmpty()) {
            return "The user has no saved delivery address on Swiggy. Tell them they " +
                "need to add one in the Swiggy app first, then try again."
        }
        if (addresses.size == 1) {
            // Swiggy's own guidance: a single address is unambiguous, so use it
            // and just say which one.
            SwiggyOrderSession.setAddress(addresses[0].id, addresses[0].label)
            return nextTurn(context)
        }
        SwiggyOrderSession.setAddresses(addresses)
        val list = addresses.take(4).mapIndexed { i, a -> "${i + 1}. ${a.label}" }.joinToString("; ")
        return "Ask the user which address to deliver to, reading these out: $list. " +
            "Ask only this, then wait."
    }

    /** Searches with the now-known address and offers the results. */
    private suspend fun askForItem(context: Context, server: SwiggyMcpClient.Server): String {
        val session = SwiggyOrderSession
        val addressId = session.addressId ?: return askForAddress(context, server)

        // The user tried to pick and we could not tell which. Re-ask against
        // the SAME list rather than searching again — searching for what they
        // said is how "Gold" (meaning Amul Gold milk) returned gold jewellery.
        session.ambiguousPick?.let { said ->
            session.setAmbiguousPick("")
            val list = session.lastChoices.take(3).mapIndexed { i, c ->
                "${i + 1}. ${c.label}${c.price?.let { " at $it" } ?: ""}"
            }.joinToString("; ")
            return "Could not tell which one \"$said\" means. Read the list out again " +
                "and ask the user to say the NUMBER of the one they want: $list"
        }

        // "order something from Instamart" names the shop but no product, so
        // there is nothing to search for yet. Ask, rather than running a query
        // that cannot match anything.
        if (!session.hasSearchTerm()) {
            return "Ask the user WHAT they would like to order - they named the " +
                "service but not the item. Ask only this, then pass their answer " +
                "to swiggy_reply."
        }

        val raw = when (server) {
            SwiggyMcpClient.Server.INSTAMART -> SwiggyMcpClient.callToolRaw(
                context, server, "search_products",
                mapOf("query" to session.request, "addressId" to addressId)
            )
            SwiggyMcpClient.Server.FOOD -> SwiggyMcpClient.callToolRaw(
                context, server, "search_restaurants",
                mapOf("query" to session.request, "addressId" to addressId)
            )
            SwiggyMcpClient.Server.DINEOUT -> SwiggyMcpClient.callToolRaw(
                context, server, "search_restaurants_dineout",
                mapOf("query" to session.request)
            )
        }

        val choices = parseChoices(raw, server)
        if (choices.isEmpty()) {
            // Swiggy's result shape is not documented and differs per server, so
            // when nothing parses, log the payload rather than guessing at it.
            //   adb logcat -s SwiggyShape
            Log.i("SwiggyShape", "EMPTY PARSE for ${server.slug} q='${session.request}'")
            Log.i("SwiggyShape", "keys=${raw?.keys()?.asSequence()?.toList()}")
            raw?.toString()?.chunked(3500)?.forEachIndexed { i, part ->
                Log.i("SwiggyShape", "[$i] $part")
            }
            // Say what was actually searched for, not what the user said: the
            // two differ ("order some milk" searches "milk"), and the model
            // needs the real term to suggest a sensible alternative.
            return "Nothing on Swiggy matched \"${session.request}\". Tell the user " +
                "that, and ask what else they would like. Pass whatever they say " +
                "next to swiggy_reply and it will be searched for."
        }
        session.setChoices(choices)
        val list = choices.take(3).mapIndexed { i, c ->
            "${i + 1}. ${c.label}${c.price?.let { " at $it" } ?: ""}"
        }.joinToString("; ")
        return "Ask the user which one they want, reading these out: $list. Ask only " +
            "this, then wait."
    }

    /**
     * Swiggy rejects checkout without a payment method — never defaulted, and
     * cash has to be confirmed out loud.
     *
     * The cart has to exist before the options are meaningful, so this builds
     * it first and asks against what Swiggy actually offers this user: UPI is
     * omitted entirely for users who are not eligible, and offering it to them
     * would strand the order at payment.
     */
    private suspend fun askForPayment(context: Context, server: SwiggyMcpClient.Server): String {
        val session = SwiggyOrderSession
        if (session.cartTotal == null) {
            session.setCartTotal(buildCartAndGetTotal(context, server))
        }

        // A cart that would not build is the real failure, and it must not be
        // reported as "UPI unavailable": with an empty cart Swiggy offers no
        // payment methods at all, so the absence of UPI says nothing about the
        // user's account. Surface the cart problem instead of hiding it behind
        // a payment question the user cannot usefully answer.
        if (lastCartError != null) {
            val why = lastCartError
            lastCartError = null
            session.clearItem()
            return "That item could not be added to the Swiggy cart ($why). Tell the " +
                "user it is unavailable, and ask what else they would like instead."
        }

        val options = loadPaymentOptions(context, server)
        val totalPart = session.cartTotal?.let { " The total is $it." } ?: ""

        return if (options.upiApps.isEmpty() && options.codAvailable) {
            "UPI isn't available on this Swiggy account, so cash on delivery is the " +
                "only option.$totalPart Tell the user that and ask them to confirm " +
                "they want to pay cash."
        } else {
            "Ask the user how they want to pay - cash on delivery, or UPI.$totalPart " +
                "Ask only this, then wait."
        }
    }

    /** Why the last cart build failed, so it can be reported instead of guessed at. */
    @Volatile private var lastCartError: String? = null

    /**
     * UPI needs the specific app, not just "UPI".
     *
     * Swiggy's own words: each method id "MUST be echoed byte-for-byte into
     * the place-order tool's intentApp argument". Sending "UPI" with no app is
     * what emptied the cart at checkout.
     */
    private suspend fun askForUpiApp(context: Context, server: SwiggyMcpClient.Server): String {
        val session = SwiggyOrderSession
        val options = loadPaymentOptions(context, server)

        if (options.upiApps.isEmpty()) {
            // UPI is not actually available, so do not leave the order stuck
            // on a question the user cannot answer.
            session.setPayment("Cash")
            return "UPI isn't available on this Swiggy account. Tell the user that and " +
                "ask whether cash on delivery is alright."
        }
        session.setUpiApps(options.upiApps)
        if (options.upiApps.size == 1) {
            val only = options.upiApps[0]
            session.setUpiApp(only.id, only.label)
            return nextTurn(context)
        }
        val list = options.upiApps.take(4).mapIndexed { i, a -> "${i + 1}. ${a.label}" }
            .joinToString("; ")
        return "Ask the user which UPI app to pay with, reading these out: $list. " +
            "Ask only this, then wait."
    }

    private data class PaymentOptions(
        val upiApps: List<SwiggyOrderSession.Choice>,
        val codAvailable: Boolean
    )

    /** Reads the live payment picker for the current cart. */
    private suspend fun loadPaymentOptions(
        context: Context,
        server: SwiggyMcpClient.Server
    ): PaymentOptions {
        val raw = try {
            SwiggyMcpClient.callToolRaw(context, server, "get_payment_options")
        } catch (e: Exception) {
            Log.w(TAG, "get_payment_options failed: ${e.message}")
            null
        } ?: return PaymentOptions(emptyList(), true)

        val apps = mutableListOf<SwiggyOrderSession.Choice>()
        // platforms.mobile.methods[] is where the UPI intent apps live;
        // allMethods is the documented flat fallback.
        val arrays = listOfNotNull(
            raw.optJSONObject("platforms")?.optJSONObject("mobile")?.optJSONArray("methods"),
            raw.optJSONObject("data")?.optJSONObject("platforms")
                ?.optJSONObject("mobile")?.optJSONArray("methods"),
            raw.optJSONArray("allMethods"),
            raw.optJSONObject("data")?.optJSONArray("allMethods")
        )
        for (arr in arrays) {
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val id = m.optString("id").takeIf { it.isNotBlank() } ?: continue
                val label = m.optString("displayName").takeIf { it.isNotBlank() }
                    ?: m.optString("name").takeIf { it.isNotBlank() }
                    ?: id
                if (apps.none { it.id == id }) {
                    apps.add(SwiggyOrderSession.Choice(id, null, label, null))
                }
            }
            if (apps.isNotEmpty()) break
        }

        val cod = raw.opt("cod") != null && raw.opt("cod") != JSONObject.NULL
        return PaymentOptions(apps, cod || apps.isEmpty())
    }

    // ------------------------------------------------------------ placing

    private suspend fun confirm(context: Context, confirmed: Boolean): String {
        val session = SwiggyOrderSession
        if (session.phase != SwiggyOrderSession.Phase.AWAITING_CONFIRMATION) {
            return "There's no order waiting for a yes right now."
        }
        if (!confirmed) {
            session.reset()
            return "Order cancelled. Nothing was placed and no money was spent."
        }

        return orderMutex.withLock {
            // Re-check inside the lock: a duplicate delivery of this call must
            // not place the order twice. Swiggy has no cancel API, so a double
            // order is real food and real money, twice.
            if (session.phase != SwiggyOrderSession.Phase.AWAITING_CONFIRMATION) {
                return@withLock "That order has already been placed."
            }
            val server = session.server ?: return@withLock "Something went wrong with that order."
            val addressId = session.addressId ?: return@withLock "That order has no address."
            session.chosenItem ?: return@withLock "That order has no item."
            val payment = session.paymentMethod ?: return@withLock "That order has no payment method."

            // Mark it spent before the network call: if the reply is slow and
            // the model retries, the second attempt must not order again.
            session.backToGathering()

            // The cart was already filled when the total was read out, so this
            // only places the order. Re-filling it here would be a second
            // update_cart against a cart Swiggy has already accepted.
            //
            // intentApp is required for UPI and must be Swiggy's own id for the
            // app, echoed byte-for-byte — sending "UPI" with no app is what
            // emptied the cart at checkout.
            val placeArgs = mutableMapOf<String, Any?>(
                "addressId" to addressId,
                "paymentMethod" to payment
            )
            if (payment == "UPI") {
                session.upiApp?.let { placeArgs["intentApp"] = it }
            }

            val result = when (server) {
                SwiggyMcpClient.Server.INSTAMART ->
                    SwiggyMcpClient.callTool(context, server, "checkout", placeArgs)

                SwiggyMcpClient.Server.FOOD -> {
                    session.restaurantId ?: return@withLock "That order has no restaurant."
                    SwiggyMcpClient.callTool(context, server, "place_food_order", placeArgs)
                }
                SwiggyMcpClient.Server.DINEOUT ->
                    return@withLock "Table booking needs a time slot. Ask the user which " +
                        "day and time they want, then start again."
            }
            session.reset()

            // A UPI order comes back PENDING_PAYMENT: the user still has to
            // approve it in their UPI app, so saying "order placed" alone
            // would be wrong.
            val upiNote = if (payment == "UPI") {
                " The payment is not finished yet - tell the user to approve it in " +
                    "their UPI app now."
            } else ""
            "Order placed. $result$upiNote Swiggy orders cannot be cancelled from " +
                "here - the user must call 080-67466729."
        }
    }

    private suspend fun track(context: Context, service: String): String {
        val server = serverFor(service) ?: SwiggyMcpClient.Server.INSTAMART
        val tool = if (server == SwiggyMcpClient.Server.FOOD) "track_food_order" else "get_orders"
        val result = SwiggyMcpClient.callTool(context, server, tool)
        return "Order status: $result"
    }

    // ------------------------------------------------------------ parsing

    /**
     * Pulls the addresses out of a get_addresses reply.
     *
     * Swiggy nests them differently per server and wraps them in a paginated
     * envelope, so this looks in the likely places rather than assuming one
     * shape — a wrong guess here reads as "no saved address" to the user.
     */
    private fun parseAddresses(raw: JSONObject?): List<SwiggyOrderSession.Choice> {
        if (raw == null) return emptyList()
        val arr = firstArrayIn(raw, listOf("addresses", "data", "items", "results", "locations"))
            ?: return emptyList()
        val out = mutableListOf<SwiggyOrderSession.Choice>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("addressId").takeIf { it.isNotBlank() }
                ?: o.optString("id").takeIf { it.isNotBlank() }
                ?: continue
            out.add(SwiggyOrderSession.Choice(id, null, SwiggyOrderSession.labelForAddress(o), null))
        }
        return out
    }

    /**
     * Pulls the products or restaurants out of a search reply.
     *
     * Instamart items carry spinId AND skuId and update_cart needs both;
     * Food items carry a menu id and the restaurant they belong to.
     */
    private fun parseChoices(
        raw: JSONObject?,
        server: SwiggyMcpClient.Server
    ): List<SwiggyOrderSession.Choice> {
        if (raw == null) return emptyList()
        val arr = firstArrayIn(
            raw,
            listOf("products", "restaurants", "items", "data", "results", "cards")
        ) ?: return emptyList()

        val out = mutableListOf<SwiggyOrderSession.Choice>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue

            // Instamart nests everything orderable one level down, in
            // "variations" — the pack sizes. The product object itself carries
            // only a productId, which update_cart does not accept, so the ids
            // and the price all come from the variation. (Confirmed against a
            // live response; "variants" is checked too in case Food differs.)
            val variant = o.optJSONArray("variations")?.optJSONObject(0)
                ?: o.optJSONArray("variants")?.optJSONObject(0)

            fun field(vararg names: String): String? {
                for (n in names) {
                    o.optString(n).takeIf { it.isNotBlank() }?.let { return it }
                    variant?.optString(n)?.takeIf { it.isNotBlank() }?.let { return it }
                }
                return null
            }

            // price is an object ({"mrp":3999,"offerPrice":643}), not a string,
            // so optString on it returns nothing — which is why no price ever
            // reached the user. offerPrice is what they actually pay.
            fun priceValue(): String? {
                val po = variant?.optJSONObject("price") ?: o.optJSONObject("price")
                if (po != null) {
                    for (k in listOf("offerPrice", "finalPrice", "sellingPrice", "mrp")) {
                        val v = po.opt(k)
                        if (v != null && v != JSONObject.NULL && v.toString() != "0") {
                            return v.toString()
                        }
                    }
                }
                return field("offerPrice", "finalPrice", "price", "mrp", "costForTwo")
            }

            val spin = field("spinId", "spin_id")
            val sku = field("skuId", "sku_id")

            val id = when (server) {
                // update_cart accepts ONLY a real spinId. productId looks like
                // an id but is rejected, and Swiggy does not say so — it drops
                // the row and reports "no valid items remained", which then
                // looks like the cart or UPI being broken. So a row with no
                // spinId is not orderable and must not be offered at all.
                SwiggyMcpClient.Server.INSTAMART -> spin
                else -> field("id", "restaurantId", "menu_item_id", "itemId")
            } ?: continue

            val name = field("name", "displayName", "title", "productName") ?: continue

            val price = priceValue()

            // Out-of-stock rows cannot be added to a cart, and offering one
            // only to have update_cart drop it is how "no valid items remained"
            // happened. Skip them rather than read them out.
            val available = variant?.optBoolean("isInStockAndAvailable", true) ?: true
            val inStock = o.optBoolean("inStock", true) && o.optBoolean("isAvail", true)
            if (!available || !inStock) {
                Log.d(TAG, "Skipping unavailable product: $name")
                continue
            }

            // The ids are what update_cart is given, and a wrong one empties
            // the cart rather than erroring usefully, so log the first row's
            // real shape — it is the only way to see what Swiggy actually
            // returns here.  adb logcat -s SwiggyShape
            if (i == 0) {
                Log.i("SwiggyShape", "row0 keys=${o.keys().asSequence().toList()}")
                Log.i("SwiggyShape", "row0 spin=$spin sku=$sku price=$price name=$name")
                variant?.let { v ->
                    Log.i("SwiggyShape", "row0 variant keys=${v.keys().asSequence().toList()}")
                }
                o.toString().chunked(3000).forEachIndexed { n, part ->
                    Log.i("SwiggyShape", "row0[$n] $part")
                }
            }

            // Food results are restaurants; remember which one was picked so
            // update_food_cart can name it later.
            if (server == SwiggyMcpClient.Server.FOOD) {
                o.optString("restaurantId").takeIf { it.isNotBlank() }?.let {
                    SwiggyOrderSession.setRestaurant(it, name)
                }
            }
            out.add(SwiggyOrderSession.Choice(id, sku, name, price?.let { "₹$it" }))
        }
        return out
    }

    /** First array found under any of [keys], searched one level deep. */
    private fun firstArrayIn(obj: JSONObject, keys: List<String>): JSONArray? {
        // Preferred names first, at the top level and one level down, so a
        // well-named array always wins over a lucky structural match.
        for (k in keys) {
            obj.optJSONArray(k)?.let { if (it.length() > 0) return it }
        }
        for (k in listOf("data", "structuredContent", "result", "response", "content")) {
            obj.optJSONObject(k)?.let { nested ->
                for (k2 in keys) {
                    nested.optJSONArray(k2)?.let { if (it.length() > 0) return it }
                }
            }
        }
        // Nothing matched by name. Swiggy nests results differently per server
        // and per query (categories, widgets, cards), and the shapes are not
        // documented, so fall back to walking the tree for the first array
        // whose objects actually look like things that can be ordered.
        return deepFindRows(obj, 0)
    }

    /**
     * Depth-first search for an array of row-like objects.
     *
     * "Row-like" means carrying an id we can order with AND a name we can read
     * out — which is exactly what [parseChoices] needs, so an array that
     * passes this is usable even when its key is one we have never seen.
     */
    private fun deepFindRows(node: Any?, depth: Int): JSONArray? {
        if (depth > 6) return null
        when (node) {
            is JSONArray -> {
                if (looksLikeRows(node)) return node
                for (i in 0 until node.length()) {
                    deepFindRows(node.opt(i), depth + 1)?.let { return it }
                }
            }
            is JSONObject -> {
                node.keys().forEach { k ->
                    deepFindRows(node.opt(k), depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private val ID_KEYS = listOf(
        "spinId", "skuId", "id", "restaurantId", "menu_item_id", "itemId", "productId"
    )
    private val NAME_KEYS = listOf("name", "displayName", "title", "productName")

    private fun looksLikeRows(arr: JSONArray): Boolean {
        if (arr.length() == 0) return false
        val first = arr.optJSONObject(0) ?: return false
        // Instamart hangs the ids off "variations"; check there too, or a
        // product list looks id-less and gets walked straight past.
        val nested = first.optJSONArray("variations")?.optJSONObject(0)
            ?: first.optJSONArray("variants")?.optJSONObject(0)
        val hasId = ID_KEYS.any { first.optString(it).isNotBlank() } ||
            (nested != null && ID_KEYS.any { nested.optString(it).isNotBlank() })
        val hasName = NAME_KEYS.any { first.optString(it).isNotBlank() }
        return hasId && hasName
    }

    private fun serverFor(service: String): SwiggyMcpClient.Server? =
        when (service.lowercase().trim()) {
            "instamart", "im", "grocery", "groceries" -> SwiggyMcpClient.Server.INSTAMART
            "food", "restaurant", "swiggy" -> SwiggyMcpClient.Server.FOOD
            "dineout", "table", "reservation" -> SwiggyMcpClient.Server.DINEOUT
            else -> null
        }

    /** Called when the user disconnects Swiggy from settings. */
    fun disconnect(context: Context) {
        SwiggyOrderSession.reset()
        SwiggyTokenStore.clear(context)
        SwiggyMcpClient.resetSessions()
    }
}
