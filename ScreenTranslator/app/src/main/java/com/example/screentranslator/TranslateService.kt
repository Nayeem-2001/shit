package com.example.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** Floating button -> screenshot -> Korean OCR -> translate -> draw translations over the original text. */
class TranslateService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private lateinit var wm: WindowManager
    private lateinit var projection: MediaProjection
    private lateinit var vd: VirtualDisplay
    private lateinit var reader: ImageReader
    private lateinit var translator: Translator
    private lateinit var button: TextView
    private var overlay: View? = null
    private var latest: Image? = null   // newest screen frame, kept so static screens still work
    private var ready = false
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("st", "Screen Translator", NotificationManager.IMPORTANCE_LOW))
        val note = Notification.Builder(this, "st")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Screen Translator is running")
            .setContentText("Long-press the floating button to stop")
            .build()
        // Must be a mediaProjection foreground service BEFORE getMediaProjection() on Android 14+.
        startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (intent == null || data == null || ::reader.isInitialized) {
            if (!::reader.isInitialized) stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(WindowManager::class.java)
        val b = wm.maximumWindowMetrics.bounds
        val w = b.width()
        val h = b.height()

        projection = getSystemService(MediaProjectionManager::class.java)
            .getMediaProjection(intent.getIntExtra("code", 0), data)
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, main)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.let { latest?.close(); latest = it }
        }, main)
        // Android 14+ allows only one VirtualDisplay per projection, so create it once and reuse it.
        vd = projection.createVirtualDisplay(
            "st", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
        )

        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.KOREAN)
                .setTargetLanguage(intent.getStringExtra("lang") ?: TranslateLanguage.ENGLISH)
                .build()
        )
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { ready = true; toast("Translator ready") }
            .addOnFailureListener { toast("Language model download failed - check your internet") }

        button = TextView(this).apply {
            text = "번역"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC1565C0.toInt())
            setPadding(32, 24, 32, 24)
            setOnClickListener { if (overlay != null) clearOverlay() else capture() }
            setOnLongClickListener { stopSelf(); true }
        }
        wm.addView(button, LP(LP.WRAP_CONTENT, LP.WRAP_CONTENT, LP.TYPE_APPLICATION_OVERLAY, LP.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        return START_NOT_STICKY
    }

    private fun capture() {
        if (busy) return
        if (!ready) { toast("Language model is still downloading..."); return }
        busy = true
        button.visibility = View.INVISIBLE            // keep our own button out of the screenshot
        main.postDelayed({
            val img = latest
            latest = null
            if (img == null) { done("No frame captured - try again"); return@postDelayed }
            val p = img.planes[0]
            val pad = (p.rowStride - p.pixelStride * img.width) / p.pixelStride
            val full = Bitmap.createBitmap(img.width + pad, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(p.buffer)
            val bmp = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
            img.close()
            recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { translateBlocks(it) }
                .addOnFailureListener { done("Text recognition failed") }
        }, 350)
    }

    private fun translateBlocks(text: Text) {
        val blocks = text.textBlocks.filter { blk ->
            blk.boundingBox != null && blk.text.any { it in '\uAC00'..'\uD7A3' }   // only blocks containing Hangul
        }
        if (blocks.isEmpty()) { done("No Korean text found"); return }
        Tasks.whenAllSuccess<String>(blocks.map { translator.translate(it.text) })
            .addOnSuccessListener { out ->
                showOverlay(blocks.map { it.boundingBox!! }.zip(out))
                done(null)
            }
            .addOnFailureListener { done("Translation failed") }
    }

    private fun showOverlay(items: List<Pair<Rect, String>>) {
        val root = FrameLayout(this)
        root.setOnClickListener { clearOverlay() }
        items.forEach { (r, t) ->
            val tv = TextView(this).apply {
                text = t
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE6000000.toInt())
                setPadding(6, 2, 6, 2)
                setAutoSizeTextTypeUniformWithConfiguration(8, 18, 1, TypedValue.COMPLEX_UNIT_SP)
            }
            root.addView(tv, FrameLayout.LayoutParams(r.width(), r.height()).apply { leftMargin = r.left; topMargin = r.top })
        }
        val lp = LP(
            LP.MATCH_PARENT, LP.MATCH_PARENT, LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(root, lp)
        overlay = root
    }

    private fun clearOverlay() { overlay?.let { wm.removeView(it) }; overlay = null }
    private fun done(msg: String?) { busy = false; button.visibility = View.VISIBLE; msg?.let { toast(it) } }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        clearOverlay()
        if (::button.isInitialized) wm.removeView(button)
        latest?.close()
        if (::vd.isInitialized) vd.release()
        if (::reader.isInitialized) reader.close()
        if (::projection.isInitialized) projection.stop()
        if (::translator.isInitialized) translator.close()
        recognizer.close()
        super.onDestroy()
    }
}
