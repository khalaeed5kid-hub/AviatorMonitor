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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * خدمة واحدة وإذن التقاط واحد تخدم المراقبتين معًا:
 *  - مراقبة الإحصائيات/النتائج (المنطقة 1) — نفس الآلية السابقة (RoundTracker) دون تغيير.
 *  - مراقبة الأموال (المنطقة 2 = مجموع الرهانات، المنطقة 3 = مجموع الأرباح).
 * كل إطار يُلتقط مرة واحدة ثم تُقتطع منه المناطق. فشل إحدى المراقبتين لا يوقف الأخرى.
 * ويُجمع الاثنتان في سجل موحّد (RoundLogDb) عبر RoundCorrelator.
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        const val ACTION_SELECT = "select"   // يطلب من الخدمة بدء رسم منطقة (من بطاقات التطبيق)
        const val EXTRA_REGION = "region"
        private const val CHANNEL = "tracker"
        private const val TICK_MS = 500L
    }

    private lateinit var wm: WindowManager
    private lateinit var resultsDb: ResultsDb
    private lateinit var logDb: RoundLogDb
    private lateinit var bg: Handler
    private val thread = HandlerThread("capture").apply { start() }
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var started = false
    private var screenW = 0
    private var screenH = 0

    // اللوحة العائمة
    private var panel: LinearLayout? = null
    private var balView: TextView? = null
    private var flowView: TextView? = null
    private var statusView: TextView? = null
    private val numBtns = arrayOfNulls<TextView>(4)
    private var selector: RegionSelectView? = null

    @Volatile private var regStats: Rect? = null
    @Volatile private var regBets: Rect? = null
    @Volatile private var regWins: Rect? = null
    @Volatile private var resetMoneyFlag = false

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var lastMultSeenMs = 0L
    private var wasStatsPaused = false
    private var wasMoneyPaused = false
    private var lastSession = -1
    private var lastVersion = -1

    // ---- ربط السجل الموحّد ----
    private val correlator = RoundCorrelator { rec ->
        try {
            logDb.add(HousePrefs.session(this), rec)
            HouseDiag.lastRecord = rec
            HouseDiag.recordedCount++
            refreshSummary()
        } catch (t: Throwable) {
            HouseDiag.error = "السجل: " + t.javaClass.simpleName + ": " + t.message
        }
    }

    // ---- مراقبة الإحصائيات (الآلية القديمة نفسها) ----
    private fun newStatsTracker() = RoundTracker { result ->
        resultsDb.add(result)
        Diag.lastSaved = result
        Diag.savedCount++
        correlator.onResult(result, if (lastMultSeenMs > 0) lastMultSeenMs else System.currentTimeMillis())
    }
    private var statsTracker = newStatsTracker()

    // ---- مراقبة الأموال ----
    private lateinit var houseTracker: HouseTracker

    private val tick = object : Runnable {
        override fun run() {
            try { loop() } catch (t: Throwable) { Diag.error = t.javaClass.simpleName + ": " + t.message }
            bg.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SELECT) {
            val id = intent.getIntExtra(EXTRA_REGION, 0)
            if (started && id in 1..3) main.post { startSelection(id) } else if (!started) stopSelf()
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
        resultsDb = ResultsDb(this)
        logDb = RoundLogDb.get(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bg = Handler(thread.looper)
        houseTracker = HouseTracker(winsCumulative = HousePrefs.cumulative(this)) { round ->
            correlator.onMoney(round.betsCents, round.winsCents, round.startMs, round.endMs)
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
            "aviator-cap", screenW, screenH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null
        )

        regStats = RegionStore.get(this, 1, screenW, screenH)
        regBets = RegionStore.get(this, 2, screenW, screenH)
        regWins = RegionStore.get(this, 3, screenW, screenH)
        syncRegionDiag()
        if (regStats == null && regBets == null && regWins == null && RegionStore.anySaved(this)) {
            Toast.makeText(this, "تغيّرت أبعاد الشاشة: أعد تحديد المناطق (1 و2 و3)", Toast.LENGTH_LONG).show()
        }
        Diag.region = regStats?.toShortString() ?: "غير محددة"
        Diag.running = true
        Diag.error = ""
        HouseDiag.running = true
        HouseDiag.error = ""
        refreshSummary()
        addPanel()
        refreshPanel()
        bg.post(tick)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Aviator Tracker", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Aviator Tracker")
            .setContentText("الخدمة تعمل — مراقبة النتائج والأموال")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    private fun syncRegionDiag() {
        HouseDiag.regionStats = regStats != null
        HouseDiag.regionBets = regBets != null
        HouseDiag.regionWins = regWins != null
    }

    private fun refreshSummary() {
        val s = logDb.summary(HousePrefs.session(this))
        HouseDiag.balance = s.balance
        HouseDiag.totalIn = s.totalIn
        HouseDiag.totalOut = s.totalOut
        main.post { refreshPanel() }
    }

    // ================= الأداة العائمة الواحدة =================
    private fun numBtn(n: Int, color: Int) = TextView(this).apply {
        text = "$n"
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER
        setBackgroundColor(color)
        setPadding(34, 12, 34, 12)
        setOnClickListener { startSelection(n) }
        numBtns[n] = this
    }

    private fun addPanel() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xDD000000.toInt())
            setPadding(16, 8, 16, 8)
            minimumWidth = 520
        }
        val bal = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setSingleLine(true)
        }
        val flow = TextView(this).apply {
            setTextColor(0xFFDDDDDD.toInt()); textSize = 11f; gravity = Gravity.CENTER; setSingleLine(true)
        }
        val st = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt()); textSize = 10f; gravity = Gravity.CENTER; setSingleLine(true)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(12, 1) }
        row.addView(numBtn(1, 0xFF1565C0.toInt())); row.addView(gap())
        row.addView(numBtn(2, 0xFF2E7D32.toInt())); row.addView(gap())
        row.addView(numBtn(3, 0xFFC62828.toInt()))
        box.addView(bal); box.addView(flow); box.addView(st); box.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 120 }

        // السحب من أسطر النص فقط حتى لا يتعارض مع الأرقام
        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0
        val drag = View.OnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - downX).toInt(); lp.y = sy + (e.rawY - downY).toInt()
                    wm.updateViewLayout(box, lp)
                }
            }
            true
        }
        bal.setOnTouchListener(drag); flow.setOnTouchListener(drag); st.setOnTouchListener(drag)
        wm.addView(box, lp)
        panel = box; balView = bal; flowView = flow; statusView = st
    }

    private fun refreshPanel() {
        val bal = balView ?: return
        val b = HouseDiag.balance
        bal.text = (if (b > 0) "▲ " else if (b < 0) "▼ " else "● ") + AmountParser.grouped(b, signed = true)
        bal.setTextColor(if (b < 0) 0xFFFF5252.toInt() else 0xFF69F0AE.toInt())
        flowView?.text = "الرهانات " + AmountParser.grouped(HouseDiag.totalIn) + "  |  المدفوعات " + AmountParser.grouped(HouseDiag.totalOut)

        val stats = when {
            regStats == null -> "نتائج: حدّد 1"
            HousePrefs.statsPaused(this) -> "نتائج: متوقفة"
            Diag.state == RoundState.FLYING -> "نتائج: ● " + "%.2fx".format(java.util.Locale.US, Diag.pending ?: 0.0)
            else -> "نتائج: ○"
        }
        val money = when {
            regBets == null || regWins == null -> "أموال: حدّد " + listOfNotNull(if (regBets == null) "2" else null, if (regWins == null) "3" else null).joinToString(" و")
            HousePrefs.moneyPaused(this) -> "أموال: متوقفة"
            HouseDiag.active -> "أموال: ● جولة جارية"
            else -> "أموال: ○"
        }
        statusView?.text = "$stats   |   $money"
        numBtns[1]?.text = if (regStats != null) "1✓" else "1"
        numBtns[2]?.text = if (regBets != null) "2✓" else "2"
        numBtns[3]?.text = if (regWins != null) "3✓" else "3"
    }

    // ---------- تحديد منطقة واحدة بكل رقم ----------
    private fun startSelection(id: Int) {
        if (selector != null) return
        val title = when (id) {
            1 -> "1 — ارسم مستطيل المضاعف (النتائج)"
            2 -> "2 — ارسم مستطيل مجموع الرهانات"
            else -> "3 — ارسم مستطيل مجموع أرباح اللاعبين"
        }
        val v = RegionSelectView(this, title) { rect ->
            selector?.let { wm.removeView(it) }
            selector = null
            if (rect != null) {
                RegionStore.set(this, id, screenW, screenH, rect)
                when (id) {
                    1 -> { regStats = rect; Diag.region = rect.toShortString() }
                    2 -> { regBets = rect; resetMoneyFlag = true }
                    else -> { regWins = rect; resetMoneyFlag = true }
                }
                syncRegionDiag()
                refreshPanel()
                val all = regStats != null && regBets != null && regWins != null
                Toast.makeText(
                    this,
                    if (all) "تم حفظ المنطقة $id — المناطق الثلاث جاهزة والمراقبة تعمل" else "تم حفظ المنطقة $id",
                    Toast.LENGTH_SHORT
                ).show()
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

    // ================= حلقة المعالجة (كل 500ms، في الذاكرة فقط) =================
    private fun loop() {
        val now = System.currentTimeMillis()

        val session = HousePrefs.session(this)
        val version = HousePrefs.version(this)
        if (session != lastSession || version != lastVersion) {
            lastSession = session; lastVersion = version
            refreshSummary()
        }
        houseTracker.winsCumulative = HousePrefs.cumulative(this)

        val rs = regStats
        val rb = regBets
        val rw = regWins
        val statsPaused = HousePrefs.statsPaused(this)
        val moneyPaused = HousePrefs.moneyPaused(this)
        val statsOn = rs != null && !statsPaused
        val moneyOn = rb != null && rw != null && !moneyPaused
        correlator.tick(now, statsActive = statsOn, moneyActive = moneyOn)

        if (statsPaused && !wasStatsPaused) lastMultSeenMs = 0
        if (wasStatsPaused && !statsPaused) statsTracker = newStatsTracker()   // استئناف: جولة جديدة نظيفة
        wasStatsPaused = statsPaused
        if (moneyPaused && !wasMoneyPaused) resetMoneyFlag = true
        wasMoneyPaused = moneyPaused
        if (resetMoneyFlag) { resetMoneyFlag = false; houseTracker.resetState() }

        if (rs == null) Diag.rawText = "(حدّد المنطقة 1 من الأداة العائمة)"

        if (!statsOn && !moneyOn) {
            reader?.acquireLatestImage()?.close()   // نفرّغ الإطارات حتى لا تتكدس
            HouseDiag.active = houseTracker.active
            main.post { refreshPanel() }
            return
        }

        val img = reader?.acquireLatestImage() ?: return   // لا إطار جديد (الشاشة لم تتغير)
        var full: Bitmap? = null
        try {
            val plane = img.planes[0]
            full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, screenH, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            img.close()

            // كل مراقبة معزولة عن الأخرى: خطأ في إحداهما لا يعطّل الثانية
            if (statsOn) {
                try { processStats(full, rs!!, now) } catch (t: Throwable) {
                    Diag.error = t.javaClass.simpleName + ": " + t.message
                }
            }
            if (moneyOn) {
                try { processMoney(full, rb!!, rw!!, now) } catch (t: Throwable) {
                    HouseDiag.error = t.javaClass.simpleName + ": " + t.message
                }
            }
            main.post { refreshPanel() }
        } finally {
            try { img.close() } catch (_: Throwable) {}
            full?.recycle()
        }
    }

    /** مراقبة الإحصائيات: المنطق نفسه كما في النسخة السابقة. */
    private fun processStats(full: Bitmap, r: Rect, now: Long) {
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            val x = r.left.coerceIn(0, screenW - 2)
            val y = r.top.coerceIn(0, screenH - 2)
            val w = r.width().coerceIn(2, screenW - x)
            val h = r.height().coerceIn(2, screenH - y)
            crop = Bitmap.createBitmap(full, x, y, w, h)

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
            if (v != null) lastMultSeenMs = now
            statsTracker.onFrame(v)

            Diag.frames++
            Diag.rawText = text.replace('\n', ' ')
            Diag.value = v
            Diag.state = statsTracker.state
            Diag.pending = statsTracker.lastValid
            Diag.crop = crop.copy(Bitmap.Config.ARGB_8888, false)
        } finally {
            if (scaled != null && scaled !== crop) scaled.recycle()
            crop?.recycle()
        }
    }

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

    private fun processMoney(full: Bitmap, rBets: Rect, rWins: Rect, now: Long) {
        val (t1, c1) = readRegion(full, rBets)
        val (t2, c2) = readRegion(full, rWins)
        val b = AmountParser.parseCents(t1)
        val w = AmountParser.parseCents(t2)
        houseTracker.onFrame(b, w, now)

        HouseDiag.frames++
        HouseDiag.rawBets = t1.replace('\n', ' ')
        HouseDiag.rawWins = t2.replace('\n', ' ')
        HouseDiag.bets = b
        HouseDiag.wins = w
        HouseDiag.active = houseTracker.active
        HouseDiag.maxBets = houseTracker.maxBets
        HouseDiag.maxWins = houseTracker.maxWins
        HouseDiag.crop1 = c1
        HouseDiag.crop2 = c2
        HouseDiag.error = ""
    }

    override fun onDestroy() {
        Diag.running = false
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
