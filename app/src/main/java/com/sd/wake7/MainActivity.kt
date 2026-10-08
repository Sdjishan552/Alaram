package com.sd.wake7

import android.Manifest
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.util.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("wake7", MODE_PRIVATE) }
    private lateinit var timeText: TextView
    private lateinit var nextText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var toneText: TextView
    private var toneUri: Uri? = null
    private val pickTone = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        // Re-register all saved alarms whenever the app is opened.
        if (!AlarmScheduler.schedule(this)) requestExactAlarmAccess()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the exact-alarm settings screen: try again silently.
        AlarmScheduler.schedule(this)
        refreshList()
    }

    private fun requestExactAlarmAccess() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }

    // Lets the alarm fire reliably when the app is closed / phone is idle.
    private fun requestBatteryUnrestricted() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Battery restriction already removed"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun buildUi() {
        val textPrimary = Color.parseColor(Palette.TEXT_PRIMARY)
        val textSecondary = Color.parseColor(Palette.TEXT_SECONDARY)
        val accent = Color.parseColor(Palette.ACCENT)
        val bg = Color.parseColor(Palette.BACKGROUND)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(this@MainActivity), 36.dp(this@MainActivity), 24.dp(this@MainActivity), 36.dp(this@MainActivity))
        }

        // ---- Header ----
        root.addView(TextView(this).apply {
            text = "WAKE 7"
            textSize = 30f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(accent)
        }, matchWrap(this))

        root.addView(TextView(this).apply {
            text = "Solve 7 questions to stop any alarm"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(textSecondary)
        }, matchWrap(this, topDp = 4, bottomDp = 28))

        // ---- Clock card ----
        val clockCard = buildCard(this, 24).apply {
            gravity = Gravity.CENTER
            setPadding(24.dp(this@MainActivity), 28.dp(this@MainActivity), 24.dp(this@MainActivity), 28.dp(this@MainActivity))
        }
        timeText = TextView(this).apply {
            textSize = 50f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(textPrimary)
        }
        clockCard.addView(timeText, matchWrap(this))
        nextText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(textSecondary)
        }
        clockCard.addView(nextText, matchWrap(this, topDp = 6))
        root.addView(clockCard, matchWrap(this, bottomDp = 28))

        // ---- Add alarm ----
        val add = buildPrimaryButton(this, "+ ADD ALARM").apply {
            setOnClickListener { showTimePicker(null) }
        }
        root.addView(add, matchHeight(this, 56, bottomDp = 20))

        // ---- Saved alarms ----
        root.addView(TextView(this).apply {
            text = "MY ALARMS"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textSecondary)
            letterSpacing = 0.08f
        }, matchWrap(this, bottomDp = 8))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, matchWrap(this, bottomDp = 14))

        // ---- Other actions ----
        val test = buildPrimaryButton(this, "TEST ALARM NOW").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, AlarmActivity::class.java)) }
        }
        root.addView(test, matchHeight(this, 56, bottomDp = 14))

        val battery = buildSecondaryButton(this, "ALLOW BACKGROUND RUNNING (IMPORTANT)").apply {
            setOnClickListener { requestBatteryUnrestricted() }
        }
        root.addView(battery, matchHeight(this, 50, bottomDp = 24))

        // ---- Tone card ----
        val toneCard = buildCard(this, 20).apply {
            setPadding(20.dp(this@MainActivity), 20.dp(this@MainActivity), 20.dp(this@MainActivity), 20.dp(this@MainActivity))
        }
        toneCard.addView(TextView(this).apply {
            text = "ALARM TONE"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textSecondary)
            letterSpacing = 0.08f
        }, matchWrap(this, bottomDp = 6))
        toneText = TextView(this).apply {
            textSize = 16f
            setTextColor(textPrimary)
        }
        toneCard.addView(toneText, matchWrap(this, bottomDp = 16))
        val pick = buildSecondaryButton(this, "CHOOSE CUSTOM ALARM TONE").apply {
            setOnClickListener { pickTone() }
        }
        toneCard.addView(pick, matchHeight(this, 50))
        root.addView(toneCard, matchWrap(this, bottomDp = 24))

        // ---- Instructions card ----
        val infoCard = buildCard(this, 20).apply {
            background = pillShape(this@MainActivity, Color.parseColor(Palette.ACCENT_SOFT), 20)
            setPadding(20.dp(this@MainActivity), 18.dp(this@MainActivity), 20.dp(this@MainActivity), 18.dp(this@MainActivity))
        }
        infoCard.addView(TextView(this).apply {
            text = "IMPORTANT"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            letterSpacing = 0.08f
        }, matchWrap(this, bottomDp = 8))
        infoCard.addView(TextView(this).apply {
            text = "• Tap ADD ALARM to save as many alarm times as you want. Each one repeats daily.\n" +
                "• Tap a saved time to change it. Use the switch to turn it on/off.\n" +
                "• Keep Alarm volume audible.\n" +
                "• Allow notifications and full-screen alerts.\n" +
                "• On some phones, disable battery optimization / autostart restrictions for this app.\n" +
                "• You can use any audio file your phone exposes in the picker.\n\n" +
                "An alarm cannot be dismissed from its screen until all 7 questions are answered. Android itself can still force-stop or uninstall any app."
            textSize = 13f
            setTextColor(textPrimary)
            setLineSpacing(6f, 1f)
        }, matchWrap(this))
        root.addView(infoCard, matchWrap(this))

        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(scroll)
        refreshList(); updateClock(); loadTone()

        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        if (android.os.Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.parse("package:$packageName")))
        }
    }

    // ------------------------------------------------------------------ alarms list

    private fun showTimePicker(existing: Alarm?) {
        val now = Calendar.getInstance()
        val h = existing?.hour ?: now.get(Calendar.HOUR_OF_DAY)
        val m = existing?.minute ?: now.get(Calendar.MINUTE)
        TimePickerDialog(this, { _, hh, mm -> onTimeChosen(existing, hh, mm) }, h, m, DateFormat.is24HourFormat(this)).show()
    }

    private fun onTimeChosen(existing: Alarm?, hour: Int, minute: Int) {
        val label = AlarmStore.format(hour, minute)
        if (existing == null) {
            if (AlarmStore.add(this, hour, minute) == null) { toast("$label is already saved"); return }
            toast("Alarm saved: $label")
        } else {
            if (!AlarmStore.update(this, existing.id, hour, minute)) { toast("$label is already saved"); return }
            toast("Alarm changed to $label")
        }
        afterChange()
    }

    private fun afterChange() {
        if (!AlarmScheduler.schedule(this)) requestExactAlarmAccess()
        refreshList()
    }

    private fun confirmDelete(a: Alarm) {
        AlertDialog.Builder(this)
            .setMessage("Delete the ${AlarmStore.format(a.hour, a.minute)} alarm?")
            .setPositiveButton("Delete") { _, _ ->
                AlarmStore.delete(this, a.id)
                AlarmScheduler.cancel(this, a.id)
                toast("Alarm deleted")
                afterChange()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshList() {
        if (!::listBox.isInitialized) return
        val textPrimary = Color.parseColor(Palette.TEXT_PRIMARY)
        val textSecondary = Color.parseColor(Palette.TEXT_SECONDARY)
        listBox.removeAllViews()
        val alarms = AlarmStore.all(this)

        if (alarms.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "No alarms saved yet.\nTap + ADD ALARM to save one."
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(textSecondary)
                setPadding(0, 12.dp(this@MainActivity), 0, 12.dp(this@MainActivity))
            }, matchWrap(this))
        }

        alarms.forEach { a ->
            val row = buildCard(this, 18).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18.dp(this@MainActivity), 12.dp(this@MainActivity), 14.dp(this@MainActivity), 12.dp(this@MainActivity))
            }
            row.addView(TextView(this).apply {
                text = AlarmStore.format(a.hour, a.minute)
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (a.enabled) textPrimary else textSecondary)
                setOnClickListener { showTimePicker(a) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val sw = Switch(this).apply {
                isChecked = a.enabled
                setOnCheckedChangeListener { _, on ->
                    AlarmStore.setEnabled(this@MainActivity, a.id, on)
                    toast(if (on) "Alarm on" else "Alarm off")
                    afterChange()
                }
            }
            row.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = 12.dp(this@MainActivity)
            })

            val del = buildSecondaryButton(this, "Delete").apply {
                minWidth = 0
                minHeight = 0
                setPadding(14.dp(this@MainActivity), 0, 14.dp(this@MainActivity), 0)
                setOnClickListener { confirmDelete(a) }
            }
            row.addView(del, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40.dp(this)))

            listBox.addView(row, matchWrap(this, bottomDp = 10))
        }
        updateNextText()
    }

    private fun updateNextText() {
        if (!::nextText.isInitialized) return
        val next = AlarmStore.all(this).filter { it.enabled }.minByOrNull { AlarmScheduler.nextTrigger(it) }
        if (next == null) { nextText.text = "No alarm is on"; return }
        val mins = ((AlarmScheduler.nextTrigger(next) - System.currentTimeMillis()) / 60000).coerceAtLeast(0)
        val h = mins / 60
        val m = mins % 60
        nextText.text = "Next alarm: ${AlarmStore.format(next.hour, next.minute)}  ·  in ${if (h > 0) "$h h " else ""}$m min"
    }

    // ------------------------------------------------------------------ clock + tone

    private fun updateClock() {
        val c = Calendar.getInstance()
        timeText.text = String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        updateNextText()
        timeText.postDelayed({ updateClock() }, 30000)
    }
    private fun pickTone(){ startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="audio/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, pickTone) }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){ super.onActivityResult(requestCode,resultCode,data); if(requestCode==pickTone && resultCode==RESULT_OK){ data?.data?.let { uri -> try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){}; prefs.edit().putString("tone",uri.toString()).apply(); loadTone() } } }
    private fun loadTone(){ toneUri=prefs.getString("tone",null)?.let(Uri::parse); toneText.text = toneUri?.lastPathSegment ?: "System default alarm" }
    private fun toast(s:String){ Toast.makeText(this,s,Toast.LENGTH_SHORT).show() }
}
