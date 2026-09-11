# android/AGENTS.md

Kotlin rules for `android/`. The cross-cutting hard rules, the doc index and the working style are in the repo-root `AGENTS.md` — read that first.

## Conventions

- Jetpack Compose + Material 3, Retrofit + OkHttp, Room, Hilt, FCM. Package root `dev.recally`, structure in `docs/android.md`.
- Clean architecture with the scaffold pattern (`docs/android.md`, "Architecture"). Screen composables are pure: `UiState` in, callbacks out — no `hiltViewModel()`, no `NavController`, no flow collection inside a screen. ViewModels are instantiated at their `NavHost` route entry, which also owns the navigation decision. One immutable `UiState` per screen. ViewModels talk to repository interfaces, never to a DAO or Retrofit service. Every screen composable and shared component ships a private `@CombinedPreviews` preview function with its `UiState` built inline.
- Offline-first: Room caches due cards; ratings are queued in the outbox (`data/sync/`) with client timestamps and flushed via `rate-batch`. The approval queue requires connectivity.
- Capture `response_ms` (flip-to-rate duration) on every rating.
- Use injected dispatchers, not hard-coded `Dispatchers.IO`. Include exception handlers on coroutines.
- Build to `docs/design/design-system.md` — colour tokens (light and dark), type scale, component specs and the required states are settled there.
- A Compose overflow/truncation test must measure the child, not the root: a width-constrained root can never fail the assertion.

## Commands

Run from `android/`. Export `ANDROID_HOME`, or add `local.properties` with `sdk.dir=…` (gitignored) — needed in a fresh worktree.

```
./gradlew ktlintCheck          # read-only; what CI runs
./gradlew ktlintFormat         # writes fixes; what the pre-commit hook runs
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## Distribution

**Releases ship by pushing a `v*` tag, never from a local machine** (issue #175). Shipping is the human's call: do not tag on your own judgement. Full procedure in `docs/workflow.md`, "Distribution".

Bump `android/gradle.properties` **first, in its own merged PR**: the `distribute` job overrides `versionName` from the tag but never `versionCode`, so tagging without bumping ships a duplicate `versionCode` that App Distribution and Play both read as the same build.

```
git tag v0.2.3 && git push origin v0.2.3
```

The local upload is a **smoke test only, never a release** (issue #136): it cuts no GitHub Release, writes no release notes, and uses whatever `versionCode` is in `gradle.properties`.

```
./gradlew assembleDebug appDistributionUploadDebug
```
