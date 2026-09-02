package org.areel.fishball.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * What a control feels like under a finger.
 *
 * Two patterns, because there are two kinds of control here and they mean different things.
 * Naming them is the point: a third toggle added tomorrow asks for [TOGGLE] rather than
 * choosing a haptic constant on its own, and the whole app changes weight from one place.
 *
 * These go through the platform's own constants rather than Compose's `HapticFeedbackType`,
 * which in this version offers exactly two effects - a long press and a text-handle tick. The
 * tick is close enough to nothing on a real phone that a tap felt unanswered, and a haptic too
 * faint to notice is worse than none: it spends the vibrator on a signal nobody receives. The
 * platform list has the middle ground that argument needs.
 */
enum class Feel(internal val down: Int, internal val up: Int) {
    /**
     * `toggle_vibration` — something opened, or closed, and is still on screen.
     *
     * Firm going down, because that is where the decision is made, and a lighter note coming
     * up. Two different weights on purpose: a toggle leaves something behind it, and the
     * lighter release says the gesture finished without claiming anything else happened.
     */
    TOGGLE(HapticFeedbackConstants.LONG_PRESS, TICK),

    /**
     * `clicky_button` — something happened and is over.
     *
     * The same weight both ways. Sending a message and letting go of the microphone are the
     * two places in the app where the release *is* the action, so it is felt exactly as hard
     * as the press that started it.
     */
    CLICKY(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.LONG_PRESS),
}

/**
 * The lighter of the two notes.
 *
 * `CONFIRM` is the one the platform designs for exactly this - a short, definite acknowledgement
 * - and it arrived in API 30. Below that, the clock tick is the nearest thing with any body to
 * it. Both are deliberately softer than a long press and deliberately louder than the text
 * handle tick this app has already found too quiet to feel.
 */
private val TICK: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

/**
 * Felt on the way down and again on the way up, for anything driven by a `clickable`.
 *
 * Collected from the interaction flow rather than read off a `collectIsPressedAsState` flag,
 * and that distinction is the whole point of it. A flag is a value sampled between
 * recompositions, so a quick tap - down and up inside one frame - could set it and clear it
 * without any composition ever observing `true`, and the tap went unanswered. Which taps those
 * were felt arbitrary from the outside: the same button, tapped the same way, buzzing or not
 * depending on where the frame boundary fell. Every press lands in this flow whether or not a
 * frame happened to sit between it and its release.
 */
@Composable
internal fun feel(interaction: InteractionSource, pattern: Feel) {
    val view = LocalView.current
    LaunchedEffect(interaction, pattern) {
        interaction.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> view.performHapticFeedback(pattern.down)
                is PressInteraction.Release -> view.performHapticFeedback(pattern.up)
                // A cancelled press is a finger that slid off. Nothing happened, so nothing
                // is felt - a buzz here would be the app confirming something it did not do.
                else -> Unit
            }
        }
    }
}

/**
 * The same two patterns, for gestures that are not a `clickable`.
 *
 * Holding the microphone is a press gesture read by hand, and filing it through an interaction
 * source to get a buzz out would be plumbing for its own sake. This is the same weights reached
 * a shorter way.
 */
class Buzzer(private val view: View) {
    fun down(pattern: Feel) = view.performHapticFeedback(pattern.down)

    fun up(pattern: Feel) = view.performHapticFeedback(pattern.up)

    /** Neither end of a press: a threshold crossed mid-gesture, or a step along the way. */
    fun tick() = view.performHapticFeedback(TICK)
}

@Composable
internal fun rememberBuzzer(): Buzzer {
    val view = LocalView.current
    return remember(view) { Buzzer(view) }
}
