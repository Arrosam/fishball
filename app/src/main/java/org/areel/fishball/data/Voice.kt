package org.areel.fishball.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import org.areel.fishball.core.config.Provider
import org.areel.fishball.core.catching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * What a held button produced.
 *
 * Three outcomes and not two, because "no file" used to mean both "you let go too fast" and
 * "the microphone gave nothing", and the composer could only say one thing about them - so it
 * said nothing at all, and a button that had plainly been pressed appeared to do nothing.
 */
sealed class Recording {
    /** Long enough, with audio in it. */
    data class Ready(val file: File) : Recording()

    /** Let go before [Voice.MIN_MS]. A mis-tap, or a word cut off at both ends. */
    object TooShort : Recording()

    /** Held long enough, and nothing came out of the microphone. */
    object Silent : Recording()
}

/** What the ASR model gives back. `text` is the whole of what this feature needs. */
@Serializable
private data class Transcript(val text: String = "", val duration: Double = 0.0)

/**
 * Holding the button down, and what comes back when it is let go.
 *
 * Two halves that only make sense together: a recorder that writes one file at a time, and one
 * upload to the proxy's ASR model. There is no playback, no draft and no send step - what the
 * mic produces is text in the composer's own send path, so a spoken question and a typed one are
 * the same turn from there on.
 *
 * **The samples are read here rather than encoded by the system.** `MediaRecorder` wrote a
 * smaller file and asked less of this class, and it was replaced because of one number:
 * `getMaxAmplitude()` answered 0 on the phone this was built for, every call, for the length of
 * a held button - while the file it wrote over the same seconds held a clearly audible sentence.
 * A meter cannot be repaired from outside a recorder that will not report; the way to know how
 * loud the room is, is to be holding the samples. So [AudioRecord] delivers 16-bit PCM, [drain]
 * takes the peak of every block on its way past, and the file is a WAV whose 44-byte header is
 * patched in at the end. The meter is then the audio itself, and cannot disagree with what was
 * recorded.
 *
 * It costs about 32 KB a second, against roughly 8 for AAC. For a few seconds of speech thrown
 * away straight after upload that is a fair price for a meter that works, and the service takes
 * WAV as readily as MP4.
 */
class Voice(private val context: Context) {

    private var record: AudioRecord? = null
    private var reader: Thread? = null
    private var out: RandomAccessFile? = null
    private var target: File? = null
    private var startedAt = 0L

    /** Read by [drain] on its own thread; the only thing that stops it. */
    @Volatile
    private var running = false

    /** The loudest sample since [level] last looked. Written by [drain], read by the meter. */
    private val peak = AtomicInteger(0)

    /**
     * How many bytes of audio are in the file. Only settled once [drain] has been joined.
     *
     * Volatile because the join is the only thing that publishes it and the join can time out.
     * A successful join carries its own happens-before edge and this would be redundant; a
     * timed-out one carries none, and [stop] reading a torn or stale count is how a header
     * comes to describe a different file than the one on disk.
     */
    @Volatile
    private var written = 0

    /**
     * Why the last transcription came back with nothing, if it was for a reason.
     *
     * Short on purpose: it is shown on the composer's one line, under the plain sentence, the
     * same way a failed turn carries its detail. Null after a recording the service simply
     * heard nothing in - that is silence, not a fault, and a code beside it would be a lie.
     * The whole of what the service said goes to the log.
     */
    var lastFailure: String? = null
        private set

    /**
     * Begin. Returns false if the microphone could not be opened at all - in use by a call, or
     * the permission revoked between the check and here - and leaves nothing behind if so.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        cancel()
        val file = File(context.cacheDir, "voice.wav")
        file.delete()

        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minimum <= 0) return false
        // Twice what the device asks for, and never smaller than one read: the thread has to
        // survive being descheduled for a frame or two without the buffer overrunning behind it.
        val buffer = maxOf(minimum * 2, CHUNK * 2)

        val rec = runCatching { AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, buffer) }
            .getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }
            // The next attempt asks for the microphone a different way. See [source].
            if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                source = MediaRecorder.AudioSource.MIC
            }
            return false
        }

        return runCatching {
            val f = RandomAccessFile(file, "rw")
            f.write(ByteArray(HEADER))          // room for the header; filled in by [stop]
            rec.startRecording()
            check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            record = rec
            out = f
            target = file
            written = 0
            peak.set(0)
            startedAt = System.currentTimeMillis()
            lastFailure = null
            running = true
            reader = thread(name = "fishball-mic") { drain(rec, f) }
            true
        }.getOrElse {
            runCatching { rec.release() }
            runCatching { file.delete() }
            false
        }
    }

    /**
     * Which microphone to ask for.
     *
     * VOICE_RECOGNITION first, and not only as a preference: it is the documented source for
     * speech on its way to a recogniser, and it comes without the automatic gain and the noise
     * shaping that MIC applies for recording a room - both of which a transcriber would rather
     * not have had done to its input.
     *
     * Falls back to MIC if the device will not open it, because a source that cannot be opened
     * at all is worse than one that has been tidied up on the way.
     */
    private var source = MediaRecorder.AudioSource.VOICE_RECOGNITION

    /**
     * The read loop: everything the microphone produces, straight to disk, taking the peak of
     * each block on the way past.
     *
     * Its own thread rather than a coroutine. `read` blocks until the buffer is full, which is
     * the behaviour wanted here - the loop is paced by the microphone itself - but it is exactly
     * what should not be done to a dispatcher's pool.
     */
    private fun drain(rec: AudioRecord, f: RandomAccessFile) {
        val block = ByteArray(CHUNK * BYTES_PER_FRAME)
        while (running) {
            val n = rec.read(block, 0, block.size)
            if (n <= 0) continue                 // a transient error reads as nothing to write

            var loudest = 0
            var i = 0
            while (i + 1 < n) {
                // Little-endian signed 16-bit. Through Short so the sign comes back, and abs by
                // hand because this runs on every sample of every block.
                val sample = ((block[i + 1].toInt() shl 8) or (block[i].toInt() and 0xFF))
                    .toShort().toInt()
                val size = if (sample < 0) -sample else sample
                if (size > loudest) loudest = size
                i += BYTES_PER_FRAME
            }
            val seen = loudest
            peak.updateAndGet { if (seen > it) seen else it }

            runCatching {
                f.write(block, 0, n)
                written += n
            }
        }
    }

    /**
     * Stop and hand back the recording, or null if there is nothing worth sending.
     *
     * A press shorter than [MIN_MS] is a mis-tap, not a message - the button is where the send
     * plate used to be, and somebody reaching for it out of habit should get silence rather than
     * an empty turn. Nobody says anything in under a second, so everything below it is either
     * that or a word cut off at both ends, and a half-word sent to be transcribed comes back as
     * either nothing or a guess, both of which cost a turn to undo.
     */
    fun stop(): Recording {
        val rec = record ?: return Recording.Silent
        val f = out
        val file = target
        val held = System.currentTimeMillis() - startedAt
        record = null
        out = null
        target = null

        // Joined before the file is touched, so [written] has settled and nothing is still
        // writing behind the header. That join is what makes the rest of this single-threaded -
        // and it only makes it single-threaded if it actually finished.
        //
        // The recorder is stopped *first* for that reason. [drain] blocks inside `read`, so
        // clearing the flag on its own leaves it there until the current block fills; stopping
        // the recorder ends that read now, and the join has something short to wait for.
        running = false
        runCatching { rec.stop() }
        val done = runCatching { reader?.join(JOIN_MS); reader?.isAlive != true }.getOrDefault(false)
        reader = null
        runCatching { rec.release() }

        val audio = written
        // Only if the writer is known to have stopped. Patching the header seeks to offset 0
        // and closing takes the handle away, and doing either under a thread still appending
        // gives a WAV whose declared length disagrees with its contents - or an exception on a
        // closed file. A recording that cannot be sealed is one to throw away, not to send.
        runCatching { if (done) f?.let { header(it, audio) } }
        runCatching { f?.close() }

        if (file == null || audio == 0 || !done) {
            file?.delete()
            return Recording.Silent
        }
        if (held < MIN_MS) {
            file.delete()
            return Recording.TooShort
        }
        return Recording.Ready(file)
    }

    /**
     * The 44 bytes in front of the samples, written once their length is known.
     *
     * Canonical PCM WAV: RIFF, one `fmt ` chunk of sixteen bytes, then `data`. Little-endian
     * throughout, which is the format's own byte order rather than the platform's.
     */
    private fun header(f: RandomAccessFile, audio: Int) {
        val h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt(36 + audio)                          // everything after this field
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)                                  // this chunk's size; PCM adds no fields
        h.putShort(1)                                 // PCM, uncompressed
        h.putShort(1)                                 // one channel
        h.putInt(SAMPLE_RATE)
        h.putInt(SAMPLE_RATE * BYTES_PER_FRAME)       // bytes per second
        h.putShort(BYTES_PER_FRAME.toShort())
        h.putShort(16)                                // bits per sample
        h.put("data".toByteArray())
        h.putInt(audio)
        f.seek(0)
        f.write(h.array())
    }

    /**
     * How loud it is right now, 0..1.
     *
     * The peak since the meter last asked, which is a reading of the interval between calls
     * rather than of an instant - what a meter wants, and why it is taken and cleared in one
     * move. Square-rooted because loudness is not linear in amplitude: without it a normal
     * speaking voice sits near the bottom of the range and the meter looks broken.
     */
    fun level(): Float {
        if (record == null) return 0f
        val loudest = peak.getAndSet(0)
        return kotlin.math.sqrt((loudest / MAX_AMPLITUDE).coerceIn(0f, 1f))
    }

    /** Throw the recording away. Safe to call when nothing is running. */
    fun cancel() {
        running = false
        runCatching { reader?.join(JOIN_MS) }
        reader = null
        record?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        record = null
        runCatching { out?.close() }
        out = null
        target?.delete()
        target = null
    }

    /**
     * The recording, as words.
     *
     * Null on anything that is not a usable transcript - unreachable, refused, or heard as
     * nothing at all. The caller shows one line about it; there is nothing here the user could
     * act on beyond saying it again.
     *
     * **The body is written out by hand**, rather than by Ktor's form builder, because the
     * builder cannot spell this request. It writes a part name bare -
     * `Content-Disposition: form-data; name=model` - where RFC 7578 asks for a quoted string,
     * so the service goes looking for a field called `model`, finds none, and refuses the whole
     * upload. Supplying the header does not help: Ktor merges its own parameter with the given
     * one and sends `name=model; name="model"`, which is worse. Both were read off the wire
     * rather than reasoned about, and until they were, every held button ended in the same
     * shrug - the microphone was fine and the transcriber was fine, and the only broken thing
     * between them was a pair of missing quotation marks.
     *
     * So: two parts, quoted names, CRLF throughout, closing boundary with its trailing `--`.
     * `MultipartShapeTest` sends exactly these bytes to the live service and reads a transcript
     * back, which is the only way to know they are right.
     */
    suspend fun transcribe(
        file: File,
        apiKey: String,
        /**
         * The provider's own base URL and ASR model, rather than the areel ones compiled in.
         *
         * Passed rather than read from a constant because a custom profile answers somewhere
         * else, and a recording uploaded to llm.areel.org with a third party's token is both a
         * transcription that fails and a token sent to a service that was never asked for.
         */
        baseUrl: String,
        model: String,
    ): String? = withContext(Dispatchers.IO) {
        lastFailure = null
        // `catching`, not `runCatching`: somebody who tapped the fish to stop this told the app
        // to stop, and swallowing that would land 「没听清，再说一遍吧」 under their own decision.
        catching {
            val audio = file.readBytes()
            // Only has to not appear in the payload; nanoTime is plenty and costs nothing.
            val boundary = "fishball" + System.nanoTime().toString(16)
            val head = (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"model\"\r\n\r\n" +
                    "$ASR_MODEL\r\n" +
                    "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n"
                ).toByteArray()
            val tail = "\r\n--$boundary--\r\n".toByteArray()

            val response = http.post("${baseUrl.trimEnd('/')}/v1/audio/transcriptions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.parse("multipart/form-data; boundary=$boundary"))
                setBody(head + audio + tail)
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                android.util.Log.w("FishBall", "transcription refused: " + response.status + " " + body)
                lastFailure = response.status.value.toString()
                return@withContext null
            }
            json.decodeFromString<Transcript>(body).text.trim()
        }.getOrElse {
            // Never reached the service, or reached it and could not read what came back. The
            // class name is the code here: there is no status to quote.
            lastFailure = it::class.simpleName
            android.util.Log.w("FishBall", "transcription failed", it)
            null
        }?.takeIf { it.isNotBlank() }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val http by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                // A few seconds of speech, and the model reads it in about its own duration.
                requestTimeoutMillis = 120_000
            }
        }
    }

    private companion object {
        /** The areel deployment's name for it. A profile carries its own — see `Provider.asrModel`. */
        const val ASR_MODEL = Provider.AREEL_ASR

        // 16 kHz mono is what speech recognition wants; anything above it is bytes spent on
        // frequencies the model discards.
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_FRAME = 2

        /** Samples per read. 1024 at 16 kHz is 64 ms - finer than the meter asks for. */
        const val CHUNK = 1024

        /** A canonical PCM WAV header, to the byte. */
        const val HEADER = 44

        /** Long enough for one blocked read to return; past that the thread is not coming back. */
        const val JOIN_MS = 500L

        /** Below this nothing was said. See [stop]. */
        const val MIN_MS = 1000L

        /** 16-bit signed, so this is as loud as a sample can be. */
        const val MAX_AMPLITUDE = 32767f
    }
}
