package org.areel.fishball.ui

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Velocity
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
 * The offset is plain state, moved synchronously inside the scroll callback. It used to be an
 * Animatable written from a coroutine launched per scroll event, which is a race with three
 * separate failures under a hard fling: every event in a frame read the same stale offset, each
 * `snapTo` cancelled the one before it, and the connection had already told the list its scroll
 * was consumed before any of that resolved. On a long thread flung hard into its top the list
 * stopped dead and sprang back from wherever the race had left the offset.
 */
class BounceState internal constructor(
    private val limitPx: Float,
) {
    internal var current by mutableFloatStateOf(0f)
        private set

    internal var viewportPx: Float = limitPx

    /** How far the band can stretch. Beyond this a pull does nothing at all. */
    internal fun limit(): Float = (viewportPx * 0.28f).coerceAtLeast(limitPx)

    /**
     * Stretch by [delta], and report how much was actually taken.
     *
     * Resistance grows with distance so the pull runs out rather than stopping dead, and the
     * result is clamped: without a ceiling the residual 8% kept accumulating on a fast drag
     * until the spring had a whole screen to travel back.
     *
     * The return value matters as much as the movement. Reporting the full delta as consumed
     * while clamped tells the list its scroll was used when it was not, which is the difference
     * between a band that stops stretching and a list that stops scrolling.
     */
    internal fun pull(delta: Float): Float {
        val limit = limit()
        val travelled = abs(current) / limit
        val eased = delta * (1f - travelled.coerceIn(0f, 0.92f))
        val next = (current + eased).coerceIn(-limit, limit)
        val applied = next - current
        current = next
        return applied
    }

    /** Drag back towards rest. Returns what it took, so the list gets the remainder. */
    internal fun close(delta: Float): Float {
        if (current == 0f) return 0f
        val closing = if (current > 0f) maxOf(delta, -current) else minOf(delta, -current)
        current += closing
        return closing
    }

    /**
     * Land at the end with momentum: run past it by [peak], then spring home.
     *
     * The same band a finger stretches, moved by the app instead. Jumping to the bottom is
     * otherwise a cut - the thread is simply somewhere else the next frame - and a cut gives no
     * sense of which way it travelled. Overrunning and coming back says "down", and says it
     * with the motion the thread already has when a hand does the same thing.
     *
     * Negative because that is the direction a drag takes at the bottom: content carried
     * upward, empty ground opening beneath it. The return leg is [settle], so the rebound past
     * zero is the same under-damped spring as every other release in this list.
     */
    internal suspend fun arrive(peak: Float) {
        animate(
            initialValue = 0f,
            targetValue = -peak.coerceAtMost(limit()),
            animationSpec = tween(durationMillis = ARRIVAL_MS, easing = LinearOutSlowInEasing),
        ) { value, _ -> current = value }
        settle(0f)
    }

    internal suspend fun settle(velocity: Float) {
        // A spring rather than a tween: a tween returns at the same speed from any distance,
        // which feels mechanical.
        animate(
            initialValue = current,
            targetValue = 0f,
            initialVelocity = velocity,
            animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        ) { value, _ -> current = value }
    }

    val translation: Int get() = current.roundToInt()
}

@Composable
fun rememberBounceState(): BounceState = remember { BounceState(limitPx = 120f) }

/** Out fast, back slow. Short enough that it reads as momentum rather than as a second scroll. */
private const val ARRIVAL_MS = 130

/**
 * Attach to a container **wrapping** the scrollable. The scrollable itself should carry
 * `Modifier.offset { IntOffset(0, state.translation) }` so the whole list moves as one sheet.
 */
@Composable
fun Modifier.bounce(state: BounceState): Modifier {
    val connection = remember(state) {
        object : NestedScrollConnection {

            // Dragging back toward rest must close the bounce before the list scrolls again,
            // or the content jumps as the two compete for the same gesture.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (state.current == 0f || available.y == 0f) return Offset.Zero
                if (sign(state.current) == sign(available.y)) return Offset.Zero
                return Offset(0f, state.close(available.y))
            }

            // Whatever the list could not use is overscroll — including *all* of it when the
            // content fits and the list can scroll nowhere.
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y == 0f) return Offset.Zero
                return Offset(0f, state.pull(available.y))
            }

            // Release: spring home, and swallow the fling so the list does not also coast.
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.current == 0f) return Velocity.Zero
                state.settle(available.y)
                return available
            }
        }
    }

    return this
        .onSizeChanged { state.viewportPx = it.height.toFloat() }
        .nestedScroll(connection)
}
