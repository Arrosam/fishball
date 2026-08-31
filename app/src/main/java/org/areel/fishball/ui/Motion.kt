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
 * arriving, a plate settling — uses this; ambient loops stay linear.
 */
val EaseMech = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private const val ENTER_MS = 260

/**
 * A message arriving: fading up out of its own corner.
 *
 * The corner is not decoration. The assistant's plate is chamfered at the top-left and points
 * at the masthead it speaks from, so its turn enters from there; the user's rule sits bottom-
 * right beside the send plate the message was just pushed out of, so its turn enters from
 * there. The motion repeats the speaker cue the shapes already carry, which means it reads
 * even when it is too fast to consciously see.
 *
 * The scale is deliberately small — 0.94 to 1.0. Anything more and a plate of dense Chinese
 * text visibly reflows as it grows, which is worse than no animation at all.
 */
@Composable
fun EnterFromCorner(
    fromUser: Boolean,
    animate: Boolean,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (animate) 0f else 1f) }

    LaunchedEffect(Unit) {
        if (animate) {
            progress.animateTo(1f, tween(durationMillis = ENTER_MS, easing = EaseMech))
        }
    }

    androidx.compose.foundation.layout.Box(
        Modifier.graphicsLayer {
            val remaining = 1f - progress.value
            alpha = progress.value

            // Toward the corner it belongs to: the user's is down-right, the assistant's
            // up-left. Sign flips, distance does not.
            val direction = if (fromUser) 1f else -1f
            translationX = direction * remaining * 26.dp.toPx()
            translationY = direction * remaining * 18.dp.toPx()

            // Growing *from* that same corner, so the plate does not appear to slide and
            // inflate from two different places at once.
            transformOrigin = TransformOrigin(
                pivotFractionX = if (fromUser) 1f else 0f,
                pivotFractionY = if (fromUser) 1f else 0f,
            )
            val scale = 0.94f + 0.06f * progress.value
            scaleX = scale
            scaleY = scale
        },
    ) {
        content()
    }
}
