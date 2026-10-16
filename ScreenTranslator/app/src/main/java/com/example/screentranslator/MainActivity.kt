package com.example.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private val langs = listOf("English" to "en", "Bengali" to "bn", "Spanish" to "es", "Japanese" to "ja", "Chinese" to "zh")
    private lateinit var spinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)

        val info = TextView(this).apply {
            text = "Translate Korean on your screen into:\n"
            textSize = 16f
        }
        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, langs.map { it.first })
        }
        val start = Button(this).apply {
            text = "Start floating translator"
            setOnClickListener { start() }
        }
        val help = TextView(this).apply {
            text = "\nThen open any app with Korean text and tap the floating 번역 button. " +
                "Tap the result to dismiss it. Long-press the button to stop."
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
            addView(info); addView(spinner); addView(start); addView(help)
        })
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            // Grant "Display over other apps", come back, and tap Start again.
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Plain Activity keeps this project dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, TranslateService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                    .putExtra("lang", langs[spinner.selectedItemPosition].second)
            )
            finish()
        }
    }
}
