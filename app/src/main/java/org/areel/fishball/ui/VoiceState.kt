package org.areel.fishball.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.delay
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
import org.areel.fishball.data.Recording

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
    private val shortNotice: String,
    private val deniedNotice: String,
) {
    var phase by mutableStateOf(VoicePhase.IDLE)
        private set

    /** One line where the field's text would be. Cleared the moment they press again. */
    var notice: String? by mutableStateOf(null)
        private set

    /**
     * What the service said, under [notice], when the failure had a code.
     *
     * Its own value rather than something glued onto the sentence. The field is one line of
     * text tall and a code is an unbreakable word: appended, it wrapped onto a second line and
     * was clipped away, so the notice read exactly as it does when the microphone simply heard
     * nothing - which is the one case it needs to be told apart from.
     *
     * Null after a recording the service heard silence in. That is not a fault, and a code
     * beside it would be inventing one.
     */
    var noticeCode: String? by mutableStateOf(null)
        private set

    /**
     * How loud the room is, 0..1, while recording. Read by the composer's meter.
     *
     * Smoothed on the way in. Raw peaks jump between frames hard enough that the meter
     * flickers rather than moves; easing towards the reading keeps the fall gentle while
     * letting a sudden word arrive immediately.
     */
    var level by mutableFloatStateOf(0f)
        private set

    /**
     * Whether letting go now would throw the recording away instead of sending it.
     *
     * Armed by dragging up off the button. It is the gesture every voice message in this
     * country is cancelled with, and it is the only one available while the button is held -
     * the finger is on the one control the screen has. The beam drawn over the plate is this
     * value, and nothing else reads it.
     */
    var cancelling by mutableStateOf(false)
        private set

    /** Set by the permission callback so a granted request can start recording immediately. */
    internal var awaitingPermission = false

    /**
     * Drop the line under the field.
     *
     * Sending anything at all answers it. The notice is what the microphone said last time and
     * it used to outlive the conversation: type a question, send it, and the field went empty
     * again underneath a line still complaining that it had not heard you.
     */
    fun dismissNotice() {
        notice = null
        noticeCode = null
    }

    /**
     * The finger has moved, and this is whether it is now far enough up to cancel.
     *
     * Only the crossings matter, in either direction: the tick is what tells somebody the
     * threshold is real, and it has to come at the edge rather than the whole way across.
     */
    fun aim(up: Boolean) {
        if (phase != VoicePhase.RECORDING || up == cancelling) return
        cancelling = up
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /**
     * Let go above the button: the recording goes in the bin without a word about it.
     *
     * Silent on purpose, like a hold too short to be speech. Somebody who cancelled a message
     * knows they cancelled it, and a line telling them so would be the app narrating their own
     * decision back at them.
     */
    fun onCancel() {
        cancelling = false
        if (phase != VoicePhase.RECORDING) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        backend.voice.cancel()
        phase = VoicePhase.IDLE
    }

    fun onHold() {
        // One voice at a time. A second hold while the first is still being turned into words
        // would take the microphone out from under it and land a transcript in the middle of a
        // recording. The plate already says as much - it is a fish turning over rather than a
        // microphone - and this is the same rule written where it is enforced.
        if (phase != VoicePhase.IDLE) return
        notice = null
        noticeCode = null
        cancelling = false
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
        if (!backend.voice.start()) return
        phase = VoicePhase.RECORDING
        level = 0f
        scope.launch {
            while (phase == VoicePhase.RECORDING) {
                val now = backend.voice.level()
                // Up fast, down slow. A word should reach the meter on the frame it is spoken;
                // the gap after it should close over a few frames rather than snap shut.
                level = if (now > level) now else level + (now - level) * LEVEL_FALL
                delay(LEVEL_POLL_MS)
            }
            level = 0f
        }
    }

    fun onRelease() {
        cancelling = false
        if (phase != VoicePhase.RECORDING) return
        // Felt on the way up too. Holding to speak is the one gesture in the app with no visible
        // moment of completion - the finger is over the button - so the end of it is told by
        // touch, the same way the beginning was.
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val heard = backend.voice.stop()
        if (heard !is Recording.Ready) {
            // It used to say nothing here, on the grounds that a mis-tap does not deserve a
            // message. But somebody who pressed a button and got silence back cannot tell
            // whether they let go too early or whether the app is broken, and those want
            // different things done about them - so each says which it was.
            notice = if (heard is Recording.TooShort) shortNotice else emptyNotice
            noticeCode = null
            phase = VoicePhase.IDLE
            return
        }
        phase = VoicePhase.TRANSCRIBING
        scope.launch {
            val text = backend.transcribe(heard.file)
            heard.file.delete()
            phase = VoicePhase.IDLE
            if (text.isNullOrBlank()) {
                // The plain sentence, and under it the code when the service gave one. A
                // reachable service that heard silence carries nothing extra; 503 is worth
                // being able to read out to somebody, and it is the only part of this that
                // tells the difference between "say it again" and "come back later".
                notice = emptyNotice
                noticeCode = backend.voice.lastFailure
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
        noticeCode = null
    }

    private companion object {
        /** Fast enough to look continuous, slow enough not to be a busy loop. */
        const val LEVEL_POLL_MS = 40L

        /** How much of the gap the meter closes per reading when the room goes quiet. */
        const val LEVEL_FALL = 0.35f
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
    val short = stringResource(R.string.voice_too_short)
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
            shortNotice = short,
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
