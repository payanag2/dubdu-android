package com.payanag2.dubdu

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var apiKey: EditText
    private lateinit var status: TextView
    private val projectionRequest = 701
    private val prefs by lazy { getSharedPreferences("dubdu", MODE_PRIVATE) }
    private val statusHandler = Handler(mainLooper)
    private val statusPoll = object : Runnable {
        override fun run() {
            if (::status.isInitialized) {
                status.text = prefs.getString("status", status.text.toString())
            }
            statusHandler.postDelayed(this, 700)
        }
    }

    override fun onResume() {
        super.onResume()
        statusHandler.post(statusPoll)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusPoll)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF101012.toInt()
        window.navigationBarColor = 0xFF101012.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            setBackgroundColor(0xFF101012.toInt())
        }
        fun label(text: String, size: Float, color: Int = 0xFFF3F1EC.toInt()) =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(color)
            }

        root.addView(label("DubDu", 30f).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(label("دوبلهٔ زندهٔ ویدئو به فارسی", 18f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(24))
        })
        root.addView(label("Gemini API key", 14f, 0xFFB5B2AC.toInt()))
        apiKey = EditText(this).apply {
            hint = "کلید API خودت را وارد کن"
            setSingleLine(true)
            setText(prefs.getString("api_key", ""))
            setTextColor(0xFFF3F1EC.toInt())
            setHintTextColor(0xFF777777.toInt())
        }
        root.addView(apiKey, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ))
        root.addView(label(
            "کلید فقط روی همین دستگاه ذخیره می‌شود. برای ترجمهٔ زنده، اتصال اینترنت و دسترسی به Gemini API لازم است.",
            13f, 0xFFB5B2AC.toInt()
        ).apply { setPadding(0, dp(8), 0, dp(20)) })

        val start = Button(this).apply {
            text = "شروع دوبلهٔ زنده"
            setOnClickListener { beginCapture() }
        }
        root.addView(start, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ))
        val stop = Button(this).apply {
            text = "توقف دوبله"
            setOnClickListener {
                stopService(Intent(this@MainActivity, AudioDubbingService::class.java))
                prefs.edit().putString("status", "دوبله متوقف شد.").apply()
                status.text = "دوبله متوقف شد."
            }
        }
        root.addView(stop, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(8) })

        status = label("آمادهٔ شروع", 14f, 0xFF6DE0BE.toInt()).apply {
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(status)
        root.addView(label(
            "توجه: اندروید فقط صدای برنامه‌هایی را ضبط می‌کند که اجازهٔ Audio Playback Capture بدهند. بعضی برنامه‌ها مثل برخی سرویس‌های پخش ممکن است صدای خود را مسدود کنند.",
            12f, 0xFF9C9890.toInt()
        ).apply { setPadding(0, dp(24), 0, 0) })
        setContentView(root)
    }

    private fun beginCapture() {
        val key = apiKey.text.toString().trim()
        if (key.isBlank()) {
            Toast.makeText(this, "ابتدا Gemini API key را وارد کن.", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString("api_key", key).apply()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        prefs.edit().putString("status", "برای اشتراک‌گذاری صدای دستگاه اجازه بده…").apply()
        status.text = "برای اشتراک‌گذاری صدای دستگاه اجازه بده…"
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
    }

    @Deprecated("Deprecated by Android; retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != projectionRequest) return
        if (resultCode != RESULT_OK || data == null) {
            prefs.edit().putString("status", "اجازهٔ ضبط صدای دستگاه داده نشد.").apply()
            status.text = "اجازهٔ ضبط صدای دستگاه داده نشد."
            return
        }
        val service = Intent(this, AudioDubbingService::class.java).apply {
            putExtra(AudioDubbingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(AudioDubbingService.EXTRA_PROJECTION_DATA, data)
            putExtra(AudioDubbingService.EXTRA_API_KEY, apiKey.text.toString().trim())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service)
        else startService(service)
        prefs.edit().putString("status", "در حال اتصال به Gemini…").apply()
        status.text = "در حال اتصال به Gemini…"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
