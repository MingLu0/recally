# Android app

Kotlin + Jetpack Compose. See [../docs/android.md](../docs/android.md).

The step 4 MVP is in: Today, Review, Approve, Decks/Book detail and Settings, over a Room cache and a rating outbox that flushes through WorkManager, with FCM registration and push deep links. The design system from [../docs/design/design-system.md](../docs/design/design-system.md) lives in `app/src/main/java/dev/recally/ui/theme/`. Conventions for agents are in [AGENTS.md](AGENTS.md); remaining work is tracked in [../docs/roadmap.md](../docs/roadmap.md).

```sh
./gradlew ktlintCheck              # read-only ktlint (what CI runs)
./gradlew ktlintFormat             # writes fixes (what the pre-commit hook runs)
./gradlew :app:lintDebug           # Android Lint against min SDK 26
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

`local.properties` with `sdk.dir=...` is needed locally when `ANDROID_HOME` is unset (gitignored; CI has the SDK preinstalled).
