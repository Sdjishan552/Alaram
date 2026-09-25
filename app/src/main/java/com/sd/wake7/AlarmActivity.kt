package com.sd.wake7

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import kotlin.random.Random

class AlarmActivity: Activity(){
    private val answers = mutableListOf<Int>()
    private lateinit var inputs: LinearLayout
    private lateinit var stop: Button
    private lateinit var progress: TextView
    private var isStopping = false

    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        startService(Intent(this, AlarmAudioService::class.java))
        build()
        lockScreen()
    }

    // Screen pinning: hides Recents and makes Home a no-op while pinned.
    // This is the real ceiling of what a normal app is allowed to do —
    // Android reserves the Back+Recents "unpin" gesture and there is no
    // API to remove that, by design.
    private fun lockScreen() {
        try { startLockTask() } catch (_: Exception) { /* already pinned, or not supported on this device */ }
    }

    private fun unlockScreen() {
        try { stopLockTask() } catch (_: Exception) { /* wasn't pinned */ }
    }

    // Block the hardware volume-down / mute buttons while the alarm screen is showing,
    // so the alarm can't be silenced without actually solving the questions.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun build(){
        val bg = Color.parseColor(Palette.BACKGROUND)
        val textSecondary = Color.parseColor(Palette.TEXT_SECONDARY)
        val accent = Color.parseColor(Palette.ACCENT)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(this@AlarmActivity), 32.dp(this@AlarmActivity), 24.dp(this@AlarmActivity), 32.dp(this@AlarmActivity))
        }

        // ---- Header ----
        val headerCard = buildCard(this, 22).apply {
            background = pillShape(this@AlarmActivity, accent, 22)
            gravity = Gravity.CENTER
            setPadding(20.dp(this@AlarmActivity), 22.dp(this@AlarmActivity), 20.dp(this@AlarmActivity), 22.dp(this@AlarmActivity))
        }
        headerCard.addView(TextView(this).apply {
            text = "WAKE UP NOW"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, matchWrap(this))
        headerCard.addView(TextView(this).apply {
            text = "Solve all 7 questions — the alarm stops automatically once they're all correct"
            textSize = 14f
            setTextColor(Color.parseColor("#FFE0E0"))
            gravity = Gravity.CENTER
        }, matchWrap(this, topDp = 6))
        root.addView(headerCard, matchWrap(this, bottomDp = 20))

        progress = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
        }
        root.addView(progress, matchWrap(this, bottomDp = 12))

        inputs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(inputs, matchWrap(this))

        stop = buildPrimaryButton(this, "SOLVE ALL 7 TO STOP").apply {
            isEnabled = false
            alpha = 0.4f
            setOnClickListener { stopAlarm() }
        }
        root.addView(stop, matchHeight(this, 58, topDp = 8))

        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(scroll)
        generate()
    }

    private fun generate(){
        val qs = ArrayList<String>(); answers.clear()
        repeat(7){
            val a = Random.nextInt(12,48); val b = Random.nextInt(6,19); val op = Random.nextInt(3)
            when(op){
                0 -> { qs.add("$a + $b = ?"); answers.add(a+b) }
                1 -> { val x=maxOf(a,b); val y=minOf(a,b); qs.add("$x − $y = ?"); answers.add(x-y) }
                else -> { val x=Random.nextInt(6,13); val y=Random.nextInt(3,9); qs.add("$x × $y = ?"); answers.add(x*y) }
            }
        }
        inputs.removeAllViews()
        qs.forEachIndexed { idx, q ->
            val row = buildCard(this, 16).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp(this@AlarmActivity), 12.dp(this@AlarmActivity), 16.dp(this@AlarmActivity), 12.dp(this@AlarmActivity))
            }
            row.addView(TextView(this).apply {
                text = "${idx+1}.  $q"
                textSize = 18f
                setTextColor(Color.parseColor(Palette.TEXT_PRIMARY))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 18f
                gravity = Gravity.CENTER
                setSingleLine(true)
                hint = "?"
                background = pillShape(this@AlarmActivity, Color.parseColor("#F0EEEC"), 12)
                setPadding(8.dp(this@AlarmActivity), 4.dp(this@AlarmActivity), 8.dp(this@AlarmActivity), 4.dp(this@AlarmActivity))
            }
            input.tag = idx
            input.setOnEditorActionListener { _,_,_ -> check(); false }
            row.addView(input, LinearLayout.LayoutParams(72.dp(this), 44.dp(this)))
            inputs.addView(row, matchWrap(this, bottomDp = 10))
        }
        stop.isEnabled = false
        stop.alpha = 0.4f
        stop.text = "SOLVE ALL 7 TO STOP"
        updateProgress()
        val checker = buildSecondaryButton(this, "CHECK ANSWERS").apply { setOnClickListener { check() } }
        inputs.addView(checker, matchHeight(this, 50, topDp = 4))
    }

    private fun updateProgress(){
        var solved = 0
        for (i in answers.indices) {
            val row = inputs.getChildAt(i) as? LinearLayout ?: continue
            val input = row.getChildAt(1) as? EditText ?: continue
            if (input.text.toString().trim().toIntOrNull() == answers[i]) solved++
        }
        progress.text = "$solved / 7 correct"
    }

    private fun check(){
        var ok = true
        for (i in 0 until 7){
            val row = inputs.getChildAt(i) as? LinearLayout ?: continue
            val input = row.getChildAt(1) as? EditText ?: continue
            if (input.text.toString().trim().toIntOrNull() != answers[i]) { ok = false; input.error = "Wrong — try again" }
        }
        updateProgress()
        if (ok) {
            stop.isEnabled = true
            stop.alpha = 1f
            stop.text = "STOPPING…"
            Toast.makeText(this,"All 7 correct. Good morning!",Toast.LENGTH_SHORT).show()
            // Auto-stop a moment after the last correct answer, so you see the
            // confirmation before the screen closes. No extra tap required.
            stop.postDelayed({ stopAlarm() }, 600)
        } else {
            Toast.makeText(this,"Not all answers are correct.",Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAlarm(){
        if (isStopping) return
        isStopping = true
        stopService(Intent(this,AlarmAudioService::class.java))
        getSystemService(NotificationManager::class.java).cancel(88)
        unlockScreen()
        finish()
    }
    override fun onBackPressed(){ /* deliberately disabled while alarm is active */ }
    override fun onUserLeaveHint(){ super.onUserLeaveHint() }
}
