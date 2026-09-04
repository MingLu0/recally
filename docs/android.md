# Recally — Android App

Native Android. Kotlin + Jetpack Compose + Material 3.

## Screens

### 1. Today
- Due count + new card count, streak, start-review button.
- Entry point from FCM notification deep link.

### 2. Review session
- Card front → tap to flip → rating buttons: Again / Hard / Good / Easy.
- Response time captured automatically (tap-to-rate duration) for review_logs.
- **Same-session relearning**: FSRS learning steps are minutes long, so a card rated Again or Hard comes back inside the session. The client re-queues it after the step interval from `learning_steps_minutes` (or at the end of the queue if the session is shorter than the step). The client does not run FSRS; it only decides *when to show the card again in this session*. The server owns the real state (ADR-005).
- Session summary at end (reviewed count, time, lapses).

### 3. Approval queue
- Pending cards grouped by chapter, with front/back, critic critique, and all source highlights shown for context (a grouped card has several).
- Approve / edit inline / reject (with optional reason).

### 4. Decks
- Book list → chapters → cards. Read-only browsing.

### 5. Stats
- Streak, retention, forecast chart, lapse rate by card type.

## Offline-first sync

- Room DB caches due cards locally; review works fully offline.
- Ratings recorded locally with the client timestamp (`rated_at`), queued, synced via `POST /reviews/rate-batch` when online. Server is authoritative for FSRS state and replays each rating at its `rated_at`; duplicates are rejected by the (`card_id`, `rated_at`) key so a retried flush is safe.
- Approval queue requires connectivity (LLM content, no offline need).

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
    ├── ui/        # compose screens (today, review, approve, decks, stats)
    ├── data/      # Room entities, DAOs, Retrofit api, sync worker
    ├── fcm/       # messaging service, token handling
    └── MainActivity.kt
```
