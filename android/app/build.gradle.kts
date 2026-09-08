plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "dev.recally"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.recally"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

// JDK 17 toolchain (docs/android.md, "Tooling"). Must match the Temurin 17 in
// the CI android job — a mismatch fails in a way that does not point at itself.
kotlin {
    jvmToolchain(17)
}
