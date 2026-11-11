package com.livesub

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {

    private val REQ = 42
    private lateinit var src: Spinner
    private lateinit var tgt: Spinner

    private val sources = listOf(
        "English" to "en", "Deutsch" to "de", "Français" to "fr", "Español" to "es",
        "日本語" to "ja", "한국어" to "ko", "中文" to "zh"
    )
    private val targets = listOf(
        "Türkçe" to "tr", "English" to "en", "Deutsch" to "de", "Français" to "fr", "Español" to "es"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTextColor(0xFF9AA4B2.toInt()); setPadding(0, pad / 2, 0, 6)
        }
        fun spinner(items: List<Pair<String, String>>) = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items.map { it.first })
        }

        src = spinner(sources)
        tgt = spinner(targets)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(0xFF0F1318.toInt())
            addView(TextView(context).apply {
                text = "LiveSub"; textSize = 32f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(context).apply {
                text = "Ekranda seçtiğin alanı anlık çevirir."; textSize = 15f; setTextColor(0xFF9AA4B2.toInt())
            })
            addView(label("Kaynak dil")); addView(src)
            addView(label("Hedef dil")); addView(tgt)
            addView(Button(context).apply {
                text = "Başlat"; gravity = Gravity.CENTER
                setOnClickListener { start() }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad })
        }
        setContentView(root)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra("src", sources[src.selectedItemPosition].second)
                    .putExtra("tgt", targets[tgt.selectedItemPosition].second)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
            )
            moveTaskToBack(true)
        }
    }
}
