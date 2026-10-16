package com.sd.wake7

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * "Armed" guard. While at least one alarm is switched on, this runs as a foreground service
 * (small silent notification). That makes Android treat Wake 7 as an app the user is relying on,
 * so it is far less likely to be frozen/killed overnight, and it lets the alarm start its sound
 * from the background reliably. It also keeps re-arming the alarms and checks for lost ones.
 */
class AlarmGuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (AlarmStore.all(this).none { it.enabled }) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            stopSelf()
            return START_NOT_STICKY
        }
        try { AlarmScheduler.schedule(this) } catch (_: Throwable) {}
        return START_STICKY
    }

    // Swiped away from Recents: some phones kill the process now. Re-arm and wake ourselves up again.
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            AlarmScheduler.schedule(this)
            AlarmScheduler.scheduleHeartbeat(this, 3_000L)
        } catch (_: Throwable) {}
        super.onTaskRemoved(rootIntent)
    }

    private fun goForeground() {
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (t: Throwable) {
            Diag.note(this, "error", "guard could not go foreground: ${t.javaClass.simpleName}")
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Alarm armed", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        val next = AlarmStore.all(this).filter { it.enabled }.minByOrNull { AlarmScheduler.nextTrigger(it) }
        val text = if (next == null) "No alarm is on"
        else "Next alarm ${AlarmStore.format(next.hour, next.minute)} - protected, will ring"
        val open = PendingIntent.getActivity(
            this, 75, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Wake 7 is armed")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "wake7_guard_v1"
        private const val NOTIFICATION_ID = 10

        /** Start (or refresh) the guard if any alarm is on. Never throws. */
        fun ensure(context: Context) {
            try {
                if (AlarmStore.all(context).none { it.enabled }) return
                val i = Intent(context, AlarmGuardService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (_: Throwable) {
                // Not allowed from this place right now (background). The next heartbeat / app open will retry.
            }
        }
    }
}
