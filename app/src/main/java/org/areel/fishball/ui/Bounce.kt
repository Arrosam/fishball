package org.areel.fishball.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Rubber-band overscroll for a list, including the case a list normally has no answer for:
 * content shorter than the viewport.
 *
 * Compose will not overscroll a list that already fits — there is nothing to scroll, so the
 * stretch never triggers and a drag does nothing at all. That reads as a dead screen. Here the
 * drag is taken from the nested-scroll chain instead of from the list's scroll position, so it
 * works identically whether the list is full, partly full, or empty.
 *
 * Resistance grows with distance, so the pull runs out rather than stopping dead, and release
 * is a spring rather than a tween — a tween returns at the same speed from any distance, which
 * feels mechanical.
 */
class BounceState internal constructor(
    private val limitPx: Float,
) {
    internal val offset = Animatable(0f)
    internal var viewportPx: Float = limitPx

    /** How far a further [delta] moves the content, given how far it has already been pulled. */
    internal fun resist(delta: Float): Float {
        val travelled = abs(offset.value) / limit()
        return delta * (1f - travelled.coerceIn(0f, 0.92f))
    }

    internal fun limit(): Float = (viewportPx * 0.28f).coerceAtLeast(limitPx)

    val translation: Int get() = offset.value.roundToInt()
}

@Composable
fun rememberBounceState(): BounceState {
    val state = remember { BounceState(limitPx = 120f) }
    return state
}

/**
 * Attach to a container **wrapping** the scrollable. The scrollable itself should carry
 * `Modifier.offset { IntOffset(0, state.translation) }` so the whole list moves as one sheet.
 */
@Composable
fun Modifier.bounce(state: BounceState): Modifier {
    val scope = rememberCoroutineScope()

    val connection = remember(state) {
        object : NestedScrollConnection {

            // Dragging back toward rest must close the bounce before the list scrolls again,
            // or the content jumps as the two compete for the same gesture.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val current = state.offset.value
                if (current == 0f || available.y == 0f) return Offset.Zero
                if (sign(current) == sign(available.y)) return Offset.Zero

                val closing = if (current > 0f) {
                    maxOf(available.y, -current)
                } else {
                    minOf(available.y, -current)
                }
                scope.launch { state.offset.snapTo(current + closing) }
                return Offset(0f, closing)
            }

            // Whatever the list could not use is overscroll — including *all* of it when the
            // content fits and the list can scroll nowhere.
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                scope.launch { state.offset.snapTo(state.offset.value + state.resist(available.y)) }
                return Offset(0f, available.y)
            }

            // Release: spring home, and swallow the fling so the list does not also coast.
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.offset.value == 0f) return Velocity.Zero
                state.offset.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.62f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialVelocity = available.y,
                )
                return available
            }
        }
    }

    return this
        .onSizeChanged { state.viewportPx = it.height.toFloat() }
        .nestedScroll(connection)
}
