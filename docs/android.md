# Recally — Android App

Native Android. Kotlin + Jetpack Compose + Material 3.

## Design

Visual spec: [`docs/design/design-system.md`](design/design-system.md) — colour tokens (light and dark), type scale, component specs, and the per-screen states. Screen mockups live on a [design canvas](https://claude.ai/code/artifact/25472047-e5cc-473c-8709-81f1ffebf89f); artboard sources are committed under `docs/design/`.

That document also lists the API fields the design needs but `api-spec.md` does not yet provide — tracked as G1–G6 in [roadmap.md](roadmap.md), *Feature gaps*. Resolve those before building.

Screen composables are pure (see *Architecture* below), so each artboard maps to a `@Preview` with a hand-built `UiState`.

The app follows the **system theme** and ships no in-app toggle — the design system specifies both light and dark token sets, and a manual switch would be one more setting to maintain for a preference a single-user app never expresses.

## Architecture

Clean architecture with the scaffold pattern, carried over from the SummarizeAI app so both projects read the same way. Three layers, dependencies point inward: `ui` → `domain` → `data`. Nothing in `data/` imports Compose; nothing in `ui/` imports Retrofit or Room.

### Unidirectional data flow

State flows **down**, events flow **up**:

```
MainActivity          theme, nav controller, deep-link intent
└── AppScaffold       Material3 Scaffold, snackbar host
    └── RecallyNavHost
        ├── route entry   ← ViewModel lives here (hiltViewModel)
        │                   collects UiState, owns navigation decisions
        └── Screen        ← pure composable: UiState in, callbacks out
```

### Pure screen composables

A screen composable takes a `UiState` and callbacks. It never calls `hiltViewModel()`, never collects a flow, never touches a `NavController`. That keeps every screen previewable and testable with a hand-built `UiState`, which matters here because the review card, the approval queue and the stats charts each have many states worth seeing in isolation (loading, empty, offline, error, mid-flip).

### Every previewable composable has a preview

A composable that can be previewed must ship a `@Preview` function in the same file, annotated with `@CombinedPreviews`. "Previewable" means it renders from its parameters alone — which, given the rule above, is every screen composable and every shared component in `ui/components/`. A composable that cannot be previewed is a smell: it means state is being reached for rather than passed in.

`@CombinedPreviews` is one custom multipreview annotation defined once in `ui/theme/`, so the set of preview configurations is declared in a single place and every composable picks up a change to it:

```kotlin
@Preview(name = "light", showBackground = true)
@Preview(name = "dark", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "font 2x", showBackground = true, fontScale = 2f)
annotation class CombinedPreviews
```

Preview functions are private, suffixed `Preview`, and build their `UiState` inline — never from a ViewModel, a repository, or a fake injected through Hilt:

```kotlin
@CombinedPreviews
@Composable
private fun ApproveScreenPreview() {
    RecallyTheme {
        ApproveScreen(
            uiState = ApproveUiState(
                pendingCards = listOf(sampleCard),
                isLoading = false,
            ),
            onApproveCard = {},
            onRejectCard = {},
            onEditCard = { _, _ -> },
            onRetry = {},
        )
    }
}
```

Screens with meaningfully different states get one preview function per state — loading, empty, error, offline, and for the review card, mid-flip. Those are the states worth catching in the IDE rather than on a device, and they are exactly what the pure-composable rule above buys.

### ViewModels are scoped to their route

Each ViewModel is instantiated at its own `NavHost` route entry, not hoisted to `MainActivity`. Recally's screens are independent — the approval queue shares no state with a review session — and the deck browse routes are parameterised (`decks/{bookId}`, optionally filtered by chapter), so route scoping gives those ViewModels their `SavedStateHandle` arguments for free and stops six ViewModels from fetching at app start. State that genuinely spans screens (backend settings, connectivity) is exposed by a repository singleton that each ViewModel injects, not by a hoisted ViewModel.

```kotlin
composable(Screen.Approve.route) {
    val approveViewModel: ApproveViewModel = hiltViewModel()
    val approveUiState by approveViewModel.uiState.collectAsStateWithLifecycle()

    ApproveScreen(
        uiState = approveUiState,
        onApproveCard = approveViewModel::approveCard,
        onRejectCard = approveViewModel::rejectCard,
        onEditCard = approveViewModel::editCard,
        onRetry = approveViewModel::refresh,
    )
}
```

### Navigation decisions live at the route, not in the screen

A screen raises an event; the route entry decides where that goes. Navigation flags on `UiState` are cleared after navigating, so a config change does not re-navigate.

```kotlin
LaunchedEffect(reviewUiState.sessionFinished) {
    if (reviewUiState.sessionFinished) {
        navController.navigate(Screen.SessionSummary.route)
        reviewViewModel.clearNavigationFlags()
    }
}
```

### One UiState per screen

Each screen has a single immutable `data class …UiState` holding everything it renders — never separate `isLoading` / `error` / `data` flows. ViewModels expose exactly one `StateFlow<…UiState>` and mutate it through `MutableStateFlow.update {}`. Collect with `collectAsStateWithLifecycle()`, never `collectAsState()`.

```kotlin
data class ReviewUiState(
    val currentCard: Card? = null,
    val remainingCount: Int = 0,
    val isFlipped: Boolean = false,
    val isOffline: Boolean = false,
    val isEditing: Boolean = false,
    val relearningQueue: List<QueuedCard> = emptyList(),
    val sessionFinished: Boolean = false,
    val errorMessage: String? = null,
)
```

### Repositories own the data layer

ViewModels talk only to repositories — never to a DAO or a Retrofit service directly. A repository is an interface in `domain/repository/` with its implementation in `data/repository/`, bound by a Hilt module, so tests substitute a fake without a network or a database. Repositories return a sealed `Result` type rather than throwing; the ViewModel maps it into `UiState`. Room is the source of truth for due cards, and the repository decides when to refresh from the API — screens never branch on which side answered. The one signal that does cross the seam is `Result.Success.servedFromCache`, set when a forced refresh failed and the cache answered instead: it exists solely so the UI can show the offline bar (design-system.md, *States*), never to change behaviour. A 401 is not a cache-fallback case — the server was reached and rejected the key, so it always surfaces as `Result.Unauthorized` and the "check settings" banner.

### Rules

**Do**
- Instantiate a ViewModel at its route entry; pass `uiState` and method references (`viewModel::approveCard`) into the screen.
- Keep screen composables free of `hiltViewModel()`, `NavController`, and flow collection.
- Model everything a screen renders as one immutable `UiState`.
- Ship a private `@CombinedPreviews` preview function beside every screen composable and shared component, with the `UiState` built inline.
- Inject dispatchers (`@IoDispatcher CoroutineDispatcher`) rather than hard-coding `Dispatchers.IO`, so tests run on a `TestDispatcher`.

**Don't**
- Call `hiltViewModel()` inside a screen composable — the screen stops being previewable and testable.
- Navigate from inside a screen composable.
- Create the same ViewModel at two places in the tree; that is two instances and two copies of the state.
- Build a preview's state from a ViewModel or a Hilt-injected fake — a preview that needs the graph is not a preview.
- Let a `data/` type (Room entity, Retrofit DTO) reach a composable — map to a domain model in the repository.

## Navigation

Bottom navigation with four items: **Today, Decks, Stats, Settings**. Review and Approve are entered *from* Today, not nav destinations — both are modal tasks you finish and leave, so a permanent nav seat would invite abandoning a session mid-task. The visual spec is the *Bottom navigation* component in [design-system.md](design/design-system.md).

## Screens

### 1. Today
- Due count + new card count, streak, start-review button.
- Entry point from FCM notification deep link.

### 2. Review session
- Card front → tap to flip → rating buttons: Again / Hard / Good / Easy.
- **Session progress**: a single `pill` progress bar plus a "N left" count — **cards left, never "N of 12"**. The same-session relearning below re-queues Again/Hard cards inside the session, so the denominator is not fixed and a fixed bar would jump backwards or silently drop the repeat.
- Response time captured automatically (`response_ms`, measured flip-to-rate) for review_logs. The clock starts at the flip, not at card display: the interval that predicts recall is the time spent retrieving the answer, not the time spent reading the front.
- **Bury** (overflow action, available before flipping): drops the card from the rest of today's session via `POST /cards/{id}/bury`. This is the honest alternative to rating a card you don't want to answer — a dishonest rating corrupts `review_logs`, which trains both the FSRS optimizer and the Learner. Requires connectivity; offline, the action is unavailable rather than queued, since the session is over before it would sync.
- **Edit** (overflow action, after flipping): fix wording in place via `PATCH /cards/{id}`. Scheduling is untouched (ADR-008).
- **Same-session relearning**: FSRS learning steps are minutes long, so a card rated Again or Hard comes back inside the session. The client re-queues it after the step interval from `learning_steps_minutes` (or at the end of the queue if the session is shorter than the step). Which step applies is seeded from the card's `step` in `GET /reviews/due` — a card already at step 1 must not restart at step 0.
- Offline, no rate response comes back, so the client keeps its own in-session step counter: it advances one step on a card it re-queues, stops re-queueing past the last entry in `learning_steps_minutes`, and overwrites the counter with the `step` from the rate response whenever a rating is posted online. This counter is a display timer for *when to show the card again in this session*, nothing more — the client never runs FSRS, the server owns the real state, and the local view is corrected on the next `GET /reviews/due` (ADR-005).
- Session summary at end: reviewed count, elapsed time, and the session's ratings grouped into three rows — **Good or Easy / Rated Hard / Rated Again**. Reviewed count and elapsed time are local. The rows are named by rating, never "lapses": whether an Again is a lapse depends on FSRS state the server owns (Again on a card already in `learning` is not a lapse, and a replayed rating reports `lapsed: false`), so a client-side lapse count would misname the bucket. The server-side `lapsed` flag on rate responses remains the source for the lapse figures on Stats.

### 3. Approval queue
- Pending cards grouped by chapter, with front/back, critic critique, and all source highlights shown for context (a grouped card has several).
- Approve / edit inline / reject (with optional reason).
- `needs_human` cards get their own **"needs you" filter chip** rather than being folded indistinguishably into the single queue — they are the cards the Writer ⇄ Critic loop could not clear, so they are opened individually and excluded from any bulk approve (hard rule 1).

### 4. Decks
- Book list → chapters → cards. Browsing, plus the per-card controls from ADR-008: edit (`PATCH /cards/{id}`), suspend and unsuspend. Suspended cards are shown here with their state — this screen is the only way back from a suspend, so it cannot filter them out.
- A single book can reach ~1000 cards. `GET /decks/{book_id}/cards` is unpaginated in v1 by decision (`api-spec.md`), so the client fetches the book and filters by chapter (`?chapter=`) to keep the rendered list small; use a lazy list so the row count, not the response size, is what matters.

### 5. Stats
- Streak, retention, forecast chart, lapse rate by card type, lapse rate by Writer guidance version (the roadmap 6b gate; hidden until there is more than one version), curation yield.

### 6. Settings
- Backend base URL, API key, connection test. See *Connecting to the backend*.

### Screen → endpoint map

Every screen and the endpoints behind it. Kept here so a gap between this doc and `api-spec.md` shows up as an empty cell rather than an implementation surprise.

| Screen | Endpoints |
|---|---|
| Today | `GET /reviews/due` (counts), `GET /stats` (streak) |
| Review session | `GET /reviews/due`, `POST /reviews/{id}/rate`, `POST /reviews/rate-batch` (outbox flush), `PATCH /cards/{id}` (edit), `POST /cards/{id}/bury` |
| Approval queue | `GET /cards/pending`, `POST /cards/{id}/approve`, `POST /cards/{id}/reject` |
| Decks | `GET /decks`, `GET /decks/{book_id}/cards`, `PATCH /cards/{id}`, `POST /cards/{id}/suspend`, `POST /cards/{id}/unsuspend` |
| Stats | `GET /stats` |
| Settings | `GET /health/auth` (connection test); `POST /devices` on token refresh |

## Offline-first sync

- Room DB caches due cards locally; review works fully offline.
- Ratings are written to a **Room outbox table** the moment the user taps a rating — not held in memory — with the client timestamp (`rated_at`). Process death mid-session must not lose a rating. A WorkManager job with a network constraint flushes the outbox via `POST /reviews/rate-batch`, expedited on app start and enqueued after each rating; rows are deleted only once the server has acknowledged them.
- Server is authoritative for FSRS state and replays each rating at its `rated_at`; a duplicate (`card_id`, `rated_at`) is ignored server-side and returns the current state with `duplicate: true`, not an error, so a retried flush is safe.
- The batch response has one result per submitted rating, in request order, so results match the queue by position. Dequeue every item that came back `ok` (including duplicates); drop items that failed with a 4xx `status` instead of retrying them forever; keep 5xx failures for the next flush.
- `device_id` comes from `POST /devices` and is stored alongside the base URL; every queued rating carries it so `review_logs.device_id` identifies the phone. The client overwrites the stored value with the one in every `POST /devices` response, including after a token refresh — a refreshed `fcm_token` is a new row and a new id.
- **Ratings are the only queued write.** Every other mutation — approve, reject, edit (`PATCH /cards/{id}`), bury, suspend, unsuspend — requires connectivity and is disabled with an offline hint rather than queued. They have no offline deadline the way a rating does, and queueing them would mean resolving edit conflicts against a server that may have changed the card.
- Approval queue requires connectivity (LLM content, no offline need).

## Connecting to the backend

- Base URL and API key are entered once in a Settings screen (reachable from Today) and stored in **DataStore, encrypted with an Android Keystore key**. Not `EncryptedSharedPreferences`: `androidx.security:security-crypto` never left alpha and is deprecated. Nothing is baked into the build.
- The connection test calls `GET /health/auth`: `200` means both the URL and the key are right, `401` means the key is wrong, and a connection failure means the server could not be reached — check the URL, that the backend is running, and that the phone is on the same network. The three cases get three different messages.
- Phase 1 talks plain HTTP to the Mac on the LAN, which means typing an IP literal like `http://192.168.1.42:8000`. A network security config **cannot** scope this: `<domain>` entries match hostnames only, connections to IP literals never match one and fall through to `base-config`. So cleartext is permitted in the **debug manifest only**, via a debug-variant network security config with `cleartextTrafficPermitted="true"`. The release manifest permits no cleartext at all, and a release build therefore cannot talk to a phase-1 LAN backend — which is correct: phase 2 hosting brings TLS, and that is when a release build gets a backend it can reach.
- Every request sends `X-API-Key`. A 401 surfaces as a "check settings" banner, not a crash.

## Push notifications (FCM)

- Server sends a high-priority data message (FCM HTTP v1 via `firebase-admin`; the legacy API is shut down) when due cards exist: "12 cards due from Evals for AI Engineers". At most one per day, inside the configured window. The data payload carries `due_count` and `book_title` and nothing else — no `notification` key (the system would render one itself, ignoring the channel and the deep link), and no card ids (stale by tap time; Today refetches). The app formats the copy itself.
- When due cards span several books, the copy names the book contributing the most due cards (a tie breaks to the book whose card is due earliest) while the count stays the total across all books — the specs never said which book to name, and naming the largest contributor keeps the copy truthful without a per-book breakdown.
- Data messages reach `onMessageReceived` in the background but not after the user force-stops the app, and some OEM battery managers (MIUI, ColorOS, One UI) drop them; the Today screen must work without ever having received a push.
- Deep link opens **Today**, not a review session directly. The notification names a count ("12 cards due from Evals for AI Engineers") and Today is where that count is actionable — it also stays correct when some of those cards were already reviewed before the tap.
- Token registered via `POST /devices` on app start and token refresh.

## Tech

| Concern | Choice |
|---|---|
| UI | Jetpack Compose, Material 3 |
| Architecture | Clean architecture + scaffold pattern (see *Architecture*) |
| Navigation | Navigation Compose, single `NavHost` per scaffold |
| Networking | Retrofit + OkHttp |
| Local DB | Room |
| Background work | WorkManager (rating outbox flush) |
| DI | Hilt |
| Push | Firebase Cloud Messaging |
| Min SDK | 26 |

## Tooling

The backend's policy is in [backend.md](backend.md), *Tooling*; this is its Android counterpart, and the split is the same one — a fast formatter in the local loop, the slow checks in CI only.

- **Build: Gradle with the Kotlin DSL** (`.gradle.kts`), dependencies declared in a **version catalog** (`gradle/libs.versions.toml`) rather than scattered through module files, so a version is stated once. The wrapper is committed, which is what pins Gradle itself.
- **JDK 17** (Temurin). AGP 8.x requires 17+; the CI job and the Gradle toolchain must name the same version or the build fails in a way that does not point at the mismatch.
- **ktlint** via the [`ktlint-gradle`](https://github.com/JLLeitschuh/ktlint-gradle) plugin — format and lint in one tool, the rough equivalent of Ruff on the backend. Chosen over Spotless because it needs no configuration to be useful, and over detekt because detekt is a deeper static-analysis tool whose value grows with a codebase that does not exist yet. Adding detekt later is a smaller decision than removing it. Run: `./gradlew ktlintCheck` (read-only) or `./gradlew ktlintFormat` (writes fixes).
- **Android Lint**, `./gradlew :app:lintDebug`. This is the Android-specific correctness gate — missing permissions, resource problems, API-level misuse against min SDK 26. It is not a type gate: Kotlin's compiler already covers what mypy does for Python.
- **pre-commit, ktlint only**: the repo-root `.pre-commit-config.yaml` runs `ktlintFormat` on Kotlin files under `android/`, scoped the way the ruff hooks are scoped to `backend/`. It goes through the Gradle wrapper because `ktlint-gradle` publishes no pre-commit hook, and because the wrapper is what keeps local rules identical to CI's. Note the honest cost: this hook starts a Gradle daemon, so it is slower than the millisecond ruff hooks — the reason nothing else is in it.
- **CI**: the `android` job in `.github/workflows/ci.yml` runs ktlint, Android Lint, `:app:testDebugUnitTest` and `:app:assembleDebug` on every PR. `assembleDebug` is a separate step from the tests on purpose — Hilt's graph is validated by KSP at compile time, so a test-only run can pass while the app does not build.
- **On the first Android PR only**, the ktlint and Android Lint steps carry `continue-on-error: true`: a fresh run of either over a new module reports a backlog that would block the very commit that creates the module. Both lines come out once `android/` is clean, and that removal is part of step 4a's definition of done.

### Not in CI

- **Instrumented tests (`androidTest/`)** need an emulator, which is minutes of boot time and the main source of flake in Android CI. They are run locally for now. This is a real gap, not a free win: the Compose UI tests described under *Tests* have no automation behind them until an emulator job exists.
- **Dependency CVE scanning**, the analogue of the backend's pip-audit. Deferred rather than rejected — worth revisiting when the app leaves the LAN.

## Project structure

Package root `dev.recally`. One package per screen under `ui/screens/`, holding the screen composable, its `UiState`, and its ViewModel together — the three change as a unit.

```
android/
└── app/src/main/java/dev/recally/
    ├── MainActivity.kt          # theme, nav controller, deep-link intent
    ├── ui/
    │   ├── AppScaffold.kt       # Material3 Scaffold + snackbar host
    │   ├── navigation/
    │   │   ├── RecallyNavHost.kt   # route entries: ViewModel + state collection
    │   │   └── Screen.kt           # sealed route definitions + createRoute()
    │   ├── screens/
    │   │   ├── today/           # TodayScreen.kt, TodayUiState.kt, TodayViewModel.kt
    │   │   ├── review/          # + session summary
    │   │   ├── approve/
    │   │   ├── decks/
    │   │   ├── stats/
    │   │   └── settings/
    │   ├── components/          # shared composables (card face, rating bar, empty states)
    │   └── theme/               # + CombinedPreviews multipreview annotation
    ├── domain/
    │   ├── model/               # Card, Deck, ReviewRating, DueSummary
    │   ├── repository/          # repository interfaces + Result type
    │   └── review/              # same-session relearning timer (ADR-005)
    ├── data/
    │   ├── local/               # Room database, entities, DAOs
    │   ├── remote/              # Retrofit service, DTOs, X-API-Key interceptor
    │   ├── repository/          # interface implementations, DTO/entity ↔ domain mapping
    │   ├── settings/            # Keystore-encrypted DataStore: base URL + API key
    │   └── sync/                # rating outbox entity + WorkManager flush worker
    ├── fcm/                     # messaging service, token registration
    └── di/                      # Hilt modules (network, database, repository, dispatchers)
```

### Tests

- `test/` — ViewModel tests against fake repositories on a `TestDispatcher`; repository tests against an in-memory Room database and a MockWebServer. No real backend, ever.
- `androidTest/` — Compose UI tests drive screen composables directly with a hand-built `UiState`, no Hilt graph needed.
