package org.areel.fishball.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

/**
 * What a control feels like under a finger.
 *
 * Three patterns, because there are three kinds of control here and they mean different things.
 * Naming them is the point: a button added tomorrow asks for one of these rather than choosing
 * a haptic constant on its own, and the whole app changes weight from one place.
 *
 * They are built out of three weights rather than three arbitrary constants, and the ordering
 * is the design: [FIRM] going down, something lighter coming back up. A press and its release
 * are not the same event and should not feel like it - when they did, a tap and a tap-and-hold
 * were indistinguishable by touch, and the release read as a second press.
 *
 * These go through the platform's own constants rather than Compose's `HapticFeedbackType`,
 * which in this version offers exactly two effects - a long press and a text-handle tick. The
 * tick is close enough to nothing on a real phone that a tap felt unanswered, and a haptic too
 * faint to notice is worse than none: it spends the vibrator on a signal nobody receives. The
 * platform list has the middle ground that argument needs.
 */
enum class Feel(internal val down: Int, internal val up: Int?) {
    /**
     * The common one. Something happened, at the moment the finger landed.
     *
     * Nothing on the way up, and that is the whole character of it. A small button does its
     * job on the press; a second buzz on release would be the app reporting the finger
     * leaving, which is not an event anybody needs told about. This is what a control gets
     * unless there is a reason for it to get something else.
     */
    TAP(FIRM, null),

    /**
     * `toggle_vibration` — something opened, or closed, and is still on screen.
     *
     * Firm going down, because that is where the decision is made, and the faintest note
     * coming up. A toggle leaves something behind it, and the light release says the gesture
     * finished without claiming anything else happened.
     */
    TOGGLE(FIRM, FAINT),

    /**
     * `clicky_button` — something happened and is over.
     *
     * Felt at both ends, because these are the gestures where the release *is* the action:
     * sending a message, letting go of the microphone, killing a running turn. The release is
     * still lighter than the press - it answers it rather than repeating it.
     */
    CLICKY(FIRM, SOFT),
}

/*
 * Three weights, heaviest first. On AOSP these map to a heavy click, a click and a tick, so
 * the gap between them is something a hand can actually tell apart - which is the only reason
 * to have three.
 */

/** The press. Every control in the app starts here, so a press feels like a press everywhere. */
private const val FIRM = HapticFeedbackConstants.LONG_PRESS

/**
 * A release that answers a press, and a threshold crossed mid-gesture.
 *
 * `CONFIRM` is the one the platform designs for a short, definite acknowledgement, and it
 * arrived in API 30. Below that the clock tick is the nearest thing with any body to it.
 */
private val SOFT: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

/**
 * The lightest note in the app: a gesture ended, nothing more claimed than that.
 *
 * `GESTURE_END` is named for exactly this and sits a step below [SOFT]. It is still deliberately
 * louder than the text-handle tick this app has already found too quiet to feel.
 */
private val FAINT: Int
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.GESTURE_END
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

/**
 * Felt on the way down, and on the way up when the pattern asks for it.
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
                is PressInteraction.Release -> pattern.up?.let { view.performHapticFeedback(it) }
                // A cancelled press is a finger that slid off. Nothing happened, so nothing
                // is felt - a buzz here would be the app confirming something it did not do.
                else -> Unit
            }
        }
    }
}

/**
 * A button: something to tap, and the feel that goes with tapping it.
 *
 * The one way a control is made clickable in this app, so that being a button and feeling like
 * one are the same decision rather than two that can drift apart. They had drifted: two
 * controls carried a named pattern and a dozen others were plain `clickable`s that gave back
 * nothing at all, which is not a design so much as the order they were written in.
 *
 * [pattern] defaults to [Feel.TAP] because most controls are small ones that do their job on
 * the press. Reach for [Feel.TOGGLE] when the thing stays on screen afterwards and
 * [Feel.CLICKY] when the release is the action.
 *
 * [indication] defaults to whatever the theme provides, so replacing a plain `clickable` with
 * this changes what a button *feels* like without changing what it looks like.
 */
@Composable
fun Modifier.pressable(
    pattern: Feel = Feel.TAP,
    enabled: Boolean = true,
    indication: Indication? = LocalIndication.current,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit,
): Modifier {
    feel(interaction, pattern)
    return clickable(
        interactionSource = interaction,
        indication = indication,
        enabled = enabled,
        onClick = onClick,
    )
}

/**
 * The same weights, for gestures that are not a `clickable`.
 *
 * Holding the microphone is a press gesture read by hand, and filing it through an interaction
 * source to get a buzz out would be plumbing for its own sake. This is the same patterns
 * reached a shorter way.
 */
class Buzzer(private val view: View) {
    fun down(pattern: Feel) = view.performHapticFeedback(pattern.down)

    fun up(pattern: Feel) {
        pattern.up?.let { view.performHapticFeedback(it) }
    }

    /**
     * Neither end of a press: a threshold crossed mid-gesture, or a step along the way.
     *
     * [SOFT] rather than [FAINT], because a threshold is news. The slide-to-cancel tick is the
     * only thing telling somebody the line they just crossed is real, and their eyes are on a
     * conversation rather than on the button their finger is covering.
     */
    fun tick() = view.performHapticFeedback(SOFT)
}

@Composable
internal fun rememberBuzzer(): Buzzer {
    val view = LocalView.current
    return remember(view) { Buzzer(view) }
}
