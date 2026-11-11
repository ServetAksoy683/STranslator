package com.livesub

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Hat: ekran karesi -> (değişti mi? ucuz örnekleme) -> OCR (cihazda) -> çeviri (cihazda, önbellekli) -> katman.
 * Kare değişmediyse OCR/çeviri hiç çalışmaz; ağ gecikmesi yoktur.
 */
class CaptureService : Service() {

    companion object {
        const val GX = 64
        const val GY = 24
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("livesub-capture").apply { start() }
    private val handler by lazy { Handler(thread.looper) }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private lateinit var recognizer: TextRecognizer
    private lateinit var translator: Translator
    private val cache = LruCache<String, String>(300)

    private lateinit var frame: View
    private lateinit var bar: LinearLayout
    private lateinit var label: TextView
    private lateinit var fp: WindowManager.LayoutParams
    private lateinit var bp: WindowManager.LayoutParams
    private var sw = 0
    private var sh = 0

    // Yalnızca capture thread'inde kullanılır
    private var scratch: Bitmap? = null
    private var lastSig: IntArray? = null
    private var busy = false
    private var pending: Bitmap? = null

    // Yalnızca main thread'inde kullanılır
    private var lastText = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        startForegroundCompat()

        val src = intent.getStringExtra("src") ?: "en"
        val tgt = intent.getStringExtra("tgt") ?: "tr"
        val data = intent.getParcelableExtra<Intent>("data")
        val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        recognizer = recognizerFor(src)
        translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
        )
        buildOverlay()

        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { ui.post { stopSelf() } }
            }, handler)
        }

        label.text = "Dil modeli hazırlanıyor…"
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                label.text = ""
                handler.post { attachReader(sw, sh) }
            }
            .addOnFailureListener { label.text = "Model indirilemedi. İnternet bağlantını kontrol et." }
        return START_NOT_STICKY
    }

    // ---------- Yakalama ----------

    private fun attachReader(w: Int, h: Int) {
        val proj = projection ?: return
        val dpi = resources.displayMetrics.densityDpi
        val old = reader
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onFrame(it) }, handler)
        reader = r
        val v = vd
        if (v == null) {
            vd = proj.createVirtualDisplay(
                "livesub", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
        } else {
            v.resize(w, h, dpi)
            v.surface = r.surface
        }
        old?.close()
        scratch = null
        lastSig = null
    }

    private fun onFrame(r: ImageReader) {
        val img = (try { r.acquireLatestImage() } catch (e: Exception) { null }) ?: return
        try {
            val inset = dp(5) // kenarlığı OCR'a sokma
            val rect = Rect(fp.x + inset, fp.y + inset, fp.x + fp.width - inset, fp.y + fp.height - inset)
            if (!rect.intersect(0, 0, img.width, img.height) || rect.width() < 16 || rect.height() < 8) return

            val p = img.planes[0]
            val sig = signature(p.buffer, p.rowStride, p.pixelStride, rect)
            if (!changed(lastSig, sig)) return
            lastSig = sig

            val bmp = cropBitmap(img, rect)
            if (busy) { pending?.recycle(); pending = bmp } else process(bmp)
        } finally {
            img.close()
        }
    }

    /** Bölgeden 64x24 ızgara örnekler (parlaklık). Bitmap oluşturmadan, çok ucuz. */
    private fun signature(buf: ByteBuffer, rowStride: Int, pixStride: Int, r: Rect): IntArray {
        val sig = IntArray(GX * GY)
        for (j in 0 until GY) {
            val y = r.top + r.height() * (2 * j + 1) / (2 * GY)
            for (i in 0 until GX) {
                val x = r.left + r.width() * (2 * i + 1) / (2 * GX)
                val o = y * rowStride + x * pixStride
                val rr = buf.get(o).toInt() and 0xFF
                val g = buf.get(o + 1).toInt() and 0xFF
                val b = buf.get(o + 2).toInt() and 0xFF
                sig[j * GX + i] = (rr * 299 + g * 587 + b * 114) / 1000
            }
        }
        return sig
    }

    private fun changed(a: IntArray?, b: IntArray): Boolean {
        if (a == null) return true
        var n = 0
        for (i in b.indices) if (abs(a[i] - b[i]) > 24) n++
        return n >= 5
    }

    private fun cropBitmap(img: Image, rect: Rect): Bitmap {
        val p = img.planes[0]
        val w = p.rowStride / p.pixelStride
        var s = scratch
        if (s == null || s.width != w || s.height != img.height) {
            s = Bitmap.createBitmap(w, img.height, Bitmap.Config.ARGB_8888)
            scratch = s
        }
        p.buffer.rewind()
        s.copyPixelsFromBuffer(p.buffer)
        return Bitmap.createBitmap(s, rect.left, rect.top, rect.width(), rect.height())
    }

    // ---------- OCR + çeviri ----------

    private fun process(bmp: Bitmap) {
        busy = true
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { res -> handleText(res.text) }
            .addOnFailureListener { release() }
            .addOnCompleteListener { bmp.recycle() }
    }

    private fun handleText(raw: String) {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text == lastText) { release(); return }
        lastText = text
        if (text.isEmpty()) { label.text = ""; release(); return }
        cache.get(text)?.let { label.text = it; release(); return }
        translator.translate(text)
            .addOnSuccessListener { t ->
                cache.put(text, t)
                if (text == lastText) label.text = t
            }
            .addOnCompleteListener { release() }
    }

    /** Meşgul bayrağını bırak; bu arada yeni kare geldiyse hemen onu işle (son durum kaçmaz). */
    private fun release() {
        handler.post {
            busy = false
            pending?.let { pending = null; process(it) }
        }
    }

    private fun recognizerFor(lang: String): TextRecognizer = when (lang) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // ---------- Katman (overlay) ----------

    private fun overlayParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun realSize(): Point =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }

    private fun buildOverlay() {
        val s = realSize(); sw = s.x; sh = s.y

        // Seçim çerçevesi: dokunuşları geçirir, altındaki uygulamayı engellemez.
        frame = View(this).apply {
            background = GradientDrawable().apply {
                setStroke(dp(2), 0xFF3DDC84.toInt())
                cornerRadius = dp(10).toFloat()
            }
        }
        val w = (sw * 0.8f).toInt()
        fp = overlayParams(w, dp(90), false).apply { x = (sw - w) / 2; y = (sh * 0.7f).toInt() }

        label = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 17f
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END
            hint = "Altyazı bekleniyor…"; setHintTextColor(0x88FFFFFF.toInt())
            setPadding(dp(14), dp(10), dp(8), dp(10))
            setOnTouchListener(dragger())
        }
        fun btn(t: String) = TextView(this).apply {
            text = t; textSize = 18f; setTextColor(0xFFB8C2CF.toInt()); gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val grip = btn("⤡").apply { setOnTouchListener(resizer()) }
        val close = btn("✕").apply { setOnClickListener { stopSelf() } }

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(0xE6141A22.toInt()); cornerRadius = dp(14).toFloat() }
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(grip); addView(close)
        }
        bp = overlayParams(w, WindowManager.LayoutParams.WRAP_CONTENT, true)

        wm.addView(frame, fp)
        wm.addView(bar, bp)
        layoutAll()
    }

    private fun layoutAll() {
        bp.x = fp.x; bp.y = fp.y + fp.height + dp(6); bp.width = fp.width
        wm.updateViewLayout(frame, fp)
        wm.updateViewLayout(bar, bp)
    }

    /** Çeviri çubuğundan sürükleyerek çerçeveyi taşı. */
    private fun dragger() = object : View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = fp.x; oy = fp.y }
                MotionEvent.ACTION_MOVE -> {
                    fp.x = (ox + e.rawX - sx).toInt().coerceIn(0, maxOf(0, sw - fp.width))
                    fp.y = (oy + e.rawY - sy).toInt().coerceIn(0, maxOf(0, sh - fp.height - dp(110)))
                    layoutAll(); lastSig = null
                }
            }
            return true
        }
    }

    /** ⤡ tutamacıyla çerçeveyi yeniden boyutlandır. */
    private fun resizer() = object : View.OnTouchListener {
        var sx = 0f; var sy = 0f; var w = 0; var h = 0
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; w = fp.width; h = fp.height }
                MotionEvent.ACTION_MOVE -> {
                    fp.width = (w + e.rawX - sx).toInt().coerceIn(dp(120), maxOf(dp(120), sw - fp.x))
                    fp.height = (h + e.rawY - sy).toInt().coerceIn(dp(48), maxOf(dp(48), sh - fp.y - dp(110)))
                    layoutAll(); lastSig = null
                }
            }
            return true
        }
    }

    // ---------- Yaşam döngüsü ----------

    /** Ekran döndürülünce (video için yatay) yakalamayı ve çerçeveyi yeniden ayarla. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (reader == null) return
        val s = realSize()
        if (s.x == sw && s.y == sh) return
        sw = s.x; sh = s.y
        fp.width = fp.width.coerceAtMost(sw - dp(16))
        fp.x = fp.x.coerceIn(0, maxOf(0, sw - fp.width))
        fp.y = fp.y.coerceIn(0, maxOf(0, sh - fp.height - dp(110)))
        layoutAll()
        handler.post { attachReader(s.x, s.y) }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("livesub", "LiveSub", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "livesub")
            .setContentTitle("LiveSub çalışıyor")
            .setContentText("Seçili alan çevriliyor")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        runCatching { wm.removeView(frame) }
        runCatching { wm.removeView(bar) }
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        runCatching { translator.close() }
        runCatching { recognizer.close() }
        thread.quitSafely()
        super.onDestroy()
    }
}
