# Android AI Usage Reporting — Backend Handoff

**As of:** 2026-09-23

## Summary

The Android app (IMI-GLASS-APP) now reports every Gemini call to `POST /v1/ai/usage`, using the same contract as iOS. No new endpoint is needed. The backend work is mainly **pricing coverage**: Android sends five Gemini model ids, and any id missing from `src/ai/pricing.js` is priced at $0, so that usage never shows up as a cost.

The Android change is code-complete and compiles. It has not yet been verified against production with a real device session.

## Backend action items

- [ ] **Add a pricing entry for every model id below.** Pricing is looked up by exact string, so an unknown id is recorded at $0.
- [ ] **Decide how to price Live audio tokens.** The native-audio Live model charges more for audio input and output than for text, but the payload carries only token totals with no text/audio split. Pricing Live at text rates will undercount it.
- [ ] **Check the volume is fine.** Live sends one POST per `usageMetadata` frame, so a long voice session means many small writes per user.
- [ ] **Confirm Android reports show up** in Users → AI Usage after the first device test (see [Verification](#verification-and-open-questions)).

| Model id Android sends | Where it's used | Pricing entry needed |
| --- | --- | --- |
| `gemini-2.5-flash` | Vision, meetings, summarizer, planners, chat fallback | Likely already there — confirm |
| `gemini-2.0-flash-lite` | Text chat (first choice) | Confirm |
| `gemini-2.0-flash` | Text chat fallback, web planner fallback | Confirm |
| `gemini-2.5-flash-native-audio-preview-09-2025` | Gemini Live voice (main) | Confirm — audio rates |
| `gemini-2.0-flash-live-001` | Gemini Live fallback if the main model is rejected | Confirm |

## What Android sends

One POST per Gemini call, with a Bearer access token, sent in the background with no retry. The body never includes a cost, and `conversationId` isn't sent yet.

```json
{
  "model": "gemini-2.5-flash",
  "inputTokens": 1200,
  "outputTokens": 400,
  "feature": "vision",
  "occurredAt": 1790152263000
}
```

A report is skipped when both token counts are 0, the model id is blank, or nobody is logged in.

**How token counts are worked out (REST calls):** `inputTokens` = `promptTokenCount`. `outputTokens` = the larger of `candidatesTokenCount` and `totalTokenCount − promptTokenCount`, so 2.5-model thinking tokens count as output.

| App feature | `feature` sent |
| --- | --- |
| Text chat, including the REST voice-chat fallback | `chat` |
| Image analysis (Vision Chat, Chat image questions, web vision planner) | `vision` |
| Meeting transcription and meeting summary | `summarize` |
| Web page summarizer | `summarize` |
| Web task / agent planners | `other` |
| Gemini Live voice session | `live` |

## Gemini Live reporting

Android reports every Live `usageMetadata` frame exactly as received, one POST each, and never subtracts the previous frame. This matches iOS. It was chosen because a Live session re-sends the whole conversation as context each turn, and Google bills that context again on every turn. A growing `promptTokenCount` is therefore a real repeated charge, not a running total.

Reporting per frame also means a session cut off by a dropped socket or a force-quit is still billed up to that point.

| Live field | Maps to |
| --- | --- |
| `promptTokenCount` | `inputTokens` |
| `responseTokenCount` (or `candidatesTokenCount`) | `outputTokens` |
| `thoughtsTokenCount` | added to `outputTokens` |
| `toolUsePromptTokenCount` | added to `outputTokens` (Live sessions declare function tools and Search grounding) |
| `totalTokenCount` only, no split | all sent as `outputTokens`, with `inputTokens: 0` |

**Not yet confirmed on a device.** If the numbers are far higher than a rough estimate, the frames may be cumulative after all. The app would then need to send only the difference since the last frame. The raw frames are logged in Logcat as `📊 Live usageMetadata:` so this can be checked.

## Known gaps

- **OpenAI Realtime voice isn't reported.** The app can use `gpt-4o-mini-realtime-preview` instead of Gemini Live. The backend only prices Gemini, so these calls aren't sent. If admin wants this cost too, the backend needs OpenAI pricing and we'll add the reporting.
- **No `conversationId`.** Reports aren't linked to `/v1/conversations` yet.
- **Reports can be lost.** A report that fails, or that is made while the user is signed out, is dropped and never retried. Expect small gaps, not duplicates.
- **Older builds send nothing.** Only Android builds with this change report usage, so admin totals will be low for users still on an older version.

## Verification and open questions

Plan for the first joint test:

1. Android runs one chat, one image analysis, one meeting summary and one Live session of at least 3 turns on a test account.
2. Backend checks `GET /v1/admin/ai-usage/:userId/breakdown` for that user. Each feature should appear, and none of the models should show `costUsd: 0`.
3. Compare the Live total to the raw frames in Logcat. If it's far higher than expected, look at the cumulative-frames issue above first.
4. Delete the test events from the admin panel afterwards.

Questions for backend:

- [ ] Are all five model ids above priced now? If not, when can they be added?
- [ ] Can pricing tell audio tokens apart from text for the native-audio Live model, or should Android send a separate audio token count?
- [ ] Is one POST per Live frame acceptable, or would you prefer the app to batch Live reports (for example, once per turn)?
