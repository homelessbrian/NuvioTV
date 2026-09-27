package com.nuvio.tv.livetv.crash

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.system.exitProcess

/**
 * Plain Android views only (no Compose, no Hilt), and it runs in its own process, so it still
 * works when the crash came from the app's own UI.
 */
class CrashReportActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = intent.getStringExtra(CrashCatcher.EXTRA_REPORT)
            ?: CrashCatcher.lastReport(this)
            ?: "No crash details were saved."

        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

        val title = TextView(this).apply {
            text = "Nuvio w/ Live TV stopped"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val hint = TextView(this).apply {
            text = "Take a photo of this screen (mainly the ROOT CAUSE part) and send it to whoever maintains this build. Use up/down to scroll."
            setTextColor(Color.parseColor("#B0B0B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, dp(12))
        }
        val body = TextView(this).apply {
            text = report
            setTextColor(Color.parseColor("#E6E6EA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(false)
        }
        val scroll = ScrollView(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.parseColor("#16161A"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(body)
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> { smoothScrollBy(0, dp(120)); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { smoothScrollBy(0, -dp(120)); true }
                    else -> false
                }
            }
        }
        val restart = Button(this).apply {
            text = "Restart app"
            setOnClickListener {
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
                finish()
                exitProcess(0)
            }
        }
        val close = Button(this).apply {
            text = "Close"
            setOnClickListener { finish(); exitProcess(0) }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
            addView(restart)
            addView(close)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0D"))
            setPadding(dp(48), dp(28), dp(48), dp(24))
            addView(title)
            addView(hint)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(buttons)
        }
        setContentView(root)
        scroll.requestFocus()
        scroll.nextFocusDownId = View.NO_ID
    }
}
