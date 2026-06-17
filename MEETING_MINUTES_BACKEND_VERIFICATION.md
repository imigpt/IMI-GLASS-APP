# IMI Glass App — Backend Verification Document
## Quick Notes & Meeting Minutes

**Purpose:** This document describes exactly what the Android app sends to and receives from the backend for the Quick Notes and Meeting Minutes features. Share with the backend team to confirm the API contract is correct on both sides before further testing.

**Date:** 2026-06-16  
**App:** IMI Glass Android App  
**Backend URL:** `http://136.243.196.163:8080`

---

## Table of Contents

1. [Authentication](#1-authentication)
2. [Quick Notes — Full API Contract](#2-quick-notes--full-api-contract)
3. [Meeting Minutes — Full API Contract](#3-meeting-minutes--full-api-contract)
4. [Launch Sync — How the App Pulls Data on Startup](#4-launch-sync--how-the-app-pulls-data-on-startup)
5. [Response Parsing — Exactly What Fields the App Reads](#5-response-parsing--exactly-what-fields-the-app-reads)
6. [Token Refresh — How Auth Works](#6-token-refresh--how-auth-works)
7. [Complete Flow Diagrams](#7-complete-flow-diagrams)
8. [Data Models](#8-data-models)
9. [Known Issues & Questions for Backend Team](#9-known-issues--questions-for-backend-team)
10. [Verification Checklist](#10-verification-checklist)

---

## 1. Authentication

Every API call (except login/register) requires a JWT Bearer token.

### Header sent on every request
```
Authorization: Bearer <access_token>
```

### Token refresh — how it works
- The app stores both `access_token` and `refresh_token` in SharedPreferences after login.
- Before every API call, the app checks if the token is expired.
- If expired, it calls `POST /v1/auth/refresh` **synchronously** before proceeding.
- If refresh fails (e.g. refresh token expired), the call is aborted. The user must re-login.

### Token refresh request
```
POST /v1/auth/refresh
Content-Type: application/json

{ "refreshToken": "<refresh_token>" }
```

### Known backend inconsistency — field casing
The app has already handled a known backend bug where `/v1/auth/login` returns camelCase fields but `/v1/auth/refresh` returns snake_case:

| Endpoint | Returns |
|---|---|
| `POST /v1/auth/login` | `accessToken`, `refreshToken`, `expiresIn` |
| `POST /v1/auth/refresh` | `access_token`, `refresh_token`, `expires_in` |

The app accepts both spellings using a `firstString("accessToken", "access_token")` helper. **Backend team: please standardize to camelCase across all auth endpoints.**

---

## 2. Quick Notes — Full API Contract

### 2.1 Create a Note

**When triggered:** User saves a new note in the app, or AI creates a note automatically.

```
POST /v1/notes
Content-Type: application/json
Authorization: Bearer <token>
```

**Request body:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Buy groceries",
  "content": "Milk, eggs, bread, coffee",
  "origin": "USER",
  "noteTime": 1718500000000
}
```

| Field | Type | Values | Notes |
|---|---|---|---|
| `id` | string (UUID v4) | any UUID | Client-generated. Backend must store and return this exact ID. |
| `title` | string | any | Note title. |
| `content` | string | any | Note body text. |
| `origin` | string | `"USER"` or `"AI"` | Who created the note. |
| `noteTime` | long | epoch milliseconds | Creation timestamp from `System.currentTimeMillis()`. |

**Expected response (200 or 201):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "title": "Buy groceries",
  "content": "Milk, eggs, bread, coffee",
  "origin": "USER",
  "noteTime": 1718500000000,
  "imageUrl": null
}
```

---

### 2.2 Update a Note

**When triggered:** User edits and saves an existing note.

```
PUT /v1/notes/{id}
Content-Type: application/json
Authorization: Bearer <token>
```

**Request body:**
```json
{
  "title": "Buy groceries (updated)",
  "content": "Milk, eggs, bread, coffee, butter"
}
```

**Upsert fallback:** If `PUT` fails with a non-401 error (e.g. note not found on server), the app automatically retries as `POST /v1/notes` with the full note body to prevent data loss.

**Expected response (200):** Updated note object (same shape as create response).

---

### 2.3 Delete a Note

**When triggered:** User deletes a note in the app.

```
DELETE /v1/notes/{id}
Authorization: Bearer <token>
```

**Expected response:** `200` or `204`.

---

### 2.4 Upload a Note Image

**When triggered:** User or AI attaches a photo to a note.

```
POST /v1/notes/{id}/image
Content-Type: multipart/form-data
Authorization: Bearer <token>
```

**Form fields:**
| Field | Type | Notes |
|---|---|---|
| `file` | binary | JPEG or PNG image file. |

**Expected response (200):**
```json
{
  "imageUrl": "https://storage.example.com/notes/image.jpg"
}
```

The app stores the returned `imageUrl` and displays it alongside the note.

---

### 2.5 List All Notes (paginated)

**When triggered:** App launch — pulls server data into local cache.

```
GET /v1/notes?page=1&pageSize=100
Authorization: Bearer <token>
```

**Expected response:**
```json
{
  "items": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Buy groceries",
      "content": "Milk, eggs, bread, coffee",
      "origin": "USER",
      "noteTime": 1718500000000,
      "imageUrl": null
    }
  ],
  "hasMore": false,
  "total": 1
}
```

The app pages through results incrementing `page` until `hasMore` is `false` or `items` is empty.

---

### 2.6 Bulk Import Notes (one-time migration)

**When triggered:** First launch after the app is installed — pushes any locally stored notes to the server.

```
POST /v1/notes/bulk
Content-Type: application/json
Authorization: Bearer <token>
```

**Request body:**
```json
{
  "items": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "title": "Buy groceries",
      "content": "Milk, eggs, bread, coffee",
      "origin": "USER",
      "noteTime": 1718500000000
    },
    {
      "id": "660f9500-f39c-52e5-b827-557766551111",
      "title": "Call doctor",
      "content": "Schedule appointment for next week",
      "origin": "AI",
      "noteTime": 1718486400000
    }
  ]
}
```

**Expected response (200):**
```json
{ "imported": 2 }
```

The app only calls this **once per installation** (guarded by a `migrated_v1` flag in SharedPreferences). Once it succeeds, it never runs again.

---

## 3. Meeting Minutes — Full API Contract

### 3.1 Start a Meeting

**When triggered:** User taps "Start Meeting", enters a title, and the recording begins.

```
POST /v1/meetings
Content-Type: application/json
Authorization: Bearer <token>
```

**Request body:**
```json
{
  "id": "770a1600-g40d-63f6-c938-668877662222",
  "title": "Team Sync",
  "startTime": 1718500000000
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | string (UUID v4) | Client-generated. Backend must store and return this exact ID. |
| `title` | string | User-entered meeting title. Defaults to `"Meeting <date>"` if blank. |
| `startTime` | long (epoch ms) | `System.currentTimeMillis()` at the moment recording starts. |

**Expected response (200 or 201):**
```json
{
  "id": "770a1600-g40d-63f6-c938-668877662222",
  "title": "Team Sync",
  "startTime": 1718500000000,
  "active": true
}
```

---

### 3.2 Append Transcript

**When triggered:** After recording stops and Gemini finishes transcribing the audio, the app sends the full transcript once.

> **Important:** This is NOT called in real time during the meeting. It is called exactly **once** after transcription completes, with the full transcript text. If the backend **appends** rather than replaces, retrying this call will duplicate the transcript.

```
PATCH /v1/meetings/{id}/transcript
Content-Type: application/json
Authorization: Bearer <token>
```

**Request body:**
```json
{
  "text": "Good morning everyone. Today we're going to discuss the Q3 roadmap. First item is the new feature rollout schedule..."
}
```

| Field | Type | Notes |
|---|---|---|
| `text` | string | Full speech-to-text transcript from Gemini 2.5 Flash. |

**Expected response (200):** Updated meeting object.

---

### 3.3 End a Meeting (Update with Summary)

**When triggered:** After transcription (Step 3.2) is saved and Gemini generates the summary, the app marks the meeting as ended.

> **This is the most critical call.** It carries the `transcript`, `summary`, `endTime`, and `active: false`.

```
PUT /v1/meetings/{id}
Content-Type: application/json
Authorization: Bearer <token>
```

**Request body:**
```json
{
  "endTime": 1718503600000,
  "summary": "Key Discussion Points:\n- Q3 roadmap reviewed\n- Feature rollout scheduled for July 15\n\nDecisions Made:\n- Team will adopt new CI pipeline\n\nAction Items:\n- Alice: Finalize specs by Friday\n- Bob: Set up staging environment\n\nImportant Notes:\n- Next meeting scheduled for next Monday",
  "transcript": "Good morning everyone. Today we're going to discuss the Q3 roadmap...",
  "speakerTranscript": "",
  "speakerCount": 0,
  "participants": "",
  "active": false,
  "isActive": false
}
```

| Field | Type | Notes |
|---|---|---|
| `endTime` | long (epoch ms) | `System.currentTimeMillis()` when user taps End Meeting. |
| `summary` | string | AI-generated summary from Gemini 2.5 Flash. Structured with Key Points, Decisions, Action Items, Notes. |
| `transcript` | string | Full speech-to-text transcript (same as sent in Step 3.2). |
| `speakerTranscript` | string | Speaker-labelled transcript (`Speaker 1: ...`). Currently always empty — reserved for future speaker diarization. |
| `speakerCount` | int | Number of unique speakers detected. Currently always `0` — reserved for future use. |
| `participants` | string | Comma-separated participant names. Currently always empty. |
| `active` | boolean | `false` — marks meeting as completed. **Backend should read this field.** |
| `isActive` | boolean | `false` — duplicate of `active` sent as a safety net for naming inconsistencies. |

> **Backend team:** The app sends **both** `active` and `isActive`. Please confirm: (1) which field name your database column is, and (2) which field name your API reads on `PUT`. The app reads `active` first from responses, falling back to `isActive`.

**Expected response (200):** Updated meeting object with all fields.

---

### 3.4 Upsert Fallback (if Start Meeting failed)

If `PUT /v1/meetings/{id}` returns any non-401 error (typically `404` because `POST /v1/meetings` from Step 3.1 failed due to a network error), the app automatically retries as a `POST /v1/meetings` with the full meeting body:

```
POST /v1/meetings
Content-Type: application/json
Authorization: Bearer <token>
```

```json
{
  "id": "770a1600-g40d-63f6-c938-668877662222",
  "title": "Team Sync",
  "startTime": 1718500000000,
  "endTime": 1718503600000,
  "transcript": "Full transcript...",
  "summary": "AI summary...",
  "participants": "",
  "speakerCount": 0,
  "speakerTranscript": "",
  "active": false,
  "isActive": false
}
```

> **Backend must accept a client-provided `id` on `POST /v1/meetings`** and use it as the record's primary key — not auto-generate a new one. If the backend ignores the `id` field on POST, every upsert retry will create a duplicate record with a different ID.

---

### 3.5 Cancel a Meeting

**When triggered:** User cancels the recording without saving.

```
POST /v1/meetings/{id}/cancel
Content-Type: application/json
Authorization: Bearer <token>

{}
```

**Expected response:** `200` with updated meeting object or a simple success body.

---

### 3.6 Delete a Meeting

**When triggered:** User deletes a saved meeting from the list.

```
DELETE /v1/meetings/{id}
Authorization: Bearer <token>
```

**Expected response:** `200` or `204`.

---

### 3.7 List All Meetings (paginated)

**When triggered:** App launch — pulls server data into local cache.

```
GET /v1/meetings?page=1&pageSize=100
Authorization: Bearer <token>
```

**Expected response:**
```json
{
  "items": [
    {
      "id": "770a1600-g40d-63f6-c938-668877662222",
      "title": "Team Sync",
      "startTime": 1718500000000,
      "endTime": 1718503600000,
      "transcript": "Full transcript...",
      "summary": "Key Discussion Points:...",
      "participants": "",
      "active": false,
      "speakerCount": 0,
      "speakerTranscript": ""
    }
  ],
  "hasMore": false,
  "total": 1
}
```

The app pages through results until `hasMore` is `false` or `items` is empty.

---

### 3.8 Bulk Import Meetings (one-time migration)

**When triggered:** First launch after install — pushes any locally stored meetings to the server.

```
POST /v1/meetings/bulk
Content-Type: application/json
Authorization: Bearer <token>
```

```json
{
  "items": [
    {
      "id": "770a1600-g40d-63f6-c938-668877662222",
      "title": "Team Sync",
      "startTime": 1718500000000,
      "endTime": 1718503600000,
      "transcript": "...",
      "summary": "...",
      "participants": "",
      "speakerCount": 0,
      "speakerTranscript": ""
    }
  ]
}
```

**Expected response (200):**
```json
{ "imported": 1 }
```

Called **once per install**, same as notes bulk import.

---

## 4. Launch Sync — How the App Pulls Data on Startup

Every time the app launches with a logged-in user, it runs a background sync:

```
SplashActivity.onCreate()
  └── if (isLoggedIn) BackendSync.syncOnLaunch(context)
        ├── migrateIfNeeded()   ← one-time bulk push of local data (runs once, ever)
        └── pullIntoCache()     ← pulls server data, replaces local SharedPreferences
              ├── GET /v1/notes?page=1&pageSize=100  (pages until hasMore=false)
              └── GET /v1/meetings?page=1&pageSize=100  (pages until hasMore=false)
```

The pulled data **replaces** local storage entirely. This means:
- If the backend returns an empty list, the app will show no notes/meetings even if data exists locally.
- If the backend returns stale or wrong data, it overwrites whatever the user sees.

**This is the most likely cause of "meetings not showing in the app after sync."**

---

## 5. Response Parsing — Exactly What Fields the App Reads

### Note object (from any notes endpoint)

```kotlin
QuickNote(
    id          = json.optString("id"),
    title       = json.optString("title"),
    content     = json.optString("content"),
    imagePath   = json.optString("imageUrl").takeIf { it.isNotBlank() && it != "null" },
    timestamp   = json.optLong("noteTime", System.currentTimeMillis()),
    createdBy   = if (json.optString("origin") == "AI") AI else USER
)
```

| App field | JSON key read | Fallback if missing |
|---|---|---|
| `id` | `id` | `""` (empty string) |
| `title` | `title` | `""` |
| `content` | `content` | `""` |
| `imagePath` | `imageUrl` | `null` if blank or `"null"` |
| `timestamp` | `noteTime` | current time |
| `createdBy` | `origin` (checks if `"AI"`) | `USER` |

---

### Meeting object (from any meetings endpoint)

```kotlin
MeetingMinute(
    id               = json.optString("id"),
    title            = json.optString("title"),
    startTime        = json.optLong("startTime", System.currentTimeMillis()),
    endTime          = if (json.isNull("endTime")) json.optLong("startTime") else json.optLong("endTime"),
    transcript       = json.optString("transcript"),
    summary          = json.optString("summary"),
    participants     = json.optString("participants"),
    isActive         = json.optBoolean("active", json.optBoolean("isActive", false)),
    speakerCount     = json.optInt("speakerCount", 0),
    speakerTranscript = json.optString("speakerTranscript")
)
```

| App field | JSON key read | Fallback if missing |
|---|---|---|
| `id` | `id` | `""` |
| `title` | `title` | `""` |
| `startTime` | `startTime` | current time |
| `endTime` | `endTime` (if JSON null → uses `startTime`) | `startTime` value |
| `transcript` | `transcript` | `""` |
| `summary` | `summary` | `""` |
| `participants` | `participants` | `""` |
| `isActive` | `active` first, then `isActive` | `false` |
| `speakerCount` | `speakerCount` | `0` |
| `speakerTranscript` | `speakerTranscript` | `""` |

---

## 6. Token Refresh — How Auth Works

```
Before every API call:
  └── AuthApi.ensureValidAccessToken()
        ├── if token not expired → return stored access_token
        └── if token expired → POST /v1/auth/refresh { refreshToken }
              ├── success → update stored tokens → return new access_token
              └── failure → return null → API call is aborted (user must re-login)
```

**Token expiry:** The app stores the expiry time from login response (`expiresIn` / `expires_in` seconds). Default assumed: 3600 seconds (1 hour).

**Refresh request:**
```
POST /v1/auth/refresh
Content-Type: application/json

{ "refreshToken": "eyJhbGci..." }
```

**Refresh response the app expects (accepts both casings):**
```json
{
  "accessToken": "eyJhbGci...",
  "refreshToken": "eyJhbGci...",
  "expiresIn": 3600
}
```
or snake_case:
```json
{
  "access_token": "eyJhbGci...",
  "refresh_token": "eyJhbGci...",
  "expires_in": 3600
}
```

---

## 7. Complete Flow Diagrams

### Quick Note — Create Flow

```
User types note → taps Save
  └── QuickNotesManager.addNote(note)
        ├── saves to SharedPreferences (immediate, synchronous)
        └── BackendSync.pushCreateNote(context, note)   [background thread]
              └── POST /v1/notes  { id, title, content, origin, noteTime }
                    ├── 200/201 → logged as success
                    └── error   → logged as warning (local data already saved)

if note has image:
  └── BackendSync.pushNoteImage(context, noteId, imagePath)   [background thread]
        └── POST /v1/notes/{id}/image  (multipart, JPEG)
```

### Quick Note — Update Flow

```
User edits note → taps Save
  └── QuickNotesManager.updateNote(noteId, title, content)
        ├── updates SharedPreferences
        └── BackendSync.pushUpdateNote(context, updatedNote)   [background thread]
              └── PUT /v1/notes/{id}  { title, content }
                    ├── 200 → success
                    └── non-401 error → retry as POST /v1/notes (full body, upsert)
```

### Meeting Minutes — Full Recording Flow

```
User enters title → taps "Start Meeting"
  └── MeetingMinutesManager.startMeeting(title)
        ├── saves meeting to SharedPreferences (isActive=true)
        └── BackendSync.pushStartMeeting()   [background thread]
              └── POST /v1/meetings  { id, title, startTime }

[MediaRecorder records audio to .3gp file]
[User speaks…]

User taps "End Meeting" → confirms dialog
  └── ActiveMeetingActivity.endMeeting()
        ├── stops MediaRecorder
        │
        ├── transcribeAudioWithGemini(audioFilePath)   [Gemini 2.5 Flash SDK]
        │     └── sends audio bytes to Gemini → returns transcript text
        │
        ├── MeetingMinutesManager.updateActiveMeetingTranscript(transcript)
        │     ├── updates SharedPreferences
        │     └── BackendSync.pushAppendTranscript()   [background thread]
        │           └── PATCH /v1/meetings/{id}/transcript  { text: "..." }
        │
        ├── generateMeetingSummary(transcript)   [Gemini 2.5 Flash SDK]
        │     └── sends transcript to Gemini → returns structured summary
        │
        ├── MeetingMinutesManager.endMeeting(summary)
        │     ├── saves completed meeting to SharedPreferences (isActive=false)
        │     └── BackendSync.pushUpdateMeeting()   [background thread]
        │           └── PUT /v1/meetings/{id}
        │                 { endTime, summary, transcript, active:false, isActive:false, ... }
        │                 ├── 200 → success
        │                 └── non-401 error → retry as POST /v1/meetings (full body, upsert)
        │
        ├── deletes .3gp audio file from device storage
        └── shows summary screen to user
```

### App Launch Sync Flow

```
SplashActivity.onCreate()
  └── BackendSync.syncOnLaunch(context)   [background thread]
        │
        ├── migrateIfNeeded()
        │     ├── check SharedPrefs: migrated_v1 = true? → skip
        │     └── migrated_v1 = false:
        │           ├── POST /v1/notes/bulk   { items: [...all local notes] }
        │           ├── POST /v1/meetings/bulk { items: [...all local meetings] }
        │           └── on success → set migrated_v1 = true (never runs again)
        │
        └── pullIntoCache()
              ├── GET /v1/notes?page=1&pageSize=100  (repeat until hasMore=false)
              │     └── QuickNotesManager.replaceAllFromServer(serverNotes)
              │           └── overwrites SharedPreferences with server list
              └── GET /v1/meetings?page=1&pageSize=100  (repeat until hasMore=false)
                    └── MeetingMinutesManager.replaceAllFromServer(serverMeetings)
                          └── overwrites SharedPreferences with server list
```

---

## 8. Data Models

### QuickNote (Android model)

```kotlin
data class QuickNote(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val imagePath: String? = null,        // local file path or remote imageUrl
    val timestamp: Long = System.currentTimeMillis(),
    val createdBy: CreatedBy = CreatedBy.USER
) {
    enum class CreatedBy { USER, AI }
}
```

### MeetingMinute (Android model)

```kotlin
data class MeetingMinute(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startTime: Long,
    val endTime: Long = System.currentTimeMillis(),
    val transcript: String,
    val summary: String = "",
    val participants: String = "",
    val isActive: Boolean = false,
    val speakerCount: Int = 0,
    val speakerTranscript: String = ""
)
```

### Suggested Database Schema (for backend reference)

**notes table:**
```sql
id              VARCHAR(36) PRIMARY KEY   -- client UUID
user_id         VARCHAR(36) NOT NULL      -- from JWT
title           TEXT
content         TEXT
origin          VARCHAR(10)               -- 'USER' or 'AI'
note_time       BIGINT                    -- epoch milliseconds
image_url       TEXT
created_at      TIMESTAMP DEFAULT NOW()
updated_at      TIMESTAMP DEFAULT NOW()
```

**meetings table:**
```sql
id                VARCHAR(36) PRIMARY KEY  -- client UUID
user_id           VARCHAR(36) NOT NULL     -- from JWT
title             TEXT
start_time        BIGINT                   -- epoch milliseconds
end_time          BIGINT
transcript        TEXT
summary           TEXT
participants      TEXT
active            BOOLEAN DEFAULT TRUE     -- NOTE: app sends "active", not "isActive"
speaker_count     INT DEFAULT 0
speaker_transcript TEXT
created_at        TIMESTAMP DEFAULT NOW()
updated_at        TIMESTAMP DEFAULT NOW()
```

---

## 9. Known Issues & Questions for Backend Team

| # | Issue / Question | Impact if wrong |
|---|---|---|
| **1** | **Does `POST /v1/meetings` and `POST /v1/notes` accept a client-provided `id`?** If the backend auto-generates its own UUID and ignores the `id` in the POST body, the upsert fallback creates a duplicate record every time. | Duplicate records in DB; app loses track of which record to update/delete. |
| **2** | **Is the meeting status field `active` or `isActive` in the DB and in GET responses?** The app sends both in PUT. But it reads only `active` from GET responses (with `isActive` as fallback). If GET returns `isActive` only, all meetings show as `active: false` (not filtering correctly). | Meetings may display as still-active or not display at all after restart. |
| **3** | **Does `GET /v1/notes` and `GET /v1/meetings` return data for the authenticated user only?** The app replaces all local data with whatever the server returns. If the endpoint returns data for all users, the local data is corrupted. | User sees other people's notes/meetings; their own data is wiped. |
| **4** | **Does `PATCH /v1/meetings/{id}/transcript` append to or replace the transcript?** The app sends the full transcript once. If it appends, a retry (e.g. due to network error) doubles the transcript. | Duplicated transcript text saved in DB. |
| **5** | **What HTTP status code does `PUT /v1/meetings/{id}` return when the meeting ID doesn't exist?** The app's upsert fallback fires on any non-401 error from PUT. If the backend returns `404`, the fallback correctly retries as POST. If it returns `400` or something else, the fallback still fires — which is fine — but backend team should be aware. | Upsert fallback behaviour; affects whether meeting data is saved at all. |
| **6** | **Is the server accessible on mobile data (LTE)?** Carriers like Airtel block outbound TCP to non-standard ports (e.g. 8080). ICMP ping may succeed but TCP connections fail. Sync only works on WiFi until the backend moves to port `443` (HTTPS). | All sync silently fails on mobile data. Only works on WiFi. |
| **7** | **Does the `GET /v1/meetings` list response include `transcript` and `summary` fields?** If these are omitted in the list response (common optimization — only include in single-record GET), the app will overwrite local meetings with empty transcripts/summaries on every launch pull. | Meeting transcripts and summaries disappear from app after each relaunch. |
| **8** | **Token refresh field casing — see Section 1.** If `/v1/auth/refresh` returns camelCase while the bug-fix was only applied to snake_case reading, token refresh may still fail silently. | All sync fails after the first 1-hour token expiry. |

---

## 10. Verification Checklist

Use this checklist to verify each endpoint is working correctly.

### Auth
- [ ] `POST /v1/auth/login` returns `accessToken` (or `access_token`) and `refreshToken`
- [ ] `POST /v1/auth/refresh` returns a new `access_token` (or `accessToken`)
- [ ] `GET /v1/notes` returns `401` when called without a token
- [ ] `GET /v1/meetings` returns `401` when called without a token

### Quick Notes
- [ ] `POST /v1/notes` with a client `id` → server stores that exact ID, returns it in response
- [ ] `GET /v1/notes` returns only the authenticated user's notes
- [ ] `GET /v1/notes` response contains `items` array and `hasMore` boolean
- [ ] `GET /v1/notes` items contain `id`, `title`, `content`, `origin`, `noteTime`, `imageUrl`
- [ ] `PUT /v1/notes/{id}` returns `404` (or similar) when ID doesn't exist
- [ ] `POST /v1/notes/bulk` accepts `items` array and returns `{ "imported": N }`
- [ ] `DELETE /v1/notes/{id}` returns `200` or `204`

### Meeting Minutes
- [ ] `POST /v1/meetings` with a client `id` → server stores that exact ID, returns it
- [ ] `POST /v1/meetings` response includes `active: true`
- [ ] `PATCH /v1/meetings/{id}/transcript` **replaces** (not appends to) the transcript field
- [ ] `PUT /v1/meetings/{id}` accepts `active` field and marks meeting as ended
- [ ] `PUT /v1/meetings/{id}` saves `transcript` and `summary` fields
- [ ] `PUT /v1/meetings/{id}` returns `404` when ID doesn't exist (so upsert fallback fires)
- [ ] `GET /v1/meetings` returns only the authenticated user's meetings
- [ ] `GET /v1/meetings` response contains `items` array and `hasMore` boolean
- [ ] `GET /v1/meetings` items include `transcript` and `summary` (not omitted in list view)
- [ ] `GET /v1/meetings` items use `active` field (not `isActive`)
- [ ] `POST /v1/meetings/bulk` accepts `items` array and returns `{ "imported": N }`
- [ ] `POST /v1/meetings/{id}/cancel` returns `200`
- [ ] `DELETE /v1/meetings/{id}` returns `200` or `204`

### Sync Behaviour
- [ ] After running the checklist above, install the app fresh, log in, and verify the meeting list loads from the server correctly
- [ ] Create a meeting in the app → verify record appears in backend DB with correct `transcript` and `summary`
- [ ] End a meeting → verify `active` is `false`, `endTime` is set, `transcript` and `summary` are present in DB

---

## 11. Source Files (for backend team reference)

| File | Full Path | Purpose |
|---|---|---|
| `NotesMeetingsApi.kt` | `app/src/main/java/com/sdk/glassessdksample/ui/sync/NotesMeetingsApi.kt` | All HTTP calls — exact request bodies, response parsing |
| `BackendSync.kt` | `app/src/main/java/com/sdk/glassessdksample/ui/sync/BackendSync.kt` | When each push fires, launch sync, bulk migration |
| `QuickNotesManager.kt` | `app/src/main/java/com/sdk/glassessdksample/ui/QuickNotesManager.kt` | Local storage for notes + triggers BackendSync |
| `MeetingMinutesManager.kt` | `app/src/main/java/com/sdk/glassessdksample/ui/MeetingMinutesManager.kt` | Local storage for meetings + triggers BackendSync |
| `ActiveMeetingActivity.kt` | `app/src/main/java/com/sdk/glassessdksample/ui/ActiveMeetingActivity.kt` | Recording, Gemini transcription, summary generation |
| `QuickNote.kt` | `app/src/main/java/com/sdk/glassessdksample/ui/QuickNote.kt` | Note data model |
| `MeetingMinute.kt` | `app/src/main/java/com/sdk/glassessdksample/ui/MeetingMinute.kt` | Meeting data model |
| `AuthApi.kt` | `app/src/main/java/com/sdk/glassessdksample/auth/AuthApi.kt` | JWT auth, token refresh |
| `SessionManager.kt` | `app/src/main/java/com/sdk/glassessdksample/auth/SessionManager.kt` | Token storage in SharedPreferences |

---

*Document version: 1.1 — 2026-06-16*
