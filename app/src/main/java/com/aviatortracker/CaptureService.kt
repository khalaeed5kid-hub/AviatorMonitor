package com.aviatortracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "tracker"
        private const val TICK_MS = 500L
        const val PREFS = "tracker"
        const val PREF_REGION = "region"

        fun loadRegion(ctx: Context): Rect? {
            val s = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_REGION, null) ?: return null
            val p = s.split(',').mapNotNull { it.toIntOrNull() }
            return if (p.size == 4) Rect(p[0], p[1], p[2], p[3]) else null
        }
    }

    private lateinit var wm: WindowManager
    private lateinit var db: ResultsDb
    private lateinit var bg: Handler
    private val thread = HandlerThread("capture").apply { start() }
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var button: TextView? = null
    private var selector: RegionSelectView? = null
    private var started = false
    private var screenW = 0
    private var screenH = 0

    @Volatile private var region: Rect? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private val tracker = RoundTracker { result ->
        db.add(result)
        Diag.lastSaved = result
        Diag.savedCount++
    }

    private val tick = object : Runnable {
        override fun run() {
            try { processFrame() } catch (t: Throwable) { Diag.error = t.javaClass.simpleName + ": " + t.message }
            bg.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        db = ResultsDb(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bg = Handler(thread.looper)

        startForegroundCompat()

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        vd = projection?.createVirtualDisplay(
            "aviator-cap", screenW, screenH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )

        region = loadRegion(this)
        Diag.region = region?.toShortString() ?: "غير محددة"
        Diag.running = true
        Diag.error = ""
        addButton()
        bg.post(tick)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aviator Tracker", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Aviator Tracker")
            .setContentText("الخدمة تعمل — قراءة منطقة المضاعف")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- الأيقونة العائمة (قابلة للسحب، والنقر يفتح تحديد المنطقة) ----------
    private fun addButton() {
        val tv = TextView(this).apply {
            text = "◎"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setBackgroundColor(0xDD1565C0.toInt())
            setPadding(28, 20, 28, 20)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 400 }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
                    if (moved) { lp.x = startX + dx; lp.y = startY + dy; wm.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) showSelector()
            }
            true
        }
        wm.addView(tv, lp)
        button = tv
    }

    private fun showSelector() {
        if (selector != null) return
        val v = RegionSelectView(this) { rect ->
            selector?.let { wm.removeView(it) }
            selector = null
            if (rect != null) {
                region = rect
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_REGION, "${rect.left},${rect.top},${rect.right},${rect.bottom}").apply()
                Diag.region = rect.toShortString()
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, lp)
        selector = v
    }

    // ---------- التقاط وتحليل (كل 500ms، في الذاكرة فقط) ----------
    private fun processFrame() {
        val r = region
        if (r == null) { Diag.rawText = "(حدّد المنطقة بالضغط على الأيقونة العائمة)"; return }
        val img = reader?.acquireLatestImage() ?: return   // لا إطار جديد (الشاشة لم تتغير)

        var full: Bitmap? = null
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val plane = img.planes[0]
            val ps = plane.pixelStride
            val rs = plane.rowStride
            full = Bitmap.createBitmap(rs / ps, screenH, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            img.close()

            val x = r.left.coerceIn(0, screenW - 2)
            val y = r.top.coerceIn(0, screenH - 2)
            val w = r.width().coerceIn(2, screenW - x)
            val h = r.height().coerceIn(2, screenH - y)
            crop = Bitmap.createBitmap(full, x, y, w, h)
            full.recycle(); full = null

            // كشف الصورة السوداء (اللعبة قد تمنع التقاط الشاشة FLAG_SECURE)
            val tiny = Bitmap.createScaledBitmap(crop, 6, 6, false)
            var allBlack = true
            for (i in 0 until 6) for (j in 0 until 6) {
                val p = tiny.getPixel(i, j)
                if ((p and 0x00FFFFFF) != 0) allBlack = false
            }
            tiny.recycle()
            if (allBlack) {
                Diag.blankFrames++
                Diag.error = "صورة سوداء: ربما اللعبة تمنع التقاط الشاشة"
            } else if (Diag.error.startsWith("صورة سوداء")) {
                Diag.error = ""
            }

            val factor = if (h < 120) 3 else 1
            scaled = if (factor > 1) Bitmap.createScaledBitmap(crop, w * factor, h * factor, true) else crop

            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(scaled, 0))).text
            val v = MultiplierParser.parse(text)
            tracker.onFrame(v)

            Diag.frames++
            Diag.rawText = text.replace('\n', ' ')
            Diag.value = v
            Diag.state = tracker.state
            Diag.pending = tracker.lastValid
            Diag.crop = crop.copy(Bitmap.Config.ARGB_8888, false)

            val label = if (tracker.state == RoundState.FLYING) "%.2fx".format(java.util.Locale.US, tracker.lastValid ?: 0.0) else "◎"
            main.post { button?.text = label }
        } finally {
            try { img.close() } catch (_: Throwable) {}
            if (scaled != null && scaled !== crop) scaled.recycle()
            crop?.recycle()
            full?.recycle()
        }
    }

    override fun onDestroy() {
        Diag.running = false
        bg.removeCallbacksAndMessages(null)
        thread.quitSafely()
        main.post {
            try { button?.let { wm.removeView(it) } } catch (_: Throwable) {}
            try { selector?.let { wm.removeView(it) } } catch (_: Throwable) {}
        }
        vd?.release()
        reader?.close()
        projection?.stop()
        try { recognizer.close() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
