package com.sd.wake7

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Keep the CPU awake while the ringing is being started.
        AlarmRinger.holdCpu(context)
        try {
            when (intent.action) {
                AlarmScheduler.ACTION_WATCHDOG -> onWatchdog(context)
                AlarmScheduler.ACTION_HEARTBEAT -> onHeartbeat(context)
                else -> onAlarm(context, intent)
            }
        } catch (t: Throwable) {
            Diag.note(context, "error", "receiver: ${t.javaClass.simpleName}")
            // Last resort: whatever went wrong, still try to make noise.
            if (intent.action == null) { try { AlarmRinger.ring(context) } catch (_: Throwable) {} }
        }
    }

    /** The real alarm time (or the test alarm) has arrived. */
    private fun onAlarm(context: Context, intent: Intent) {
        val firedId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        Diag.note(context, "received", if (firedId == AlarmScheduler.TEST_ALARM_ID) "TEST alarm arrived" else "alarm #$firedId arrived")
        if (firedId >= 0) AlarmHandled.mark(context, firedId)

        // Schedule tomorrow's alarm, but never let a problem here stop the ringing.
        try { AlarmScheduler.schedule(context, firedId) } catch (_: Throwable) {}

        AlarmRinger.ring(context)
    }

    /** Ringing but not solved yet: make sure sound is really playing; restart it if not. */
    private fun onWatchdog(context: Context) {
        if (!RingState.isRinging(context)) return
        AlarmRinger.ring(context)
    }

    /** Runs every few minutes all night: heal alarms, keep the guard alive, ring lost alarms. */
    private fun onHeartbeat(context: Context) {
        try { AlarmScheduler.schedule(context) } catch (_: Throwable) {}
        AlarmGuardService.ensure(context)

        val missed = AlarmScheduler.findMissed(context)
        if (missed >= 0) {
            AlarmHandled.mark(context, missed)
            Diag.note(context, "received", "alarm #$missed was LOST by the phone - ringing late")
            AlarmRinger.ring(context)
        }
    }
}

/** One place that starts the ringing, with several layers of fallback. */
object AlarmRinger {
    private var cpuLock: PowerManager.WakeLock? = null

    @Synchronized
    fun holdCpu(context: Context) {
        try {
            val l = cpuLock ?: context.applicationContext
                .getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wake7:receiver")
                .also { it.setReferenceCounted(false); cpuLock = it }
            l.acquire(60_000L)
        } catch (_: Throwable) {}
    }

    /**
     * Start (or re-assert) the alarm. Safe to call repeatedly.
     *  1. mark the alarm as "ringing" (survives process death)
     *  2. arm the watchdog, which re-checks every ~45 s even if everything below fails
     *  3. start the audio service
     *  4. if the audio service cannot start, post a notification that rings by itself
     */
    fun ring(context: Context) {
        val app = context.applicationContext
        RingState.start(app)
        AlarmScheduler.scheduleWatchdog(app)
        if (!startAudio(app)) {
            try { AlarmNotifications.postFallback(app) } catch (_: Throwable) {}
        }
    }

    fun startAudio(context: Context): Boolean = try {
        val svc = Intent(context, AlarmAudioService::class.java)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc) else context.startService(svc)
        true
    } catch (t: Throwable) {
        Diag.note(context, "error", "audio service refused to start: ${t.javaClass.simpleName}")
        false
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
            .setOnlyAlertOnce(true)            // re-posting it from the watchdog must not pop up again
            .setContentIntent(open)            // tapping the heads-up opens the alarm screen
            .setFullScreenIntent(open, true)   // and it still auto-opens on the lock screen
            .build()
    }

    /** Plainest possible notification, used only if build() ever throws. A foreground service needs one. */
    fun buildMinimal(context: Context): Notification {
        ensureChannel(context)
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("WAKE UP")
            .setOngoing(true)
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

    /** Fallback path: rings on its own (even if our process dies); AlarmActivity starts the audio service when opened. */
    fun postFallback(context: Context) {
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildFallback(context))
    }
}
