package com.aviatortracker

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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * أداة صافي أموال الشركة — خدمة مستقلة تمامًا عن CaptureService (إذن التقاط خاص بها،
 * قاعدة بيانات خاصة، إعدادات خاصة). لا تشترك مع الأداة القديمة في أي حالة.
 */
class HouseService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "house"
        private const val TICK_MS = 500L
    }

    private lateinit var wm: WindowManager
    private lateinit var db: HouseDb
    private lateinit var bg: Handler
    private val thread = HandlerThread("house-capture").apply { start() }
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var panel: LinearLayout? = null
    private var balanceView: TextView? = null
    private var statusView: TextView? = null
    private var selector: RegionSelectView? = null
    private var started = false
    private var screenW = 0
    private var screenH = 0
    private var wasPaused = false

    @Volatile private var reg1: Rect? = null
    @Volatile private var reg2: Rect? = null
    @Volatile private var balance = 0L
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private lateinit var tracker: HouseTracker

    private val tick = object : Runnable {
        override fun run() {
            try { processFrame() } catch (t: Throwable) { HouseDiag.error = t.javaClass.simpleName + ": " + t.message }
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
        db = HouseDb(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bg = Handler(thread.looper)
        tracker = HouseTracker(winsCumulative = HousePrefs.cumulative(this)) { round ->
            db.add(HousePrefs.session(this), round)
            balance = db.balance(HousePrefs.session(this))
            HouseDiag.lastRound = round
            HouseDiag.savedCount++
            main.post { refreshPanel() }
        }

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
            "house-cap", screenW, screenH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )

        reg1 = HousePrefs.region(this, 1)
        reg2 = HousePrefs.region(this, 2)
        balance = db.balance(HousePrefs.session(this))
        HouseDiag.running = true
        HouseDiag.error = ""
        addPanel()
        refreshPanel()
        bg.post(tick)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aviator Tracker - صافي الشركة", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("صافي أموال الشركة")
            .setContentText("المراقبة تعمل")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, n)
        }
    }

    // ---------- اللوحة العائمة: الرصيد + أزرار ①  ②  ⏯ ----------
    private fun smallBtn(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setBackgroundColor(color)
        setPadding(22, 10, 22, 10)
        setOnClickListener { onClick() }
    }

    private fun addPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(14, 8, 14, 8)
        }
        val bal = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        }
        val st = TextView(this).apply {
            setTextColor(0xFFCCCCCC.toInt()); textSize = 10f; gravity = Gravity.CENTER
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val gap = {
            android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(10, 1) }
        }
        row.addView(smallBtn("1", 0xFF1565C0.toInt()) { startSelection(1, true) })
        row.addView(gap())
        row.addView(smallBtn("2", 0xFF2E7D32.toInt()) { startSelection(2, false) })
        row.addView(gap())
        row.addView(smallBtn("⏯", 0xFF6A1B9A.toInt()) {
            HousePrefs.setPaused(this, !HousePrefs.paused(this)); refreshPanel()
        })
        box.addView(bal); box.addView(st); box.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 120; y = 120 }

        // السحب من منطقة الرصيد فقط حتى لا تتعارض مع الأزرار
        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0
        bal.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - downX).toInt(); lp.y = sy + (e.rawY - downY).toInt()
                    wm.updateViewLayout(box, lp)
                }
            }
            true
        }
        wm.addView(box, lp)
        panel = box; balanceView = bal; statusView = st
    }

    private fun refreshPanel() {
        val bal = balanceView ?: return
        bal.text = "الشركة: " + AmountParser.format(balance, signed = true)
        bal.setTextColor(if (balance < 0) 0xFFFF5252.toInt() else 0xFF69F0AE.toInt())
        val ready = reg1 != null && reg2 != null
        statusView?.text = when {
            !ready -> "① رهانات ${if (reg1 != null) "✓" else "✗"}  ② أرباح ${if (reg2 != null) "✓" else "✗"} — حدّد المنطقتين"
            HousePrefs.paused(this) -> "متوقفة مؤقتًا"
            else -> "تعمل ● ①✓ ②✓"
        }
    }

    // ---------- تحديد المستطيلين على مرحلتين ----------
    private fun startSelection(index: Int, thenSecond: Boolean) {
        if (selector != null) return
        val title = if (index == 1) "① ارسم مستطيل «مجموع الرهانات»" else "② ارسم مستطيل «مجموع الأرباح»"
        val v = RegionSelectView(this, title) { rect ->
            selector?.let { wm.removeView(it) }
            selector = null
            if (rect != null) {
                HousePrefs.setRegion(this, index, rect)
                if (index == 1) reg1 = rect else reg2 = rect
                tracker.resetState()
                refreshPanel()
                if (thenSecond) {
                    Toast.makeText(this, "تم حفظ ①. الآن ارسم المستطيل ② (مجموع الأرباح)", Toast.LENGTH_LONG).show()
                    main.postDelayed({ startSelection(2, false) }, 700)
                } else if (reg1 != null && reg2 != null) {
                    Toast.makeText(this, "المنطقتان جاهزتان — بدأت المراقبة", Toast.LENGTH_SHORT).show()
                }
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

    // ---------- التقاط وتحليل ----------
    private fun readRegion(full: Bitmap, r: Rect): Pair<String, Bitmap> {
        val x = r.left.coerceIn(0, screenW - 2)
        val y = r.top.coerceIn(0, screenH - 2)
        val w = r.width().coerceIn(2, screenW - x)
        val h = r.height().coerceIn(2, screenH - y)
        val crop = Bitmap.createBitmap(full, x, y, w, h)
        val factor = if (h < 120) 3 else 1
        val scaled = if (factor > 1) Bitmap.createScaledBitmap(crop, w * factor, h * factor, true) else crop
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(scaled, 0))).text
            return text to crop.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            if (scaled !== crop) scaled.recycle()
            crop.recycle()
        }
    }

    private fun processFrame() {
        val r1 = reg1
        val r2 = reg2
        val paused = HousePrefs.paused(this)
        val ready = r1 != null && r2 != null
        tracker.winsCumulative = HousePrefs.cumulative(this)

        if (!ready || paused) {
            reader?.acquireLatestImage()?.close()   // نفرّغ الإطارات حتى لا تتكدس
            if (paused && !wasPaused) tracker.resetState()
            wasPaused = paused
            HouseDiag.active = tracker.active
            main.post { refreshPanel() }
            return
        }
        if (wasPaused) { tracker.resetState(); wasPaused = false }

        val img = reader?.acquireLatestImage() ?: return
        var full: Bitmap? = null
        try {
            val plane = img.planes[0]
            full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, screenH, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            img.close()

            val (t1, c1) = readRegion(full, r1!!)
            val (t2, c2) = readRegion(full, r2!!)
            full.recycle(); full = null

            val b = AmountParser.parseCents(t1)
            val w = AmountParser.parseCents(t2)
            tracker.onFrame(b, w)

            HouseDiag.frames++
            HouseDiag.rawBets = t1.replace('\n', ' ')
            HouseDiag.rawWins = t2.replace('\n', ' ')
            HouseDiag.bets = b
            HouseDiag.wins = w
            HouseDiag.active = tracker.active
            HouseDiag.maxBets = tracker.maxBets
            HouseDiag.maxWins = tracker.maxWins
            HouseDiag.crop1 = c1
            HouseDiag.crop2 = c2
            HouseDiag.error = ""
        } finally {
            try { img.close() } catch (_: Throwable) {}
            full?.recycle()
        }
    }

    override fun onDestroy() {
        HouseDiag.running = false
        bg.removeCallbacksAndMessages(null)
        thread.quitSafely()
        main.post {
            try { panel?.let { wm.removeView(it) } } catch (_: Throwable) {}
            try { selector?.let { wm.removeView(it) } } catch (_: Throwable) {}
        }
        vd?.release()
        reader?.close()
        projection?.stop()
        try { recognizer.close() } catch (_: Throwable) {}
        super.onDestroy()
    }
}
