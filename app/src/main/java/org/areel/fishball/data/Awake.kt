package org.areel.fishball.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.areel.fishball.MainActivity
import org.areel.fishball.R

/**
 * Keeping the app alive for as long as a turn takes.
 *
 * A turn is allowed to run for a hundred rounds, which is minutes of network on a question
 * somebody asked and then put the phone down over - and a backgrounded Android process with
 * nothing to show for itself is exactly what the system reclaims first. Doze cuts its network,
 * and the OEM shells this app is actually used on will kill it outright within seconds of the
 * screen going elsewhere. Neither of those is a bug that can be fixed from inside the coroutine.
 *
 * A foreground service is the whole of the answer the platform offers, so this is one - and it
 * is deliberately nothing but that. It runs no work: the turn stays where it always was, on the
 * screen's own scope, and this exists solely to tell the system that the process is doing
 * something on the user's behalf. Moving the agent loop into the service was the alternative and
 * buys nothing that [Conversation.resume] does not already cover: an activity destroyed while
 * the process lives leaves the same half-finished row in the log as a process that was killed,
 * and both are picked up on the next launch.
 *
 * The notification is the price, not a feature. It is the quietest one the platform allows - its
 * own channel at minimum importance, no sound, no vibration - and it lives exactly as long as
 * the turn does.
 */
class Awake(private val context: Context) {

    /**
     * Held from the moment a turn starts.
     *
     * Wrapped, and the failure is swallowed on purpose. `startForegroundService` throws if the
     * app is not in a state that may start one, and every call here is made from the foreground
     * where it is - but a turn must not fail because the phone would not let it raise its own
     * priority. The worst case is the old behaviour: a turn that dies when the app is put away,
     * and is resumed on the next launch.
     */
    fun hold() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TurnService::class.java))
        }
    }

    /** Let go the moment it lands, however it lands. The notification goes with it. */
    fun release() {
        runCatching { context.stopService(Intent(context, TurnService::class.java)) }
    }
}

/**
 * The service itself, which does nothing.
 *
 * There is no work in here and there should not be. Its only job is to exist while a turn is
 * running, so the process it is in is one the system leaves alone. See [Awake].
 */
class TurnService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Named explicitly as well as in the manifest. From API 34 a service that starts
                // itself in the foreground without saying what for is refused outright, and this
                // one is a network transfer on the user's behalf.
                startForeground(NOTE_ID, working(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTE_ID, working())
            }
        }
        // Not sticky. If the process is killed the turn in it died too, and what picks that up
        // is the half-written row in the log on the next launch - not a service restarted into
        // an app with no conversation in it. See `Conversation.resume`.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * The quietest notification the platform will accept for this.
     *
     * Its own channel rather than the one answers arrive on: that one is set to alert, and this
     * one must never be the reason a phone lights up - it says nothing the person who asked the
     * question does not already know. Tapping it goes back to the conversation, which is the
     * only thing anybody could want from it.
     */
    private fun working(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.notify_working_channel),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    description = getString(R.string.notify_working_channel_why)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.notify_working))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "working"

        /** Its own id, so raising this never takes down the answer notification or vice versa. */
        const val NOTE_ID = 2
    }
}
