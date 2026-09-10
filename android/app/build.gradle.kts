import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.firebase.appdistribution)
}

// google-services.json is gitignored (it comes from the Firebase console).
// A contributor without it must still build: skip the plugin, Firebase never
// initialises, and push is inert (docs/android.md, "Push notifications").
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Versioning lives in one place: gradle.properties, so a bump edits one file
// instead of scattered literals (issue #135).
val appVersionCode = (findProperty("recally.versionCode") as String?)?.toInt() ?: 1
val appVersionName = findProperty("recally.versionName") as String? ?: "0.1.0"

// Stable debug signing: keystore.properties is gitignored and names a fixed
// keystore, so a rebuild installs over the top instead of being refused for a
// changed signature. Without the file (clean checkout, CI) the build falls
// back to the AGP-generated ~/.android/debug.keystore and still succeeds.
val debugKeystorePropertiesFile = rootProject.file("keystore.properties")
val debugKeystoreProperties =
    Properties().apply {
        if (debugKeystorePropertiesFile.exists()) {
            debugKeystorePropertiesFile.inputStream().use { load(it) }
        }
    }

// Release notes for the App Distribution upload: the subject of the commit
// being built, per issue #135.
val latestCommitSubject =
    providers
        .exec {
            commandLine("git", "log", "-1", "--pretty=%s")
        }.standardOutput.asText
        .get()
        .trim()

android {
    namespace = "dev.recally"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.recally"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // Overrides the AGP-generated debug config only when the gitignored
        // keystore.properties is present; otherwise the default stays.
        named("debug") {
            if (debugKeystorePropertiesFile.exists()) {
                storeFile = rootProject.file(debugKeystoreProperties.getProperty("storeFile"))
                storePassword = debugKeystoreProperties.getProperty("storePassword")
                keyAlias = debugKeystoreProperties.getProperty("keyAlias")
                keyPassword = debugKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // We build and distribute the DEBUG variant only (issue #135): the
        // release manifest permits no cleartext, so a release build cannot
        // reach the phase-1 LAN backend (docs/android.md:203). No release
        // build type until phase 2 brings TLS.
        debug {
            firebaseAppDistribution {
                artifactType = "APK"
                groups = "android-testers"
                releaseNotes = latestCommitSubject
            }
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME backs the Settings About row.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric compose tests (ChapterHeaderTest) need the merged
            // resources and manifest on the unit-test classpath.
            isIncludeAndroidResources = true
        }
    }
}

// JDK 17 toolchain (docs/android.md, "Tooling"). Must match the Temurin 17 in
// the CI android job — a mismatch fails in a way that does not point at itself.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.core.ktx)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Base URL + API key, Keystore-encrypted (docs/android.md, "Connecting to
    // the backend"). DataStore, never the deprecated SharedPreferences
    // encryption wrapper — the doc carries the why.
    implementation(libs.datastore.preferences)

    implementation(libs.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // FCM push (docs/android.md, "Push notifications"); the BOM pins the
    // firebase-* versions. Works without google-services.json — see above.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.navigation.testing)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
