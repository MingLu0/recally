# Recally — Android App

Native Android. Kotlin + Jetpack Compose + Material 3.

## Screens

### 1. Today
- Due count + new card count, streak, start-review button.
- Entry point from FCM notification deep link.

### 2. Review session
- Card front → tap to flip → rating buttons: Again / Hard / Good / Easy.
- Response time captured automatically (tap-to-rate duration) for review_logs.
- **Bury** (overflow action, available before flipping): drops the card from the rest of today's session via `POST /cards/{id}/bury`. This is the honest alternative to rating a card you don't want to answer — a dishonest rating corrupts `review_logs`, which trains both the FSRS optimizer and the Learner. Requires connectivity; offline, the action is unavailable rather than queued, since the session is over before it would sync.
- **Edit** (overflow action, after flipping): fix wording in place via `PATCH /cards/{id}`. Scheduling is untouched (ADR-008).
- **Same-session relearning**: FSRS learning steps are minutes long, so a card rated Again or Hard comes back inside the session. The client re-queues it after the step interval from `learning_steps_minutes` (or at the end of the queue if the session is shorter than the step). Which step applies is seeded from the card's `step` in `GET /reviews/due` — a card already at step 1 must not restart at step 0.
- Offline, no rate response comes back, so the client keeps its own in-session step counter: it advances one step on a card it re-queues, stops re-queueing past the last entry in `learning_steps_minutes`, and overwrites the counter with the `step` from the rate response whenever a rating is posted online. This counter is a display timer for *when to show the card again in this session*, nothing more — the client never runs FSRS, the server owns the real state, and the local view is corrected on the next `GET /reviews/due` (ADR-005).
- Session summary at end (reviewed count, time, lapses). Reviewed count and elapsed time are local; the lapse count sums the `lapsed` flag over rate responses with `duplicate: false`, because whether a rating is a lapse depends on FSRS state the server owns (Again on a card already in `learning` is not a lapse) and a replayed rating reports `lapsed: false`.

### 3. Approval queue
- Pending cards grouped by chapter, with front/back, critic critique, and all source highlights shown for context (a grouped card has several).
- Approve / edit inline / reject (with optional reason).

### 4. Decks
- Book list → chapters → cards. Browsing, plus the per-card controls from ADR-008: edit (`PATCH /cards/{id}`), suspend and unsuspend. Suspended cards are shown here with their state — this screen is the only way back from a suspend, so it cannot filter them out.

### 5. Stats
- Streak, retention, forecast chart, lapse rate by card type, lapse rate by Writer guidance version (the roadmap 6b gate; hidden until there is more than one version), curation yield.

### 6. Settings
- Backend base URL, API key, connection test. See *Connecting to the backend*.

## Offline-first sync

- Room DB caches due cards locally; review works fully offline.
- Ratings recorded locally with the client timestamp (`rated_at`), queued, synced via `POST /reviews/rate-batch` when online. Server is authoritative for FSRS state and replays each rating at its `rated_at`; a duplicate (`card_id`, `rated_at`) is ignored server-side and returns the current state with `duplicate: true`, not an error, so a retried flush is safe.
- The batch response has one result per submitted rating, in request order, so results match the queue by position. Dequeue every item that came back `ok` (including duplicates); drop items that failed with a 4xx `status` instead of retrying them forever; keep 5xx failures for the next flush.
- `device_id` comes from `POST /devices` and is stored alongside the base URL; every queued rating carries it so `review_logs.device_id` identifies the phone. The client overwrites the stored value with the one in every `POST /devices` response, including after a token refresh — a refreshed `fcm_token` is a new row and a new id.
- Approval queue requires connectivity (LLM content, no offline need).

## Connecting to the backend

- Base URL and API key are entered once in a Settings screen (reachable from Today) and stored in `EncryptedSharedPreferences`. Nothing is baked into the build.
- The connection test calls `GET /health/auth`: `200` means both the URL and the key are right, `401` means the key is wrong, and a connection failure means the server could not be reached — check the URL, that the backend is running, and that the phone is on the same network. The three cases get three different messages.
- Phase 1 talks plain HTTP to the Mac on the LAN. The app ships a network security config that permits cleartext **only** for private-range hosts (`10.*`, `172.16-31.*`, `192.168.*`, `*.local`); everything else must be HTTPS. Revisit when phase 2 hosting adds TLS.
- Every request sends `X-API-Key`. A 401 surfaces as a "check settings" banner, not a crash.

## Push notifications (FCM)

- Server sends a high-priority data message (FCM HTTP v1 via `firebase-admin`; the legacy API is shut down) when due cards exist: "12 cards due from Evals for AI Engineers". At most one per day, inside the configured window.
- Data messages reach `onMessageReceived` in the background but not after the user force-stops the app, and some OEM battery managers (MIUI, ColorOS, One UI) drop them; the Today screen must work without ever having received a push.
- Deep link → review session.
- Token registered via `POST /devices` on app start and token refresh.

## Tech

| Concern | Choice |
|---|---|
| UI | Jetpack Compose, Material 3 |
| Networking | Retrofit + OkHttp |
| Local DB | Room |
| DI | Hilt |
| Push | Firebase Cloud Messaging |
| Min SDK | 26 |

## Project structure

```
android/
└── app/src/main/java/dev/recally/
    ├── ui/        # compose screens (today, review, approve, decks, stats, settings)
    ├── data/      # Room entities, DAOs, Retrofit api, sync worker
    ├── fcm/       # messaging service, token handling
    └── MainActivity.kt
```
