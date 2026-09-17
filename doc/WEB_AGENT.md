# The in-app web agent

How the voice-driven browser agent works, what it is made of, and — just as
importantly — the things that went wrong building it and why the code looks the
way it does as a result.

Written for: an engineer picking this up who did not build it.

---

## 1. What it is

A **web browsing agent**. The user speaks; the app drives a real browser on the
phone and carries out a multi-step task on a live website.

It is the same category of thing as Browser Use or Skyvern, with one meaningful
difference: those run a browser in the cloud, this runs in the phone's own
WebView, holding **the user's own logged-in sessions**. It can therefore act as
the user on sites they are signed into, which a cloud browser cannot do.

It is **not** a script. A script says "click the third button" and breaks the
day the site changes. This reads the page, asks a model what to do next, does
one thing, and looks again.

### The loop

```
  ┌─────────────────────────────────────────────┐
  │  read the page   (PageReader)               │
  │        ↓                                    │
  │  plan ONE action (WebAgentPlanner → Gemini) │
  │        ↓                                    │
  │  check it       (ActionValidator)           │
  │        ↓                                    │
  │  do it          (ActionExecutor)            │
  └──────────────────┬──────────────────────────┘
                     └── repeat
```

One action per turn, never a plan of ten steps. Pages change underneath you, so
a plan made three steps ago is usually wrong by the time you reach it.

### The conversation around the loop

```
user: "start a task"
   ↓  start_task
IMI asks what it needs (date? how many? which one?)
   ↓  task_answer  ×N
IMI writes a plan → speaks it AND shows it on the phone
   ↓  task_approve
THE LOOP RUNS
   ↓
hits a login/CAPTCHA → shows the page, user does it, taps Continue
hits something unexpected → asks the user, takes the answer, carries on
   ↓
done → says what happened, shows the page it ended on
```

---

## 2. Design decisions worth knowing

### Triggered by name, never chosen by the model

The browser is reachable **only** through `start_task`, which fires on the
phrases *"do a task for me"* / *"start a task"*.

This is not arbitrary. `browse_web` was previously declared to the live voice
model, and the model reliably picked it for ordinary questions — "flights to
Jaipur", "restaurants nearby". That opened a real WebView, hit a sign-in wall,
and the user got *"I couldn't get past the security check"* instead of an
answer. No amount of prompt wording stopped a model reaching for a tool sitting
in front of it.

Ordinary questions now go to Gemini's own `google_search` grounding, answered
server-side in the same reply. The browser is for tasks the user asks for by
name.

### Plan first, act second

The agent used to start navigating the moment it had a goal, inventing every
detail it had not been told — dates, times, passenger counts — and then getting
stuck deep inside a booking flow for the wrong journey.

`TaskPlanner` now runs a short interview first (max six questions), then writes
a plan with its assumptions and handoff points stated. Nothing touches the
browser until the user approves, by voice or by tapping.

### It never handles credentials or money

Enforced in code (`ActionValidator`), not in the prompt — a prompt is a request,
this needs to be a guarantee:

- never types into a password/OTP/CVV field
- never answers a CAPTCHA
- login, CAPTCHA and payment all **park and hand the phone to the user**
- anything that spends money needs an explicit confirmation

### Two agents, one brain

- `HeadlessAgentRunner` — the voice path. No screen to ask, so every branch ends
  in something speakable.
- `WebAgentSession` — the on-screen path in the Web section, which can show
  dialogs and a status strip.

Both share `WebAgentPlanner`, `ActionValidator`, `ActionExecutor`, `PageReader`.

---

## 3. The files

| File | What it does |
|---|---|
| `GlassBrowserEngine` | Process-wide off-screen WebView. Shares its cookie jar with the visible browser, so the user's logins carry over. Holds the parked state when the agent stops. |
| `GlassBrowserTools` | The tools the voice model calls: `start_task`, `task_answer`, `task_approve`, `browser_continue`, `browser_cancel`, plus direct manipulation (`browser_click`, `browser_type`, …). |
| `TaskSession` | The task's state across voice turns — GATHERING → AWAITING_APPROVAL → EXECUTING. A voice turn is stateless, so the half-built task lives here. |
| `TaskPlanner` / `TaskPlan` | The interview and the plan. Separate spoken and on-screen renderings. |
| `WebAgentPlanner` | Decides one browser action from the page summary. |
| `VisionPlanner` | Decides one action from a **screenshot**, when the page code is not describing the page. |
| `PageReader` | Injected JS that turns the live page into a compact summary — visible inputs, buttons, links, each with a stable selector. |
| `ActionValidator` | The security boundary. Every action crosses it. |
| `ActionExecutor` | Performs the action against the WebView. |
| `HeadlessAgentRunner` | The loop, for voice. |
| `WebAgentSession` | The loop, for the Web screen. |
| `BrowserHandoffOverlay` | Full-screen overlay on the home screen showing a blocked page or a plan awaiting approval. |
| `SignInSite` / `SignInAccountsActivity` | Settings → More → Signed-in sites. Pre-authenticate the sites the agent will act on. |
| `WebSessionManager` | The shared cookie jar. Why any of this works. |
| `WebBrowserActivity` | The manual in-app browser (Web section). |

---

## 4. The hybrid: page code first, eyes second

Reading the DOM is instant, free, and gives exact selectors. It is the normal
path. But a site that draws its controls as unmarked `<div>`s produces a summary
with nothing usable in it, and no amount of widening the query finds a control
that was never marked up as one.

So a screenshot is the **last resort**, and deliberately expensive to reach —
roughly 2000 input tokens and a second or two per step, against ~0 for a DOM
read.

**Vision fires when any of these is true** (and never on step 1, when nothing
has loaded yet):

1. the page summary has no inputs, no buttons and no links;
2. **two actions in a row have failed** — the planner is quoting selectors that
   do not work;
3. **the page has not changed for three steps** — every action claims success
   while nothing moves.

Trigger 3 exists because trigger 2 only catches the *loud* kind of stuck. See
§5.

`VisionPlanner` returns coordinates rather than selectors, because coordinates
are what a picture can honestly give. `ActionExecutor.runTapAt` resolves the
element under the point with `elementFromPoint` and walks to the nearest
interactive ancestor, so the result can report *what it actually hit* — a blind
coordinate landing on background reports failure instead of false success.

Coordinate taps run the **same** login and payment checks as a normal click. A
screenshot must not become a way around the guards.

---

## 5. Bugs found the hard way

Every one of these cost a real debugging session. They are recorded because the
code's shape only makes sense with them in mind.

### maxOutputTokens = 400

The action planner was capped at 400 output tokens. On a big page the model ran
out mid-JSON, the SDK threw `ResponseStoppedException(MAX_TOKENS)`, and a
generic `catch` turned that into `null` — which the user heard as *"I couldn't
work out how to do that."* A one-line config bug that looked like a broken
feature. Now 2048, and MAX_TOKENS is reported as itself rather than collapsed
into "confused".

### CAPTCHA detection matched everything

`hasCaptcha` tested the whole page source against `/captcha/`. Nearly every
large site ships reCAPTCHA in some bundled script, so Google's results page and
a 404 page both "had a CAPTCHA". Now it requires a **rendered, visibly sized**
challenge iframe or widget.

### A pin code is not an OTP

`looksLikeCredential` treated any bare 4–8 digit number as a secret. Indian
postcodes are six digits. So *"deliver to pin code 302020"* was unachievable by
construction: the one string the task required was the one string the validator
forbade, and the agent looped on a login handoff while already signed in.
The digit-shape rule is gone; where the text is *going* is the reliable signal.

### The mic was muted at the moment it asked you to speak

A gate dropped audio frames while `phase == EXECUTING`, so the agent said *"say
continue and I'll resume"* through a microphone that had just been switched off.
The gate now exempts `awaitingUser`.

### Continue did not continue

`resume()` cleared a flag and returned the parked goal — and nothing consumed
it. The actual re-run lived behind a voice tool, so if the session had ended the
goal was dropped on the floor. Both the tap path and the voice path now run it.

### Resuming started from nothing

A resumed run began with an **empty history**. It re-planned from scratch, could
not see what it had already achieved, and concluded within a step or two that it
was finished. History is now parked on every wait path and carried across.

### Vision was photographing a blank page

The loop reads the page *before* navigating anywhere, so on step 1 the summary
is empty — correctly, there is no page. The "empty means unreadable" gate fired
on that, and every run spent its vision budget on a screenshot of `about:blank`.
Now gated on `isBlank` and `steps > 1`.

### The summary was truncated before the search box

`MAX_PAGE_CHARS` was 6000. The widened element selector legitimately finds far
more controls on a modern site, so the summary was chopped mid-list and the
planner quoted a selector from a view that no longer existed — *"field not
found"* for a box in plain sight. Raised to 24000; links capped at 25 so they
cannot crowd out inputs and buttons; truncation now announces itself.

### It typed the same thing forty times

The type result said *"the next page summary will show suggestions — pick one
from there."* When no suggestions appeared, the planner did the only thing that
seemed available: typed again. Every step reported **success**, so the
failure-counter never fired.

Three fixes: the result now states *"the text is now IN it, do NOT type it
again"* and names the next move; a planner rule forbids retyping; and the
history window went from 8 to 20 steps, because a loop longer than the window is
invisible to the model.

This is also why page-signature stuck-detection exists — success is not evidence
of progress.

---

## 6. Steps, and why it stops

The budget **extends itself**. A long job stopping every 40 steps to ask "shall
I keep going?" was the single most annoying thing about it — nothing had gone
wrong, and the user was granting steps the agent could have taken itself.

- first leg: **40** steps (headless) / 50 (on-screen)
- reaching the budget while *still making progress* → silently grants **+20**
- hard ceiling: **200** (headless) / 250 (on-screen)

It stops early only when genuinely stuck — nothing in the last 6 steps succeeded
— because more steps would repeat the same failure and the user's guidance is
worth more than the budget.

The hard ceiling is deliberate. Every step is a paid model call driving a
browser on the user's logged-in accounts; an agent that extends itself
indefinitely could run a long way on a misunderstanding with nobody watching.

---

## 7. Sites

Amazon works well. The DOM read describes it accurately.

**MakeMyTrip is the hostile case** and worth understanding before optimising for
it. It has produced `ERR_HTTP2_PROTOCOL_ERROR` — the server closing the
connection at the protocol level, which is bot detection rejecting the WebView
before any page renders. That is not fixable by better parsing. Its form is also
entirely custom `<div>` widgets, which is what motivated the vision fallback.

Flight OTAs and ticketing sites are the hardest targets on the web. A site the
user is signed into and that is not actively defending itself is the case this
agent is good at.

---

## 8. Known limits

- **Vision cannot type.** It taps a field to focus it; typing still goes through
  the DOM path. On a page where fields are *also* invisible to the DOM, it will
  focus a field and have nothing to type into.
- **Coordinates are fuzzier than selectors.** The ancestor-walk mitigates a near
  miss; it does not eliminate it.
- **"Signed in earlier" is a guess.** All that can be detected is whether
  session-shaped cookies exist — not whether the site still honours them. The
  wording is deliberately not a confident tick.
- **Several behaviours are prompt-level**, not code: the trigger-phrase gate,
  the feasibility check, the no-retyping rule. They are strong instructions, not
  guarantees.
- **The handoff overlay is not wired into `ListeningService`.** It attaches in
  `MainActivity` and `Mark1MainActivity` only, so during a background voice
  session it does not appear. Outstanding.

---

## 9. Debugging

```bash
adb logcat -v time -s TaskSession:D GlassBrowserTools:D GlassBrowserEngine:D \
  HeadlessAgentRunner:D VisionPlanner:D WebAgentPlanner:D
```

Every step now logs its action and outcome:

```
step 7: Typing into the page -> ok: Typed "coffee beans" into the field...
Stuck after 2 failures — using vision
Page unchanged for 3 steps — using vision
Extending budget to 60 (total 40)
```

A note from experience: several rounds of fixes here were made from user
descriptions rather than logs, and most of them were aimed at the wrong thing.
The per-step logging exists because diagnosing a stuck run from a budget counter
alone is guesswork. **Get the log first.**
