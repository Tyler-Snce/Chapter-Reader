package com.chapterreader.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Dark full-screen view of the chapter being read. Highlights the paragraph
 * being spoken, scrolls along with it and shows how far through you are.
 */
class ReadingViewActivity : Activity(), ReaderService.Listener {

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var progressText: TextView
    private lateinit var bar: ProgressBar
    private lateinit var playBtn: TextView
    private lateinit var rateText: TextView

    private val paraViews = ArrayList<TextView>()
    private var builtFor: String? = null
    private var current = -1
    private var lastTouch = 0L
    private var textSize = 19f

    private val prefs by lazy { ReaderService.prefs(this) }
    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = PANEL
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        textSize = prefs.getFloat("viewText", 19f)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // Header: chapter name and progress
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(12), px(18), px(10))
            setBackgroundColor(PANEL)
        }
        titleText = TextView(this).apply {
            textSize = 16f
            setTextColor(TEXT_ON)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        progressText = TextView(this).apply {
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, px(4), 0, px(6))
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF3A3545.toInt())
        }
        header.addView(titleText)
        header.addView(progressText)
        header.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(4)))
        root.addView(header)

        // Chapter text
        scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(20), px(18), px(260))
        }
        scroll.addView(list)
        scroll.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                lastTouch = SystemClock.uptimeMillis()
            }
            false
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Controls
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
            setPadding(px(8), px(8), px(8), px(10))
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("⏮\nChapter") { svc()?.prevChapter() }, weighted())
        row1.addView(button("⏪\nPara") { svc()?.jumpPara(-1) }, weighted())
        playBtn = button("▶", ACCENT) { svc()?.toggle() }.apply { textSize = 22f }
        row1.addView(playBtn, weighted())
        row1.addView(button("⏩\nPara") { svc()?.jumpPara(1) }, weighted())
        row1.addView(button("⏭\nChapter") { svc()?.nextChapter() }, weighted())
        controls.addView(row1)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(6), 0, 0)
        }
        row2.addView(button("−") { svc()?.let { it.setRate(it.rate - 0.1f) } }, small())
        rateText = TextView(this).apply {
            textSize = 14f
            setTextColor(TEXT_ON)
            gravity = Gravity.CENTER
        }
        row2.addView(rateText, LinearLayout.LayoutParams(px(52), px(40)))
        row2.addView(button("+") { svc()?.let { it.setRate(it.rate + 0.1f) } }, small())
        row2.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row2.addView(button("A−") { setText(textSize - 1f) }, small())
        row2.addView(button("A+") { setText(textSize + 1f) }, small())
        row2.addView(button("◎ Follow") { lastTouch = 0; scrollToCurrent(true) },
            LinearLayout.LayoutParams(px(84), px(40)).apply { marginStart = px(6) })
        controls.addView(row2)
        root.addView(controls)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        ReaderService.viewListener = this
        ReaderService.viewVisible = true
        svc()?.updateBubble()
        rebuild()
    }

    override fun onPause() {
        super.onPause()
        ReaderService.viewListener = null
        ReaderService.viewVisible = false
        svc()?.updateBubble()
    }

    private fun svc(): ReaderService? = ReaderService.instance

    // ---------- content ----------

    private fun rebuild() {
        val s = svc()
        val ch = s?.chapter
        if (s == null || ch == null) {
            list.removeAllViews()
            paraViews.clear()
            builtFor = null
            list.addView(TextView(this).apply {
                text = "Nothing is being read yet.\n\nOpen a chapter in your browser and tap the bubble, then come back here."
                textSize = 17f
                setTextColor(MUTED)
            })
            refresh()
            return
        }
        if (ch.url != builtFor) {
            list.removeAllViews()
            paraViews.clear()
            current = -1
            if (ch.heading.isNotEmpty()) {
                list.addView(TextView(this).apply {
                    text = ch.heading
                    textSize = this@ReadingViewActivity.textSize + 4f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(TEXT_ON)
                    setPadding(px(10), 0, px(10), px(16))
                })
            }
            ch.paras.forEachIndexed { i, t ->
                val tv = TextView(this).apply {
                    text = t
                    textSize = this@ReadingViewActivity.textSize
                    setTextColor(TEXT)
                    setLineSpacing(0f, 1.35f)
                    setPadding(px(10), px(8), px(10), px(8))
                    setOnClickListener { svc()?.startAtPara(i) }
                }
                list.addView(tv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = px(4) })
                paraViews.add(tv)
            }
            builtFor = ch.url
            scroll.scrollTo(0, 0)
        }
        highlight(s.currentPara, smooth = false)
        refresh()
    }

    private fun highlight(i: Int, smooth: Boolean) {
        if (i != current) {
            paraViews.getOrNull(current)?.apply {
                background = null
                setTextColor(TEXT)
            }
            current = i
            paraViews.getOrNull(i)?.apply {
                background = GradientDrawable().apply {
                    cornerRadius = 10 * dp
                    setColor(HIGHLIGHT)
                }
                setTextColor(TEXT_ON)
            }
        }
        scrollToCurrent(smooth)
    }

    private fun scrollToCurrent(smooth: Boolean) {
        // Don't fight you if you've just scrolled by hand
        if (SystemClock.uptimeMillis() - lastTouch < 5000) return
        val v = paraViews.getOrNull(current) ?: return
        scroll.post {
            val y = (v.top - scroll.height / 4).coerceAtLeast(0)
            if (smooth) scroll.smoothScrollTo(0, y) else scroll.scrollTo(0, y)
        }
    }

    private fun setText(size: Float) {
        textSize = size.coerceIn(13f, 32f)
        prefs.edit().putFloat("viewText", textSize).apply()
        paraViews.forEach { it.textSize = textSize }
        scrollToCurrent(false)
    }

    private fun refresh() {
        val s = svc()
        titleText.text = when {
            s == null -> "Chapter Reader"
            s.status.isNotEmpty() -> s.status
            else -> s.chapterName
        }
        val total = paraViews.size
        val p = s?.progress ?: 0f
        progressText.text = if (total > 0) {
            String.format(
                Locale.UK, "Paragraph %d of %d · %d%%",
                (current + 1).coerceIn(1, total), total, (p * 100).toInt()
            )
        } else ""
        bar.progress = (p * 1000).toInt()
        playBtn.text = when {
            s?.loading == true -> "…"
            s?.playing == true -> "⏸"
            else -> "▶"
        }
        rateText.text = String.format(Locale.UK, "%.1f×", s?.rate ?: prefs.getFloat("rate", 1f))
    }

    // ---------- service callbacks ----------

    override fun onStateChanged() = refresh()

    override fun onParagraph(index: Int) {
        highlight(index, smooth = true)
        refresh()
    }

    override fun onChapterChanged(url: String) = rebuild()

    override fun onLoadFailed(url: String) = refresh()

    // ---------- helpers ----------

    private fun button(label: String, color: Int = SURFACE, onTap: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setTextColor(TEXT_ON)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = 10 * dp
            setColor(color)
        }
        isClickable = true
        setOnClickListener { onTap() }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, px(54), 1f).apply {
        setMargins(px(3), 0, px(3), 0)
    }

    private fun small() = LinearLayout.LayoutParams(px(46), px(40)).apply {
        setMargins(px(3), 0, px(3), 0)
    }

    companion object {
        private val BG = 0xFF121016.toInt()
        private val PANEL = 0xFF1D1B22.toInt()
        private val SURFACE = 0xFF34303D.toInt()
        private val ACCENT = 0xFF7B5CFF.toInt()
        private val HIGHLIGHT = 0xFF2E2754.toInt()
        private val TEXT = 0xFFB9B3C4.toInt()
        private val TEXT_ON = 0xFFF4F1F8.toInt()
        private val MUTED = 0xFF8E879C.toInt()
    }
}
