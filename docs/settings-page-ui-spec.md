# Settings Page — UI Specification
**File:** `app/src/main/res/layout/activity_settings.xml`  
**Controller:** `app/src/main/java/com/sdk/glassessdksample/SettingsActivity.kt`

---

## Overview

The Settings page is a vertically scrollable screen that lets the user configure AI model, wake word engine, view usage limits, and manage system permissions. It uses the app's glassmorphism design language — semi-transparent dark cards on a gradient background.

---

## Screen Layout

```
┌─────────────────────────────────┐
│  "Settings"          (title)    │
│  "Configure app behavior..."    │
│                                 │
│ ┌─────────────────────────────┐ │
│ │  CARD 1 — AI Model          │ │
│ └─────────────────────────────┘ │
│ ┌─────────────────────────────┐ │
│ │  CARD 2 — Wake Word Engine  │ │
│ └─────────────────────────────┘ │
│ ┌─────────────────────────────┐ │
│ │  CARD 3 — Usage Limits      │ │
│ └─────────────────────────────┘ │
│ ┌─────────────────────────────┐ │
│ │  CARD 4 — Settings & Perms  │ │
│ └─────────────────────────────┘ │
│                                 │
│ ═══════ BOTTOM NAV ════════════ │
└─────────────────────────────────┘
```

**Root container:** `CoordinatorLayout`  
**Scrollable area:** `NestedScrollView` with `94dp` bottom margin (clears the bottom nav bar)  
**Content padding:** `16dp` on all sides  
**Background:** `@drawable/bg_app_gradient`

---

## Color & Style Tokens Used

| Token | Hex | Usage |
|---|---|---|
| `glass_surface` | `#CC2F3136` | Card background (semi-transparent dark) |
| `glass_stroke_soft` | `#0FFFFFFF` | Card border (very subtle white) |
| `glass_primary` | `#C9CDD3` | Highlighted text (current values) |
| `text_primary` | `#FFFFFF` | Main labels, button text |
| `text_secondary` | `#D7D9DD` | Subtitles, hint text |
| Card corner radius | `16dp` | All four cards |
| Card elevation | `0dp` | Flat (no shadow) |

---

## Card 1 — AI Model

**Purpose:** Switch the AI assistant backend between GPT Realtime and Gemini Live.

```
┌─────────────────────────────────────┐
│ AI Model                    (bold)  │
│ Current model: GPT          (chip)  │
│                                     │
│ [ GPT                             ] │
│ [ Gemini                          ] │
└─────────────────────────────────────┘
```

### Elements

| ID | Type | Text | Behavior |
|---|---|---|---|
| *(none)* | `TextView` | "AI Model" | Section title — `14sp`, bold, `text_primary` |
| `tvCurrentModel` | `TextView` | "Current model: GPT" | Live status label — `12sp`, `glass_primary` |
| `btnModelGpt` | `Button` | "GPT" | `44dp` tall, `bg_button_secondary`, `text_primary`. **Disabled** when GPT is active |
| `btnModelGemini` | `Button` | "Gemini" | Same style. **Disabled** when Gemini is active |

### Logic
- On tap: calls `GeminiLiveService.saveModelProvider()` and updates `tvCurrentModel`.
- The **active** button is `isEnabled = false` (visually indicates the current selection).
- The **inactive** button is `isEnabled = true` and acts as the switch action.

---

## Card 2 — Wake Word Engine

**Purpose:** Choose which wake word detection engine runs on device.

```
┌─────────────────────────────────────┐
│ Wake Word Engine            (bold)  │
│ Current engine: Custom Wake Word    │
│                                     │
│ [ Use Custom Wake Word            ] │
│ [ Use Snowboy                     ] │
│                                     │
│ "Set this before connecting..."     │
└─────────────────────────────────────┘
```

### Elements

| ID | Type | Text | Behavior |
|---|---|---|---|
| *(none)* | `TextView` | "Wake Word Engine" | Section title — `14sp`, bold |
| `tvCurrentWakeEngine` | `TextView` | "Current engine: Custom Wake Word" | Live status — `12sp`, `glass_primary` |
| `btnWakeEngineCustom` | `Button` | "Use Custom Wake Word" | `44dp`, disabled when CUSTOM_ONNX is active |
| `btnWakeEngineSnowboy` | `Button` | "Use Snowboy" | `44dp`, disabled when SNOWBOY is active |
| *(none)* | `TextView` | "Set this before connecting glasses. Snowboy requires…" | Footer note — `11sp`, `text_secondary` |

### Logic
- On tap: calls `WakeWordEngineSettings.setSelectedEngine()`, then attempts to update the running `HotHelper` instance (silently ignores failure if HotHelper isn't active yet).
- Same active/inactive `isEnabled` pattern as Card 1.

---

## Card 3 — Usage Limits

**Purpose:** Show how many calls of each AI feature the user has consumed this period, with a plan badge and upgrade path.

```
┌─────────────────────────────────────┐
│ Usage Limits                (bold)  │
│ Track used vs remaining calls...    │
│                                     │
│ Current plan          Free          │
│ ₹0 — Basic plan                     │
│ ─────────────────────────────────── │
│ Voice Chat              0 / 15      │
│ ████░░░░░░░░░░░░░░░░░░░ (progress)  │
│ Remaining: 15                       │
│                                     │
│ Vision (Image/Camera)    0 / 5      │
│ ████░░░░░░░░░░░░░░░░░░░ (progress)  │
│ Remaining: 5                        │
│                                     │
│ Chat                     0 / 50     │
│ ████░░░░░░░░░░░░░░░░░░░ (progress)  │
│ Remaining: 50                       │
│                                     │
│ [ Upgrade Plan                    ] │
└─────────────────────────────────────┘
```

### Plan Row

| ID | Type | Default text |
|---|---|---|
| `tvCurrentPlan` | `TextView` | "Free" — `16sp`, bold, `glass_primary` |
| `tvPlanPrice` | `TextView` | "₹0" — `11sp`, `text_secondary` |

A `1dp` horizontal divider (`glass_stroke_soft`) separates the plan info from the usage rows.

### Usage Rows (repeated ×3)

Each feature (Voice Chat, Vision, Chat) has three elements stacked vertically:

| ID | Type | Content |
|---|---|---|
| `tvVoiceCalls` / `tvSeeingCalls` / `tvChatCalls` | `TextView` | "used / limit" — `11sp`, `text_secondary` |
| `progressVoiceCalls` / `progressSeeingCalls` / `progressChatCalls` | `ProgressBar` (horizontal) | `8dp` tall, `max=100`, tint = `glass_primary` |
| `tvVoiceRemaining` / `tvSeeingRemaining` / `tvChatRemaining` | `TextView` | "Remaining: N" — `11sp`, `text_secondary` |

Feature label and count are displayed on the same horizontal row using a weight-based `LinearLayout` (label `weight=1`, count `wrap_content`).

### Upgrade Button

| ID | Type | Text |
|---|---|---|
| `btnUpgradePlan` | `Button` | "Upgrade Plan" — `44dp`, `bg_button_secondary` |

On tap: opens `UsageLimitManager.showUpgradeDialog()`, then refreshes all usage numbers on dialog close.

---

## Card 4 — Settings & Permissions

**Purpose:** Miscellaneous toggle settings and a shortcut to Android's system permission manager.

```
┌─────────────────────────────────────┐
│ Gemini acknowledgement    [ ON/OFF ]│
│                                     │
│ [ Open Permission Settings        ] │
└─────────────────────────────────────┘
```

### Elements

| ID | Type | Default | Notes |
|---|---|---|---|
| *(none)* | `TextView` | "Gemini acknowledgement" | `13sp`, `text_primary`, `weight=1` |
| `switchGeminiAck` | `SwitchCompat` | `true` (on) | Saves to `imi_prefs` key `useGeminiAck`. Shows toast on change. |
| `btnOpenPermissionSettings` | `Button` | "Open Permission Settings" | Fires `ACTION_APPLICATION_DETAILS_SETTINGS` intent. `44dp` height. |

---

## Bottom Navigation Bar

Shared with all main screens. Managed by `BottomNavManager.setup()`.

| Property | Value |
|---|---|
| ID | `bottomNavigation` |
| Height | `68dp` |
| Background | `@drawable/bg_bottom_nav_glass` |
| Margins | `12dp` left/right, `8dp` bottom |
| Active item | `nav_settings` |
| Icon/text tint | `@color/bottom_nav_item_color` |
| Label mode | `labeled` |

---

## Data Flow Summary

```
onResume()
  └─ refreshUsageUi()        ← pulls from UsageLimitManager.getSnapshot()
  └─ updateWakeEngineUi()    ← pulls from WakeWordEngineSettings

User taps model button
  └─ GeminiLiveService.saveModelProvider()
  └─ updateModelUi()         ← swaps isEnabled on both buttons + updates tvCurrentModel

User taps wake engine button
  └─ WakeWordEngineSettings.setSelectedEngine()
  └─ HotHelper.setWakeWordEngine()   (best-effort, may fail silently)
  └─ updateWakeEngineUi()

User taps Upgrade Plan
  └─ UsageLimitManager.showUpgradeDialog()
  └─ refreshUsageUi()        ← called in dialog callback

User toggles Gemini Ack switch
  └─ imi_prefs.putBoolean("useGeminiAck", checked)
  └─ Toast shown

User taps Open Permission Settings
  └─ startActivity(ACTION_APPLICATION_DETAILS_SETTINGS)
```

---

## Known Design Notes

- **Active button = disabled.** Both model and wake engine selectors use `isEnabled = false` on the currently active option. There is no visual difference beyond the Android disabled state — no color change or checkmark. This is the main UX gap a redesign should fix.
- **Progress bar tint** uses `glass_primary` (`#C9CDD3`, light gray) — all three bars look identical regardless of feature type.
- **Card 4 has no section title** — unlike Cards 1–3 which have a bold header label, the permissions/toggle card has no heading.
- **`bg_button_secondary`** is the same drawable for every button on this page — primary, secondary, and destructive actions are visually identical.
