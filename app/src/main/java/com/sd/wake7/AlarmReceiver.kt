package com.sd.wake7

import android.app.*
import android.content.*
import android.os.*

class AlarmReceiver: BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val nm=context.getSystemService(NotificationManager::class.java)
        val channel=NotificationChannel("alarm","Wake 7 Alarm",NotificationManager.IMPORTANCE_HIGH).apply { setSound(null,null); lockscreenVisibility=Notification.VISIBILITY_PUBLIC }
        nm.createNotificationChannel(channel)
        val full=PendingIntent.getActivity(context,88,Intent(context,AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(context,"alarm").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("WAKE UP — 7 QUESTIONS").setContentText("Solve every question to stop the alarm").setCategory(Notification.CATEGORY_ALARM).setPriority(Notification.PRIORITY_MAX).setOngoing(true).setFullScreenIntent(full,true).build()
        nm.notify(88,n)
        AlarmScheduler.schedule(context)
    }
}
