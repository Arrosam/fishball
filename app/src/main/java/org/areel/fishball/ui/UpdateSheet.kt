package org.areel.fishball.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.areel.fishball.R
import org.areel.fishball.data.Release
import org.areel.fishball.ui.theme.Areel
import java.io.File
import java.util.Locale

/** Where the update flow has got to. The sheet draws one of these and nothing else. */
sealed interface UpdateState {
    /** A newer build exists and the user has not answered yet. */
    data class Offered(val release: Release) : UpdateState

    /** Fetching. [progress] is 0..1, or negative while the server declines to say how big. */
    data class Downloading(val release: Release, val progress: Float) : UpdateState

    /**
     * Downloaded, but Android will not install it until the user permits this app to.
     *
     * Carries the [apk] because the answer to that question is given in Settings, in another
     * app, and the only thing left to do on the way back is install a file that is already on
     * disk. Without it, granting the permission led back to a sheet that could only offer to
     * open Settings again.
     */
    data class NeedsPermission(val release: Release, val apk: File) : UpdateState

    /** Nothing to say. */
    data object Idle : UpdateState
}

/**
 * The update notice: one glass pane over a dimmed thread.
 *
 * A modal rather than a banner, which is a deliberate choice about who this app is for. A
 * quiet dot in a corner is a convention learned from years of using software; the person this
 * was built for has not learned it and would never press it. An update they never install is
 * the same as an update that was never published — so once, on launch, it stands in front of
 * the conversation and asks, and one tap on 以后再说 makes it go away for the session.
 *
 * The pane is the site's `.shell` over the same dimmed ground the rest of the app uses, so it
 * reads as another surface in the app rather than a system dialog that has appeared on top
 * of it.
 */
@Composable
fun UpdateSheet(
    state: UpdateState,
    onInstall: () -> Unit,
    onGrantPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val release = when (state) {
        is UpdateState.Offered -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.NeedsPermission -> state.release
        UpdateState.Idle -> return
    }
    val downloading = state is UpdateState.Downloading

    Dialog(
        // Dismissed by the button, not by a stray tap outside it, and not by back. The whole
        // point is that it gets read; a modal this app raises once should not be closable by
        // the reflex that closes a keyboard.
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Areel.Ink.copy(alpha = 0.55f))
                .padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // The pane sits on the concrete ground with its grid, exactly as every
                    // other surface does — glass over nothing would show the scrim through it.
                    .background(Areel.Concrete, RectangleShape)
                    .cadGrid()
                    .glassSurface()
                    .padding(22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FishMark(Modifier.size(26.dp), body = Areel.Ink)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.update_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Areel.Ink,
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    stringResource(R.string.update_version, release.versionName),
                    style = MaterialTheme.typography.labelSmall,
                    color = Areel.Magenta,
                )

                if (release.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Areel.Ink60,
                    )
                }

                Spacer(Modifier.height(20.dp))

                when (state) {
                    is UpdateState.Downloading -> ProgressRule(state.progress)

                    is UpdateState.NeedsPermission -> {
                        // Not an error. Android has asked a question and the only place it can
                        // be answered is Settings, so the copy says what to do there rather
                        // than reporting that something failed.
                        Text(
                            stringResource(R.string.update_permission),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Areel.Ink,
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            UpdatePlate(
                                stringResource(R.string.update_open_settings),
                                filled = true,
                                modifier = Modifier.weight(1f),
                                onClick = onGrantPermission,
                            )
                            UpdatePlate(
                                stringResource(R.string.update_later),
                                filled = false,
                                modifier = Modifier.weight(1f),
                                onClick = onDismiss,
                            )
                        }
                    }

                    else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        UpdatePlate(
                            stringResource(R.string.update_install),
                            filled = true,
                            modifier = Modifier.weight(1f),
                            onClick = onInstall,
                        )
                        UpdatePlate(
                            stringResource(R.string.update_later),
                            filled = false,
                            modifier = Modifier.weight(1f),
                            onClick = onDismiss,
                        )
                    }
                }

                if (downloading) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.update_downloading),
                        style = MaterialTheme.typography.labelSmall,
                        color = Areel.Ink40,
                    )
                }
            }
        }
    }
}

/**
 * The download, as a rule that fills.
 *
 * A bar rather than a spinner because the question being answered is "how much longer", and a
 * spinner cannot answer it. A negative fraction means the server never said how large the file
 * is, and the bar shows a fixed sliver rather than animating a number it does not have — a
 * progress bar that invents its own position is worse than one that admits it cannot tell.
 */
@Composable
private fun ProgressRule(progress: Float) {
    val known = progress >= 0f
    val filled by animateFloatAsState(
        targetValue = if (known) progress.coerceIn(0f, 1f) else 0.12f,
        label = "update-progress",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Areel.Ink20)
            .drawBehind {
                drawRect(Areel.Magenta, Offset.Zero, Size(size.width * filled, size.height))
            },
    )
    if (known) {
        Spacer(Modifier.height(8.dp))
        Text(
            String.format(Locale.US, "%.0f%%", filled * 100f),
            style = MaterialTheme.typography.labelSmall,
            color = Areel.Ink40,
        )
    }
}

/** [SettingsScreen]'s plate, which is the app's one button. Kept private to each file it is in. */
@Composable
private fun UpdatePlate(
    label: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(if (filled) Areel.Magenta else Areel.Concrete2, RectangleShape)
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
            color = if (filled) Areel.Paper else Areel.Ink,
        )
    }
}
