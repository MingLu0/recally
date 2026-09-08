package dev.recally

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt application (docs/android.md, "Architecture"). The composition root
 * for the app; modules live under `di/`.
 */
@HiltAndroidApp
class RecallyApplication : Application()
