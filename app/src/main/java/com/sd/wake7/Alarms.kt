package com.sd.wake7

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One saved alarm. Repeats daily while [enabled] is true. */
data class Alarm(val id: Int, val hour: Int, val minute: Int, val enabled: Boolean)

/** Saves the list of alarms permanently (SharedPreferences, as JSON). */
object AlarmStore {
    private const val KEY_ALARMS = "alarms"
    private const val KEY_NEXT_ID = "alarm_next_id"

    private fun prefs(c: Context) = c.getSharedPreferences("wake7", Context.MODE_PRIVATE)

    fun all(c: Context): List<Alarm> {
        val raw = prefs(c).getString(KEY_ALARMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Alarm(o.getInt("id"), o.getInt("h"), o.getInt("m"), o.optBoolean("on", true))
            }.sortedWith(compareBy({ it.hour }, { it.minute }))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(c: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("h", it.hour).put("m", it.minute).put("on", it.enabled))
        }
        // commit() (not apply()) so the data is on disk before we schedule anything from it.
        prefs(c).edit().putString(KEY_ALARMS, arr.toString()).commit()
    }

    /** Adds a new alarm. Returns null if an alarm with the same time already exists. */
    fun add(c: Context, hour: Int, minute: Int): Alarm? {
        val list = all(c)
        if (list.any { it.hour == hour && it.minute == minute }) return null
        val p = prefs(c)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).commit()
        val alarm = Alarm(id, hour, minute, true)
        save(c, list + alarm)
        AlarmHandled.mark(c, id)
        return alarm
    }

    /** Changes the time of an alarm (and turns it on). Returns false if another alarm already has that time. */
    fun update(c: Context, id: Int, hour: Int, minute: Int): Boolean {
        val list = all(c)
        if (list.any { it.id != id && it.hour == hour && it.minute == minute }) return false
        save(c, list.map { if (it.id == id) Alarm(id, hour, minute, true) else it })
        AlarmHandled.mark(c, id)
        return true
    }

    fun setEnabled(c: Context, id: Int, enabled: Boolean) {
        save(c, all(c).map { if (it.id == id) it.copy(enabled = enabled) else it })
        if (enabled) AlarmHandled.mark(c, id)
    }

    fun delete(c: Context, id: Int) {
        save(c, all(c).filter { it.id != id })
    }

    fun format(hour: Int, minute: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return String.format("%d:%02d %s", h12, minute, if (hour < 12) "AM" else "PM")
    }
}

/**
 * Custom alarm tone. The picked file is COPIED into the app's own private storage,
 * so it plays with no internet, even if the original is deleted, moved, or lives
 * in a cloud-backed provider (Google Drive, etc.) that is unreachable offline.
 */
object ToneStore {
    fun file(c: Context): File = File(c.filesDir, "alarm_tone")

    fun hasCustom(c: Context): Boolean = file(c).let { it.exists() && it.length() > 0 }

    /** Copies the chosen audio into private storage. Returns true on success. Run off the main thread. */
    fun importFrom(c: Context, uri: Uri): Boolean {
        val tmp = File(c.filesDir, "alarm_tone.tmp")
        try {
            val input = c.contentResolver.openInputStream(uri) ?: return false
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
            if (tmp.length() == 0L) { tmp.delete(); return false }
            val dest = file(c)
            if (dest.exists()) dest.delete()
            return tmp.renameTo(dest)
        } catch (_: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
            return false
        }
    }

    fun displayName(c: Context, uri: Uri): String {
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val n = it.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "Custom tone"
    }
}

/**
 * Tiny on-phone "black box" so we can SEE what happened overnight
 * (shown in the ALARM HEALTH CHECK card on the main screen).
 */
object Diag {
    private fun prefs(c: Context) = c.getSharedPreferences("wake7", Context.MODE_PRIVATE)

    fun time(millis: Long): String =
        SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault()).format(Date(millis))

    fun note(c: Context, key: String, msg: String) {
        try {
            prefs(c).edit().putString("diag_$key", "${time(System.currentTimeMillis())}  -  $msg").commit()
        } catch (_: Throwable) {}
    }

    fun get(c: Context, key: String): String =
        try { prefs(c).getString("diag_$key", null) ?: "never" } catch (_: Throwable) { "unknown" }
}

/**
 * "An alarm is ringing and has not been solved yet."
 * Survives the process being killed, so the watchdog / restarted service can keep ringing.
 */
object RingState {
    private const val KEY_RINGING = "ringing"
    private const val KEY_SINCE = "ring_since"
    private const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L

    private fun prefs(c: Context) = c.getSharedPreferences("wake7", Context.MODE_PRIVATE)

    fun isRinging(c: Context): Boolean {
        val p = prefs(c)
        if (!p.getBoolean(KEY_RINGING, false)) return false
        val since = p.getLong(KEY_SINCE, 0L)
        if (System.currentTimeMillis() - since > STALE_AFTER_MS) {
            p.edit().putBoolean(KEY_RINGING, false).commit()
            return false
        }
        return true
    }

    fun start(c: Context) {
        if (isRinging(c)) return
        prefs(c).edit().putBoolean(KEY_RINGING, true).putLong(KEY_SINCE, System.currentTimeMillis()).commit()
    }

    fun clear(c: Context) {
        prefs(c).edit().putBoolean(KEY_RINGING, false).commit()
    }
}

/**
 * Remembers, per alarm, the last moment it was "handled" (rang, or was created/changed/enabled).
 * If an alarm's time passed recently and it was never handled, it was LOST (phone off, process killed,
 * OS dropped it) and we ring it late instead of staying silent.
 */
object AlarmHandled {
    private fun prefs(c: Context) = c.getSharedPreferences("wake7", Context.MODE_PRIVATE)
    private fun key(id: Int) = "handled_$id"

    fun mark(c: Context, id: Int, at: Long = System.currentTimeMillis()) {
        try { prefs(c).edit().putLong(key(id), at).commit() } catch (_: Throwable) {}
    }

    /** -1 when never recorded. */
    fun get(c: Context, id: Int): Long = prefs(c).getLong(key(id), -1L)
}

object AlarmScheduler {
    private const val REQ_BASE = 1000   // each alarm uses request code 1000 + its id
    private const val REQ_LEGACY = 77   // the old single hard-coded 7:00 AM alarm
    private const val REQ_TEST = 78     // "test real alarm" button
    private const val REQ_HEARTBEAT = 79
    private const val REQ_WATCHDOG = 80
    private const val MISS_WINDOW_MS = 30 * 60 * 1000L

    const val EXTRA_ALARM_ID = "alarm_id"
    const val TEST_ALARM_ID = -2
    const val ACTION_HEARTBEAT = "com.sd.wake7.HEARTBEAT"
    const val ACTION_WATCHDOG = "com.sd.wake7.WATCHDOG"

    /** Next time (in millis) this alarm should ring, strictly after [from]. */
    fun nextTrigger(alarm: Alarm, from: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= from) add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    /** Most recent moment (<= [now]) at which this alarm was supposed to ring. */
    fun prevTrigger(alarm: Alarm, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis > now) add(Calendar.DAY_OF_YEAR, -1)
        }
        return c.timeInMillis
    }

    /**
     * Id of an enabled alarm that should have rung within the last 30 minutes but never did, or -1.
     * Alarms with no history yet are just baselined (never rung retroactively).
     */
    fun findMissed(context: Context, now: Long = System.currentTimeMillis()): Int {
        for (a in AlarmStore.all(context).filter { it.enabled }) {
            val handled = AlarmHandled.get(context, a.id)
            if (handled < 0L) { AlarmHandled.mark(context, a.id, now); continue }
            val prev = prevTrigger(a, now)
            if (prev > handled && now - prev <= MISS_WINDOW_MS) return a.id
        }
        return -1
    }

    // ---------------------------------------------------------------- helpers

    private fun helperIntent(context: Context, action: String) =
        Intent(context, AlarmReceiver::class.java).setAction(action)

    private fun helperPending(context: Context, req: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, req, helperIntent(context, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun cancelHelper(context: Context, req: Int, action: String) {
        try {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi: PendingIntent? = PendingIntent.getBroadcast(
                context, req, helperIntent(context, action),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) { am.cancel(pi); pi.cancel() }
        } catch (_: Throwable) {}
    }

    /** Exact + works in Doze; falls back to the inexact version if exact alarms are refused. */
    private fun setBackupAlarm(context: Context, triggerAt: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (_: Exception) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) } catch (_: Exception) {}
        }
    }

    /** While an alarm is ringing: every ~45 s make sure sound is really playing, restart it if not. */
    fun scheduleWatchdog(context: Context, delayMs: Long = 45_000L) {
        try { setBackupAlarm(context, System.currentTimeMillis() + delayMs, helperPending(context, REQ_WATCHDOG, ACTION_WATCHDOG)) } catch (_: Throwable) {}
    }

    fun cancelWatchdog(context: Context) = cancelHelper(context, REQ_WATCHDOG, ACTION_WATCHDOG)

    /** Every few minutes: re-arm alarms, restart the guard, ring any alarm that got lost. */
    fun scheduleHeartbeat(context: Context, delayMs: Long = 5 * 60_000L) {
        try { setBackupAlarm(context, System.currentTimeMillis() + delayMs, helperPending(context, REQ_HEARTBEAT, ACTION_HEARTBEAT)) } catch (_: Throwable) {}
    }

    fun cancelHeartbeat(context: Context) = cancelHelper(context, REQ_HEARTBEAT, ACTION_HEARTBEAT)

    /** A REAL alarm (same path as the 7 AM one) that rings after [seconds]. Lock the phone and wait. */
    fun scheduleTest(context: Context, seconds: Int = 20) {
        val am = context.getSystemService(AlarmManager::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val show = PendingIntent.getActivity(context, 76, Intent(context, MainActivity::class.java), flags)
        val fire = PendingIntent.getBroadcast(
            context, REQ_TEST,
            Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_ALARM_ID, TEST_ALARM_ID),
            flags
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + seconds * 1000L, show), fire)
    }

    private fun cancelRequest(context: Context, requestCode: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi: PendingIntent? = PendingIntent.getBroadcast(
            context, requestCode, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) { am.cancel(pi); pi.cancel() }
    }

    /** Remove the system alarm for a deleted alarm. */
    fun cancel(context: Context, alarmId: Int) = cancelRequest(context, REQ_BASE + alarmId)

    /**
     * (Re)schedules every enabled alarm and cancels the disabled ones.
     * Safe to call any time (app start, boot, time change, heartbeat, after an alarm fires).
     * [firedAlarmId] is the alarm that is ringing right now: its next ring is forced to
     * tomorrow so it can never be re-armed for "a few milliseconds from now".
     *
     * setAlarmClock() does NOT need the exact-alarm permission. Returns false only if
     * Android throws a SecurityException.
     */
    fun schedule(context: Context, firedAlarmId: Int = -1): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)

        // The old version of the app had one fixed 7:00 AM alarm. Remove it so it can't ring on its own.
        cancelRequest(context, REQ_LEGACY)

        val alarms = AlarmStore.all(context)
        alarms.filter { !it.enabled }.forEach { cancel(context, it.id) }
        val active = alarms.filter { it.enabled }
        if (active.isEmpty()) {
            cancelHeartbeat(context)
            return true
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // What opens when the user taps the alarm icon in the status bar / clock UI.
        // It must NOT be the receiver, otherwise tapping the icon would trigger the alarm.
        val show = PendingIntent.getActivity(context, 76, Intent(context, MainActivity::class.java), flags)

        val now = System.currentTimeMillis()
        var ok = true
        var soonest = Long.MAX_VALUE
        for (a in active) {
            // Extras are ignored when Android matches PendingIntents, so cancel() above still finds this one.
            val fire = PendingIntent.getBroadcast(
                context, REQ_BASE + a.id,
                Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_ALARM_ID, a.id),
                flags
            )
            val from = if (a.id == firedAlarmId) now + 60_000L else now
            val at = nextTrigger(a, from)
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
                if (at < soonest) soonest = at
            } catch (_: SecurityException) {
                ok = false
            } catch (_: Exception) {
                // never let one bad alarm stop the others
            }
        }
        if (soonest != Long.MAX_VALUE) Diag.note(context, "scheduled", "next alarm armed for ${Diag.time(soonest)}")

        // Safety net that keeps checking all night.
        scheduleHeartbeat(context)
        return ok
    }
}
