package com.sd.wake7

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

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
        prefs(c).edit().putString(KEY_ALARMS, arr.toString()).apply()
    }

    /** Adds a new alarm. Returns null if an alarm with the same time already exists. */
    fun add(c: Context, hour: Int, minute: Int): Alarm? {
        val list = all(c)
        if (list.any { it.hour == hour && it.minute == minute }) return null
        val p = prefs(c)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        val alarm = Alarm(id, hour, minute, true)
        save(c, list + alarm)
        return alarm
    }

    /** Changes the time of an alarm (and turns it on). Returns false if another alarm already has that time. */
    fun update(c: Context, id: Int, hour: Int, minute: Int): Boolean {
        val list = all(c)
        if (list.any { it.id != id && it.hour == hour && it.minute == minute }) return false
        save(c, list.map { if (it.id == id) Alarm(id, hour, minute, true) else it })
        return true
    }

    fun setEnabled(c: Context, id: Int, enabled: Boolean) {
        save(c, all(c).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun delete(c: Context, id: Int) {
        save(c, all(c).filter { it.id != id })
    }

    fun format(hour: Int, minute: Int): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return String.format("%d:%02d %s", h12, minute, if (hour < 12) "AM" else "PM")
    }
}

object AlarmScheduler {
    private const val REQ_BASE = 1000   // each alarm uses request code 1000 + its id
    private const val REQ_LEGACY = 77   // the old single hard-coded 7:00 AM alarm

    /** Next time (in millis) this alarm should ring. */
    fun nextTrigger(alarm: Alarm): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    private fun cancelRequest(context: Context, requestCode: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) { am.cancel(pi); pi.cancel() }
    }

    /** Remove the system alarm for a deleted alarm. */
    fun cancel(context: Context, alarmId: Int) = cancelRequest(context, REQ_BASE + alarmId)

    /**
     * (Re)schedules every enabled alarm and cancels the disabled ones.
     * Safe to call any time (app start, boot, after an alarm fires).
     * Returns false only if Android refuses exact alarms (permission revoked).
     */
    fun schedule(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)

        // The old version of the app had one fixed 7:00 AM alarm. Remove it so it can't ring on its own.
        cancelRequest(context, REQ_LEGACY)

        val alarms = AlarmStore.all(context)
        alarms.filter { !it.enabled }.forEach { cancel(context, it.id) }
        val active = alarms.filter { it.enabled }
        if (active.isEmpty()) return true

        if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) return false

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // What opens when the user taps the alarm icon in the status bar / clock UI.
        // It must NOT be the receiver, otherwise tapping the icon would trigger the alarm.
        val show = PendingIntent.getActivity(context, 76, Intent(context, MainActivity::class.java), flags)

        var ok = true
        for (a in active) {
            val fire = PendingIntent.getBroadcast(
                context, REQ_BASE + a.id, Intent(context, AlarmReceiver::class.java), flags
            )
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(nextTrigger(a), show), fire)
            } catch (_: SecurityException) {
                ok = false
            }
        }
        return ok
    }
}
