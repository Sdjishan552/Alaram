package com.sd.wake7

import android.Manifest
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.util.*

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("wake7", MODE_PRIVATE) }
    private lateinit var timeText: TextView
    private lateinit var toneText: TextView
    private var toneUri: Uri? = null
    private val pickTone = 1001

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi() }

    private fun buildUi() {
        val bg = Color.parseColor(Palette.BACKGROUND)
        val textPrimary = Color.parseColor(Palette.TEXT_PRIMARY)
        val textSecondary = Color.parseColor(Palette.TEXT_SECONDARY)
        val accent = Color.parseColor(Palette.ACCENT)

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
            text = "7:00 AM  ·  Solve 7 questions to stop"
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
        root.addView(clockCard, matchWrap(this, bottomDp = 28))

        // ---- Primary actions ----
        val setTime = buildPrimaryButton(this, "SET 7:00 AM DAILY").apply {
            setOnClickListener { AlarmScheduler.schedule(this@MainActivity); toast("Daily 7:00 AM alarm set") }
        }
        root.addView(setTime, matchHeight(this, 56, bottomDp = 14))

        val test = buildPrimaryButton(this, "TEST ALARM NOW").apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, AlarmActivity::class.java)) }
        }
        root.addView(test, matchHeight(this, 56, bottomDp = 24))

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
            text = "• Keep Alarm volume audible.\n" +
                "• Allow notifications and full-screen alerts.\n" +
                "• On some phones, disable battery optimization / autostart restrictions for this app.\n" +
                "• You can use any audio file your phone exposes in the picker.\n\n" +
                "The alarm cannot be dismissed from its screen until all 7 questions are answered. Android itself can still force-stop or uninstall any app."
            textSize = 13f
            setTextColor(textPrimary)
            setLineSpacing(6f, 1f)
        }, matchWrap(this))
        root.addView(infoCard, matchWrap(this))

        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(scroll)
        updateClock(); loadTone()

        if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        if (android.os.Build.VERSION.SDK_INT >= 34 && !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.parse("package:$packageName")))
        }
    }

    private fun updateClock(){ timeText.text=String.format("%02d:%02d",Calendar.getInstance().get(Calendar.HOUR_OF_DAY),Calendar.getInstance().get(Calendar.MINUTE)); timeText.postDelayed({updateClock()},30000) }
    private fun pickTone(){ startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="audio/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }, pickTone) }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){ super.onActivityResult(requestCode,resultCode,data); if(requestCode==pickTone && resultCode==RESULT_OK){ data?.data?.let { uri -> try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){}; prefs.edit().putString("tone",uri.toString()).apply(); loadTone() } } }
    private fun loadTone(){ toneUri=prefs.getString("tone",null)?.let(Uri::parse); toneText.text = toneUri?.lastPathSegment ?: "System default alarm" }
    private fun toast(s:String){ Toast.makeText(this,s,Toast.LENGTH_SHORT).show() }
}

object AlarmScheduler {
    fun schedule(context:Context){
        val am=context.getSystemService(AlarmManager::class.java)
        val i=Intent(context,AlarmReceiver::class.java)
        val pi=PendingIntent.getBroadcast(context,77,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val c=Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY,7); set(Calendar.MINUTE,0); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0); if(timeInMillis<=System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR,1) }
        am.setAlarmClock(AlarmManager.AlarmClockInfo(c.timeInMillis,pi),pi)
    }
}
