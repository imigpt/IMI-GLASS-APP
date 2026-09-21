# Backend Spec: Syncing Imported ChatGPT/Claude Profile Summary to User Account

## Purpose

The app has a feature that imports a short "personal profile" summary directly from the user's own ChatGPT and/or Claude account (via in-app browser automation, not a file export) and uses it to personalize the on-device AI assistant. Today this data is **local-only, encrypted on-device, and never sent anywhere** — by explicit original design ("Nothing here ever leaves the phone"). We now want to change that: once imported, this profile should also be saved to the backend against the user's account, so it's available across devices / re-installs and can be referenced from the dashboard.

This doc describes exactly how the import currently works, the data shape involved, and what the backend needs to support.

---

## 1. Current State — How Import Actually Works (important, read carefully)

**This is not a file upload of a ChatGPT/Claude export (no `conversations.json`, no zip).** It is a live, in-app browser automation flow:

1. User picks "Import from ChatGPT" or "Import from Claude" (`ProfileImportActivity.kt`).
2. An in-app `WebView` loads the real login page (`chatgpt.com/auth/login` or `claude.ai/login`) and the user signs in with their own credentials directly with OpenAI/Anthropic — the app never sees or stores their ChatGPT/Claude password.
3. The app opens a new chat and programmatically sends one fixed prompt to the assistant:
   > "What do you know about me? Write it as a short profile I can give to another AI assistant so it understands who I am. Include whatever you know about my work, what I'm building or focused on, where I'm based, and how I like to be talked to. Write it as plain prose in the third person, around 150-250 words. No headings, no bullet points."
4. The app scrapes the rendered reply text out of the page (waiting for it to stop changing), strips UI chrome, and truncates to a max of **4,000 characters**.
5. If the assistant has no memory of the user, it's instructed to reply with the sentinel `NO_MEMORY_AVAILABLE`, which the app treats as "nothing to import" rather than an error.
6. The scraped text is shown to the user in an editable text box for review before saving — the user can edit it freely before confirming.
7. On save, the app stores it locally in `EncryptedSharedPreferences`, keyed separately per source, so both a ChatGPT-imported and a Claude-imported profile can coexist.
8. The app signs the WebView session out of ChatGPT/Claude afterward (note: currently disabled by a debug flag on our side — flagging only for our own tracking, not something the backend needs to handle).

**Consumption today:** all saved profiles (from both sources) are concatenated into a block of text prepended to the system instructions given to the on-device Gemini Live voice assistant, explicitly labeled as reference-only context (not instructions to follow).

## 2. Data Shape

Per import, the data is:

| Field | Type | Description |
|---|---|---|
| `source` | enum: `"CHATGPT"` \| `"CLAUDE"` | Which provider this profile came from |
| `body` | string (≤ 4000 chars) | The free-text profile summary, plain prose, possibly edited by the user before saving |
| `importedAt` | long (epoch millis) | When this import/save happened |

A user may have **up to two** such profiles at once (one per source) — they are independent, not merged, and a re-import of the same source overwrites the previous one for that source.

This is a plain text blob, not structured data — there are no separate fields for "occupation," "location," etc. extracted at import time (that kind of structured extraction happens in a completely separate, unrelated local-only feature — see Section 4 — which is out of scope for this sync).

## 3. What We Need From the Backend

### 3.1 API

**`PUT /v1/profile/imported-summary`**

Auth: `Authorization: Bearer <accessToken>` (existing auth pattern, same as `/v1/chat-summaries`).

Request body:
```json
{
  "source": "CHATGPT",
  "body": "Nikhil is a software engineer based in ... (up to 4000 chars)",
  "importedAt": 1758184800000
}
```

Behavior: **upsert by `(userId, source)`** — a new import for the same source replaces the previous one for that user, matching current on-device overwrite behavior. Use `PUT` (idempotent upsert) rather than `POST`, since there's no concept of multiple historical imports per source to preserve (though see open question #2 below).

Response `200 OK`:
```json
{
  "source": "CHATGPT",
  "body": "...",
  "importedAt": 1758184800000,
  "updatedAt": "2026-09-18T10:00:00Z"
}
```

**`GET /v1/profile/imported-summary`**

Auth: same Bearer token.

Returns all profiles saved for the current user (0, 1, or 2 entries — one per source):
```json
{
  "profiles": [
    { "source": "CHATGPT", "body": "...", "importedAt": 1758184800000 },
    { "source": "CLAUDE", "body": "...", "importedAt": 1758100000000 }
  ]
}
```

Used so the app can restore this data on a fresh install / new device without re-running the import flow, and so a dashboard can display it against the user's account.

**`DELETE /v1/profile/imported-summary/{source}`**

Auth: same Bearer token. Deletes the stored profile for that source only. Needed so a user can revoke/clear an imported profile (privacy-sensitive data — see below).

### 3.2 Security / privacy requirements — please read carefully

This data is **sensitive personal information written by a third-party AI about the user** (their work, location, communication style, etc.), scraped from their personal ChatGPT/Claude account. Treat it accordingly:

- Store encrypted at rest, not plaintext, same standard as any other PII field on the account.
- Never expose via any unauthenticated or cross-user-accessible endpoint.
- Support the `DELETE` endpoint fully (actual deletion, not soft-delete-only) so we can honor user deletion requests.
- Do not log the `body` field content in application logs/APM in plaintext.
- If the dashboard displays this to internal staff, consider whether that needs to be access-controlled/audited given it's user-authored-by-proxy personal data.

---

## 4. Important Scope Clarification

There is a **second, unrelated "profile summary" feature** in the app (`UserProfileSummaryActivity`) that builds a local heuristic summary (top topics, tone, recent prompts) from the user's *in-app* conversation history with our own assistant — this is **not** the ChatGPT/Claude import, uses a different local storage key, and is **not** part of this sync request. Please don't conflate the two if you see both terms "profile" and "summary" elsewhere in the codebase or in the `/v1/chat-summaries` endpoint (which is a different existing feature for meeting/chat summaries, already synced). This doc is specifically about the ChatGPT/Claude-imported text blob described in Sections 1–2.

---

## 5. What the Android App Will Do (our side)

- After a successful local save in `UserProfileStore.save()`, fire an authenticated call to `PUT /v1/profile/imported-summary` (fire-and-forget with retry, following the existing `ChatSync`/`ConversationSync` pattern — single-thread executor, only attempted `if (SessionManager.isLoggedIn)`).
- On login / app start, call `GET /v1/profile/imported-summary` to hydrate local storage if the device doesn't already have it locally (e.g. new device).
- Wire the "clear imported profile" UI action (if/when added) to call `DELETE /v1/profile/imported-summary/{source}`.
- No change to the import/scrape mechanism itself — only what happens after the user hits Save.

---

## 6. Open Questions for Backend Team

1. Do we want to keep any history of prior imports per source (e.g. for audit/rollback), or is overwrite-only sufficient? (Recommendation: overwrite-only for v1, matches current on-device behavior.)
2. Should this data be included in a "delete my account" cascade? (Recommendation: yes, must be.)
3. Is there an existing PII/encryption-at-rest policy for the backend DB we should confirm this fits, or does a new column/table need explicit review?

---

*This document reflects the current app implementation as of 2026-09-18. The import mechanism (WebView automation) itself is unchanged by this proposal — only server-side persistence of the resulting text is new.*
