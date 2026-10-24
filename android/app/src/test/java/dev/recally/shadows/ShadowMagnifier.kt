package dev.recally.shadows

import android.view.View
import android.widget.Magnifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric leaves [Magnifier] unshadowed and its real dismiss path NPEs
 * (`InternalPopupWindow.mSurface` is null), which crashes Compose text
 * selection the moment a long-press selection ends. No-op the platform
 * magnifier so selection tests can run; the magnifier is a platform widget,
 * not app behaviour.
 */
@Implements(Magnifier::class)
class ShadowMagnifier {
    @Suppress("FunctionName")
    @Implementation
    fun __constructor__(view: View) {
    }

    @Implementation
    fun show() {
    }

    @Implementation
    fun show(
        sourceCenterX: Float,
        sourceCenterY: Float,
    ) {
    }

    @Implementation
    fun dismiss() {
    }

    @Implementation
    fun update() {
    }
}
