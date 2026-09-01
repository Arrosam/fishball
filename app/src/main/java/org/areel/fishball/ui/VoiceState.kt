package org.areel.fishball.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.areel.fishball.R
import org.areel.fishball.data.Backend

/** What the microphone is doing, which is the only thing the composer needs to draw. */
enum class VoicePhase { IDLE, RECORDING, TRANSCRIBING }

/**
 * Holding the button down, from the press to the words appearing.
 *
 * The whole flow is four steps and each one can end it: permission, record, transcribe, send.
 * Nothing here ever produces an error screen — a refused permission, an unreachable proxy and a
 * recording of silence all land on one plain line in the composer, because they all mean the
 * same thing to somebody holding a phone: it did not hear you, say it again.
 */
class VoiceState internal constructor(
    private val backend: Backend,
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val onGranted: () -> Unit,
    private val onText: (String) -> Unit,
    private val emptyNotice: String,
    private val deniedNotice: String,
) {
    var phase by mutableStateOf(VoicePhase.IDLE)
        private set

    /** One line where the field's text would be. Cleared the moment they press again. */
    var notice: String? by mutableStateOf(null)
        private set

    /** Set by the permission callback so a granted request can start recording immediately. */
    internal var awaitingPermission = false

    fun onHold() {
        notice = null
        if (!granted()) {
            // Ask, and remember that a press is what asked — a granted permission then starts
            // recording on the spot rather than making them press a second time for the same
            // thing they already pressed for.
            awaitingPermission = true
            onGranted()
            return
        }
        begin()
    }

    internal fun begin() {
        // The press is felt before anything is heard. This is the only confirmation that the
        // hold registered, and without it a button that looks the same pressed or not gives
        // nothing back until the first word is already lost.
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (backend.voice.start()) phase = VoicePhase.RECORDING
    }

    fun onRelease() {
        if (phase != VoicePhase.RECORDING) return
        // Felt on the way up too. Holding to speak is the one gesture in the app with no visible
        // moment of completion - the finger is over the button - so the end of it is told by
        // touch, the same way the beginning was.
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        val file = backend.voice.stop()
        if (file == null) {
            // Too short to be speech. Silent on purpose: this is the mis-tap of somebody
            // reaching for the send plate out of habit, and it does not deserve a message.
            phase = VoicePhase.IDLE
            return
        }
        phase = VoicePhase.TRANSCRIBING
        scope.launch {
            val text = backend.transcribe(file)
            file.delete()
            phase = VoicePhase.IDLE
            if (text.isNullOrBlank()) {
                notice = emptyNotice
            } else {
                // Straight into the same send path a typed question takes. There is no draft
                // step: the person spoke a whole question and asking them to press again to
                // confirm it would be a form where a conversation was promised.
                onText(text)
            }
        }
    }

    internal fun refused() {
        awaitingPermission = false
        phase = VoicePhase.IDLE
        notice = deniedNotice
    }

    private fun granted() = ContextCompat.checkSelfPermission(
        backend.appContext,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
}

/**
 * Wires the microphone to a composable that can ask for permissions.
 *
 * The launcher has to be created here rather than inside [VoiceState] because registering for a
 * result is a composition-time thing; the state object only holds what happens after.
 */
@Composable
fun rememberVoiceState(backend: Backend, onText: (String) -> Unit): VoiceState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val empty = stringResource(R.string.voice_empty)
    val denied = stringResource(R.string.voice_no_permission)

    // A box rather than a captured local. The state is remembered once and the launcher is
    // created after it, so the state cannot hold the launcher directly; putting the call in a
    // holder both of them can see keeps that ordering from being something to reason about.
    val ask = remember { arrayOfNulls<() -> Unit>(1) }
    val state = remember {
        VoiceState(
            backend = backend,
            scope = scope,
            haptics = haptics,
            onGranted = { ask[0]?.invoke() },
            onText = onText,
            emptyNotice = empty,
            deniedNotice = denied,
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        if (!state.awaitingPermission) return@rememberLauncherForActivityResult
        state.awaitingPermission = false
        // The finger has long since left the button by the time a system dialog is answered,
        // so this cannot resume the hold. It reports the outcome and waits to be pressed again.
        if (!allowed) state.refused()
    }
    ask[0] = { launcher.launch(Manifest.permission.RECORD_AUDIO) }

    return state
}
