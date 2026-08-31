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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
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
fun TopBand(
    onMemoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Areel.Ink)
                // Painted first, inset second: the ink runs up behind the status bar and the
                // camera sits in the masthead rather than on a strip of grid above it.
                .statusBarsPadding()
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
            MorePlate(onMemory = onMemoryClick, onSettings = onSettingsClick)
        }
    }
}

/**
 * The masthead's one control.
 *
 * It used to be 记忆 alone. A second destination made it a menu, and a menu of two is still
 * worth having: the alternative was two plates competing for the same corner, and the corner is
 * the only spot on the masthead that is not the wordmark.
 *
 * Hand-rolled rather than Material's DropdownMenu, which arrives with rounded corners and a
 * tonal elevation that belong to a different design.
 */
@Composable
private fun MorePlate(onMemory: () -> Unit, onSettings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box {
        Box(
            Modifier
                .size(44.dp)
                .offset(x = if (pressed) 1.dp else 0.dp, y = if (pressed) 1.dp else 0.dp)
                .background(if (pressed || open) Areel.Concrete2 else Areel.Paper, RectangleShape)
                .clickable(interactionSource = interaction, indication = null) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_more),
                contentDescription = stringResource(R.string.more),
                tint = Areel.Ink,
                modifier = Modifier.size(20.dp),
            )
        }

        if (open) {
            val drop = with(LocalDensity.current) { 46.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, drop),
                onDismissRequest = { open = false },
            ) {
                Column(
                    Modifier
                        // The width lives here, not on the rows. A Popup gives its content the
                        // whole screen to measure against, and the divider inside fills what it
                        // is given - so an unconstrained menu came out full-bleed.
                        .width(190.dp)
                        .shadow(8.dp, clip = false, ambientColor = Areel.Ink, spotColor = Areel.Ink)
                        .background(Areel.Paper, RectangleShape)
                        .border(1.dp, Areel.Ink),
                ) {
                    MenuRow(R.drawable.ic_brain, stringResource(R.string.memory)) {
                        open = false
                        onMemory()
                    }
                    Hairline(color = Areel.Ink20)
                    MenuRow(R.drawable.ic_gear, stringResource(R.string.settings)) {
                        open = false
                        onSettings()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Areel.Magenta,
            modifier = Modifier.size(17.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
            color = Areel.Ink,
            modifier = Modifier.padding(start = 12.dp),
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
    /**
     * Present only when this turn is an apology rather than an answer. Hidden behind a tap for
     * the same reason the gate's is: unreadable to the person the app is for, and the first
     * thing anyone helping them will ask for.
     */
    detail: String? = null,
    /** §21's narration for this turn, kept after the answer landed rather than discarded. */
    steps: List<String> = emptyList(),
    /** What it was thinking while it worked. Same: kept, not thrown away when the answer came. */
    thinking: String = "",
) {
    val edge = remember { Path() }
    var showDetail by remember(detail) { mutableStateOf(false) }
    var showWork by remember(thinking) { mutableStateOf(false) }
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
            if (detail != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(if (showDetail) R.string.detail_hide else R.string.detail_show),
                    style = MaterialTheme.typography.labelMedium,
                    color = Areel.Magenta,
                    modifier = Modifier.clickable { showDetail = !showDetail },
                )
                if (showDetail) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelMedium,
                        color = Areel.Ink60,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Areel.Concrete2)
                            .padding(10.dp),
                    )
                }
            }
            SourceCard(sources)

            // The working-out does not vanish when the answer arrives. Watching it and then
            // losing it is worse than never seeing it — the one moment you want to check how
            // something was reached is after you have read what it says.
            if (steps.isNotEmpty() || thinking.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(if (showWork) R.string.detail_hide else R.string.process_show),
                    style = MaterialTheme.typography.labelMedium,
                    color = Areel.Ink40,
                    modifier = Modifier.clickable { showWork = !showWork },
                )
                if (showWork) {
                    Spacer(Modifier.height(9.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Areel.Concrete2)
                            .padding(11.dp),
                    ) {
                        steps.forEach {
                            Row(
                                Modifier.padding(bottom = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(4.dp).background(Areel.Ink40))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Areel.Ink,
                                    modifier = Modifier.padding(start = 9.dp),
                                )
                            }
                        }
                        if (thinking.isNotBlank()) {
                            if (steps.isNotEmpty()) Spacer(Modifier.height(6.dp))
                            Text(
                                thinking,
                                style = MaterialTheme.typography.labelMedium,
                                color = Areel.Ink40,
                            )
                        }
                    }
                }
            }
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
            val uriHandler = LocalUriHandler.current
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassSurface(small = true)
                    .clickable(enabled = source.quote != null) { open = !open }
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    // The mark opens the page; the rest of the row opens the quotation. Two
                    // targets on one row, and the smaller one is the one that leaves the app,
                    // which is the right way round for a control nobody meant to press.
                    Icon(
                        painter = painterResource(R.drawable.ic_source),
                        contentDescription = stringResource(R.string.open_source),
                        tint = if (source.url.isBlank()) Areel.Ink40 else Areel.Magenta,
                        modifier = Modifier
                            .size(30.dp)
                            .offset(x = (-6).dp, y = (-4).dp)
                            .clickable(enabled = source.url.isNotBlank()) {
                                runCatching { uriHandler.openUri(source.url) }
                            }
                            .padding(9.dp),
                    )
                    Column(Modifier.padding(start = 3.dp).weight(1f)) {
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
    /** Where it came from. The card opens this. */
    val url: String = "",
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
fun PendingBubble(
    steps: List<String>,
    modifier: Modifier = Modifier,
    /** The model's reasoning as it arrives. Shown to fill the wait, never kept. */
    thinking: String = "",
    /** The reply itself, once it starts arriving. Replaces the reasoning when it does. */
    streamed: String = "",
) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Bubbling()
                Text(
                    stringResource(R.string.thinking),
                    style = MaterialTheme.typography.labelSmall,
                    color = Areel.Ink,
                    modifier = Modifier.padding(start = 11.dp),
                )
            }

            steps.forEachIndexed { index, step ->
                val live = index == steps.lastIndex
                Row(
                    Modifier.padding(top = 7.dp),
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

            // Once the reply starts arriving it takes over: reasoning is what fills a wait,
            // and there is no wait left to fill once there are real words to read.
            // Reasoning is shown as a tail, not in full: a live turn produced 4,400 characters
            // of it, and a placeholder that grows without limit walks the composer off the
            // bottom of the screen. The answer is not clipped — that one is the point.
            val trailing = streamed.ifBlank { thinking.takeLast(THINKING_TAIL) }
            if (trailing.isNotBlank()) {
                Spacer(Modifier.height(11.dp))
                Hairline(color = Areel.Ink20)
                Spacer(Modifier.height(9.dp))
                Text(
                    trailing,
                    style = if (streamed.isBlank()) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = if (streamed.isBlank()) Areel.Ink40 else Areel.Ink,
                )
            }
        }
    }
}

/**
 * The mark, blowing bubbles.
 *
 * Three magenta squares rising on staggered loops. Squares because this design has no circles
 * in it anywhere, and a round bubble here would be the only one - so they are bubbles by
 * behaviour rather than by shape, which is the same trick the checker and the hatch play.
 */
@Composable
private fun Bubbling() {
    val transition = rememberInfiniteTransition(label = "bubbles")
    val rise = List(BUBBLE_COUNT) { i ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1500, delayMillis = i * 380, easing = LinearEasing),
            ),
            label = "bubble$i",
        )
    }

    Box(Modifier.size(width = 46.dp, height = 34.dp)) {
        FishMark(
            Modifier
                .size(26.dp)
                .align(Alignment.BottomStart),
            body = Areel.Ink,
        )
        rise.forEachIndexed { i, phase ->
            val p = phase.value
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        // Drifting right as they climb, so three identical squares do not read
                        // as one square stuttering.
                        x = (-10 + i * 5).dp + (p * 8).dp,
                        y = (26 - p * 26).dp,
                    )
                    .size((5 - i).dp.coerceAtLeast(3.dp))
                    // Fade in fast, out slow: a bubble that pops at full strength reads as a
                    // dropped frame.
                    .alpha(((1f - p) * (p * 4f).coerceAtMost(1f)).coerceIn(0f, 1f))
                    .background(Areel.Magenta),
            )
        }
    }
}

private const val BUBBLE_COUNT = 3

/** About four lines. Enough to watch a thought form, not enough to become the screen. */
private const val THINKING_TAIL = 180

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
