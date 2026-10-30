package com.chapterreader.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible screen that receives "Share → Chapter Reader" from any browser,
 * starts reading that page in the background and closes straight away.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString
        val url = text?.let { Regex("https?://\\S+").find(it)?.value }
        if (url == null) {
            Toast.makeText(this, "No web link found to read", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Chapter Reader: loading…", Toast.LENGTH_SHORT).show()
            val i = Intent(this, ReaderService::class.java)
                .setAction(ReaderService.ACTION_LOAD_URL)
                .putExtra(ReaderService.EXTRA_URL, url)
            startForegroundService(i)
        }
        finish()
    }
}
