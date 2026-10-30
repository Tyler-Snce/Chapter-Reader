package com.chapterreader.app

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Finds the address of the page open in your browser (Chrome, Samsung Internet,
 * Edge, Brave, Firefox, Opera...) when you tap the bubble.
 */
class BrowserWatcher : AccessibilityService() {

    companion object {
        var instance: BrowserWatcher? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return s.split(':').any { it.startsWith(ctx.packageName + "/") }
        }

        private val URL_BAR_IDS = listOf(
            "url_bar",                          // Chrome, Edge, Brave, Vivaldi
            "location_bar_edit_text",           // Samsung Internet
            "mozac_browser_toolbar_url_view",   // Firefox
            "url_field",                        // Opera
            "address_bar_edit_text",
            "url",
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // The accessibility button / shortcut brings the bubble back
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(
                object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController) {
                        val svc = ReaderService.instance
                        if (svc != null) {
                            svc.showBubble()
                        } else if (Settings.canDrawOverlays(this@BrowserWatcher)) {
                            ReaderService.prefs(this@BrowserWatcher).edit().putBoolean("bubble", true).apply()
                            try {
                                startForegroundService(
                                    Intent(this@BrowserWatcher, ReaderService::class.java)
                                        .setAction(ReaderService.ACTION_SHOW_BUBBLE)
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }
            )
        } catch (_: Exception) {}
        val prefs = ReaderService.prefs(this)
        if (prefs.getBoolean("bubble", true) && Settings.canDrawOverlays(this)) {
            try {
                startForegroundService(
                    Intent(this, ReaderService::class.java).setAction(ReaderService.ACTION_IDLE)
                )
            } catch (_: Exception) {}
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun findUrlNode(): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { rootInActiveWindow?.let { roots.add(it) } } catch (_: Exception) {}
        try {
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) w.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {}
        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == packageName) continue
            for (id in URL_BAR_IDS) {
                val found = try { root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") } catch (_: Exception) { null }
                if (!found.isNullOrEmpty()) return found[0]
            }
        }
        return null
    }

    private fun toUrl(text: CharSequence?, needPath: Boolean): String? {
        val s = text?.toString()?.trim() ?: return null
        if (s.isEmpty() || s.contains(' ')) return null
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (!s.contains('.')) return null
        if (needPath && !s.contains('/')) return null
        return "https://$s"
    }

    /** Works out the browser's current page address and passes it to [done] (null if none). */
    fun currentUrl(done: (String?) -> Unit) {
        val node = findUrlNode()
        if (node == null) { done(null); return }
        toUrl(node.text, needPath = true)?.let { done(it); return }

        // The address bar only shows the site name. Tapping it reveals the full
        // address; read that, then close the address bar again.
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            val full = findUrlNode()?.let { toUrl(it.text, needPath = false) }
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({
                if (findUrlNode()?.isFocused == true) performGlobalAction(GLOBAL_ACTION_BACK)
                done(full)
            }, 300)
        }, 500)
    }
}
