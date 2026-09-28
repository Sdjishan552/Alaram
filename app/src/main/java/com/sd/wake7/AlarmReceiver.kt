package com.sd.wake7

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
