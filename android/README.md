# Android app

Kotlin + Jetpack Compose. See [../docs/android.md](../docs/android.md).

Step 4a foundation is in: the Gradle project builds, Hilt is wired (`RecallyApplication`, `di/DispatchersModule`), and the design system from [../docs/design/design-system.md](../docs/design/design-system.md) is implemented in `app/src/main/java/dev/recally/ui/theme/` — light and dark colour schemes, the Inter Tight type scale on M3 names, spacing/radius constants, the `@CombinedPreviews` multipreview, and the fixed book-cover colour list. `MainActivity` + `AppScaffold` + `RecallyNavHost` are wired with the sealed routes; the screens themselves land from step 4b onwards (see [../docs/roadmap.md](../docs/roadmap.md)).

```sh
./gradlew ktlintCheck              # read-only ktlint (what CI runs)
./gradlew ktlintFormat             # writes fixes (what the pre-commit hook runs)
./gradlew :app:lintDebug           # Android Lint against min SDK 26
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

`local.properties` with `sdk.dir=...` is needed locally when `ANDROID_HOME` is unset (gitignored; CI has the SDK preinstalled).
