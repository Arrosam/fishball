package org.areel.fishball.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.areel.fishball.ui.theme.Areel

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
 * areel.org's `.shell`, translated.
 *
 * CSS gets four stacked box-shadows, two of them inset. Compose has no inset shadow, so the
 * top light lip and the bottom inner shade are drawn explicitly instead. The result is close
 * enough that the two surfaces read as the same material; it is not pixel-identical, and the
 * approximation is the reason this lives in one place.
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
     * The white outline and the bright top lip. They make a card read as a mounted slip, which
     * suits the source card and the memory ledger. On a large surface they read as a white
     * frame drawn around the pane instead — so the user's own surfaces turn them off and let
     * the magenta rule be the only edge.
     */
    framed: Boolean = true,
): Modifier = this
    // No elevation. Android draws an elevation shadow behind the whole node, and opaque content
    // is what normally hides it — translucent glass does not, so the shadow showed *through*
    // the pane as a dark band inside its own edges: 11px down the top, 8px in from the left,
    // measured on device. The right edge looked fine only because the opaque magenta rule
    // covered it. That inset grey frame is what kept reading as "the white part is smaller
    // than the pane". A stronger fill carries the pane on its own now; nothing floats.
    .drawBehind {
        // Mostly clear. The CAD grid underneath must stay legible through the pane — that
        // show-through is the whole point, and it is also the speaker cue in the thread.
        //
        // This covers the node edge to edge. It has to: drawn any smaller it becomes a pale
        // rectangle floating inside a larger pane, which is exactly the "white part" that got
        // reported three times. The fill IS the pane, so its bounds are the pane's bounds.
        drawRect(
            Brush.linearGradient(
                0f to Areel.GlassHi,
                0.5f to Areel.GlassMid,
                1f to Areel.GlassLo,
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            ),
        )
        // Specular streak on a fixed slope rather than one normalised to the box. Normalising
        // it made the glint a near-vertical bar on wide, short panes like a chat bubble, which
        // read as a rendering artefact rather than a highlight.
        val run = size.height * 2.5f
        val originX = size.width * 0.22f
        drawRect(
            Brush.linearGradient(
                0.00f to Color.Transparent,
                0.46f to Color.Transparent,
                0.50f to Areel.GlassStreak,
                0.54f to Color.Transparent,
                1.00f to Color.Transparent,
                start = Offset(originX - run, size.height),
                end = Offset(originX + run, 0f),
            ),
        )
        // Top light lip only. The bottom inner shade is gone with the drop shadow and for the
        // same reason: any tone that fades in near an edge stops the sheet short of that edge,
        // and at this size a few pixels of that is plainly visible.
        if (framed) {
            drawLine(Areel.GlassLip, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
        }
    }
    .then(if (framed) Modifier.border(1.dp, Areel.GlassBorder) else Modifier)

/**
 * The user's surface: bare glass with the magenta rule as its only hard edge.
 *
 * Shared by their message bubbles and the composer field on purpose — what they are typing and
 * what they have already said should look like the same object, so the composer reads as the
 * next bubble rather than as a separate control.
 */
fun Modifier.userPane(): Modifier = this
    .glassSurface(framed = false)
    .drawBehind {
        val w = 3.dp.toPx()
        drawRect(Areel.Magenta, Offset(size.width - w, 0f), Size(w, size.height))
    }

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
