package org.areel.fishball.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.areel.fishball.R
import org.areel.fishball.ui.theme.Areel

/** The chamfer, shared by the assistant plate and the pending plate so they read as one family. */
private val CUT = 14.dp
private val BubbleShape = CutCornerShape(topStart = CUT)

/** areel.org's `--rule`: a 1px CAD hairline, not a Material divider. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Areel.Ink20) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * The checkerboard motif from the site, used as a band edge rather than as decoration.
 *
 * [cell] is a Dp, not a raw pixel count. It was a Float before, used straight as pixels, which
 * on a 3x screen drew 2.7dp squares instead of 8dp — a third of the intended size, and the
 * reason the band read as a fine dotted line rather than a checker.
 */
@Composable
fun CheckerBand(modifier: Modifier = Modifier, cell: Dp = 8.dp, color: Color = Areel.Ink) {
    // Two rows, so the strip is a full checker tile tall rather than half of one.
    //
    // It used to be declared one cell tall and then draw two rows anyway. drawBehind does not
    // clip, so the second row was painted outside the band, over the thread, where the first
    // message covered it. Deleting that row was the wrong repair - the row belongs here, the
    // band was simply not tall enough to hold it.
    Canvas(modifier.fillMaxWidth().height(cell * 2)) {
        val c = cell.toPx()
        val cols = (size.width / c).toInt() + 1
        for (row in 0 until 2) {
            for (col in 0 until cols) {
                // Ink squares only. The other half of the checker is left unpainted rather
                // than filled grey, so the ground reads straight through it.
                if ((row + col) % 2 == 0) {
                    drawRect(color, Offset(col * c, row * c), Size(c, c))
                }
            }
        }
    }
}

/** Near-black band: the mark and wordmark locked up left, the memory plate right. */
@Composable
fun TopBand(onMemoryClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Areel.Ink)
                .padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FishMark(Modifier.size(22.dp), body = Areel.Paper)
                Spacer(Modifier.width(9.dp))
                Text(
                    stringResource(R.string.wordmark),
                    style = MaterialTheme.typography.displaySmall,
                    color = Areel.Paper,
                )
            }
            MemoryPlate(onClick = onMemoryClick)
        }
    }
}

/**
 * The memory entry point. 44dp plate so it reads as something to press; no tilt, because the
 * app's one diagonal belongs to the mark.
 *
 * The brain is magenta and the label is ink, and that split is deliberate: magenta on paper
 * measures 3.86:1, which fails the 4.5:1 bar for text at this size and clears the 3:1 bar for
 * a non-text component. The icon may carry the accent; the word may not.
 */
@Composable
private fun MemoryPlate(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Column(
        Modifier
            .size(44.dp)
            .offset(x = if (pressed) 1.dp else 0.dp, y = if (pressed) 1.dp else 0.dp)
            .background(if (pressed) Areel.Concrete2 else Areel.Paper, RectangleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_brain),
            contentDescription = null,
            tint = Areel.Magenta,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(R.string.memory),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = Areel.Ink,
        )
    }
}

// ---------------------------------------------------------------- the thread

/**
 * The assistant's turn: a flat paper plate, chamfered top-left, with the weight concentrated
 * on the cut edge alone. Flat rather than glass on purpose — this is the turn carrying a
 * meter, a source card and dense text, and opaque paper is a better ground for that.
 */
@Composable
fun AssistantBubble(
    text: String,
    modifier: Modifier = Modifier,
    confidence: Confidence? = null,
    conflict: Boolean = false,
    sources: List<Source> = emptyList(),
) {
    val edge = remember { Path() }
    Box(
        modifier
            .fillMaxWidth()
            .background(Areel.Paper, BubbleShape)
            .border(1.dp, Areel.Ink20, BubbleShape)
            .drawWithContent {
                drawContent()
                drawCutEdge(edge)
            }
            .padding(16.dp),
    ) {
        Column {
            ConfidenceMeter(confidence, conflict, Modifier.align(Alignment.End))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Areel.Ink)
            SourceCard(sources)
        }
    }
}

/**
 * The user's turn: an open text block on a floating glass pane, with the magenta rule as its
 * only hard edge.
 *
 * The speaker cue is material, not colour — the grid reads *through* this and stops dead at
 * the assistant's plate. That survives greyscale and colour-blindness, which a hue difference
 * would not.
 */
@Composable
fun UserBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        // .userwrap.shell - width:86%; padding:12px 0 12px 14px. Fixed, not content-hugging:
        // it was tried the other way, and a rule that slides left to meet a short question
        // stops being a margin and starts being an underline on the words.
        Box(
            Modifier
                .fillMaxWidth(0.86f)
                .glassSurface()
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
        ) {
            // .user - padding:2px 13px 2px 0; border-right:3px solid --magenta. The 16dp end
            // padding is the CSS's 13px gap plus the 3px the rule itself occupies.
            Box(
                Modifier
                    .fillMaxWidth()
                    .userRule()
                    .padding(top = 2.dp, bottom = 2.dp, end = 16.dp),
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = Areel.Ink)
            }
        }
    }
}

/**
 * Spec §6, amended — the meter is labelled now, so it is no longer the wordless instrument the
 * original rule described. Label and cells share one colour off the confidence ramp, so the
 * value is encoded twice: by how many cells are lit, and by how far the hue has travelled from
 * ink toward magenta.
 *
 * Nothing is drawn at all when there is no answer to be confident about — an empty bar would
 * claim an answer exists that we have zero confidence in.
 */
@Composable
fun ConfidenceMeter(
    confidence: Confidence?,
    conflict: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (confidence == null && !conflict) return

    Row(
        modifier.padding(bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (conflict) R.string.conflict_label else R.string.confidence_label),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.sp),
            color = if (conflict) Areel.Magenta else confidence!!.color,
        )
        Spacer(Modifier.width(6.dp))
        if (conflict) FaultLine() else Cells(confidence!!)
    }
}

/** Four cells, filled left to right in the ramp colour; unlit cells keep a hairline outline. */
@Composable
private fun Cells(confidence: Confidence) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(4) { index ->
            val lit = index < confidence.filled
            Box(
                Modifier
                    .size(width = 9.dp, height = 5.dp)
                    .then(
                        if (lit) Modifier.background(confidence.color)
                        else Modifier.border(1.dp, Areel.Ink20),
                    ),
            )
        }
    }
}

/**
 * CONFLICT is not a point on the scale — the app is certain, the world is split. Two fully
 * filled runs, misaligned by 1dp, pressed against a magenta seam. Anything half-full would be
 * a lie in the wrong direction.
 */
@Composable
private fun FaultLine() {
    Canvas(Modifier.size(width = 45.dp, height = 8.dp)) {
        val u = size.width / 45f
        drawRect(Areel.Ink, Offset(0f, 2f * u), Size(20f * u, 5f * u))
        drawRect(Areel.Ink, Offset(25f * u, 1f * u), Size(20f * u, 5f * u))
        drawRect(Areel.Magenta, Offset(22f * u, 0f), Size(1f * u, 8f * u))
    }
}

/**
 * Spec §6 — the receipt below the answer. Glass, floating on the assistant's paper plate: the
 * quotation is evidence mounted on the drawing rather than part of it.
 *
 * The bracket mark is a drawn Chinese quotation pair, which is also the §25 affordance —
 * tapping it shows the verified quote, so the icon and the action are the same idea.
 */
@Composable
fun SourceCard(sources: List<Source>, modifier: Modifier = Modifier) {
    if (sources.isEmpty()) return
    Column(
        modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sources.forEach { source ->
            // Spec §25, the half the user sees. Tapping a source shows the passage the answer
            // rests on - and it is the passage as the *source* wrote it, sliced out of the
            // retrieved text by the verifier, never the model's rendering of it. A source with
            // nothing to open says so rather than opening onto a paraphrase.
            var open by remember(source) { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassSurface(small = true)
                    .clickable(enabled = source.quote != null) { open = !open }
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        painter = painterResource(R.drawable.ic_source),
                        contentDescription = null,
                        tint = Areel.Ink,
                        modifier = Modifier.size(12.dp).offset(y = 2.dp),
                    )
                    Column(Modifier.padding(start = 9.dp).weight(1f)) {
                        Text(source.name, style = MaterialTheme.typography.labelSmall, color = Areel.Ink)
                        Text(source.host, style = MaterialTheme.typography.labelMedium, color = Areel.Ink40)
                    }
                    if (source.quote != null) {
                        Text(
                            stringResource(if (open) R.string.quote_hide else R.string.quote_show),
                            style = MaterialTheme.typography.labelMedium,
                            color = Areel.Magenta,
                        )
                    }
                }
                if (open && source.quote != null) {
                    Spacer(Modifier.height(9.dp))
                    Hairline(color = Areel.Ink20)
                    Spacer(Modifier.height(9.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        // The magenta rule again, and on purpose: it is the mark this app uses
                        // for words that are quoted rather than composed.
                        Box(Modifier.width(3.dp).height(quoteRuleHeight).background(Areel.Magenta))
                        Text(
                            source.quote,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Areel.Ink,
                            modifier = Modifier.padding(start = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

data class Source(
    val name: String,
    val host: String,
    /**
     * Spec §25 — the source's own wording, sliced out of the retrieved text by the verifier.
     * Null when the model cited a source without quoting it, which is allowed; what is not
     * allowed, and cannot happen, is a value here that the source does not contain.
     */
    val quote: String? = null,
)

/**
 * Spec §21 — the wait, in the thread rather than pinned above the composer, so the placeholder
 * occupies the slot the answer will land in.
 *
 * Hatching is the CAD convention for material not yet resolved. The magenta segment laps the
 * whole outline including the chamfer — indeterminate by construction, since a 24dp segment on
 * a long path never resolves into a percentage.
 */
@Composable
fun PendingBubble(steps: List<String>, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pending")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5600, easing = LinearEasing)),
        label = "lap",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    // Hoisted so the lap costs a stroke redraw per frame, not two allocations per frame.
    val outline = remember { Path() }
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    val hatchClip = remember { Path() }

    Box(
        modifier
            .fillMaxWidth()
            .background(Areel.Paper, BubbleShape)
            .border(1.dp, Areel.Ink20, BubbleShape)
            .drawBehind { hatch(hatchClip) }
            .drawWithContent {
                drawContent()
                drawLap(phase, outline, measure, segment)
            }
            .padding(16.dp),
    ) {
        Column {
            steps.forEachIndexed { index, step ->
                val live = index == steps.lastIndex
                Row(
                    Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .alpha(if (live) pulse else 0.35f)
                            .background(if (live) Areel.Magenta else Areel.Ink40),
                    )
                    Text(
                        step,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (live) Areel.Ink else Areel.Ink40,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * The chamfered outline, built into a caller-owned Path so nothing allocates per frame.
 * Shared by the hatch clip and the lap so both trace exactly the same edge.
 */
private fun DrawScope.bubbleOutline(path: Path): Path {
    val cut = CUT.toPx()
    path.reset()
    path.moveTo(cut, 0f)
    path.lineTo(size.width, 0f)
    path.lineTo(size.width, size.height)
    path.lineTo(0f, size.height)
    path.lineTo(0f, cut)
    path.close()
    return path
}

/**
 * The heavy edge on the chamfer, clipped to the plate.
 *
 * Drawn unclipped, a 3dp stroke centred on the chamfer put half its width outside the shape,
 * so on a device it read as a small flag floating off the bubble's corner rather than as a
 * weighted edge of it. Clipping and drawing at double width leaves exactly 3dp inside.
 */
private fun DrawScope.drawCutEdge(path: Path) {
    clipPath(bubbleOutline(path)) {
        val c = CUT.toPx()
        drawLine(Areel.Ink, Offset(0f, c), Offset(c, 0f), 6.dp.toPx(), StrokeCap.Butt)
    }
}

/**
 * 45° hatch at ink-06, 8dp pitch — "area under construction".
 *
 * Clipped to the chamfer. Unclipped it painted hatch lines across the cut corner, where there
 * is no plate — so the bubble appeared to have a ghost triangle floating off its top-left.
 * Stroke is 1dp rather than a raw 1f: a literal pixel is a third of a hairline at 3x.
 */
private fun DrawScope.hatch(clip: Path) {
    clipPath(bubbleOutline(clip)) {
        val gap = 8.dp.toPx()
        val weight = 1.dp.toPx()
        var x = -size.height
        while (x < size.width) {
            drawLine(Areel.Ink06, Offset(x, size.height), Offset(x + size.height, 0f), weight)
            x += gap
        }
    }
}

/** One 24dp magenta segment travelling the chamfered outline, wrapping at the seam. */
private fun DrawScope.drawLap(phase: Float, outline: Path, measure: PathMeasure, segment: Path) {
    measure.setPath(bubbleOutline(outline), false)
    val length = measure.length
    if (length <= 0f) return

    val segLength = 24.dp.toPx()
    val start = phase * length
    segment.reset()
    if (start + segLength <= length) {
        measure.getSegment(start, start + segLength, segment, true)
    } else {
        measure.getSegment(start, length, segment, true)
        measure.getSegment(0f, start + segLength - length, segment, true)
    }
    drawPath(segment, Areel.Magenta, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Butt))
}

/**
 * The thread before anything is in it: the mark as a watermark, and one line introducing the
 * app.
 *
 * Ground rather than a message. An opening bubble makes the app look like it spoke before the
 * user arrived, and it puts something in the thread that nobody asked for; a watermark is
 * plainly the empty state and disappears the moment there is anything to show.
 */
@Composable
fun EmptyThread(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The gate's watermark treatment: ink at a tenth, no eye. The magenta eye would be a
        // muddy pink at this alpha, and the mark reads as a silhouette without it.
        //
        // Nudged, because the path is not centred inside its own 24-unit box: it spans 2.6 to
        // 18.4 across and 5.6 to 21.4 down, putting its middle 1.5 units left of and below the
        // box's. Beside a wordmark that never shows. Alone on an empty screen, over centred
        // text, it measured 11px off and looked it.
        FishMark(
            Modifier
                .size(MARK)
                .offset(x = MARK_NUDGE, y = -MARK_NUDGE)
                .alpha(0.10f),
            body = Areel.Ink,
            eye = null,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.thread_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = Areel.Ink40,
            textAlign = TextAlign.Center,
        )
    }
}

private val MARK = 104.dp
private val MARK_NUDGE = MARK * (1.5f / 24f)

/**
 * The quoted passage's rule. Fixed rather than matched to the text: a rule that grows with a
 * long quotation starts reading as a container round it, and this is a margin mark.
 */
private val quoteRuleHeight = 18.dp
