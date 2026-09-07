package org.areel.fishball.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.areel.fishball.data.Attachment
import org.areel.fishball.data.Attachments

/**
 * The pictures waiting to go with the next thing said.
 *
 * They ride the *next message*, whichever way that message is made — typed or spoken — and the
 * composer lets go of them as part of sending, so a photo cannot silently follow the
 * conversation into a second question it had nothing to do with.
 *
 * Capped at [LIMIT]. Not an arbitrary number: every one of these is re-encoded and uploaded on
 * the writing turn, and five photographs of a medicine box is already more than any answer
 * needs. The cap is enforced here rather than in the picker so it holds however they arrive.
 */
class AttachState internal constructor(
    private val attachments: Attachments,
    private val scope: CoroutineScope,
    private val openGallery: (Int) -> Unit,
    private val openCamera: () -> Unit,
) {
    /** Whether the row of choices is out. */
    var open by mutableStateOf(false)
        private set

    /** Ready to send, in the order they were added. */
    val pending = mutableStateListOf<Attachment>()

    /** How many are still being decoded. The row shows a placeholder for each. */
    var loading by mutableStateOf(0)
        private set

    /** Full-screen, when one is tapped. Null the rest of the time. */
    var viewing: Attachment? by mutableStateOf(null)

    /**
     * The earlier answer the next question is about, or null.
     *
     * Here rather than in the composer's text because that is what it is: a thing riding the
     * next message, let go of when the message goes, exactly like a picture. It used to be
     * pasted into the field instead, and that was wrong in a way worth remembering - the whole
     * answer landed in the box somebody was about to type in, so the first thing they had to do
     * with a quotation was scroll past it.
     *
     * One, not a list. Two quoted answers and one question is not a thing anybody means.
     */
    var quoted: Quoted? by mutableStateOf(null)
        private set

    fun quote(text: String) {
        quoted = Quoted.of(text)
    }

    fun dropQuote() {
        quoted = null
    }

    val full: Boolean get() = pending.size + loading >= LIMIT
    val room: Int get() = (LIMIT - pending.size - loading).coerceAtLeast(0)

    fun toggle() {
        open = !open
    }

    fun close() {
        open = false
    }

    fun pickImage() {
        open = false
        if (room > 0) openGallery(room)
    }

    fun takePhoto() {
        open = false
        if (room > 0) openCamera()
    }

    fun remove(item: Attachment) {
        pending.remove(item)
        if (viewing == item) viewing = null
    }

    fun clear() {
        pending.clear()
        viewing = null
        quoted = null
    }

    /** Called by the launchers. Silent on a failed decode: the picture simply does not attach. */
    internal fun accept(uris: List<Uri>) {
        val taking = uris.take(room)
        if (taking.isEmpty()) return
        loading += taking.size
        taking.forEach { uri ->
            scope.launch {
                val read = attachments.read(uri)
                loading = (loading - 1).coerceAtLeast(0)
                if (read != null && pending.size < LIMIT) pending += read
            }
        }
    }

    companion object {
        /** Five. Enough for a box, its label and its leaflet; past that nothing is being read. */
        const val LIMIT = 5
    }
}

/**
 * An earlier answer the next question is about.
 *
 * A mention rather than the thing itself. The model already has that answer in front of it -
 * every turn of the session is replayed - so carrying the whole of it back would be paying to
 * say something twice. What has to travel is only enough to say *which* answer, and the opening
 * words of one are enough for that.
 *
 * Trimmed on the way in, at [MENTION_CHARS], so the same string is what the chip shows and what
 * the model is sent. A chip showing one thing while something longer goes out is the kind of
 * difference nobody finds until it matters.
 */
data class Quoted(val words: String) {

    companion object {
        /**
         * Enough of an answer to know which one it was.
         *
         * The chip shows one line of this and lets the rest fall off the end, so this is set by
         * what identifies an answer rather than by what fits: two clauses of Chinese prose,
         * which is past the point where two answers in a conversation still read the same.
         */
        const val MENTION_CHARS = 40

        /** The words, cut to length, with the cut made visible. */
        fun of(text: String): Quoted {
            // Flattened first: an answer's line breaks and bullet marks are layout, and a chip
            // is one line. Without this the mention carries a ▪ into the middle of a sentence.
            val flat = text.replace(Regex("""[\s▪]+"""), " ").trim()
            return Quoted(
                if (flat.length <= MENTION_CHARS) flat else flat.take(MENTION_CHARS) + "…",
            )
        }
    }
}

/**
 * Wires the two pickers to a composable that can register for their results.
 *
 * The gallery goes through the photo picker rather than a storage permission: it hands back the
 * chosen images and no rights to anything else, so the app never asks to read the user's photos.
 */
@Composable
fun rememberAttachState(attachments: Attachments): AttachState {
    val scope = rememberCoroutineScope()

    // Boxes rather than captured locals, for the same reason as the microphone's launcher: the
    // state is remembered once and the launchers are created after it.
    val gallery = remember { arrayOfNulls<(Int) -> Unit>(1) }
    val camera = remember { arrayOfNulls<() -> Unit>(1) }

    val state = remember {
        AttachState(
            attachments = attachments,
            scope = scope,
            openGallery = { room -> gallery[0]?.invoke(room) },
            openCamera = { camera[0]?.invoke() },
        )
    }

    // The multiple-item picker takes its maximum at construction, so it is built for the cap
    // and the remaining room is applied to what comes back.
    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachState.LIMIT),
    ) { state.accept(it) }

    // Held across the round trip: TakePicture reports success, not a location, so the target
    // has to be remembered from before the camera opened.
    var shot by remember { mutableStateOf<Uri?>(null) }
    val take = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok -> state.accept(listOfNotNull(shot.takeIf { ok })) }

    gallery[0] = {
        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    camera[0] = {
        val (_, uri) = attachments.cameraTarget()
        shot = uri
        runCatching { take.launch(uri) }
    }

    return state
}
