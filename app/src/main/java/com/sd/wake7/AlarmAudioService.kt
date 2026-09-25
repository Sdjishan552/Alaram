package com.sd.wake7

import android.app.*
import android.content.*
import android.media.*
import android.net.Uri
import android.os.*

class AlarmAudioService: Service(){
    private var player:MediaPlayer?=null
    override fun onCreate(){ super.onCreate(); startForeground(9, notification()); play() }
    private fun notification():Notification { val ch=NotificationChannel("audio","Alarm audio",NotificationManager.IMPORTANCE_HIGH); getSystemService(NotificationManager::class.java).createNotificationChannel(ch); return Notification.Builder(this,"audio").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Wake 7 alarm running").setOngoing(true).build() }
    private fun play(){
        val prefs=getSharedPreferences("wake7",MODE_PRIVATE); val uri=prefs.getString("tone",null)?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        player=MediaPlayer().apply { setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()); setDataSource(this@AlarmAudioService,uri); isLooping=true; prepare(); start() }
    }
    override fun onDestroy(){ player?.stop(); player?.release(); player=null; super.onDestroy() }
    override fun onBind(intent:Intent?)=null
}
