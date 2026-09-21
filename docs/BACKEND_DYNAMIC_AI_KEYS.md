# Backend Spec: Dynamic AI API Keys (Gemini / OpenAI / etc.)

## Purpose

Today, the Gemini API key (and OpenAI key) used by the Android app is pushed via **Firebase Remote Config** — a global, push-only value with no per-user control and no dashboard workflow beyond the Firebase console. We want to replace this with a backend-owned config endpoint so an admin dashboard can update the Gemini key (and other AI provider keys) without a Firebase deploy, and potentially scope keys per user/plan in the future.

This doc describes **current app behavior**, exactly what the backend needs to expose, and what the Android team will change on our side. No app code has been modified yet — this is the contract to build against.

---

## 1. Current State (for context)

- The key is fetched from Firebase Remote Config (`RemoteConfigManager.kt`), parameter name `gemini_api_key`, cached on-device with a 1-hour minimum fetch interval, and read via `RemoteConfigManager.geminiApiKey`.
- It is consumed in `GeminiLiveService.kt` and passed as a URL query parameter when opening a WebSocket to:
  ```
  wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API_KEY>
  ```
- The same key is also expected to work for Google Cloud Speech-to-Text REST calls (`https://speech.googleapis.com/v1/speech:recognize`) used in the meeting-diarization feature.
- There is a parallel, currently-dead `OPENAI_API_KEY` path (`RemoteConfigManager.openAiApiKey`) for an alternate "GPT Realtime" provider, hardcoded off today but present in the code — worth supporting the same way for future flexibility.
- The app **already has a working authenticated backend** at `https://imi-app-backend.onrender.com` with login/register/refresh (`AuthApi.kt`) and a `Bearer <accessToken>` auth pattern used by existing sync endpoints (`/v1/chat-summaries`, `/v1/conversations`). Tokens are stored in `SessionManager` (SharedPreferences).

**What we want to change:** replace the Firebase Remote Config fetch with a call to a new backend endpoint, gated behind the same Bearer-token auth already used elsewhere in the app.

---

## 2. What We Need From the Backend

### 2.1 Admin-facing (dashboard side)

A dashboard screen where an admin can:
- View/edit the current Gemini API key (and optionally OpenAI key, Picovoice key, YouTube key, AUDD key — same pattern, lower priority).
- Save changes, which take effect for the app **without requiring an app release or Firebase console change**.
- Ideally: mark a key active/inactive, and keep a short history/audit log of who changed it and when (nice-to-have, not blocking).
- Optional stretch goal: support per-user or per-plan key overrides (e.g. enterprise customers use their own Gemini key), but a single global key is sufficient for v1.

### 2.2 App-facing API

**`GET /v1/config/ai-keys`**

Auth: `Authorization: Bearer <accessToken>` (same access/refresh token pair issued by `/v1/auth/login`).

Response `200 OK`:
```json
{
  "geminiApiKey": "AIza...",
  "openAiApiKey": "",
  "picovoiceAccessKey": "",
  "youtubeApiKey": "",
  "auddApiKey": "",
  "updatedAt": "2026-09-18T10:00:00Z"
}
```
Notes:
- Empty string (not null) for keys that aren't configured, matching current Remote Config default behavior (empty defaults force the app to treat the provider as "not configured properly" and show an error, which is existing, expected behavior — see `GeminiLiveService.kt` check for `apiKey.isEmpty()`).
- `updatedAt` lets the app decide whether to re-fetch/invalidate a cached value.
- 401 if the access token is expired/invalid — the app already knows how to call `/v1/auth/refresh` and retry once (`AuthApi.ensureValidAccessToken()` pattern), so standard 401 semantics are fine, no special handling needed.

**Caching / rate limiting expectations:**
- The app will cache the fetched key locally (encrypted storage) and only re-fetch periodically (e.g. once per hour, mirroring today's Remote Config interval) or on app start — so this endpoint should be cheap to call frequently but doesn't need to support high QPS per user.
- No need for real-time push (no websocket/SSE) — polling on app start / hourly is sufficient for v1.

### 2.3 Security requirements

- The Gemini/OpenAI keys are secrets. They must only be returned to authenticated app users (Bearer token), never exposed via an unauthenticated endpoint.
- Recommend keys are stored encrypted at rest in the backend DB, not plaintext.
- Recommend the dashboard requires admin-role auth, separate from regular app-user accounts, to edit these values.
- Transport is HTTPS only (already true for `imi-app-backend.onrender.com`).

---

## 3. What the Android App Will Do (our side, for the backend team's awareness)

- Add a `ConfigApi.kt` class mirroring the existing `ChatApi.kt`/`ConversationsApi.kt` pattern (OkHttp + `org.json` + `AuthApi.ensureValidAccessToken()`), calling `GET /v1/config/ai-keys`.
- Cache the result in `EncryptedSharedPreferences` (the same encryption mechanism already used for the imported AI profile data in `UserProfileStore.kt`), with a timestamp, so the app works offline / on cold start before the first fetch completes.
- Replace `RemoteConfigManager.geminiApiKey` (and `.openAiApiKey`) call sites in `GeminiLiveService.kt` with the new locally-cached backend-sourced value, falling back to Firebase Remote Config only if the backend call fails (transitional safety net, can be removed once the backend path is proven stable).
- No change to how the key is actually used against Google's Gemini WebSocket/REST endpoints — only the *source* of the key value changes.

---

## 4. Open Questions for Backend Team

1. Should key rotation be logged/audited from day one, or can that be added later?
2. Do we want per-user/per-plan key overrides in v1, or is a single global key acceptable to start? (Recommendation: global key for v1, revisit if enterprise customers need their own billing.)
3. Should there be a way to push an "emergency disable" (e.g. return empty key) if a key gets rate-limited/revoked, so the app fails gracefully rather than hammering a dead key?

---

*This document reflects the current app implementation as of 2026-09-18 and the proposed backend contract. Once the backend endpoint is available in a staging environment, the Android team will implement `ConfigApi.kt` and wire it in.*
