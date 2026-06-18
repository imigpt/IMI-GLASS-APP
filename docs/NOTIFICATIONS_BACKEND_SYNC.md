# Notifications Backend Sync — Implementation Guide

**For:** Backend Team
**From:** IMI Glass App (Android)
**Created:** 2026-06-18
**Goal:** Store every phone notification captured by the app on the backend, so notifications survive app reinstalls, sync across devices, and can be queried/served back to the app and the AI assistant.

---

## 1. What the App Already Does (Context)

The Android app runs a `NotificationListenerService` that captures every notification posted on the user's phone (WhatsApp, Gmail, SMS, etc.). Today it:

- Extracts `appName`, `title`, `text`, `time`, `packageName`
- Stores the **last 50** notifications locally in `SharedPreferences`
- Lets the AI assistant read them on request ("What notifications do I have?") — implemented in **both Mark 1 and Mark 2**

**What's missing:** notifications are only stored on-device. We need the backend to store **all** of them permanently.

### The exact data object the app captures

```kotlin
data class NotificationItem(
    val appName: String,      // "WhatsApp"
    val title: String?,       // "John Doe"
    val text: String?,        // "Are we still meeting at 5?"
    val time: Long,           // epoch millis, e.g. 1718706000000
    val packageName: String   // "com.whatsapp"
)
```

This is the payload the app will send to the backend. **Build the backend around this shape.**

---

## 2. What We Need From the Backend

Two things:

1. **Ingest endpoint** — the app POSTs a notification (or a batch) the moment it arrives. Backend stores it.
2. **Fetch endpoint** — the app (and AI) can pull the user's stored notifications back, newest first, with paging.

Everything is **per-user**, so all endpoints require the authenticated user (JWT — see existing `BACKEND_IMPLEMENTATION_GUIDE.md` auth section; reuse the same auth).

---

## 3. Database Schema

```sql
CREATE TABLE notifications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    app_name        VARCHAR(255) NOT NULL,
    title           TEXT,
    body            TEXT,                 -- maps to NotificationItem.text
    package_name    VARCHAR(255) NOT NULL,
    posted_at       TIMESTAMP WITH TIME ZONE NOT NULL,  -- from NotificationItem.time
    device_id       VARCHAR(255),         -- optional: which phone sent it
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    -- de-duplication: same notification should not be stored twice
    dedupe_key      VARCHAR(512)
);

CREATE INDEX idx_notifications_user_id   ON notifications(user_id);
CREATE INDEX idx_notifications_posted_at ON notifications(posted_at DESC);
CREATE INDEX idx_notifications_package   ON notifications(package_name);

-- Prevent duplicates (same user + app + title + body + time)
CREATE UNIQUE INDEX idx_notifications_dedupe
    ON notifications(user_id, dedupe_key);
```

**`dedupe_key`** = a hash the app sends (or the backend computes) of
`packageName + "|" + title + "|" + text + "|" + time`. This stops the same
notification being inserted twice if the app retries after a network failure.
On conflict, **ignore** (don't error).

---

## 4. API Endpoints

All endpoints are prefixed `/api/v1/notifications` and require
`Authorization: Bearer <token>`.

### 4.1 POST `/api/v1/notifications` — ingest a single notification

The app calls this immediately when a new notification arrives.

**Request**
```json
{
    "app_name": "WhatsApp",
    "title": "John Doe",
    "text": "Are we still meeting at 5?",
    "time": 1718706000000,
    "package_name": "com.whatsapp",
    "device_id": "pixel-7-abc123"
}
```

**Response (201)**
```json
{
    "success": true,
    "data": { "id": "uuid", "stored": true }
}
```

If it's a duplicate (dedupe_key already exists), still return `200` with
`"stored": false` — not an error.

---

### 4.2 POST `/api/v1/notifications/batch` — ingest many at once

Used when the phone was offline and the app needs to flush a backlog.

**Request**
```json
{
    "notifications": [
        { "app_name": "WhatsApp", "title": "John", "text": "Hi", "time": 1718706000000, "package_name": "com.whatsapp" },
        { "app_name": "Gmail", "title": "Invoice", "text": "Your bill", "time": 1718706100000, "package_name": "com.google.android.gm" }
    ]
}
```

**Response (200)**
```json
{
    "success": true,
    "data": { "received": 2, "stored": 2, "duplicates": 0 }
}
```

---

### 4.3 GET `/api/v1/notifications` — fetch stored notifications

Used by the app and the AI assistant to read history.

**Query params**
| param      | type | default | notes |
|------------|------|---------|-------|
| `limit`    | int  | 20      | max 100 |
| `offset`   | int  | 0       | paging |
| `app_name` | str  | —       | optional filter |
| `since`    | long | —       | epoch millis; only newer |

**Response (200)**
```json
{
    "success": true,
    "data": [
        {
            "id": "uuid",
            "app_name": "WhatsApp",
            "title": "John Doe",
            "text": "Are we still meeting at 5?",
            "time": 1718706000000,
            "package_name": "com.whatsapp"
        }
    ],
    "pagination": { "total": 134, "limit": 20, "offset": 0 }
}
```

> **Important:** keep the JSON field names **`app_name`, `title`, `text`, `time`,
> `package_name`** in the response so they map straight back onto the app's
> existing `NotificationItem` model.

---

### 4.4 DELETE `/api/v1/notifications` — clear the user's notifications

Mirrors the in-app "clear notifications" action.

**Response (200)**
```json
{ "success": true, "data": { "deleted": 134 } }
```

---

## 5. App-Side Integration Point (for reference)

When you give us the live endpoints, the app will call them from
`NotificationListener.onNotificationPosted()` — the same place that already
saves to local storage:

```kotlin
// app/src/main/java/com/sdk/glassessdksample/NotificationListener.kt
override fun onNotificationPosted(sbn: StatusBarNotification) {
    // ... build NotificationItem (already implemented) ...
    saveNotification(notificationItem)        // local cache (exists today)
    NotificationSync.upload(notificationItem) // -> POST /api/v1/notifications  (to add)
}
```

- Uploads run on a background thread, **fire-and-forget** with retry.
- On failure (offline), items queue locally and flush via the **batch** endpoint
  when connectivity returns.
- The AI's `read_notifications` tool will prefer the backend when online, falling
  back to the local cache when offline.

The app handles all of that. **You only need to build sections 3 and 4.**

---

## 6. Acceptance Checklist (Backend)

- [ ] `notifications` table + indexes created (Section 3)
- [ ] `POST /api/v1/notifications` stores one, de-dupes on `dedupe_key`
- [ ] `POST /api/v1/notifications/batch` stores many, reports counts
- [ ] `GET /api/v1/notifications` returns newest-first with paging + filters
- [ ] `DELETE /api/v1/notifications` clears for the user
- [ ] All endpoints require JWT and are scoped to the authenticated user
- [ ] Response field names match `app_name / title / text / time / package_name`
- [ ] Duplicate inserts return success (not 4xx/5xx)

---

## 7. Notes & Privacy

- Notification content can be sensitive (messages, OTPs). Store encrypted at rest
  and serve only over HTTPS, scoped to the owning user — same standard as chat
  messages in `BACKEND_IMPLEMENTATION_GUIDE.md`.
- Consider a retention policy (e.g. auto-delete notifications older than 90 days)
  and a per-user cap if storage becomes a concern.
- OTP/banking notifications: optionally let the backend skip storing certain
  `package_name`s via a configurable blocklist.

---

**Version:** 1.0 · **Created:** 2026-06-18 · **Owner:** Mobile + Backend teams
