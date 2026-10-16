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
    private var toneGen: ToneGenerator? = null
    private var restarts = 0
    private var destroyed = false
    private var failed = false
    private val handler = Handler(Looper.getMainLooper())

    private val alarmAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Last-resort loud beeping, used only while no media player could be started. Needs no files at all. */
    private val beep = object : Runnable {
        override fun run() {
            if (destroyed || player != null) return
            try {
                if (toneGen == null) toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                toneGen?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 900)
            } catch (_: Throwable) {}
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 1. Become a foreground service IMMEDIATELY (Android kills us if this takes > 5 s).
        if (!goForeground()) {
            // Android refused (e.g. started from a restricted place). Don't crash: hand over to the
            // self-ringing backup notification and let the watchdog try again.
            failed = true
            Diag.note(this, "error", "foreground service refused - using backup notification")
            try { AlarmNotifications.postFallback(this) } catch (_: Throwable) {}
            stopSelf()
            return
        }
        RingState.start(this)
        acquireWakeLock()
        maxAlarmVolume()
        requestFocus()
        play()
        vibrate()
        AlarmScheduler.scheduleWatchdog(this)
        Diag.note(this, "service", if (player != null) "audio is playing" else "audio player failed - using beep fallback")
    }

    /**
     * Called again every time the alarm is "re-asserted" (watchdog, alarm screen opening, ...).
     * Each time we make sure it is still loud and actually playing.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (failed) return START_NOT_STICKY
        goForeground()
        acquireWakeLock()
        maxAlarmVolume()
        ensurePlaying()
        if (vibrator == null) vibrate()
        AlarmScheduler.scheduleWatchdog(this)
        // If the system kills the process while the alarm is ringing, bring the alarm back.
        return START_STICKY
    }

    private fun goForeground(): Boolean {
        val n = try { AlarmNotifications.build(this) } catch (_: Throwable) {
            try { AlarmNotifications.buildMinimal(this) } catch (_: Throwable) { return false }
        }
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(AlarmNotifications.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(AlarmNotifications.NOTIFICATION_ID, n)
            }
            true
        } catch (_: Throwable) {
            try { startForeground(AlarmNotifications.NOTIFICATION_ID, n); true } catch (_: Throwable) { false }
        }
    }

    private fun prefs() = getSharedPreferences("wake7", MODE_PRIVATE)

    private fun acquireWakeLock() {
        try {
            val wl = wakeLock ?: getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wake7:alarm")
                .also { it.setReferenceCounted(false); wakeLock = it }
            wl.acquire(30 * 60 * 1000L)   // re-acquiring just extends it
        } catch (_: Throwable) {}
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
        } catch (_: Throwable) {}
    }

    private fun restoreVolume() {
        try {
            val p = prefs()
            if (p.contains(KEY_ORIG_VOLUME)) {
                val am = getSystemService(AudioManager::class.java)
                am.setStreamVolume(AudioManager.STREAM_ALARM, p.getInt(KEY_ORIG_VOLUME, 5), 0)
                p.edit().remove(KEY_ORIG_VOLUME).commit()
            }
        } catch (_: Throwable) {}
    }

    private fun requestFocus() {
        try {
            val am = getSystemService(AudioManager::class.java)
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(alarmAttrs)
                .setOnAudioFocusChangeListener { handler.post { ensurePlaying() } }
                .build()
            am.requestAudioFocus(req)   // we play regardless of the result
            focusRequest = req
        } catch (_: Throwable) {}
    }

    private fun play() {
        releasePlayer()
        val candidates = ArrayList<Uri>()
        // 1. Custom tone, copied into the app's private storage -> works offline, always readable.
        val f = ToneStore.file(this)
        if (f.exists() && f.length() > 0) candidates.add(Uri.fromFile(f))
        // 2. Older versions stored the original picked URI; still try it if present.
        prefs().getString("tone", null)?.let { candidates.add(Uri.parse(it)) }
        // 3. System sounds. A custom file must never mean a silent alarm.
        try { RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)?.let { candidates.add(it) } } catch (_: Throwable) {}
        candidates.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
        candidates.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        candidates.add(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
        player = candidates.firstNotNullOfOrNull { startPlayer(it) }

        // 4. Nothing playable at all? Loud beeping, so the alarm is never silent.
        handler.removeCallbacks(beep)
        if (player == null) handler.post(beep)
    }

    private fun startPlayer(uri: Uri?): MediaPlayer? {
        if (uri == null) return null
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(alarmAttrs)
            try { mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK) } catch (_: Throwable) {}
            mp.setDataSource(this, uri)
            mp.isLooping = true
            // If the media engine dies mid-alarm, start again instead of going silent.
            mp.setOnErrorListener { _, _, _ -> handler.post { restartPlayback() }; true }
            mp.prepare()
            mp.start()
            mp
        } catch (e: Throwable) {
            try { mp.release() } catch (_: Throwable) {}
            null
        }
    }

    private fun ensurePlaying() {
        if (destroyed) return
        val playing = try { player?.isPlaying == true } catch (_: Throwable) { false }
        if (!playing) play()
    }

    private fun restartPlayback() {
        if (destroyed || restarts >= 30) return
        restarts++
        play()
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        try { p.stop() } catch (_: Throwable) {}
        try { p.release() } catch (_: Throwable) {}
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
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        try { toneGen?.release() } catch (_: Throwable) {}
        toneGen = null
        try { vibrator?.cancel() } catch (_: Throwable) {}
        vibrator = null
        try {
            focusRequest?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        } catch (_: Throwable) {}
        focusRequest = null
        if (!failed) restoreVolume()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val KEY_ORIG_VOLUME = "orig_alarm_volume"
    }
}
