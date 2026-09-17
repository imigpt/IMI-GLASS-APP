# Ordering from Swiggy by voice

How the Swiggy integration works, what it is made of, and — just as importantly
— the things that went wrong building it and why the code looks the way it does
as a result.

Written for: an engineer picking this up who did not build it.

---

## 1. What it is

The user says *"order some milk"*; the app holds a short conversation with them
and places a **real Swiggy order**. Money is spent. Food arrives.

It runs against **Swiggy Builders Club** — Swiggy's own MCP servers, launched
April 2026, which expose Food, Instamart and Dineout as callable tools. This is
a first-party API, not scraping and not deep links: `checkout` really is
Swiggy's checkout.

Three servers, 48 tools between them:

| Server | URL | Tools |
|---|---|---|
| Food | `mcp.swiggy.com/food` | 20 |
| Instamart | `mcp.swiggy.com/im` | 16 |
| Dineout | `mcp.swiggy.com/dineout` | 12 |

**Status:** Instamart ordering is proven end to end — a real order was placed
(ID `248533084992381`, cash on delivery). Food and Dineout are wired but
unproven. See §9 for the full state of play.

### The shape of it

```
   user speaks
        ↓
   Gemini Live picks a tool          (GeminiLiveService)
        ↓
   SwiggyTools.handle(...)           ← the translation layer
        ↓
   SwiggyOrderSession                ← the half-built order, between turns
        ↓
   SwiggyMcpClient                   ← JSON-RPC over HTTP
        ↓
   mcp.swiggy.com/{food,im,dineout}
```

---

## 2. Why it is a conversation, not one call

This is the central design decision, and it is forced by Swiggy, not chosen.

Nobody says a complete order in one breath. *"Order some milk"* does not say
**which** milk, to **which** address, or paid **how**. And none of those can be
guessed:

- `search_products` **requires** an `addressId`. You cannot even search without
  knowing where the user is.
- `checkout`, in Swiggy's own words, *"REJECTS a call with no payment method"*
  — and cash must be explicitly confirmed by the user first.

So the integration is two tools called repeatedly rather than one big one:

```
swiggy_start  →  returns the NEXT QUESTION to ask
swiggy_reply  →  returns the next question, or the final summary
swiggy_confirm → places the order
```

`SwiggyOrderSession` holds the partial order between turns. This is the same
shape as `start_task` / `task_answer` in the web agent next door, deliberately —
but it is **not** a copy of `TaskSession`: that one plans arbitrary web work and
needs an LLM planner. Here the required fields are fixed and known from
Swiggy's schemas, so the "planner" is just *which field is still empty?*

```kotlin
fun missingField(): Field? = when {
    addressId == null      -> Field.ADDRESS
    chosenItem == null     -> Field.ITEM
    paymentMethod == null  -> Field.PAYMENT
    !paymentComplete()     -> Field.UPI_APP   // "UPI" alone is not payable
    else                   -> null            // ready to confirm
}
```

The order of those checks is the order the user is asked, and it is not
arbitrary — address first, because search itself needs it.

---

## 3. The files

| File | Lines | What it does |
|---|---|---|
| `SwiggyTools.kt` | 1058 | Tool declarations, the conversation, response parsing |
| `SwiggyOrderSession.kt` | 538 | The half-built order; speech → intent resolution |
| `SwiggyMcpClient.kt` | 555 | JSON-RPC/MCP transport, retry, rate limits, schema dumper |
| `SwiggyAuth.kt` | 356 | OAuth 2.1 + PKCE, loopback redirect, token refresh |
| `SwiggyTokenStore.kt` | 59 | Token persistence |
| `SwiggyDebugReceiver.kt` | 41 | Debug-only broadcast hook |

Wired in at four places — the same four the browser tools use:

- `GeminiLiveService.kt` — tool declarations + the system-prompt section
- `MainActivity.kt` — tool dispatch
- `Mark1MainActivity.kt` — tool dispatch (Mark 1 glasses)
- `ListeningService.kt` — tool dispatch (background)

---

## 4. Auth: why loopback and not an app scheme

This is the one piece of the design that looks wrong until you know why.

Swiggy's `/auth/register` **rejects custom app schemes**. Registering
`com.aselea.imiglass://swiggy/callback` returns:

```
invalid_redirect_uri: protocol must be one of:
  http:, https:, cursor:, claude:, raycast:, vscode:,
  swiggyinternal:, com.spontaa.app:, superclaw:, smartpicks:
```

An allowlist of desktop AI clients plus a few named partners. Our package is not
on it, so the usual Android *Custom Tab → app scheme* redirect is **not
available at all**.

`http://localhost` **is** accepted, so the code uses the loopback flow — which
is what RFC 8252 (OAuth for Native Apps) recommends for native clients anyway:

1. Bind a one-shot `ServerSocket` on `127.0.0.1:8765` — **before** opening the
   browser, so a fast user cannot beat the listener to the redirect.
2. Open the system browser at Swiggy's consent page.
3. Read the authorization code off the redirect request.
4. Close the socket in a `finally`. Nothing is left listening.

This works because `usesCleartextTraffic="true"` is already set in the manifest.

Two further findings, both from probing the live API:

- **DCR is a formality.** `/auth/register` returns the same `client_id`
  (`swiggy-mcp`) to everyone. The code still calls it — that is the documented
  flow and the id is Swiggy's to change — and falls back to the constant.
- **Sessions expire after a few days.** A 401 clears the stored token and raises
  `NotConnectedException`, which the tool layer turns into an *instruction* for
  the model ("tell the user to connect") rather than an error message.

> **If applying for production:** ask Swiggy to allowlist
> `com.aselea.imiglass://`. `SwiggyAuth.REDIRECT_URI` is the single place to
> change if they do.

---

## 5. The tool names are not guessable

Every tool name in the first version of this integration was wrong. Not
approximately wrong — wrong in a way that returns `-32601 Unknown tool`.

| Guessed | Actually |
|---|---|
| `add_to_cart` | `update_cart` (Instamart) / `update_food_cart` (Food) |
| `view_cart` | `get_cart` / `get_food_cart` |
| `place_order` | `checkout` (Instamart) / `place_food_order` (Food) |

The names were recovered by asking a live server with a real token. That
capability is **kept in the shipping code**, because the contract is Swiggy's to
change and they publish a 6-month deprecation window:

```
adb shell am broadcast -a com.aselea.imiglass.SWIGGY_DUMP_SCHEMAS
adb logcat -s SwiggySchema
```

`SwiggyDebugReceiver` is declared in `app/src/debug/AndroidManifest.xml` only,
so it does not exist in a release build. It reads schemas and orders nothing.

**When something breaks, re-run the dump. Do not guess again.**

### Two Swiggy behaviours that matter

- **`update_cart` REPLACES the cart.** It does not append. The whole order goes
  in one call; sending items one at a time drops everything but the last.
- **Orders cannot be cancelled through the API, by design.** Swiggy's own
  instruction is to tell the user to call **080-67466729**. This is why the
  confirm-before-spending gate is not decoration — it is the only safety net
  that exists.

---

## 6. Payment, and the UPI trap

Payment is never defaulted. Swiggy rejects an order that does not say how it is
being paid, and cash must be confirmed aloud by the user.

UPI has a second requirement that cost a debugging round. From Swiggy's schema:

> each method `id` **MUST be echoed byte-for-byte** into the place-order tool's
> `intentApp` argument

So *"UPI"* alone is not a payment method — the user must choose **which app**
(GPay, PhonePe, Paytm), and the code must send back Swiggy's id for it, not the
name the user said. That is why `Field.UPI_APP` exists as a separate step and
why `paymentComplete()` is not just a null check.

The apps come from `get_payment_options`, which also reveals when UPI is
genuinely unavailable for an account (Swiggy omits the field entirely). After a
UPI order, the response is `PENDING_PAYMENT` — the user still has to approve it
in their UPI app, so the spoken result says so rather than claiming success.

### The failure that looked like a UPI bug

Symptom: *"UPI isn't available on this account."*
Cause: the **cart** had failed, and Swiggy offers no payment methods for an
empty cart. The absence of UPI said nothing about the account.

The code now surfaces the cart error instead of inferring a payment one
(`lastCartError` in `SwiggyTools`). A wrong diagnosis sent the user down a dead
end for two test rounds; it was worth the extra field.

---

## 7. Parsing Swiggy's responses

Swiggy does not document its response shapes, and they differ per server. Two
rounds of guessing failed before the shape was logged from a live call:

```json
{ "displayName": "...",
  "variations": [{                    // ← NOT "variants"
      "spinId": "HZMV04A9TV",         // ← what update_cart needs
      "skuId":  "54IV282VTR",
      "price":  { "mrp": 3999, "offerPrice": 643 },   // ← an object
      "isInStockAndAvailable": true
  }],
  "productId": "SNMSSYLFHR" }         // ← NOT accepted by update_cart
```

Three separate bugs lived in that one object:

1. **`variations`, not `variants`.** The ids were never found.
2. **`productId` is not a `spinId`.** Sending it does not error — Swiggy
   silently drops the row and reports *"no valid items remained"*, which then
   looks like the cart, or UPI, being broken. There is now **no fallback**: a
   row without a `spinId` is not offered to the user at all.
3. **`price` is an object.** Read as a string it yields nothing, which is why no
   price ever reached the user. `offerPrice` is what they actually pay.

Out-of-stock rows are skipped rather than read out — offering one only to have
`update_cart` drop it is exactly how *"no valid items remained"* happened.

Because the shapes are undocumented, `firstArrayIn` falls back to walking the
tree for the first array whose objects carry both an id and a name
(`looksLikeRows`), rather than relying only on known key names. And when a parse
still comes back empty, the payload is logged (`adb logcat -s SwiggyShape`)
instead of being guessed at a fourth time.

---

## 8. Understanding what the user said

Three classes of bug here, all found in real use.

**Filler words.** *"order some milk"* was searched literally as `some milk` and
matched nothing. `searchTermFrom()` strips leading command verbs, trailing Hindi
verbs (*"doodh mangao"* — the verb comes last), where-clauses (*"on Instamart"*
names the shop, not the product), and placeholder words (*"order something"*).
If everything strips away, it returns blank and the user is asked what they
want, rather than a query being run that cannot match.

**First-match-wins.** Asking for *"Country Delight Cow Milk"* silently selected
*"Saras Pasteurised Toned Milk"* — both contain "milk", and the first hit won.
Choices are now **scored** by word overlap and a clear winner is required; no
winner means asking again. Silently ordering the wrong product is the worst
outcome available here, given orders cannot be cancelled.

**Picking vs. searching.** Answering *"Gold"* (meaning Amul Gold) was treated as
a new search and returned **gold jewellery**. A one- or two-word answer while
options are on the table is now treated as an attempt to *pick*; if it cannot be
resolved, the list is re-read and the user is asked for the number.

Position words work in both languages: *first / pehla / 1 / number two /
dusra / last*.

---

## 9. State of play

**Proven end to end:**

- OAuth login (loopback flow)
- Address selection from saved addresses
- Instamart product search
- Choosing a product by name or number
- Real price and cart total
- Cash-on-delivery order — **real order placed**
- Confirm-before-spending gate

**Written but unproven:**

- UPI payment (the whole `intentApp` path)
- Food / restaurant ordering
- Quantity handling (*"two packets"*)

**Deliberately incomplete:**

- **Dineout.** Booking needs a time-slot flow (`get_available_slots` →
  `book_table`, plus a separate paid-deal path through `create_cart`). The tool
  returns a message asking for day and time rather than pretending to book.

**Fixed after the first successful order:** the cart total was read out as raw
JSON — `total {"label":"To Pay","value":"₹125"}` — because the matched field is
a display object, not a number, and `toString()` on it yields JSON.
`extractTotal` now unwraps `value` / `amount` / `text`, and leaves an
already-formatted `₹` string alone rather than prefixing a second symbol. Built
but not yet re-tested on device.

### The go-live checklist

Most of Swiggy's production checklist is now in `SwiggyMcpClient`. Built and
compiling, but **only lightly exercised on device** — a clean-install run of the
OAuth and Instamart order flow passed, which means nothing here broke the happy
path. The retry and 429 branches have not been made to fire on purpose.

- **Retry with backoff** — 500ms → 1s → 2s → 4s against a 30s total budget
  (`RETRY_DELAYS_MS`, `RETRY_BUDGET_MS`). Only network faults, 5xx and 429 are
  retried; any other 4xx is a contract error that will fail the same way twice.
- **429 handling** — was previously a generic "returned an error (429)" that
  gave up. Now retried, and `Retry-After` is honoured when the server sends it.
- **Rate limiting** — 70 req/min per server, 30/min for writes, checked
  *before* the request goes out rather than discovered as a 429 mid-order.
  Raises `RateLimitedException`, which the tool layer turns into an instruction
  ("the order is still in progress — do not start it again") so the model waits
  rather than restarting the order.
- **Session id and latency logged** on every attempt — `adb logcat -s
  SwiggyMetrics`. One line per attempt carrying server, tool, attempt number,
  elapsed ms, session id and outcome; p50/p95/p99 are computed from these
  rather than tracked in the app.
- **Deprecation watching** — `_meta.swiggy.deprecation` is read off every
  result and logged as a warning.

**Writes are never retried.** `checkout`, `place_food_order`, `book_table`,
`update_cart`, `update_food_cart` and `create_cart` are listed in
`NON_RETRYABLE_TOOLS`. A retry of a checkout whose *response* was merely lost
would be a second real order, real money, with no cancel API to undo it — so a
failed write tells the user to check the Swiggy app instead. This is the safe
half of Swiggy's "check-then-retry, never blind retry" rule; the check half
needs an order-status tool and is **not built**.

On **hashing user ids at rest**: not done, deliberately. Nothing identifying the
user is written to logs at all, which is a stronger position than hashing.

### Still not built

- **Check-then-retry on order placement** — needs an order-status lookup so a
  lost response can be resolved without ordering twice. Until then, writes fail
  closed (above).
- **Gradual traffic ramp** (1% → 10% → 50% → 100%) — a release process, not
  code.

---

## 10. Session lifetime — the bug worth remembering

The conversation silently broke in a way that looked like the model being
stupid: IMI asked *"which address?"*, the user answered *"Work"*, and IMI
replied *"What kind of work are you trying to do?"*

Cause: with Continuous Chat **off**, the live session closes after one reply. So
the question ended the session, and the answer arrived in a **fresh session with
no memory of the order**.

The codebase already solved this for the web agent (`taskInFlight`). Swiggy now
holds the session the same way, in all three dispatchers:

```kotlin
val orderInFlight = SwiggyOrderSession.isActive
if (!continuousChat && ... && !taskInFlight && !orderInFlight) {
    endSessionAfterCurrentReply("Continuous Chat off")
}
```

**Any future feature that asks the user a question mid-flow must add itself to
that check**, or it will break in exactly this way — and the symptom will look
like a model problem, not a session one.

---

## 11. Safety properties

Worth stating explicitly, because orders are irreversible:

- **No order without a spoken summary** including items, quantity, address,
  **total price** and payment method. The model is told never to omit the total.
- **`swiggy_confirm` cannot run in the same turn as the summary.** The model is
  instructed to wait for a separate, explicit yes.
- **Double-order protection.** The phase is flipped *before* the network call,
  inside a mutex, so a duplicate tool delivery (Gemini Live has several dispatch
  paths and one call can arrive twice) cannot place a second order.
- **Stale quotes are re-priced, not honoured.** A summary older than 3 minutes
  is not confirmed: the cart is rebuilt, the new total is read out, and the user
  is asked again. Prices, stock and fees move in quick commerce, and the user
  agreed to a specific number. A session idle for 10 minutes is abandoned
  entirely.
- **Cart changes invalidate the total**, so a price read aloud always belongs to
  the item being ordered.
- **No credentials are ever spoken.** Login happens in the browser; the model is
  instructed never to ask for a password, OTP or card details.

---

## 12. Production access

Swiggy Builders Club is invite-based. Prototyping against real tool schemas
needs no approval; production does.

- Apply at `mcp.swiggy.com/builders/access/` — **Developer** track (Enterprise
  is 4+ weeks and wants SOC 2 and commercial terms). Contact:
  `builders@swiggy.in`.
- They ask for: integration name, org, use case, target servers, expected
  volume, HTTPS redirect URIs, static/gateway IPs, security contact, data
  handling declaration.
- **A demo video is the fastest route** — their words. A published Play Store
  listing is *not* required.
- Timeline: staging credentials during review, production after 48h+ of stable
  staging.

The redirect-URI problem in §4 should be raised **in the application**, not
after approval.

### Application status

A demo video has been recorded against a clean install, so it shows the OAuth
consent flow from scratch as well as an order. The form and the covering email
to `builders@swiggy.in` are the remaining step.

Four details the form asks for that are not facts about the code:

- Organisation name
- Expected volume (req/min peak, users at launch)
- **Static / gateway IPs** — there are none. `SwiggyMcpClient` calls
  `mcp.swiggy.com` directly from the handset, so every user's traffic arrives
  from a different mobile-network address. The honest answer is "mobile
  clients, no static egress IP". If Swiggy requires one, the calls have to move
  behind our own backend, which is a real architecture change and not a config
  toggle.
- Security contact

Proof of a working integration is order `248533084992381`, which Swiggy can
verify on their side — worth citing rather than placing a fresh order.
