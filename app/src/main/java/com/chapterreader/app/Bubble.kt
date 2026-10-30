package com.chapterreader.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

/**
 * Floating bubble shown over other apps while reading.
 * Tap: play/pause. Long-press: more controls. Drag: move it.
 */
class Bubble(private val svc: ReaderService) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = svc.resources.displayMetrics.density
    private val prefs = ReaderService.prefs(svc)
    private val handler = Handler(Looper.getMainLooper())

    private val root = LinearLayout(svc)
    private val ball = TextView(svc)
    private val panel = LinearLayout(svc)
    private val rateLabel = TextView(svc)
    private var shown = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = prefs.getInt("bx", (8 * dp).toInt())
        y = prefs.getInt("by", (300 * dp).toInt())
    }

    private fun px(v: Int) = (v * dp).toInt()

    init {
        root.orientation = LinearLayout.VERTICAL

        ball.text = "▶"
        ball.textSize = 22f
        ball.setTextColor(0xFFFFFFFF.toInt())
        ball.gravity = Gravity.CENTER
        ball.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xEE6B4FD8.toInt())
            setStroke(px(2), 0x55FFFFFF)
        }
        ball.elevation = 8 * dp
        root.addView(ball, LinearLayout.LayoutParams(px(58), px(58)))

        panel.orientation = LinearLayout.VERTICAL
        panel.visibility = View.GONE
        panel.setPadding(px(6), px(6), px(6), px(6))
        panel.background = GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(0xF01D1B22.toInt())
        }
        val row1 = row()
        row1.addView(button("⏮") { svc.prevChapter() })
        row1.addView(button("⏪") { svc.jumpPara(-1) })
        row1.addView(button("⏩") { svc.jumpPara(1) })
        row1.addView(button("⏭") { svc.nextChapter() })
        val row2 = row()
        row2.addView(button("−") { svc.setRate(svc.rate - 0.1f) })
        rateLabel.setTextColor(0xFFFFFFFF.toInt())
        rateLabel.textSize = 14f
        rateLabel.gravity = Gravity.CENTER
        row2.addView(rateLabel, LinearLayout.LayoutParams(px(48), px(44)))
        row2.addView(button("+") { svc.setRate(svc.rate + 0.1f) })
        row2.addView(button("↗") { panel.visibility = View.GONE; svc.openApp() })
        row2.addView(button("✕") {
            Toast.makeText(svc, "Bubble off. Turn it back on in the app.", Toast.LENGTH_SHORT).show()
            svc.hideBubbleForNow()
        })
        panel.addView(row1)
        panel.addView(row2)
        root.addView(panel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = px(6) })

        setupTouch()
    }

    private fun row(): LinearLayout = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }

    private fun button(label: String, onTap: () -> Unit): TextView = TextView(svc).apply {
        text = label
        textSize = 18f
        setTextColor(0xFFFFFFFF.toInt())
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = 10 * dp
            setColor(0xFF34303D.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(px(48), px(44)).apply {
            setMargins(px(3), px(3), px(3), px(3))
        }
        setOnClickListener { onTap() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressed = false
        val slop = 10 * dp
        val longPress = Runnable {
            longPressed = true
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            update()
        }

        ball.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false; longPressed = false
                    handler.postDelayed(longPress, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        if (shown) wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (dragging) {
                        prefs.edit().putInt("bx", params.x).putInt("by", params.y).apply()
                    } else if (!longPressed) {
                        svc.toggle()
                        if (!prefs.getBoolean("bubbleTipShown", false)) {
                            prefs.edit().putBoolean("bubbleTipShown", true).apply()
                            Toast.makeText(svc, "Tip: long-press the bubble for more controls", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            }
            true
        }
    }

    fun show() {
        if (shown) return
        try {
            wm.addView(root, params)
            shown = true
        } catch (_: Exception) {}
        update()
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        if (!shown) return
        try { wm.removeView(root) } catch (_: Exception) {}
        shown = false
    }

    fun update() {
        ball.text = when {
            svc.loading -> "…"
            svc.playing -> "⏸"
            else -> "▶"
        }
        rateLabel.text = String.format(Locale.UK, "%.1f×", svc.rate)
    }
}
