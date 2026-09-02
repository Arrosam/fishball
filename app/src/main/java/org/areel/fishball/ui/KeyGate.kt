package org.areel.fishball.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.areel.fishball.R
import org.areel.fishball.ui.theme.Areel

/**
 * First launch. Spec §1 — one key, handed over in person, no signup or password.
 *
 * Deliberately wordless: the placeholder names the thing being asked for and there is no
 * instructional paragraph. A user who does not read UI chrome is not helped by more of it.
 *
 * Design A1 + A-bg1. The field it leaves behind is filled the way the site fills a field —
 * a drafting grid, one drifting crosshair, and one large cropped object. Two ambient systems
 * would read fidgety, so only the crosshair moves: the fish is static under everything at 10%
 * and reads as printed on the paper rather than as a second animation.
 */
@Composable
fun KeyGate(
    checking: Boolean,
    error: String?,
    /**
     * The underlying failure, verbatim - exception type, message, endpoint. Not for the person
     * this app is built for; for whoever they hand the phone to when it does not work.
     */
    detail: String?,
    onSubmit: (String) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var showDetail by remember(error) { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Areel.Concrete)
            .cadGrid()
            .imePadding(),
    ) {
        Watermark(Modifier.align(Alignment.BottomEnd))
        Crosshair()

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FishMark(Modifier.size(24.dp), body = Areel.Ink)
                Spacer(Modifier.width(9.dp))
                Text(
                    stringResource(R.string.wordmark),
                    style = MaterialTheme.typography.displaySmall,
                    color = Areel.Ink,
                )
            }
            CheckerBand()

            ActivationField(
                value = key,
                onValueChange = { key = it },
                onSubmit = { if (key.isNotBlank() && !checking) onSubmit(key.trim()) },
            )

            // Whatever went wrong, said in one line without a status code - the person reading
            // it cannot act on "401". The detail is one tap underneath rather than absent,
            // because the same screen has to serve whoever they ask for help.
            if (error != null) {
                Column {
                    Row(
                        Modifier.pressable(Feel.TOGGLE, enabled = detail != null) { showDetail = !showDetail },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Areel.Magenta,
                        )
                        if (detail != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    if (showDetail) R.string.detail_hide else R.string.detail_show,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = Areel.Ink40,
                            )
                        }
                    }
                    if (showDetail && detail != null) {
                        Spacer(Modifier.height(8.dp))
                        // Mono, because this is a machine's words - and left unwrapped-looking
                        // rather than prettied up, so it can be read back over the phone.
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelMedium,
                            color = Areel.Ink60,
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassSurface(small = true)
                                .padding(10.dp),
                        )
                    }
                }
            }

            Button(
                onClick = { if (key.isNotBlank() && !checking) onSubmit(key.trim()) },
                enabled = key.isNotBlank() && !checking,
                shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Areel.Magenta,
                    contentColor = Areel.Paper,
                    disabledContainerColor = Areel.Ink20,
                    disabledContentColor = Areel.Paper,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.gate_confirm),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * The mark as an architectural watermark, cropped by the right edge.
 *
 * The bottom is no longer cropped. The mark's lowest point is its tail, at 21.4 of 24 down its
 * own box - so at this size the drawing stops 50dp short of the box it is laid out in, and the
 * old 56dp downward offset pushed the tail off the screen while looking, in code, like it was
 * only bleeding the empty margin. 36dp leaves the tail whole with 14dp to spare.
 *
 * The horizontal offset scales with the size, so the head still runs off the right edge and the
 * mark reads as a detail of something larger rather than a picture placed in the corner.
 */
@Composable
private fun Watermark(modifier: Modifier = Modifier) {
    FishMark(
        modifier = modifier
            .size(460.dp)
            .offset(x = 140.dp, y = 36.dp)
            .alpha(0.10f),
        body = Areel.Ink,
        eye = null,
    )
}

/**
 * A1 — two hairlines drifting on different periods so the pattern never visibly repeats, with
 * a tick riding the intersection. 56s and 74s: slow enough that you notice the drawing has
 * moved rather than watching it move.
 */
@Composable
private fun Crosshair() {
    val transition = rememberInfiniteTransition(label = "crosshair")
    val h by transition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(56_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "vertical-rule",
    )
    val v by transition.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.80f,
        animationSpec = infiniteRepeatable(tween(74_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "horizontal-rule",
    )

    Box(
        Modifier.fillMaxSize().drawBehind {
            val x = size.width * h
            val y = size.height * v
            val hairline = 1.dp.toPx()
            drawLine(Areel.Ink10, Offset(x, 0f), Offset(x, size.height), hairline)
            drawLine(Areel.Ink10, Offset(0f, y), Offset(size.width, y), hairline)
            val tick = 5.dp.toPx()
            drawRect(Areel.Ink20, Offset(x - tick / 2, y - tick / 2), Size(tick, tick))
        },
    )
}

/**
 * Glass, per the surface allocation: the input is a display case, not a control that presses.
 * BasicTextField rather than OutlinedTextField because the field needs a translucent pane and
 * Material's outlined variant owns its own container and indicator.
 */
@Composable
private fun ActivationField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            // A bold black border, not glass's soft white one. This is the single control a
            // first-time user has to find on a screen with nothing else on it.
            .glassSurface(framed = false)
            .border(2.dp, Areel.Ink)
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                stringResource(R.string.gate_key_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = Areel.Ink40,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Areel.Ink),
            cursorBrush = SolidColor(Areel.Magenta),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
