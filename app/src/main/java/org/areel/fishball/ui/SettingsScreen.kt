package org.areel.fishball.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.areel.fishball.R
import org.areel.fishball.ui.theme.Areel

/** Which model the app drives, in the two words a user should have to think about. */
enum class Mode { FAST, PRO }

/**
 * Settings. Spec §1 and §8.
 *
 * Two things, because there are only two the user can meaningfully change: the code that lets
 * them in, and how hard the thing thinks. Everything else about this product is a decision
 * somebody already made on their behalf, and putting it on a screen would only invite them to
 * get it wrong.
 *
 * Wears the memory screen's clothes on purpose — same band, same tabs, same ledger rows — so
 * that "the other screen" is one idea rather than two.
 */
@Composable
fun SettingsScreen(
    mode: Mode,
    keyHint: String,
    busy: Boolean,
    error: String?,
    /** Turns kept, and what they cost on disk. */
    history: Pair<Int, Long>,
    onModeChange: (Mode) -> Unit,
    onKeyChange: (String) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
) {
    var editingKey by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var confirmingClear by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Areel.Concrete)
            .cadGrid()
            .imePadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            SettingsBand(onBack = onBack)

            // Enters the way the ledger does, from the plate that opened it. The band stays
            // put: it is the same masthead in the same place, and animating it would make the
            // app's one fixed landmark look like it was being replaced.
            Box(
                Modifier
                    .weight(1f)
                    .clipToBounds(),
            ) {
            EnterFrom(pivotX = 1f, pivotY = 0f, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                SettingLabel(stringResource(R.string.settings_mode))
                Row(Modifier.fillMaxWidth()) {
                    ModeTab(
                        stringResource(R.string.mode_fast),
                        mode == Mode.FAST,
                        Modifier.weight(1f),
                    ) { if (mode != Mode.FAST) onModeChange(Mode.FAST) }
                    Spacer(Modifier.width(1.dp))
                    ModeTab(
                        stringResource(R.string.mode_pro),
                        mode == Mode.PRO,
                        Modifier.weight(1f),
                    ) { if (mode != Mode.PRO) onModeChange(Mode.PRO) }
                }
                Spacer(Modifier.height(8.dp))
                // Said plainly, because the effect is not obvious and it is not undoable: the
                // new model has read none of the conversation, so what carries over is a
                // summary rather than the thing itself.
                Text(
                    stringResource(R.string.mode_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = Areel.Ink40,
                )

                Spacer(Modifier.height(28.dp))
                SettingLabel(stringResource(R.string.settings_key))

                /*
                 * One box in both states.
                 *
                 * It used to be two: a glass card when idle and a differently-padded box with a
                 * 2dp ink frame when editing. Same declared width, but the frame and the extra
                 * padding moved the text inwards, so tapping it made the field visibly jump -
                 * which reads as the field resizing under your finger. Only the contents swap
                 * now; the surface, the padding and the edges hold still.
                 */
                val focus = remember { FocusRequester() }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .glassSurface(small = true)
                        .clickable(enabled = !editingKey) { editingKey = true; draft = "" }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                ) {
                    if (editingKey) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelMedium.copy(color = Areel.Ink),
                            cursorBrush = SolidColor(Areel.Magenta),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                keyHint,
                                style = MaterialTheme.typography.labelMedium,
                                color = Areel.Ink,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.settings_change),
                                style = MaterialTheme.typography.labelMedium,
                                color = Areel.Magenta,
                            )
                        }
                    }
                }

                // Out from under the field, and back into it. Expanding and sliding together so
                // they look extruded rather than revealed - a pair of buttons that merely faded
                // in would read as a second thing appearing beside the field rather than as the
                // field opening up.
                AnimatedVisibility(
                    visible = editingKey,
                    enter = expandVertically(tween(190, easing = EaseMech), Alignment.Top) +
                        slideInVertically(tween(190, easing = EaseMech)) { -it } +
                        fadeIn(tween(140)),
                    exit = shrinkVertically(tween(150, easing = EaseMech), Alignment.Top) +
                        slideOutVertically(tween(150, easing = EaseMech)) { -it } +
                        fadeOut(tween(110)),
                ) {
                    Row(Modifier.padding(top = 10.dp)) {
                        Plate(
                            stringResource(R.string.settings_save),
                            enabled = draft.isNotBlank() && !busy,
                            filled = true,
                            modifier = Modifier.weight(1f),
                        ) { onKeyChange(draft.trim()) }
                        Spacer(Modifier.width(10.dp))
                        Plate(
                            stringResource(R.string.settings_cancel),
                            enabled = !busy,
                            filled = false,
                            modifier = Modifier.weight(1f),
                        ) { editingKey = false; draft = "" }
                    }
                }

                Spacer(Modifier.height(28.dp))
                SettingLabel(stringResource(R.string.settings_history))

                Row(
                    Modifier
                        .fillMaxWidth()
                        .glassSurface(small = true)
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (turns, bytes) = history
                    Text(
                        stringResource(R.string.history_summary, turns, readableSize(bytes)),
                        style = MaterialTheme.typography.labelMedium,
                        color = Areel.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    // Two taps, not one. Deleting a conversation is the only irreversible thing
                    // on this screen, and the second tap is where the word changes from "清空"
                    // to something that says it is about to happen.
                    Text(
                        stringResource(
                            if (confirmingClear) R.string.history_confirm else R.string.history_clear,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = Areel.Magenta,
                        modifier = Modifier.clickable(enabled = turns > 0) {
                            if (confirmingClear) {
                                onClearHistory()
                                confirmingClear = false
                            } else {
                                confirmingClear = true
                            }
                        },
                    )
                }

                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Areel.Magenta,
                    )
                }
            }
            }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
        color = Areel.Ink40,
        modifier = Modifier.padding(bottom = 9.dp),
    )
}

/**
 * Bytes, in the units a person thinks in.
 *
 * One decimal past a megabyte and none below it: "1.4 MB" is a size, "1434 KB" is a number, and
 * nobody deciding whether to delete their conversations needs the second one.
 */
private fun readableSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** The memory screen's tab, doing a different job with the same shape. */
@Composable
private fun ModeTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(if (selected) Areel.Paper else Areel.Concrete2, RectangleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
            color = if (selected) Areel.Ink else Areel.Ink40,
        )
    }
}

@Composable
private fun Plate(
    label: String,
    enabled: Boolean,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(
                when {
                    !enabled -> Areel.Ink20
                    filled -> Areel.Magenta
                    else -> Areel.Concrete2
                },
                RectangleShape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (filled && enabled) Areel.Paper else Areel.Ink,
        )
    }
}

/** The thread's band, with the gear where the mark goes. */
@Composable
private fun SettingsBand(onBack: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Areel.Ink)
                .statusBarsPadding()
                .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.back),
                    tint = Areel.Paper,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.settings),
                style = MaterialTheme.typography.displaySmall,
                color = Areel.Paper,
            )
        }
        Hairline(color = Areel.Ink)
    }
}
