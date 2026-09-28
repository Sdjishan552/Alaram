package com.sd.wake7

import android.app.*
import android.content.*
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 1. Schedule tomorrow's alarm FIRST, so nothing below can ever break the daily chain.
        AlarmScheduler.schedule(context)

        // 2. Start the sound immediately from here. It no longer depends on the
        //    full-screen activity being allowed to open. Alarms set with setAlarmClock()
        //    are exempt from the "no foreground service from background" rule.
        try {
            val svc = Intent(context, AlarmAudioService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
        } catch (e: Exception) {
            // 3. Fallback: at least show the full-screen notification; AlarmActivity starts the audio.
            AlarmNotifications.post(context)
        }
    }
}

/** Builds the alarm notification used both by the audio service and as a fallback. */
object AlarmNotifications {
    // New channel id: the old "audio"/"alarm" channels were created with default sound
    // settings, and Android does not let an app change a channel after it is created.
    const val CHANNEL_ID = "wake7_alarm_v2"
    const val NOTIFICATION_ID = 9

    private fun ensureChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL_ID, "Wake 7 Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)          // sound is played by AlarmAudioService, not the channel
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun build(context: Context): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 88,
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("WAKE UP — 7 QUESTIONS")
            .setContentText("Tap to solve every question and stop the alarm")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)            // tapping the heads-up now opens the alarm screen
            .setFullScreenIntent(open, true)   // and it still auto-opens on the lock screen
            .build()
    }

    /** Fallback path: notification only. AlarmActivity starts the audio service when it opens. */
    fun post(context: Context) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(context))
    }
}
