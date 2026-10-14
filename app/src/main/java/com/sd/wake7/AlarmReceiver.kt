package com.sd.wake7

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 1. Schedule tomorrow's alarm FIRST, so nothing below can ever break the daily chain.
        //    Wrapped in try/catch so a scheduling problem can never stop the alarm from ringing.
        val firedId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        try { AlarmScheduler.schedule(context, firedId) } catch (_: Exception) {}

        // 2. Start the sound immediately from here. It does not depend on the
        //    full-screen activity being allowed to open. Alarms set with setAlarmClock()
        //    are exempt from the "no foreground service from background" rule.
        try {
            val svc = Intent(context, AlarmAudioService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
        } catch (e: Exception) {
            // 3. Fallback: a notification that makes its OWN alarm sound (repeating until dismissed)
            //    and opens AlarmActivity, which starts the audio service as soon as it can.
            try { AlarmNotifications.postFallback(context) } catch (_: Exception) {}
        }
    }
}

/** Builds the alarm notifications used by the audio service and as a fallback. */
object AlarmNotifications {
    // Silent channel: the sound is played by AlarmAudioService, not the channel.
    // (A channel can't be changed after creation, hence the version suffix.)
    const val CHANNEL_ID = "wake7_alarm_v2"
    // Backup channel that rings by itself if the audio service could not be started.
    const val FALLBACK_CHANNEL_ID = "wake7_alarm_fallback_v1"
    const val NOTIFICATION_ID = 9

    private fun ensureChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL_ID, "Wake 7 Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun ensureFallbackChannel(context: Context) {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val ch = NotificationChannel(FALLBACK_CHANNEL_ID, "Wake 7 Alarm (backup)", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 500, 700, 500)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 88,
        Intent(context, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Silent notification attached to the audio foreground service. */
    fun build(context: Context): Notification {
        ensureChannel(context)
        val open = openIntent(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("WAKE UP — 7 QUESTIONS")
            .setContentText("Tap to solve every question and stop the alarm")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)            // tapping the heads-up opens the alarm screen
            .setFullScreenIntent(open, true)   // and it still auto-opens on the lock screen
            .build()
    }

    /** Backup notification that plays the alarm sound itself and keeps repeating it. */
    fun buildFallback(context: Context): Notification {
        ensureFallbackChannel(context)
        val open = openIntent(context)
        val n = Notification.Builder(context, FALLBACK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("WAKE UP — 7 QUESTIONS")
            .setContentText("Tap to solve every question and stop the alarm")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT   // repeat the sound until dismissed
        return n
    }

    /** Fallback path: AlarmActivity starts the audio service when it opens. */
    fun postFallback(context: Context) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildFallback(context))
    }
}
