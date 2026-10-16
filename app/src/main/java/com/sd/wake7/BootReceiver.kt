package com.sd.wake7

import android.content.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { AlarmScheduler.schedule(context) } catch (_: Throwable) {}
        try { AlarmGuardService.ensure(context) } catch (_: Throwable) {}

        val a = intent.action
        val isBoot = a == Intent.ACTION_BOOT_COMPLETED ||
            a == "android.intent.action.QUICKBOOT_POWERON" ||
            a == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            a == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot) return

        try {
            Diag.note(context, "received", "phone started / app updated ($a)")
            // Phone rebooted while an alarm was ringing -> keep ringing.
            if (RingState.isRinging(context)) { AlarmRinger.ring(context); return }
            // Phone was off at alarm time but is back within 30 min -> ring late rather than never.
            val missed = AlarmScheduler.findMissed(context)
            if (missed >= 0) {
                AlarmHandled.mark(context, missed)
                Diag.note(context, "received", "alarm #$missed passed while phone was off - ringing late")
                AlarmRinger.ring(context)
            }
        } catch (_: Throwable) {}
    }
}
