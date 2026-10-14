package com.sd.wake7

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*

class AlarmAudioService : Service() {
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null
    private var restarts = 0
    private val handler = Handler(Looper.getMainLooper())

    private val alarmAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    override fun onCreate() {
        super.onCreate()
        // Keep the CPU awake from the very first moment (not only after the player is ready).
        acquireWakeLock()

        try {
            val n = AlarmNotifications.build(this)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(AlarmNotifications.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(AlarmNotifications.NOTIFICATION_ID, n)
            }
        } catch (_: Exception) {
            // Even if the notification/foreground step is refused, still try to ring.
        }

        maxAlarmVolume()
        requestFocus()
        play()
        vibrate()
    }

    // If the system kills the process while the alarm is ringing, bring the alarm back.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun prefs() = getSharedPreferences("wake7", MODE_PRIVATE)

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wake7:alarm").apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    /** Make sure a muted/low alarm volume can never mean a silent alarm. The original level is restored afterwards. */
    private fun maxAlarmVolume() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val p = prefs()
            if (!p.contains(KEY_ORIG_VOLUME)) {
                p.edit().putInt(KEY_ORIG_VOLUME, am.getStreamVolume(AudioManager.STREAM_ALARM)).commit()
            }
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
    }

    private fun restoreVolume() {
        try {
            val p = prefs()
            if (p.contains(KEY_ORIG_VOLUME)) {
                val am = getSystemService(AudioManager::class.java)
                am.setStreamVolume(AudioManager.STREAM_ALARM, p.getInt(KEY_ORIG_VOLUME, 5), 0)
                p.edit().remove(KEY_ORIG_VOLUME).commit()
            }
        } catch (_: Exception) {}
    }

    private fun requestFocus() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(alarmAttrs)
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(req)   // we play regardless of the result
            focusRequest = req
        } catch (_: Exception) {}
    }

    private fun play() {
        val candidates = ArrayList<Uri>()
        // 1. Custom tone, copied into the app's private storage -> works offline, always readable.
        val f = ToneStore.file(this)
        if (f.exists() && f.length() > 0) candidates.add(Uri.fromFile(f))
        // 2. Older versions stored the original picked URI; still try it if present.
        prefs().getString("tone", null)?.let { candidates.add(Uri.parse(it)) }
        // 3. System sounds. A custom file must never mean a silent alarm.
        RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)?.let { candidates.add(it) }
        candidates.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        candidates.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        candidates.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        player = candidates.firstNotNullOfOrNull { startPlayer(it) }
    }

    private fun startPlayer(uri: Uri?): MediaPlayer? {
        if (uri == null) return null
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(alarmAttrs)
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            // If the media engine dies mid-alarm, start again (a few times) instead of going silent.
            mp.setOnErrorListener { _, _, _ -> handler.post { restartPlayback() }; true }
            mp
        } catch (e: Exception) {
            try { mp.release() } catch (_: Exception) {}
            null
        }
    }

    private fun restartPlayback() {
        if (restarts >= 5) return
        restarts++
        releasePlayer()
        play()
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        try { p.stop() } catch (_: Exception) {}
        try { p.release() } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                getSystemService(Vibrator::class.java)
            }
            vibrator = v
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0), attrs)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
        try {
            focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        } catch (_: Exception) {}
        focusRequest = null
        restoreVolume()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val KEY_ORIG_VOLUME = "orig_alarm_volume"
    }
}
