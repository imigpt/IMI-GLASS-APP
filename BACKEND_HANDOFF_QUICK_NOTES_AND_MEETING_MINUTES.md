# Backend Handoff — Quick Notes & Meeting Minutes Persistence

**Project:** IMI Glass App
**Author:** Frontend / Android team
**Date:** 2026-06-16
**Status:** Ready for backend design & implementation
**Audience:** Backend engineering team

---

## 1. Purpose of this document

The **Quick Notes** and **Meeting Minutes (MoM)** features are already fully implemented in the Android app. Today both features store all data **locally on the device** (Android `SharedPreferences` as JSON, plus image files in app-internal storage). This means:

- Data is lost if the app is uninstalled or storage is cleared.
- Data does not sync across devices for the same user.
- Data cannot be backed up, searched server-side, or used by other services.

We now want to **persist this data in the backend**, scoped per authenticated user, so it survives reinstalls and follows the user across devices.

This document describes both features, the exact data we store today, and the database schema, API endpoints, payloads, validation, and migration approach the backend team needs to build. It is written so the backend team can implement against it without needing to read the Android code.

---

## 2. How this fits the existing backend

The app **already has an authenticated backend** used for auth. New endpoints should follow the **same conventions**:

| Concern | Existing convention (must reuse) |
|---|---|
| Base URL | `http://136.243.196.163:8080` |
| API version prefix | `/v1/...` (e.g. `/v1/auth/login`) |
| Auth scheme | `Authorization: Bearer <accessToken>` (JWT) |
| Token lifecycle | Access token + refresh token; refresh via `/v1/auth/refresh` |
| User identifier | `userId` (string) is returned in the login response and identifies the owner |
| Error envelope | `{ "error": { "message": "<human readable>" } }` with appropriate HTTP status |
| Content type | `application/json; charset=utf-8` |

> **Important:** The new endpoints must return errors in the **same `{ "error": { "message": ... } }` envelope**, because the Android networking layer (`AuthApi`) already parses `error.message` from that shape. Keeping the shape consistent lets us reuse the existing error handling.

All new endpoints below are **authenticated** — the user is derived from the JWT, and ownership is enforced server-side (see §9).

---

## 3. Feature 1 — Quick Notes

### 3.1 What it is
Quick Notes is a personal note-taking feature. A note has a **title** and a **body**, and can optionally have **one image attached**. Notes can be created two ways:

1. **By the user manually** — typed in the note editor.
2. **By the AI assistant automatically** — the in-app AI (Gemini) can create a note on the user's behalf (e.g. "remind me to…"), and can attach a captured photo to it.

We track which of these created the note (`createdBy = USER | AI`) and display it accordingly.

### 3.2 What the user can do
- Create a note (title + content, optional image).
- View a list of all their notes, **newest first**, grouped by month in the UI.
- Open and **edit** a note (title, content, image).
- **Delete** a note (this must also delete its attached image).
- **Search** notes by keyword (matches title or content).

### 3.3 Data stored today (per note)
This is the exact current on-device model. Every field must be representable in the backend.

| Field | Type | Notes |
|---|---|---|
| `id` | string (UUID) | Generated on device today. Backend should accept a client-supplied UUID **or** assign its own — see §13. |
| `title` | string | Required. |
| `content` | string | The note body. Can be long. |
| `imagePath` | string \| null | Today this is a **local file path**. In the backend this becomes an uploaded image referenced by a URL / object key — see §11. |
| `timestamp` | long (epoch millis) | Creation time. Used for sort (desc) and month grouping. |
| `createdBy` | enum: `USER` \| `AI` | Who created the note. |

---

## 4. Feature 2 — Meeting Minutes (MoM)

### 4.1 What it is
Meeting Minutes records a live meeting. The user starts a meeting, the app captures a **speech-to-text transcript** in real time, optionally with **speaker labels** (speaker diarization, "Speaker 1: …", "Speaker 2: …"), and when the meeting ends an **AI-generated summary** is produced. The completed meeting is saved to a history list.

### 4.2 Meeting lifecycle (important for API design)
There are two states:

1. **Active meeting** — exactly **one** at a time per user. While active, the transcript is appended to repeatedly as speech comes in. Today this is stored separately from the saved list (a single "active meeting" slot).
2. **Completed meeting** — when the meeting ends, it gets an `endTime` + `summary`, `isActive` becomes `false`, and it is saved into the meetings history list. An active meeting can also be **cancelled** (discarded without saving).

The backend should support this lifecycle (start → append transcript → end/save, or cancel). See §7.2.

### 4.3 What the user can do
- Start a meeting (with a title; a default title is generated if blank).
- Have the transcript continuously appended while the meeting is active.
- End the meeting → it's saved with summary + speaker info.
- Cancel an active meeting → discarded.
- View all past meetings, **newest first** (sorted by `startTime`).
- View meeting details (transcript, speaker-labelled transcript, summary, duration, participants).
- **Delete** a meeting.
- **Search** meetings by keyword (matches title, transcript, or summary).

### 4.4 Data stored today (per meeting)

| Field | Type | Notes |
|---|---|---|
| `id` | string (UUID) | See §13. |
| `title` | string | e.g. "Team Sync", "Client Call". Auto-generated if blank. |
| `startTime` | long (epoch millis) | Meeting start. Primary sort key (desc). |
| `endTime` | long (epoch millis) | Meeting end. `endTime - startTime` = duration. |
| `transcript` | string | Full plain speech-to-text transcript. Can be **large** (thousands of chars). |
| `summary` | string | AI-generated summary. May be empty while active. |
| `participants` | string | Optional, comma-separated names today. |
| `isActive` | boolean | `true` while ongoing, `false` once ended. |
| `speakerCount` | int | Number of unique speakers detected. |
| `speakerTranscript` | string | Transcript with speaker labels, e.g. `Speaker 1: ... Speaker 2: ...`. |

---

## 5. Data ownership & audit fields (applies to BOTH features)

Every record is **owned by one user** and must be filtered by the authenticated user on every read/write. In addition to the feature fields above, the backend should add standard audit fields:

| Field | Type | Notes |
|---|---|---|
| `userId` | string (FK → users) | Owner. Taken from the JWT, **never** trusted from the request body. |
| `createdAt` | timestamp | Server-set on create. |
| `updatedAt` | timestamp | Server-set on every update. |
| `createdBy` | string | For notes this overlaps with the `USER`/`AI` enum (origin). Recommend keeping the app's `createdBy` enum as a separate `origin` column to avoid confusion, and use a generic audit `createdBy = userId`. See note below. |
| `updatedBy` | string | userId of last editor (same as owner in single-user model, but keep for consistency). |
| `deletedAt` | timestamp \| null | Optional — for **soft delete** (recommended; see §12). |

> **Naming clash to resolve:** the app's note `createdBy` means *origin* (`USER` vs `AI`), not *audit author*. To keep both, store the origin as a dedicated column (e.g. `origin ENUM('USER','AI')`) and use `created_by`/`updated_by` purely as audit columns holding a `userId`. The API response should expose **both** (`origin` for the app, audit fields for tooling).

---

## 6. Suggested database schema

Relational (PostgreSQL/MySQL) is recommended. Use the user table that already backs auth.

### 6.1 `quick_notes`
```sql
CREATE TABLE quick_notes (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL REFERENCES users(id),
    title         VARCHAR(300) NOT NULL,
    content       TEXT NOT NULL DEFAULT '',
    origin        VARCHAR(8) NOT NULL DEFAULT 'USER',   -- 'USER' | 'AI'
    image_url     TEXT,                                 -- null if no image
    image_key     TEXT,                                 -- storage object key (for delete)
    note_time     TIMESTAMPTZ NOT NULL,                 -- maps to app `timestamp`
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by    UUID,
    updated_by    UUID,
    deleted_at    TIMESTAMPTZ                            -- soft delete
);

CREATE INDEX idx_quick_notes_user_time   ON quick_notes (user_id, note_time DESC);
CREATE INDEX idx_quick_notes_not_deleted ON quick_notes (user_id) WHERE deleted_at IS NULL;
-- Optional full-text search:
-- CREATE INDEX idx_quick_notes_fts ON quick_notes USING GIN (to_tsvector('simple', title || ' ' || content));
```

### 6.2 `meeting_minutes`
```sql
CREATE TABLE meeting_minutes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id),
    title               VARCHAR(300) NOT NULL,
    start_time          TIMESTAMPTZ NOT NULL,
    end_time            TIMESTAMPTZ,                     -- null while active
    transcript          TEXT NOT NULL DEFAULT '',
    speaker_transcript  TEXT NOT NULL DEFAULT '',
    summary             TEXT NOT NULL DEFAULT '',
    speaker_count       INT NOT NULL DEFAULT 0,
    is_active           BOOLEAN NOT NULL DEFAULT false,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by          UUID,
    updated_by          UUID,
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_meetings_user_start    ON meeting_minutes (user_id, start_time DESC);
CREATE UNIQUE INDEX idx_one_active_meeting
    ON meeting_minutes (user_id) WHERE is_active = true AND deleted_at IS NULL;
```

> The partial unique index on `is_active` enforces the **"one active meeting per user"** rule at the DB level.

### 6.3 `participants` (optional normalization)
Today participants are a single comma-separated string. If you prefer to keep parity with the app, store it as a `participants TEXT` column on `meeting_minutes`. If you want it queryable, normalize:
```sql
CREATE TABLE meeting_participants (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meeting_id  UUID NOT NULL REFERENCES meeting_minutes(id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL
);
```
**Recommendation:** keep a simple `participants` string column for v1 (matches the app), normalize later if needed.

### 6.4 Relationships
- `users 1 — * quick_notes` (one user owns many notes)
- `users 1 — * meeting_minutes` (one user owns many meetings)
- `meeting_minutes 1 — * meeting_participants` (only if normalized)

---

## 7. Required API endpoints

All endpoints:
- Are prefixed with the version (`/v1/...`).
- Require `Authorization: Bearer <accessToken>`.
- Derive `userId` from the token.
- Return errors as `{ "error": { "message": "..." } }`.
- Return timestamps as **epoch milliseconds** in JSON (to match the app's `Long` fields) — or ISO-8601 if the team prefers, but please confirm with frontend so we map correctly. **Default assumption: epoch millis.**

### 7.1 Quick Notes endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/notes` | Create a note |
| `GET` | `/v1/notes` | List the user's notes (paginated, searchable, sortable) |
| `GET` | `/v1/notes/{id}` | Get one note by id |
| `PUT` | `/v1/notes/{id}` | Update a note (title, content, image) |
| `DELETE` | `/v1/notes/{id}` | Delete a note (and its image) |
| `POST` | `/v1/notes/{id}/image` | Upload/replace the note image (multipart) — see §11 |
| `DELETE` | `/v1/notes/{id}/image` | Remove the note image |

### 7.2 Meeting Minutes endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/v1/meetings` | Start/create a meeting (sets `isActive=true`) |
| `GET` | `/v1/meetings` | List meetings (paginated, searchable, sortable) |
| `GET` | `/v1/meetings/active` | Get the current active meeting (or `null`/404) |
| `GET` | `/v1/meetings/{id}` | Get one meeting by id |
| `PATCH` | `/v1/meetings/{id}/transcript` | **Append** transcript text to the active meeting |
| `PUT` | `/v1/meetings/{id}` | Update a meeting (e.g. end it: set `endTime`, `summary`, `isActive=false`, speaker data) |
| `POST` | `/v1/meetings/{id}/cancel` | Cancel/discard an active meeting |
| `DELETE` | `/v1/meetings/{id}` | Delete a meeting |

> **Transcript append:** while a meeting is active the app appends transcript chunks frequently. A dedicated `PATCH .../transcript` (append semantics) avoids the client re-sending the whole transcript each time and avoids race conditions. The app currently appends with a single space separator — backend should append with a leading space when the existing transcript is non-empty (mirrors current behavior). If the team prefers, an alternative is letting the client send the full transcript on `PUT` periodically; please confirm preference. **Recommendation: support the append PATCH.**

---

## 8. Request / response payload examples

> Timestamps shown as epoch millis. All list responses are paginated.

### 8.1 Create note — `POST /v1/notes`
Request:
```json
{
  "id": "f0c1b8e2-2c4a-4b1e-9b7a-1c2d3e4f5a6b",
  "title": "Buy groceries",
  "content": "Milk, eggs, bread",
  "origin": "USER",
  "noteTime": 1718539200000
}
```
Response `201 Created`:
```json
{
  "id": "f0c1b8e2-2c4a-4b1e-9b7a-1c2d3e4f5a6b",
  "userId": "u_12345",
  "title": "Buy groceries",
  "content": "Milk, eggs, bread",
  "origin": "USER",
  "imageUrl": null,
  "noteTime": 1718539200000,
  "createdAt": 1718539200500,
  "updatedAt": 1718539200500
}
```

### 8.2 List notes — `GET /v1/notes?query=milk&sort=noteTime&order=desc&page=1&pageSize=20`
Response `200 OK`:
```json
{
  "items": [
    {
      "id": "f0c1b8e2-...",
      "title": "Buy groceries",
      "content": "Milk, eggs, bread",
      "origin": "USER",
      "imageUrl": null,
      "noteTime": 1718539200000,
      "createdAt": 1718539200500,
      "updatedAt": 1718539200500
    }
  ],
  "page": 1,
  "pageSize": 20,
  "total": 1,
  "hasMore": false
}
```

### 8.3 Update note — `PUT /v1/notes/{id}`
Request:
```json
{
  "title": "Buy groceries (updated)",
  "content": "Milk, eggs, bread, coffee"
}
```
Response `200 OK`: the updated note object (same shape as create response, with refreshed `updatedAt`).

### 8.4 Start meeting — `POST /v1/meetings`
Request:
```json
{
  "id": "9d8c7b6a-...",
  "title": "Team Sync",
  "startTime": 1718539200000
}
```
Response `201 Created`:
```json
{
  "id": "9d8c7b6a-...",
  "userId": "u_12345",
  "title": "Team Sync",
  "startTime": 1718539200000,
  "endTime": null,
  "transcript": "",
  "speakerTranscript": "",
  "summary": "",
  "participants": "",
  "speakerCount": 0,
  "isActive": true,
  "createdAt": 1718539200500,
  "updatedAt": 1718539200500
}
```

### 8.5 Append transcript — `PATCH /v1/meetings/{id}/transcript`
Request:
```json
{ "text": "Let's review last week's action items." }
```
Response `200 OK`: the meeting object with the appended `transcript`.

### 8.6 End meeting — `PUT /v1/meetings/{id}`
Request:
```json
{
  "endTime": 1718542800000,
  "summary": "Discussed Q3 roadmap and assigned owners.",
  "speakerTranscript": "Speaker 1: ... Speaker 2: ...",
  "speakerCount": 2,
  "participants": "Alice, Bob",
  "isActive": false
}
```
Response `200 OK`: the completed meeting object.

### 8.7 Error example (any endpoint)
Response `404 Not Found`:
```json
{ "error": { "message": "Note not found." } }
```

---

## 9. User ownership & permissions

- Every record is scoped to `userId` derived from the JWT. **Never** read `userId` from the request body.
- On **GET by id / PUT / PATCH / DELETE**, verify the record's `user_id` matches the caller. If it doesn't, return **404** (not 403) so the existence of other users' records isn't leaked.
- List endpoints return **only** the caller's records.
- No sharing/collaboration is required in v1 (single-owner model). If sharing is added later, the schema's `user_id` ownership and audit fields are the foundation.

---

## 10. Search, filtering, and sorting

Mirror what the app does today, server-side:

**Quick Notes**
- **Search** (`query` param): case-insensitive match on `title` OR `content`.
- **Sort:** default `noteTime DESC` (newest first). The UI groups by month client-side — backend just needs reliable descending order.

**Meeting Minutes**
- **Search** (`query` param): case-insensitive match on `title` OR `transcript` OR `summary`.
- **Sort:** default `startTime DESC`.
- **Filter (nice to have):** `isActive`, date range (`from`/`to`).

**Common list params** (both):
| Param | Default | Notes |
|---|---|---|
| `query` | — | Keyword search |
| `sort` | `noteTime` / `startTime` | Sort field |
| `order` | `desc` | `asc` \| `desc` |
| `page` | 1 | 1-based |
| `pageSize` | 20 | Cap at e.g. 100 |

Use DB indexes from §6 to back search/sort. For large transcript search, full-text indexing is recommended over `LIKE %...%`.

---

## 11. Attachments, tags & metadata

**Note image (the only attachment today):**
- Today: one JPEG per note, compressed at ~85% quality, stored at an internal file path.
- Backend: store the image in object storage (S3-compatible) or a files endpoint. The DB keeps `image_url` (served back to the client) and `image_key` (for deletion).
- **Upload flow:** `POST /v1/notes/{id}/image` as `multipart/form-data` (field `file`). Response returns `{ "imageUrl": "...", "imageKey": "..." }` and updates the note.
- **Delete:** deleting a note (or calling `DELETE /v1/notes/{id}/image`) must delete the stored image object too — the app currently deletes the local image file on note delete, so parity is expected.
- Limits: accept `image/jpeg` and `image/png`, max size e.g. **10 MB**. Reject others with a validation error.

**Tags:** not used today. If you want to future-proof, an optional `tags TEXT[]`/join table is fine but **not required** for v1.

**Meeting metadata:** `speakerCount`, `speakerTranscript`, `participants` are already covered as columns (§6.2).

---

## 12. Data validation

| Field | Rule |
|---|---|
| `title` (note & meeting) | Required, non-empty after trim, max 300 chars |
| `content` (note) | Max length (suggest ~50 KB); allow empty |
| `origin` (note) | Must be `USER` or `AI` |
| `noteTime`, `startTime` | Required, valid epoch millis (> 0) |
| `endTime` (meeting) | If present, must be `>= startTime` |
| `summary`, `transcript`, `speakerTranscript` | Length cap (transcript can be large — suggest up to ~1 MB; confirm) |
| `speakerCount` | `>= 0` |
| `text` (transcript append) | Required, non-empty |
| image upload | mime in `{jpeg,png}`, size `<= 10 MB` |

Validation failures → **400** with `{ "error": { "message": "..." } }`.

---

## 13. ID strategy (client UUID vs server UUID)

Today the app generates a **UUID on the device** at creation time and uses it as the primary key locally. Two viable options:

1. **Accept the client UUID** (recommended for smooth migration & offline-create). Backend uses the provided `id` as the PK; enforce uniqueness; reject duplicates with 409.
2. **Server-assigned id**: backend ignores the client `id` and returns its own. The app then has to reconcile local id → server id.

**Recommendation: Option 1.** It makes offline-first behavior and the migration in §14 far simpler. Please confirm which the backend will support so the frontend can adapt.

---

## 14. Migration of existing local data

Existing users already have notes and meetings stored only on-device. We need a one-time push to seed the backend.

**Proposed approach:**
- Add **bulk import** endpoints (auth required), used once per device on first launch of the backend-enabled version:
  - `POST /v1/notes/bulk` — body: `{ "items": [ <note>, ... ] }`
  - `POST /v1/meetings/bulk` — body: `{ "items": [ <meeting>, ... ] }`
- These must be **idempotent**: upsert by `id` (client UUID). Re-running the import (e.g. app reinstalled before migration flag synced) must not create duplicates.
- Images: the app will upload each note's image separately via the per-note image endpoint after the note is imported.
- After a successful import, the app sets a local "migrated" flag and switches to using the backend as the source of truth.

**Backend asks:**
- Confirm bulk endpoints + upsert-by-id semantics are acceptable.
- Confirm a reasonable batch size limit (e.g. 200 items/request) so the app can chunk.

---

## 15. Error handling & edge cases

| Case | Expected behavior |
|---|---|
| Record not found / not owned | `404` `{ "error": { "message": "Note not found." } }` |
| Duplicate `id` on create/import | `409 Conflict`, or upsert in bulk import |
| Starting a meeting when one is already active | `409 Conflict` with a clear message (enforced by partial unique index), OR auto-end the previous — **please decide & document; recommend 409 so the app prompts the user** |
| Append transcript to a non-active / ended meeting | `409`/`400` with message |
| Expired access token | `401`; the app will refresh via `/v1/auth/refresh` and retry |
| Validation failure | `400` with message (see §12) |
| Oversized transcript / image | `413` or `400` with message |
| Empty list | `200` with `items: []`, `total: 0` |
| Partial bulk import failure | Return per-item results, or all-or-nothing transaction — **please specify**; recommend per-item results so a few bad rows don't block the rest |

---

## 16. Scalability & performance

- **Indexing:** the `(user_id, note_time DESC)` and `(user_id, start_time DESC)` indexes back the most common query (list newest-first for one user). Already in §6.
- **Pagination:** mandatory on list endpoints; cap `pageSize`. Consider keyset/cursor pagination (`?before=<timestamp>`) for very large histories.
- **Large text:** transcripts can be large. Store as `TEXT`; consider not returning full `transcript`/`speakerTranscript` in **list** responses (return a preview/excerpt + word count) and only the full body on **GET by id**. This matches how the app shows a list of cards then a detail screen. **Recommendation: list returns metadata + short preview; detail returns full transcript.**
- **Transcript append throughput:** the append endpoint can be called frequently during a live meeting. Keep it a cheap append (single `UPDATE ... SET transcript = transcript || ?`); avoid read-modify-write round trips where possible.
- **Image storage:** keep images in object storage, not the DB.
- **Soft delete:** recommended (`deleted_at`) so accidental deletes are recoverable and sync conflicts are easier; filter `deleted_at IS NULL` on all reads.

---

## 17. Open questions for the backend team

1. **Timestamp format** in JSON — epoch millis (frontend default assumption) or ISO-8601?
2. **ID strategy** (§13) — accept client UUID (recommended) or server-assigned?
3. **Active-meeting conflict** (§15) — reject with 409 (recommended) or auto-end previous?
4. **Transcript append** (§7.2) — dedicated PATCH append (recommended) or full-body PUT?
5. **Bulk import** (§14) — endpoints, batch size cap, and per-item vs all-or-nothing results.
6. **Transcript size cap** and whether list responses should omit full transcript bodies.

Once these are confirmed, the frontend will implement the network layer (reusing `AuthApi` patterns and the existing token/refresh flow) and the one-time migration.

---

## 18. Summary of deliverables requested from backend

- [ ] `quick_notes` and `meeting_minutes` tables (+ optional participants), with indexes and soft delete.
- [ ] CRUD + list endpoints for notes (§7.1) and meetings (§7.2), authenticated and owner-scoped.
- [ ] Note image upload/delete + object storage.
- [ ] Meeting lifecycle support (start, transcript append, end, cancel, active fetch).
- [ ] Search / sort / pagination on list endpoints.
- [ ] Validation + consistent `{ error: { message } }` responses.
- [ ] Bulk import endpoints for one-time migration (idempotent upsert by id).
- [ ] Answers to the open questions in §17.
