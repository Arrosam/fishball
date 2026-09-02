package org.areel.fishball.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * The one easing curve the whole app moves on. Anything that travels once — a message
 * arriving, a plate settling, a screen opening — uses this; ambient loops stay linear.
 */
val EaseMech = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private const val ENTER_MS = 260

/**
 * Something arriving out of the corner it came from: fading up, sliding in, growing from that
 * same corner.
 *
 * The corner is not decoration. It repeats, in motion, the cue the layout already carries — so
 * it reads even when it is too fast to consciously see.
 *
 * [pivotX] and [pivotY] are fractions: 0 is the left/top edge, 1 the right/bottom. The travel
 * direction falls out of them, so a thing always slides *from* the corner it grows out of
 * rather than sliding from one place and inflating from another.
 *
 * The scale is deliberately small — 0.94 to 1.0. Anything more and a plate of dense Chinese
 * text visibly reflows as it grows, which is worse than no animation at all.
 */
@Composable
fun EnterFrom(
    pivotX: Float,
    pivotY: Float,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (animate) 0f else 1f) }

    LaunchedEffect(Unit) {
        if (animate) {
            progress.animateTo(1f, tween(durationMillis = ENTER_MS, easing = EaseMech))
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier.graphicsLayer {
            val remaining = 1f - progress.value
            alpha = progress.value

            // -1 towards a near edge, +1 towards a far one. Distance is the same either way.
            translationX = (pivotX * 2f - 1f) * remaining * 26.dp.toPx()
            translationY = (pivotY * 2f - 1f) * remaining * 18.dp.toPx()

            transformOrigin = TransformOrigin(pivotFractionX = pivotX, pivotFractionY = pivotY)
            val scale = 0.94f + 0.06f * progress.value
            scaleX = scale
            scaleY = scale
        },
    ) {
        content()
    }
}

/**
 * A message arriving.
 *
 * The assistant's plate is chamfered at the top-left and points at the masthead it speaks from,
 * so its turn enters from there; the user's rule sits bottom-right beside the send plate the
 * message was just pushed out of, so its turn enters from there.
 */
@Composable
fun EnterFromCorner(
    fromUser: Boolean,
    animate: Boolean,
    content: @Composable () -> Unit,
) {
    val pivot = if (fromUser) 1f else 0f
    EnterFrom(pivotX = pivot, pivotY = pivot, animate = animate, content = content)
}

/**
 * A message that has just arrived.
 *
 * The only motion left in the thread, and it runs once per message: the turn somebody just sent
 * and the answer that comes back for it. Everything already on screen is drawn plainly, with no
 * wrapper and no layer, because a conversation that was already there has not arrived.
 *
 * When it is not animating there is no [androidx.compose.ui.graphics.graphicsLayer] and no
 * [Animatable] at all - not a layer sitting at alpha 1. That distinction is the point of
 * rewriting this: the old version wrapped every row in a graphics layer whether or not it had
 * anything to animate, and a long thread paid for it on every frame of every drag.
 *
 * A fade and a short rise, no scale. Scaling a plate of dense Chinese reflows the text as it
 * grows, which is more distracting than no animation at all.
 */
@Composable
fun Arriving(animate: Boolean, content: @Composable () -> Unit) {
    if (!animate) {
        content()
        return
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = ARRIVE_MS, easing = EaseMech))
    }
    androidx.compose.foundation.layout.Box(
        Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 12.dp.toPx()
        },
    ) {
        content()
    }
}

/** One message arriving. Shorter than the old entrance: it is a fade, not a flight. */
private const val ARRIVE_MS = 200
