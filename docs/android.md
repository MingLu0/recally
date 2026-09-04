# Recally — Android App

Native Android. Kotlin + Jetpack Compose + Material 3.

## Screens

### 1. Today
- Due count + new card count, streak, start-review button.
- Entry point from FCM notification deep link.

### 2. Review session
- Card front → tap to flip → rating buttons: Again / Hard / Good / Easy.
- Response time captured automatically (tap-to-rate duration) for review_logs.
- Session summary at end (reviewed count, time, lapses).

### 3. Approval queue
- Pending cards with front/back, critic critique, and the source highlight shown for context.
- Approve / edit inline / reject (with optional reason).

### 4. Decks
- Book list → chapters → cards. Read-only browsing.

### 5. Stats
- Streak, retention, forecast chart, lapse rate by card type.

## Offline-first sync

- Room DB caches due cards locally; review works fully offline.
- Ratings recorded locally with timestamps, queued, synced to `POST /reviews/{id}/rate` when online. Server is authoritative for FSRS state; conflict policy: server recomputes from the ordered log.
- Approval queue requires connectivity (LLM content, no offline need).

## Push notifications (FCM)

- Server sends data-message push when due cards exist: "12 cards due from Evals for AI Engineers".
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
