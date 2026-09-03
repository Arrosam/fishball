package org.areel.fishball.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import androidx.core.content.ContextCompat
import org.areel.fishball.MainActivity
import org.areel.fishball.R
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Telling somebody their answer has arrived when they have stopped watching for it.
 *
 * A turn can now run for a hundred rounds, which is the whole point of letting it - but it also
 * means the person who asked has had time to put the phone down. Both halves of this exist
 * because of that: a sound for somebody still holding it, and a notification for somebody who
 * is not.
 */
class Alert(private val context: Context) {

    /**
     * The bubble, synthesised rather than shipped.
     *
     * This app draws its own fish, its own bin and its own bubbles; a WAV in `res/raw` would be
     * the one part of its character that arrived as an asset. It is also thirty lines against a
     * binary nobody can review in a diff.
     *
     * A bubble is a pitch that rises as the cavity collapses, and dies almost at once. So: a
     * sine sweeping up, under an exponential decay. The rise is what makes it read as a bubble
     * rather than a beep - swept downward the same code sounds like an error.
     */
    fun bubble() {
        runCatching {
            val frames = SAMPLE_RATE * DURATION_MS / 1000
            val pcm = ShortArray(frames)
            var phase = 0.0
            for (i in 0 until frames) {
                val t = i.toDouble() / frames
                val hz = FROM_HZ + (TO_HZ - FROM_HZ) * t * t
                phase += 2 * PI * hz / SAMPLE_RATE
                // Fade the first few milliseconds in as well as out. Starting a sine at full
                // amplitude puts a step in the waveform, and a step is a click.
                val open = (i / (SAMPLE_RATE * 0.004)).coerceAtMost(1.0)
                val decay = exp(-DECAY * t)
                pcm[i] = (sin(phase) * open * decay * Short.MAX_VALUE * LEVEL).toInt().toShort()
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // A notification, not media: it should duck under a phone call and
                        // follow the ringer's volume rather than whatever music was playing.
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()

            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(frames)
            // Released when it finishes rather than left to the finalizer: an AudioTrack holds a
            // hardware buffer, and one per answer would run the device out of them.
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack?) {
                        runCatching { t?.release() }
                    }

                    override fun onPeriodicNotification(t: AudioTrack?) = Unit
                },
            )
            track.play()
        }
    }

    /**
     * The answer is ready, said where somebody who has left the app will see it.
     *
     * Silent by design: the sound is [bubble]'s job and this would otherwise double it for
     * anybody still looking at the screen. Tapping it reopens the conversation - the only place
     * the answer exists - rather than trying to carry the answer in the notification, which
     * would show the first line of something written to be read in full.
     */
    fun answered(preview: String) {
        if (!permitted()) return
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        context.getString(R.string.notify_channel),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.notify_channel_why)
                        setSound(null, null)
                        enableVibration(false)
                    },
                )
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(context.getString(R.string.notify_title))
                .setContentText(preview.take(PREVIEW).replace('\n', ' '))
                .setStyle(Notification.BigTextStyle().bigText(preview.take(PREVIEW)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTE_ID, notification)
        }
    }

    /** Nothing is posted without it, and on 33+ it is a runtime grant like any other. */
    fun permitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /** Taken down when the conversation is looked at again, so it cannot outlive its answer. */
    fun clear() {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID)
        }
    }

    companion object {
        const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

        private const val CHANNEL = "answers"

        /** One id, reused. A second answer replaces the first rather than stacking. */
        private const val NOTE_ID = 1

        /** Enough to know whether it is worth going back for. */
        private const val PREVIEW = 240

        private const val SAMPLE_RATE = 44_100

        /*
         * A bubble, in four numbers. Short, because a notification sound that outlasts the
         * glance at the screen is an annoyance; and swept upward through roughly an octave and
         * a half, which is where it stops sounding like a UI beep and starts sounding wet.
         */
        private const val DURATION_MS = 140
        private const val FROM_HZ = 420.0
        private const val TO_HZ = 1_150.0
        private const val DECAY = 5.5
        private const val LEVEL = 0.55
    }
}
