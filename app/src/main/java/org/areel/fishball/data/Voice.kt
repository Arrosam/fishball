package org.areel.fishball.data

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What the ASR model gives back. `text` is the whole of what this feature needs. */
@Serializable
private data class Transcript(val text: String = "", val duration: Double = 0.0)

/**
 * Holding the button down, and what comes back when it is let go.
 *
 * Two halves that only make sense together: a recorder that writes one file at a time, and one
 * upload to the proxy's ASR model. There is no playback, no waveform and no draft — what the
 * mic produces is text in the composer's own send path, so a spoken question and a typed one
 * are the same turn from there on.
 *
 * AAC in an MP4 container, which is what `MediaRecorder` has been able to write since long
 * before this app's floor of API 26. Opus would be smaller, but it needs API 29 and the file is
 * a few seconds of speech either way.
 */
class Voice(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L

    /** True between [start] and whichever of [stop]/[cancel] ends it. */
    val recording: Boolean get() = recorder != null

    /**
     * Begin. Returns false if the microphone could not be opened at all — in use by a call, or
     * the permission revoked between the check and here — and leaves nothing behind if so.
     */
    fun start(): Boolean {
        cancel()
        val file = File(context.cacheDir, "voice.m4a")
        file.delete()

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        return runCatching {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // 16 kHz mono is what speech recognition wants; anything above it is bytes spent
            // on frequencies the model discards.
            rec.setAudioSamplingRate(SAMPLE_RATE)
            rec.setAudioChannels(1)
            rec.setAudioEncodingBitRate(BIT_RATE)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            target = file
            startedAt = System.currentTimeMillis()
            true
        }.getOrElse {
            runCatching { rec.release() }
            false
        }
    }

    /**
     * Stop and hand back the recording, or null if there is nothing worth sending.
     *
     * A press shorter than [MIN_MS] is a mis-tap, not a message — the button is where the send
     * plate used to be, and somebody reaching for it out of habit should get silence rather than
     * an empty turn. `MediaRecorder.stop()` also throws outright on a file with no frames in it,
     * which is the same case seen from the other side.
     *
     * A whole second, not the fraction it was. Nobody says anything in under a second, so
     * everything below it is either a mis-tap or a word that was cut off at both ends - and a
     * half-word sent to be transcribed comes back as either nothing or a guess, both of which
     * cost a turn to undo.
     */
    fun stop(): File? {
        val rec = recorder ?: return null
        val file = target
        val held = System.currentTimeMillis() - startedAt
        recorder = null
        target = null

        val ok = runCatching { rec.stop() }.isSuccess
        // reset before release. Releasing straight after stop leaves queued events behind and
        // logcat says so - "mediarecorder went away with unhandled events" - which is a
        // resource being dropped mid-sentence rather than closed.
        runCatching { rec.reset() }
        runCatching { rec.release() }
        if (!ok || file == null || held < MIN_MS || !file.exists() || file.length() == 0L) {
            file?.delete()
            return null
        }
        return file
    }

    /**
     * How loud it is right now, 0..1.
     *
     * `getMaxAmplitude` reports the peak since the last time it was asked, so it is a reading
     * of the interval between calls rather than of an instant - which is what a meter wants.
     * Square-rooted because loudness is not linear in amplitude: without it a normal speaking
     * voice sits near the bottom of the range and the meter looks broken.
     *
     * Zero when nothing is recording, and zero on the very first call, which MediaRecorder
     * always answers with 0 whatever the room is doing.
     */
    fun level(): Float {
        val rec = recorder ?: return 0f
        val peak = runCatching { rec.maxAmplitude }.getOrDefault(0)
        // TEMPORARY, for one diagnosis: the meter draws correctly when fed a synthetic level
        // - verified on the emulator, bars sweeping 20px to 96px in a 96px field - and the
        // emulator's own microphone answers this call with 0. What is not yet known is what a
        // real one answers, and this is the only way to find out.
        android.util.Log.d("FishBallLevel", "peak=" + peak)
        return kotlin.math.sqrt((peak / MAX_AMPLITUDE).coerceIn(0f, 1f))
    }

    /** Throw the recording away. Safe to call when nothing is running. */
    fun cancel() {
        recorder?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.reset() }
            runCatching { rec.release() }
        }
        recorder = null
        target?.delete()
        target = null
    }

    /**
     * The recording, as words.
     *
     * Null on anything that is not a usable transcript — unreachable, refused, or heard as
     * nothing at all. The caller shows one line about it; there is nothing here the user could
     * act on beyond saying it again.
     */
    suspend fun transcribe(file: File, apiKey: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = http.submitFormWithBinaryData(
                url = "${Backend.LLM_URL}/v1/audio/transcriptions",
                formData = formData {
                    append("model", ASR_MODEL)
                    append(
                        "file",
                        file.readBytes(),
                        Headers.build {
                            append(HttpHeaders.ContentType, "audio/mp4")
                            append(HttpHeaders.ContentDisposition, "filename=\"voice.m4a\"")
                        },
                    )
                },
            ) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }.bodyAsText()
            json.decodeFromString<Transcript>(body).text.trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
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
        const val ASR_MODEL = "ASR"
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 64_000

        /** Below this nothing was said. See [stop]. */
        const val MIN_MS = 1000L

        /** 16-bit signed, so this is as loud as a sample can be. */
        const val MAX_AMPLITUDE = 32767f
    }
}
