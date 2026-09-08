# Android app

Kotlin + Jetpack Compose. See [../docs/android.md](../docs/android.md).

Step 4a-0 tooling baseline is in: Gradle wrapper (Kotlin DSL, `gradle/libs.versions.toml`), JDK 17 toolchain, ktlint, Android Lint, and the CI `android` job. Only a placeholder `MainActivity` exists so far — the screens land from step 4a onwards (see [../docs/roadmap.md](../docs/roadmap.md)).

```sh
./gradlew ktlintCheck              # read-only ktlint (what CI runs)
./gradlew ktlintFormat             # writes fixes (what the pre-commit hook runs)
./gradlew :app:lintDebug           # Android Lint against min SDK 26
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

`local.properties` with `sdk.dir=...` is needed locally when `ANDROID_HOME` is unset (gitignored; CI has the SDK preinstalled).
