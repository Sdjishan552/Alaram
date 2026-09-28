package com.sd.wake7

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*

class AlarmAudioService : Service() {
    private var player: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val n = AlarmNotifications.build(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(AlarmNotifications.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(AlarmNotifications.NOTIFICATION_ID, n)
        }
        play()
    }

    // If the system kills the process while the alarm is ringing, bring the alarm back.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun play() {
        val prefs = getSharedPreferences("wake7", MODE_PRIVATE)
        val custom = prefs.getString("tone", null)?.let(Uri::parse)
        val fallbacks = listOf(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        )
        // A custom file can become unreadable (deleted, permission lost). Never let that
        // mean a silent alarm: fall back to the system alarm sound.
        player = (listOf(custom) + fallbacks).firstNotNullOfOrNull { startPlayer(it) }
    }

    private fun startPlayer(uri: Uri?): MediaPlayer? {
        if (uri == null) return null
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK) // keep CPU awake while ringing
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            try { mp.release() } catch (_: Exception) {}
            null
        }
    }

    override fun onDestroy() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release(); player = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
