package org.areel.fishball.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.composed
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.areel.fishball.ui.theme.Areel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * The two surfaces everything else sits on, plus the mark.
 *
 * Both are lifted from areel.org rather than invented: the ground is the site's double CAD
 * grid, and the glass is its `.shell` recipe. Neither uses a blur, so both are cheap.
 */

/**
 * The site's ground: a double engineering grid, 96dp majors over 16dp minors, both 1px at
 * ink-06. Drawn rather than tiled because a 16dp bitmap tile costs more memory than the lines
 * cost to stroke, and this repaints only on resize.
 */
fun Modifier.cadGrid(
    minor: Dp = 16.dp,
    major: Dp = 96.dp,
    color: Color = Areel.Ink06,
): Modifier = this.drawBehind {
    val minorPx = minor.toPx()
    val majorPx = major.toPx()
    // CSS 1px is a device-independent hairline, so this must be 1.dp. As a literal 1f it was
    // one physical pixel — a third of a hairline at 3x — and the grid was all but invisible.
    val weight = 1.dp.toPx()

    fun rule(step: Float) {
        var x = 0f
        while (x <= size.width) {
            drawLine(color, Offset(x, 0f), Offset(x, size.height), weight)
            x += step
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), weight)
            y += step
        }
    }
    rule(minorPx)
    // The majors are drawn over the minors, doubling their density exactly as the site's
    // four stacked gradients do. Without this the grid reads as one uniform mesh.
    rule(majorPx)
}

/**
 * areel.org's `.shell`, translated line by line from the CSS in `docs/preview/designs.html`
 * rather than from memory:
 *
 * ```
 * background: linear-gradient(148deg, rgba(255,255,255,.26) 0%,
 *                                     rgba(255,255,255,.06) 46%,
 *                                     rgba(255,255,255,.18) 100%);
 * border: 1px solid rgba(255,255,255,.6);
 * box-shadow: rgba(255,255,255,.7) 0 1px 0 inset,
 *             rgba(16,16,16,.10)  0 0 0 1px inset,
 *             rgba(16,16,16,.40)  0 -16px 30px -28px inset,
 *             rgba(16,16,16,.45)  0 22px 40px -32px;
 * ::before  linear-gradient(112deg, transparent 40%, rgba(255,255,255,.22) 46%, transparent 52%)
 * ```
 *
 * Every earlier attempt at this paraphrased it, and each paraphrase cost a round: a
 * corner-to-corner fill instead of 148deg, a hand-rolled streak instead of 112deg, an even
 * elevation instead of a directional drop. Compose has no inset shadow, so the lip, the ring
 * and the bottom shade are drawn explicitly — that is the one genuine approximation left, and
 * it is why this lives in exactly one place.
 *
 * [small] retunes the drop for card-sized surfaces — a spread tuned for a 400px panel reads as
 * a rendering bug under a 40dp source card.
 *
 * Rectangular only, deliberately. It previously took a `shape`, but the fill is painted as a
 * plain rect while only the border followed the shape — so any non-rectangular glass would have
 * leaked its gradients outside its own outline. Every glass surface in this design is a
 * rectangular pane; if a chamfered one is ever wanted, clip the fills properly rather than
 * re-adding the parameter.
 */
fun Modifier.glassSurface(
    small: Boolean = false,
    /**
     * The white outline and the bright top lip. Part of the shell as specified, so it is on by
     * default; the gate turns it off because its 2dp ink frame supersedes it, which is the one
     * exception the design doc calls out.
     */
    framed: Boolean = true,
): Modifier = this
    // rgba(16,16,16,.45) 0 22px 40px -32px is the one line of the recipe that is *not* here.
    //
    // It is a smudge under the lower edge: offset down, blurred wide, then pulled back in hard
    // by the negative spread. Compose offers only `elevation`, which surrounds the box evenly,
    // and the platform paints it behind the whole node — so through a translucent pane it shows
    // up inside the edges as a grey frame several pixels deep. Suppressing the ambient
    // component and keeping only the spot did not fix it either; measured on device the ring
    // was still there. Against the mockup rendered at 4x, which has no such ring, the honest
    // choice is to drop the shadow rather than approximate it into an artefact. It is the
    // faintest line in the recipe and the only one Compose cannot express.
    .drawBehind {
        val px = 1.dp.toPx()

        // background — the CAD grid underneath stays legible through it. That show-through is
        // the whole point, and it is also the speaker cue in the thread.
        drawRect(
            cssGradient(148f, 0f to Areel.GlassHi, 0.46f to Areel.GlassMid, 1f to Areel.GlassLo),
        )

        // ::before — the specular streak.
        drawRect(
            cssGradient(
                112f,
                0.40f to Color.Transparent,
                0.46f to Areel.GlassStreak,
                0.52f to Color.Transparent,
            ),
        )

        // rgba(16,16,16,.40) 0 -16px 30px -28px inset — blur 30 against spread -28 barely
        // reaches in. A sheet of plastic, not a bevel.
        val reach = (if (small) 8.dp else 18.dp).toPx()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Areel.GlassShade.copy(alpha = if (small) 0.09f else 0.13f),
                startY = size.height - reach,
                endY = size.height,
            ),
        )

        // rgba(16,16,16,.10) 0 0 0 1px inset — the pane's actual edge, one pixel inside the
        // white border. The border is the highlight sitting on this, not the edge itself.
        val ring = if (framed) px else 0f
        drawRect(
            color = Areel.GlassInset,
            topLeft = Offset(ring + px / 2f, ring + px / 2f),
            size = Size(size.width - 2f * ring - px, size.height - 2f * ring - px),
            style = Stroke(px),
        )

        // rgba(255,255,255,.7) 0 1px 0 inset — the top lip, inside the border.
        if (framed) {
            drawLine(Areel.GlassLip, Offset(px, px * 1.5f), Offset(size.width - px, px * 1.5f), px)
        }
    }
    // border: 1px solid rgba(255,255,255,.6)
    .then(if (framed) Modifier.border(1.dp, Areel.GlassBorder) else Modifier)

/**
 * A CSS `linear-gradient(<deg>, …)`, in Compose terms.
 *
 * CSS measures the angle from north, clockwise, and sizes the gradient line so the percentage
 * stops land where the spec says on *this* box. Compose takes two points, so the tempting
 * translation is a corner-to-corner diagonal — but that only equals 148deg at one aspect ratio.
 * On a wide chat bubble it dragged the 6% stop out towards two corners, and the sheet looked
 * like it faded out before reaching its own edges.
 */
private fun DrawScope.cssGradient(deg: Float, vararg stops: Pair<Float, Color>): Brush {
    val rad = (deg * PI / 180.0).toFloat()
    // Screen coordinates put y downwards, so north is -y: 0deg → (0,-1), 90deg → (1,0).
    val dx = sin(rad)
    val dy = -cos(rad)
    val len = abs(size.width * dx) + abs(size.height * dy)
    val cx = size.width / 2f
    val cy = size.height / 2f
    return Brush.linearGradient(
        *stops,
        start = Offset(cx - dx * len / 2f, cy - dy * len / 2f),
        end = Offset(cx + dx * len / 2f, cy + dy * len / 2f),
    )
}

/**
 * `.user`'s `border-right: 3px solid --magenta`.
 *
 * It belongs to the *text block*, not to the pane around it. An earlier version drew it down
 * the pane's own right edge at full height, which is a border on a box; here the wrap's 12dp of
 * vertical padding insets it top and bottom, and that inset is what makes it read as a margin
 * rule standing beside the words.
 *
 * Shared by the message bubble and the composer field, so that what is being typed and what has
 * already been said are the same object.
 */
fun Modifier.userRule(): Modifier = this.drawBehind {
    val w = 3.dp.toPx()
    drawRect(Areel.Magenta, Offset(size.width - w, 0f), Size(w, size.height))
}

/**
 * What the microphone is hearing, leaving the rule that marks the user's own words.
 *
 * The rule is already the thing that says "this is you"; while it is listening, it sends what
 * it hears out across the field. Lines rather than a waveform, on the same grid as everything
 * else - a smooth curve would be the only round thing in the app.
 *
 * Each line is emitted at the rule and travels left at a constant speed, fading evenly the
 * whole way and reaching nothing by [TRAVEL] of the width. That fraction is what keeps it clear
 * of 录音中 and 松开发送语音 at the other end: the words are read, not decorated.
 *
 * **The gap between lines is the voice.** Speed is constant, so how far apart they sit is
 * decided entirely by how often one is emitted, and that interval shortens with volume - a loud
 * moment packs them, a quiet one strings them out. An earlier version modulated brightness
 * instead, which the eye reads as one thing pulsing rather than as a rate.
 *
 * It starts empty. The lines are a queue rather than a standing pattern, so pressing the button
 * emits the first one at the rule and the field fills from there; the previous version ran a
 * loop the whole time and appeared already full the instant it was shown.
 */
fun Modifier.voiceWave(active: Boolean, level: () -> Float): Modifier = composed {
    // Progress of each live line, 0 at the rule to 1 where it is gone. A plain list, because
    // there are never more than a handful and they leave in the order they arrived.
    val lines = remember { mutableStateListOf<Float>() }

    LaunchedEffect(active) {
        lines.clear()
        if (!active) return@LaunchedEffect
        var last = withFrameNanos { it }
        var sinceEmit = Float.MAX_VALUE      // emit one immediately, so the press is answered
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000L).toFloat()
            last = now

            val loud = level().coerceIn(0f, 1f)
            val interval = EMIT_SLOW_MS + (EMIT_FAST_MS - EMIT_SLOW_MS) * loud

            sinceEmit += dt
            if (sinceEmit >= interval) {
                sinceEmit = 0f
                lines.add(0f)
            }
            for (i in lines.indices) lines[i] = lines[i] + dt / LIFE_MS
            while (lines.isNotEmpty() && lines.first() >= 1f) lines.removeAt(0)
        }
    }

    drawBehind {
        if (lines.isEmpty()) return@drawBehind
        val rule = 3.dp.toPx()
        val width = 2.dp.toPx()
        val travel = size.width * TRAVEL

        lines.forEach { p ->
            val x = size.width - rule - width - p * travel
            if (x < 0f) return@forEach
            drawRect(
                color = Areel.Magenta.copy(alpha = (1f - p).coerceIn(0f, 1f)),
                topLeft = Offset(x, 0f),
                size = Size(width, size.height),
            )
        }
    }
}

/**
 * How often a line leaves the rule, in milliseconds, at silence and at full voice.
 *
 * These two numbers are the whole meter. Speed is fixed, so the interval is the spacing, and
 * the spacing is what somebody reads as loud or quiet.
 */
private const val EMIT_SLOW_MS = 260f
private const val EMIT_FAST_MS = 70f

/** How long a line takes to cross [TRAVEL] and fade out. */
private const val LIFE_MS = 1300f

/** How far across the field a line gets before it is gone. Leaves the words at the left alone. */
private const val TRAVEL = 0.55f

/**
 * The mark. Every edge lands on 0/45/90 with the head into the upper-right, so it reads as a
 * glyph beside the wordmark rather than a picture placed next to it. Drawn rather than shipped
 * as a VectorDrawable because it needs three different colour pairings (band, gate, watermark)
 * and a drawable would mean three files or a tint that also recolours the eye.
 */
@Composable
fun FishMark(
    modifier: Modifier,
    body: Color,
    eye: Color? = Areel.Magenta,
) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val path = Path().apply {
            // body
            moveTo(7.4f * s, 16.6f * s)
            lineTo(7.4f * s, 9.9f * s)
            lineTo(11.3f * s, 5.6f * s)
            lineTo(18.4f * s, 5.6f * s)
            lineTo(18.4f * s, 12.7f * s)
            lineTo(14.1f * s, 16.6f * s)
            close()
            // tail
            moveTo(7.4f * s, 16.6f * s)
            lineTo(2.6f * s, 21.4f * s)
            lineTo(7.4f * s, 21.4f * s)
            close()
        }
        drawPath(path, body)
        eye?.let {
            drawRect(it, topLeft = Offset(14.6f * s, 7.4f * s), size = Size(2f * s, 2f * s))
        }
    }
}

/**
 * What the meter shows. Maps from `:core`'s AnswerShape at the wiring layer — CONFIDENT and
 * REFUTED both land on [FULL], because the meter reports *sureness* and the prose reports
 * direction. A confident "no" is a full bar.
 */
enum class Confidence(val filled: Int, val color: Color) {
    FULL(4, Areel.Conf4),
    HIGH(3, Areel.Conf3),
    MEDIUM(2, Areel.Conf2),
    LOW(1, Areel.Conf1),
}
