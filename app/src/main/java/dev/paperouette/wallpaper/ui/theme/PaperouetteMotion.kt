package dev.paperouette.wallpaper.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * The app's motion, in one place.
 *
 * Material 3 Expressive's own motion scheme would be the right source for these, but in material3
 * 1.4.0 `MotionScheme`, `MaterialTheme.motionScheme` and even the expressive opt-in annotation are
 * all `internal`, so none of it can be reached from outside the library. These springs stand in
 * until the API opens up — spatial ones move things, effects ones fade them, matching how Material
 * splits the two.
 */
object PaperouetteMotion {
    /** Movement: springy enough to feel alive, damped enough not to wobble. */
    fun <T> spatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    /** Movement that should feel immediate, such as a control responding to a tap. */
    fun <T> fastSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

    /** Fades and colour changes, which should not overshoot. */
    fun <T> effects(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Colour washes as the artwork changes; slow enough to read as a transition. */
    fun <T> colorWash(): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 120f)
}
