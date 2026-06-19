# IMI Glass — Complete Backend Integration & Data Persistence Guide

> **Purpose of this document**
> This is the single source of truth for the backend team. It describes **every feature** in the IMI Glass Android app (Mark One and Mark Two), **every piece of data** the app touches, **how that data is currently stored**, and **exactly what API endpoints are required** so that *every single thing* — AI chat summaries (voice + text), vision/photo descriptions, notes, meetings, user memory, usage/billing, device pairing, settings — is persisted in the backend and follows the user across devices and reinstalls.
>
> If you read only this document, you will know everything about the frontend and be able to build every API the app needs.

---

## 0. TL;DR — What already exists vs. what is missing

The app **already** talks to a backend at `http://136.243.196.163:8080` for a few things. Most data, however, still lives **only on the phone** in `SharedPreferences` / local JSON files and is lost on reinstall or device change.

| Domain | Local storage today | Backend today | Backend needed |
|---|---|---|---|
| Auth (register/login/refresh/logout) | `IMI_PREFS` (tokens) | ✅ `/v1/auth/*` | Exists — keep |
| AI Chat summaries (voice + text turns) | — (fire-and-forget only) | ✅ `/v1/chat-summaries` | **Enhance** (see §4) |
| Quick Notes | `quick_notes_prefs` | ✅ `/v1/notes` | Exists — keep |
| Meeting Minutes | `meeting_minutes_prefs` | ✅ `/v1/meetings` | Exists — keep |
| Vision / photo descriptions | `image_description_vault/vault.json` + `IMI_CONVERSATION_MEMORY` | ❌ none | **NEW** (see §5) |
| Live Gallery photos (image files) | `<filesDir>/live_gallery/` | ❌ none | **NEW** (see §5) |
| User Memory / AI personalization | `user_memory_prefs` | ❌ none | **NEW** (see §6) |
| Conversation sessions (full history) | `imi_conversation_sessions` | ❌ none | **NEW** (see §7) |
| AI response preferences | `imi_prefs` | ❌ none | **NEW** (see §8) |
| Token usage tracking | `imi_token_usage` | ❌ none | **NEW** (see §9) |
| Usage limits / Plan / Billing | `imi_usage_limits` | ❌ none | **NEW** (see §9) |
| Device pairing / type (Mark1/Mark2) | `device_prefs`, `glass_pairing` | ❌ none | **NEW** (see §10) |
| Battery status | `imi_prefs` | ❌ none | **NEW** (optional, §10) |
| WhatsApp auto-send settings | `whatsapp_auto_send` | ❌ none | local-only (OK) |

**The big asks from the product team:**
1. Save **the summary of every AI chat** — whether it is a **voice chat or a text chat** — to the backend.
2. Save **every vision/photo interaction** (the picture + its AI description + the user's question) to the backend.
3. Save **literally everything else** so the backend has a full picture of the user.

---

## 1. App architecture overview

- **Platform:** Android (Kotlin), `minSdk 24`, `targetSdk 35`, applicationId `com.aselea.imiglass`, version `1.8`.
- **AI engine:** Google **Gemini** (Live API for voice, generative API for text/vision). Token usage is tracked per mode.
- **Two hardware products share one app:**
  - **Mark One** — entry-level glasses. Voice assistant centered (`Mark1MainActivity`). AI turns are tagged with surface `"Mark 1 Glasses"`.
  - **Mark Two** — full-featured glasses with **camera** (vision). Driven by `MainActivity`. AI turns are tagged `"Mark 2 Glasses"`. Adds photo capture, video, live gallery, vision chat.
- **Device type** is stored in `DevicePreferenceManager` (`DeviceType.MARK1` / `MARK2`) and decides which home screen and which features are shown.
- **Networking:** OkHttp. Auth via JWT Bearer token. Error envelope is `{ "error": { "message": "..." } }`.
- **Current backend base URL:** `http://136.243.196.163:8080` (constant `AuthApi.BASE_URL`).

### Existing client networking files (for reference)
- `auth/AuthApi.kt` — register/login/logout/refresh.
- `auth/SessionManager.kt` — persists tokens + basic profile in `IMI_PREFS`.
- `ui/sync/ChatApi.kt` — Chat Summaries client (`/v1/chat-summaries`).
- `ui/sync/ChatSync.kt` — fire-and-forget pusher for each AI turn.
- `ui/sync/NotesMeetingsApi.kt` — Notes + Meetings client.
- `ui/sync/BackendSync.kt` — offline-first orchestrator (migrate-once + pull + push-on-mutation).

---

## 2. Conventions for ALL endpoints

These conventions already exist in the app and **must be preserved** for new endpoints so the client code stays uniform.

- **Base URL:** `http://136.243.196.163:8080` (please move to HTTPS for production).
- **Auth:** `Authorization: Bearer <accessToken>` on every authenticated call.
- **Content type:** `application/json; charset=utf-8` (multipart for image uploads).
- **Timestamps:** epoch **milliseconds** (Long). The client sends and expects ms.
- **Success envelope for lists:** `{ "items": [...], "hasMore": <bool> }` with query params `?page=<n>&pageSize=100` (client pages until `hasMore=false`). *Note: chat-summaries currently uses `{ "data": [...] }` — see §4 for normalization.*
- **Error envelope:** `{ "error": { "message": "human readable" } }`.
- **401** → client treats as "session expired" and stops (does not retry). Backend should return 401 only for genuinely invalid/expired tokens.
- **Token refresh:** `POST /v1/auth/refresh` accepts `{ "refreshToken": "..." }`. The backend currently returns **inconsistent casing** (login → `accessToken`/`expiresIn`; refresh → `access_token`/`expires_in`). **Please standardize on camelCase everywhere.** The client tolerates both today, but consistency removes a class of bugs.
- **Client supplies IDs:** Notes and Meetings are created with a client-generated UUID (`id`) so offline-created records keep a stable identity after sync. New endpoints should also accept a client `id` for upsert semantics.
- **Idempotency / upsert:** PUT must upsert (create if missing) — the client already falls back from PUT→POST when a record doesn't exist server-side. Please make PUT genuinely idempotent.

---

## 3. Authentication (EXISTS — documented for completeness)

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/v1/auth/register` | `{ fullName, email, password, confirmPassword }` | created user (no token) |
| POST | `/v1/auth/login` | `{ email, password }` | `{ accessToken, refreshToken, expiresIn, user: { userId, fullName, email, plan, profileImageUrl } }` |
| POST | `/v1/auth/logout` | `{}` (Bearer) | 200 |
| POST | `/v1/auth/refresh` | `{ refreshToken }` | `{ accessToken, refreshToken, expiresIn }` |

**Profile fields the client reads** from `user`: `userId`, `fullName`, `email`, `plan`, `profileImageUrl`. The `plan` field is important — it should drive usage limits (§9).

**Recommended additions (currently missing):**
- `GET /v1/auth/me` / `GET /v1/users/profile` — fetch the current profile (name, email, plan, profile image) so the app can refresh it without re-login.
- `PATCH /v1/users/profile` — update `fullName`, `profileImageUrl`.
- `POST /v1/users/profile/image` (multipart) — upload a profile picture.
- Email verification: the app has a `VerifyCodeActivity` (OTP screen). If verification is required, expose `POST /v1/auth/verify` `{ email, code }` and `POST /v1/auth/resend-code`.

---

## 4. AI Chat Summaries — VOICE + TEXT (EXISTS, needs enhancement) ⭐

This is the **#1 priority**: *"save the summary of all the chats with the AI — voice or text — to the backend."*

### How it works in the app today

Every completed conversation **turn** (one user utterance + the AI's reply) is pushed fire-and-forget via `ChatSync.pushTurn(...)`:

- **Mark Two** (`MainActivity.kt:6648`): `ChatSync.pushTurn(ctx, "Mark 2 Glasses", fullInput, fullOutput)`
- **Mark One** (`Mark1MainActivity.kt:729`): `ChatSync.pushTurn(ctx, "Mark 1 Glasses", fullInput, fullOutput)`

`ChatApi.postSummary` (`ui/sync/ChatApi.kt`) packs the turn into:

```json
POST /v1/chat-summaries
{
  "title": "Mark 2 Glasses",          // surface name, used as title
  "type": "QUICK_NOTES",              // or "MEETING_SUMMARY"
  "content": "[User] ...\n[AI] ...",  // both sides combined
  "keyPoints": [], "actionItems": [], "participants": [], "tags": [],
  "startTime": 1718800000000,
  "endTime":   1718800005000
}
```

> Note: **both voice chats and text chats** flow through this exact same call — the surface title and timing differ, the endpoint is identical. Voice turns come from the Gemini Live path; text turns from the typed chat path. The backend does **not** need to distinguish them unless we add a `channel` field (recommended below).

### Existing endpoints the client already calls

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/chat-summaries` | create a summary (one per turn) |
| GET | `/v1/chat-summaries` | list all (returns `{ "data": [...] }`) |
| GET | `/v1/chat-summaries/active` | not archived |
| GET | `/v1/chat-summaries/pinned` | pinned only |
| GET | `/v1/chat-summaries/type/{type}` | by `MEETING_SUMMARY` / `QUICK_NOTES` |
| GET | `/v1/chat-summaries/stats` | `{ total, meetings, quickNotes }` |
| GET | `/v1/chat-summaries/{id}` | single |
| PATCH | `/v1/chat-summaries/{id}` | partial update (title/content/keyPoints/actionItems/participants/tags/endTime) |
| DELETE | `/v1/chat-summaries/{id}` | delete |
| POST | `/v1/chat-summaries/{id}/pin` | toggle pin → `{ isPinned }` |
| POST | `/v1/chat-summaries/{id}/archive` | toggle archive → `{ isArchived }` |

### Full `ChatSummary` schema the client parses

```
id, title, type, content,
keyPoints[], actionItems[], participants[], tags[],
startTime (ms), endTime (ms|null), duration (ms),
isPinned (bool), isArchived (bool),
createdAt (ISO string), updatedAt (ISO string)
```

### Enhancements the backend should add (so we capture *everything*)

The current shape stores one row per turn with no link to the broader conversation, no modality, and no relationship to vision. Recommended additions:

1. **`channel` enum** — `"VOICE"` or `"TEXT"`. The app knows which path produced the turn; expose it so analytics/UI can split them. (Default `"VOICE"` for Live, `"TEXT"` for typed.)
2. **`surface` field** — keep `"Mark 1 Glasses"` / `"Mark 2 Glasses"` as a first-class field (today it is overloaded into `title`).
3. **`sessionId`** — group turns belonging to one continuous conversation (ties into §7 Conversation Sessions). Today every turn is standalone.
4. **`imageRefs[]`** — when a turn was about a photo, store the related vision-record id(s) (§5).
5. **`tokensUsed`** — optional, so server can aggregate usage (§9) without a separate write.
6. **Conversation summarization endpoint** — `POST /v1/chat-summaries/summarize` `{ sessionId }` → server (or our LLM) produces a rolled-up summary, keyPoints, actionItems for a whole session. This is what "save the **summary** of all chats" ultimately means: a digest, not just raw turns.

Suggested enriched create body:

```json
POST /v1/chat-summaries
{
  "id": "<client-uuid optional>",
  "surface": "Mark 2 Glasses",
  "channel": "VOICE",                 // VOICE | TEXT
  "type": "QUICK_NOTES",
  "sessionId": "sess_1718800000000",
  "userText": "what's the weather",
  "aiText": "It's 30°C and sunny.",
  "content": "[User] ...\n[AI] ...",  // keep for backwards compat
  "imageRefs": [],
  "tokensUsed": 412,
  "startTime": 1718800000000,
  "endTime":   1718800005000,
  "tags": []
}
```

---

## 5. Vision / Photo features (Mark Two) — NEW endpoints required ⭐

This is the **#2 priority**: *"every single data — pics, vision chat — send to backend."* Mark Two captures photos through the glasses' camera and the AI describes them. Today this lives **entirely on the phone** and is lost on reinstall. There are **three** distinct stores:

### 5a. Live Gallery — the raw image files
- **Where:** `LiveGalleryManager` saves JPEG bytes to `<filesDir>/live_gallery/IMG_<timestamp>_<source>.jpg`.
- **Source label:** `"BLE"` (Bluetooth transfer) or `"WiFi"` (direct upload), captured from the glasses.
- **Screens:** `LiveGalleryActivity`, `GlassMediaGalleryActivity`, `ImageViewerActivity`, `VideoPlayerActivity`.

**Needed endpoints:**
| Method | Path | Body | Notes |
|---|---|---|---|
| POST | `/v1/gallery/photos` | multipart `file` + `{ source, capturedAt, deviceType }` | upload a captured photo; returns `{ id, imageUrl, thumbnailUrl }` |
| GET | `/v1/gallery/photos?page&pageSize` | — | list user's photos (paged) |
| GET | `/v1/gallery/photos/{id}` | — | metadata + URL |
| DELETE | `/v1/gallery/photos/{id}` | — | delete one |
| DELETE | `/v1/gallery/photos` | — | delete all |

### 5b. Image Description Vault — AI descriptions of each photo
- **Where:** `ImageDescriptionStore` writes `<filesDir>/image_description_vault/vault.json`.
- **Schema per entry:** `{ fileName, timestamp, description }`.
- **Why it matters:** when the user asks "what was in my last photo," the AI reads `ImageDescriptionStore.getLast()` instantly (no network). `buildAIContext()` injects recent descriptions into the system prompt. This is core "vision memory."

**Needed endpoints (can be merged into the photo record from 5a):**
| Method | Path | Body | Notes |
|---|---|---|---|
| POST | `/v1/vision/descriptions` | `{ photoId\|fileName, description, userQuery, timestamp }` | store an AI description for a photo |
| GET | `/v1/vision/descriptions?page&pageSize` | — | all descriptions, newest first |
| GET | `/v1/vision/descriptions/last` | — | most recent description (for instant AI recall) |
| DELETE | `/v1/vision/descriptions/{id}` | — | remove one |
| DELETE | `/v1/vision/descriptions` | — | clear vault |

### 5c. Conversation Memory — multi-modal image history
- **Where:** `ConversationMemory` (`IMI_CONVERSATION_MEMORY` prefs) keeps the last **50 images** (`ImageMemory{ id, imagePath, description, userQuery, conversationId, timestamp }`) and last **30 context messages** (`ContextMessage{ text, isFromUser, hasImage, imageId, timestamp }`).
- **Why it matters:** lets the AI answer "like the photo I showed you earlier" by referencing prior images across sessions.

**Recommendation:** unify 5a/5b/5c into **one vision record** per captured photo so the backend has a single coherent object:

```json
{
  "id": "vis_...",
  "deviceType": "MARK2",
  "source": "BLE",                     // BLE | WiFi
  "imageUrl": "https://.../IMG_...jpg",
  "thumbnailUrl": "https://.../thumb.jpg",
  "userQuery": "what is this?",        // what the user asked about the photo
  "aiDescription": "A cup of coffee on a wooden table...",
  "sessionId": "sess_...",             // links to the chat session
  "capturedAt": 1718800000000,
  "createdAt": "...", "updatedAt": "..."
}
```

This single object satisfies all three local stores and means **every picture + its question + its AI answer is in the backend**.

---

## 6. User Memory / AI Personalization — NEW endpoints required

- **Where:** `UserMemoryManager` (`user_memory_prefs`), model `UserMemory`.
- **What it stores** (auto-learned from conversations **and** manually editable in `UserMemoryActivity`):

```
userName, occupation, location,
likes[], dislikes[], interests[],
preferredResponseStyle (BRIEF|BALANCED|DETAILED),
preferredLanguage (English|Hindi|...),
importantNotes[],
autoLearnedFacts[]   (capped at 50, learned via regex from chat),
conversationCount, totalMessages,
lastUpdated (ms)
```

- **Why it matters:** `UserMemory.toPromptContext()` is injected into the AI system prompt to personalize every reply. This is high-value, identity-level data that must survive reinstall and sync across devices.

**Needed endpoints:**
| Method | Path | Body | Notes |
|---|---|---|---|
| GET | `/v1/user/memory` | — | full memory object |
| PUT | `/v1/user/memory` | full object | replace (upsert) |
| PATCH | `/v1/user/memory` | partial | update individual fields/lists |
| POST | `/v1/user/memory/facts` | `{ fact }` | append an auto-learned fact (server dedupes, caps at 50) |
| DELETE | `/v1/user/memory` | — | clear all |

Stats (`conversationCount`, `totalMessages`) can also be derived server-side from §4/§7 — pick one source of truth.

---

## 7. Conversation Sessions (full history) — NEW endpoints required

- **Where:** `ConversationSessionStore` (`imi_conversation_sessions`).
- **Model:** `ConversationSession{ id, startedAt, lastUpdatedAt, messages[] }`, where `messages` is `SessionMessage{ role: "User"|"AI", text }`. Capped at **100 sessions**, **400 messages/session**.
- **Screens:** `ConversationHistoryActivity` (one row per session, titled by date/time), `UserProfileSummaryActivity`.
- **Difference from §4:** chat-summaries are per-turn rows; **sessions are the full transcript grouped per conversation**. Both are needed — summaries for the "saved summaries" list, sessions for the "history" timeline.

**Needed endpoints:**
| Method | Path | Body | Notes |
|---|---|---|---|
| POST | `/v1/conversations` | `{ id, startedAt }` | start a session |
| POST | `/v1/conversations/{id}/turns` | `{ userText, aiText, at }` | append a turn |
| GET | `/v1/conversations?page&pageSize` | — | sessions newest-first |
| GET | `/v1/conversations/{id}` | — | full transcript |
| DELETE | `/v1/conversations/{id}` | — | delete one |
| DELETE | `/v1/conversations` | — | clear all |

> Implementation tip: §4 (summaries) and §7 (sessions) can share a `sessionId`. The cleanest design is: client writes turns to `/v1/conversations/{id}/turns`; server asynchronously generates the `/v1/chat-summaries` digest. Then the single `ChatSync.pushTurn` call covers both.

---

## 8. AI Response Preferences / Settings — NEW endpoints required

- **Where:** `AiResponsePrefs` (`imi_prefs`), surfaced in `SettingsActivity`.
- **What it stores:**

```
ai_response_length  : SHORT | BALANCED | DETAILED
ai_response_tone    : FRIENDLY | PROFESSIONAL | CASUAL | DIRECT
ai_greeting_style   : TIME_BASED | SIMPLE | CUSTOM
ai_custom_greeting  : string
ai_greeting_use_name: bool
```

- **Why it matters:** these shape the system instruction and the spoken greeting for every voice/text session. Should sync so settings follow the user.

**Needed endpoints:**
| Method | Path | Body |
|---|---|---|
| GET | `/v1/user/settings` | — |
| PUT | `/v1/user/settings` | `{ responseLength, responseTone, greetingStyle, customGreeting, greetingUseName, ... }` |

Recommend making this a generic **key/value settings bag** so future toggles (WhatsApp auto-send, preferred language, notification preferences) can be added without schema changes.

---

## 9. Usage, Tokens, Plans & Billing — NEW endpoints required ⭐

This is **critical** because it currently lets users bypass paid limits (everything is client-side `SharedPreferences`). Move enforcement server-side.

### 9a. Token usage (`TokenUsageTracker`, `imi_token_usage`)
Per-mode totals: `AI_CHAT`, `SEEING` (vision), `VOICE_CHAT`. Each mode tracks `promptTokens`, `completionTokens`, `totalTokens`, `requestCount`.

### 9b. Usage limits & plans (`UsageLimitManager`, `imi_usage_limits`)
Per-mode counters consumed by `tryConsume()`. Plans:

| Plan | Price | Voice | Vision | Chat |
|---|---|---|---|---|
| FREE | ₹0 | 15 | 5 | 50 |
| PRO | ₹500 | 120 | 30 | 300 |
| ULTRA | ₹3000 | 1000 | 250 | 2500 |

**Needed endpoints:**
| Method | Path | Body | Notes |
|---|---|---|---|
| POST | `/v1/usage/consume` | `{ mode }` (`AI_CHAT`/`SEEING`/`VOICE_CHAT`) | atomically check+increment; returns `{ allowed, used, limit, remaining }`. **Enforce limit here, not on the client.** |
| POST | `/v1/usage/tokens` | `{ mode, promptTokens, completionTokens, totalTokens }` | record token usage |
| GET | `/v1/usage` | — | snapshot per mode `{ used, limit, remaining }` + plan + token totals |
| GET | `/v1/plans` | — | available plans + limits + prices |
| POST | `/v1/plans/subscribe` | `{ planKey }` | change plan (wire to a payment provider) |
| GET | `/v1/billing/history` | — | past payments/invoices |

The `plan` returned by login (§3) should be the authoritative source for limits.

---

## 10. Device Pairing, Type & Battery — NEW endpoints (lower priority)

- **Device type:** `DevicePreferenceManager` (`device_prefs`) → `MARK1` / `MARK2`. Drives the whole UI.
- **Pairing:** `glass_pairing` prefs + `DeviceBindActivity` / `DeviceSelectionActivity` (BLE bind, MAC address, watchface). SDK: HeyCyan Smart Glasses.
- **Battery:** `BatteryStatusStore` (`imi_prefs`) — last known glass battery level + timestamp.

**Recommended endpoints (so support/analytics know what device a user has):**
| Method | Path | Body |
|---|---|---|
| POST | `/v1/devices` | `{ deviceType, macAddress, name, firmware, pairedAt }` |
| GET | `/v1/devices` | — |
| PATCH | `/v1/devices/{id}` | `{ batteryLevel, lastSeenAt, firmware }` |
| DELETE | `/v1/devices/{id}` | — (unpair) |

Battery telemetry can be a lightweight `PATCH /v1/devices/{id}` ping; full historical battery logging is optional.

---

## 11. Notes & Meetings (EXISTS — documented for completeness)

These are fully wired through `NotesMeetingsApi` + `BackendSync` (offline-first: local is source of truth for the UI, every mutation pushes to the server, launch does migrate-once + pull).

### Quick Notes (`/v1/notes`)
`QuickNote{ id, title, content, imagePath?, timestamp, createdBy: USER|AI }` → JSON uses `origin` (`USER`/`AI`) and `noteTime`.

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/notes` | create (client-supplied `id`) |
| PUT | `/v1/notes/{id}` | update (upsert) |
| DELETE | `/v1/notes/{id}` | |
| GET | `/v1/notes?page&pageSize=100` | paged `{ items, hasMore }` |
| POST | `/v1/notes/bulk` | `{ items: [...] }` → `{ imported }` (migration) |
| POST | `/v1/notes/{id}/image` | multipart `file` → `{ imageUrl }` (AI photo notes) |

### Meeting Minutes (`/v1/meetings`)
`MeetingMinute{ id, title, startTime, endTime, transcript, summary, participants, isActive, speakerCount, speakerTranscript }`.

| Method | Path | Notes |
|---|---|---|
| POST | `/v1/meetings` | start (only **one active meeting** per user → 409 `ACTIVE_MEETING_EXISTS`; client clears stale + retries) |
| GET | `/v1/meetings/active` | current active meeting |
| PATCH | `/v1/meetings/{id}/transcript` | `{ text }` append live transcript |
| PUT | `/v1/meetings/{id}` | end/update (sets endTime, summary, isActive=false, speaker data) — upsert |
| POST | `/v1/meetings/{id}/cancel` | cancel active |
| DELETE | `/v1/meetings/{id}` | |
| GET | `/v1/meetings?page&pageSize=100` | paged |
| POST | `/v1/meetings/bulk` | migration |

> **Backend nit to fix:** standardize on `isActive` (legacy `active` is tolerated by the client). And ensure ending a meeting reliably clears the active flag, otherwise new meetings get stuck on 409.

---

## 12. Full feature inventory (so nothing is missed)

These are all the user-facing features. Items marked 🔴 produce data with **no backend** yet.

**Shared (Mark One + Mark Two)**
- Onboarding, splash, "Let us know you" profiling, login/signup/verify ✅(auth)
- AI **voice chat** (Gemini Live) 🔴(turns pushed, but session/memory not) ⭐
- AI **text chat** (`ChatActivity`) 🔴(same as above)
- Conversation **history** timeline 🔴 (§7)
- **User memory** / personalization 🔴 (§6)
- AI response **settings** (length/tone/greeting) 🔴 (§8)
- **Quick Notes** ✅
- **Meeting Minutes** (live transcription, speaker labels, AI summary) ✅
- **Profile** (name, email, plan, picture) ⚠️ partial (§3)
- **Usage limits / plans / upgrade** 🔴 ⭐ (§9)
- Device **pairing** & battery 🔴 (§10)
- AI tool-calls the assistant can perform: `make_phone_call`, `send_message` (incl. WhatsApp auto-send), `play_music`/`play_youtube`/`play_shayari`, `set_reminder`, `create_note`, `get_weather`, `web_search`, `get_news`, `control_volume`, `open_maps`, `open_camera`, `read_notifications`, `start_meeting` — these execute on-device; the **resulting note/reminder/meeting** should be persisted via the relevant endpoint above.

**Mark Two only (camera/vision)**
- **Vision chat** (`VisionChatActivity`) — ask about what the glasses see 🔴 ⭐ (§5)
- **Photo capture** (`take_photo`, `capture_photo_note`, `capture_new_frame`) 🔴 (§5)
- **Video record** (`record_video`) 🔴 (§5, add video record analogous to photos)
- **Live Gallery** / Glass media gallery 🔴 (§5)
- **Image Description Vault** + Conversation image memory 🔴 ⭐ (§5)

---

## 13. Recommended build order for the backend team

1. **Vision records** (§5) — pics + descriptions + questions. *(Highest product priority, no backend exists.)*
2. **Chat enhancements + sessions** (§4 + §7) — full voice/text chat persistence with sessions and server-side summarization.
3. **Usage/plans/tokens** (§9) — move limit enforcement server-side (stops paywall bypass).
4. **User memory** (§6) and **settings** (§8) — identity/personalization sync.
5. **Devices/battery** (§10) and **profile** (§3) — polish & analytics.

### Cross-cutting requirements
- All new endpoints are **per-user** (scoped by the JWT subject); never trust a client-supplied user id.
- Support **client-supplied UUIDs** and **PUT-as-upsert** for offline-first sync.
- Provide **bulk import** endpoints for any store that currently has local-only history (so first-login migration uploads existing data — mirrors the notes/meetings `bulk` pattern in `BackendSync.migrateIfNeeded`).
- Return **paged lists** `{ items, hasMore }` (`page`, `pageSize`) for everything listable. *(Normalize chat-summaries from `{ data }` to `{ items, hasMore }` too.)*
- Standardize **camelCase** JSON and **epoch-ms** timestamps everywhere.
- Move to **HTTPS** before production.

---

## 14. Quick endpoint index

```
AUTH (exists)
  POST   /v1/auth/register
  POST   /v1/auth/login
  POST   /v1/auth/logout
  POST   /v1/auth/refresh
  GET    /v1/auth/me                         (NEW)
  PATCH  /v1/users/profile                   (NEW)
  POST   /v1/users/profile/image             (NEW)

CHAT SUMMARIES (exists; enhance)
  POST   /v1/chat-summaries                  (+channel,surface,sessionId,imageRefs,tokensUsed)
  GET    /v1/chat-summaries[/active|/pinned|/type/{t}|/stats|/{id}]
  PATCH  /v1/chat-summaries/{id}
  DELETE /v1/chat-summaries/{id}
  POST   /v1/chat-summaries/{id}/pin|/archive
  POST   /v1/chat-summaries/summarize        (NEW)

CONVERSATIONS / SESSIONS (NEW)
  POST   /v1/conversations
  POST   /v1/conversations/{id}/turns
  GET    /v1/conversations[/{id}]
  DELETE /v1/conversations[/{id}]

VISION / PHOTOS (NEW)
  POST   /v1/gallery/photos                  (multipart)
  GET    /v1/gallery/photos[/{id}]
  DELETE /v1/gallery/photos[/{id}]
  POST   /v1/vision/descriptions
  GET    /v1/vision/descriptions[/last]
  DELETE /v1/vision/descriptions[/{id}]

USER MEMORY (NEW)
  GET/PUT/PATCH/DELETE /v1/user/memory
  POST   /v1/user/memory/facts

SETTINGS (NEW)
  GET/PUT /v1/user/settings

USAGE / PLANS (NEW)
  POST   /v1/usage/consume
  POST   /v1/usage/tokens
  GET    /v1/usage
  GET    /v1/plans
  POST   /v1/plans/subscribe
  GET    /v1/billing/history

DEVICES (NEW)
  POST/GET/PATCH/DELETE /v1/devices[/{id}]

NOTES (exists)
  POST/PUT/DELETE/GET /v1/notes[/{id}]
  POST   /v1/notes/bulk
  POST   /v1/notes/{id}/image

MEETINGS (exists)
  POST/GET/PUT/DELETE /v1/meetings[/{id}]
  GET    /v1/meetings/active
  PATCH  /v1/meetings/{id}/transcript
  POST   /v1/meetings/{id}/cancel
  POST   /v1/meetings/bulk
```

---

*Generated from a full read of the IMI Glass Android codebase (Mark One + Mark Two). Existing client networking lives in `app/src/main/java/com/sdk/glassessdksample/auth/` and `.../ui/sync/`; local stores are in `.../ui/*Manager.kt`, `.../ui/*Store.kt`, `.../ui/*Prefs.kt`, and `.../ui/gallery/`.*
