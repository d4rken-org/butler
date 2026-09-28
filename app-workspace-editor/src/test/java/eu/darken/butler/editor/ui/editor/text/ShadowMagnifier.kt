package eu.darken.butler.editor.ui.editor.text

import android.widget.Magnifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Robolectric gives the magnifier's popup no surface to draw on, so showing it crashes. */
@Implements(value = Magnifier::class, minSdk = 28)
class ShadowMagnifier {

    /** The two-argument show() delegates here. */
    @Implementation
    fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit
}
