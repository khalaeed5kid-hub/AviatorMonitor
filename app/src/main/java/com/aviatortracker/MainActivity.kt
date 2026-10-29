package com.aviatortracker

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var db: ResultsDb
    private val logDb by lazy { RoundLogDb(this) }
    private lateinit var diagView: TextView
    private lateinit var cropView: ImageView
    private lateinit var resultsView: TextView
    private lateinit var summaryView: TextView
    private lateinit var logView: TextView
    private lateinit var houseView: TextView
    private lateinit var statsPauseBtn: Button
    private lateinit var moneyPauseBtn: Button
    private lateinit var houseModeBtn: Button
    private lateinit var houseCrop1: ImageView
    private lateinit var houseCrop2: ImageView
    private val ui = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ResultsDb(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            root.addView(this)
        }
        fun title(t: String) = root.addView(TextView(this).apply { text = t; setTypeface(null, Typeface.BOLD) })

        root.addView(TextView(this).apply { text = "Aviator Tracker"; textSize = 22f; setTypeface(null, Typeface.BOLD) })
        btn("1) منح إذن الظهور فوق التطبيقات") { requestOverlay() }
        btn("2) بدء الخدمة (إذن التقاط الشاشة)") { startCapture() }
        btn("إيقاف الخدمة") {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        }

        title("\nالتحكم (كل مراقبة مستقلة عن الأخرى)")
        statsPauseBtn = btn("") {
            HousePrefs.setStatsPaused(this, !HousePrefs.statsPaused(this)); render()
        }
        moneyPauseBtn = btn("") {
            HousePrefs.setMoneyPaused(this, !HousePrefs.moneyPaused(this)); render()
        }
        houseModeBtn = btn("") {
            HousePrefs.setCumulative(this, !HousePrefs.cumulative(this)); render()
        }
        btn("تصفير الرصيد (جلسة جديدة، السجل يبقى)") { confirmBalanceReset() }

        title("\nالسجل الموحّد (جولة = سطر)")
        btn("نسخ السجل كاملًا") { copyLog() }
        btn("حذف السجل") { confirmLogDelete() }
        summaryView = TextView(this).apply { textSize = 12f }
        root.addView(summaryView)
        logView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 10f }
        root.addView(HorizontalScrollView(this).apply { addView(logView) })

        title("\nتشخيص الأموال")
        houseView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        root.addView(houseView)
        houseCrop1 = ImageView(this).apply {
            setBackgroundColor(Color.LTGRAY); adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 140)
        }
        houseCrop2 = ImageView(this).apply {
            setBackgroundColor(Color.LTGRAY); adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 140)
        }
        root.addView(houseCrop1); root.addView(houseCrop2)

        title("\nتشخيص النتائج (المضاعف)")
        diagView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f }
        root.addView(diagView)
        cropView = ImageView(this).apply {
            setBackgroundColor(Color.LTGRAY)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 220)
        }
        root.addView(cropView)

        title("\nسجل المضاعفات فقط (القديم)")
        btn("نسخ كل النتائج") { copyAll() }
        btn("حذف كل النتائج") { confirmDelete() }
        resultsView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 14f }
        root.addView(resultsView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "الإذن ممنوح بالفعل", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "امنح إذن الظهور فوق التطبيقات أولًا", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data)
            startForegroundService(i)
            Toast.makeText(this, "بدأت الخدمة. من الأداة العائمة: 1 نتائج، 2 رهانات، 3 أرباح", Toast.LENGTH_LONG).show()
        }
    }

    private fun fmt(v: Double?) = if (v == null) "—" else String.format(Locale.US, "%.2fx", v)
    private fun m(v: Long?) = if (v == null) "—" else AmountParser.grouped(v)
    private fun a(v: Long?) = if (v == null) "?" else AmountParser.abbrev(v)
    private fun s(v: Long?) = if (v == null) "?" else AmountParser.abbrev(v, signed = true)

    private fun flag(st: Int) = when (st) {
        RoundStatus.NO_RESULT -> " ؟نتيجة"
        RoundStatus.NO_MONEY -> " ناقصة"
        RoundStatus.SUSPECT -> " ⚠مشكوكة"
        else -> ""
    }

    private fun render() {
        val d = Diag
        val h = HouseDiag
        val session = HousePrefs.session(this)

        statsPauseBtn.text = if (HousePrefs.statsPaused(this)) "مراقبة النتائج: متوقفة ← اضغط للاستئناف" else "مراقبة النتائج: تعمل ← اضغط للإيقاف المؤقت"
        moneyPauseBtn.text = if (HousePrefs.moneyPaused(this)) "مراقبة الأموال: متوقفة ← اضغط للاستئناف" else "مراقبة الأموال: تعمل ← اضغط للإيقاف المؤقت"
        houseModeBtn.text = if (HousePrefs.cumulative(this)) "الأرباح في اللعبة: تراكمية (اضغط للتغيير)"
        else "الأرباح في اللعبة: تُصفَّر كل جولة (اضغط للتغيير)"

        // ----- السجل الموحّد -----
        val sum = logDb.summary(session)
        summaryView.text = "الرصيد: ${AmountParser.grouped(sum.balance, true)}  (${AmountParser.abbrev(sum.balance, true)})\n" +
            "دخل ${AmountParser.abbrev(sum.totalIn)}  |  خرج ${AmountParser.abbrev(sum.totalOut)}  |  " +
            "جولات ${sum.count}  |  غير محتسبة ${sum.flagged}"
        val f = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val rows = logDb.recent(session, 100)
        logView.text = if (rows.isEmpty()) "(لا جولات بعد)" else buildString {
            appendLine(String.format("%-5s %-14s %-7s %-7s %-7s %-7s %s", "#", "الوقت", "نتيجة", "رهان", "مدفوع", "صافي", "الرصيد قبل→بعد"))
            for (r in rows) {
                appendLine(
                    String.format(
                        "%-5d %-14s %-7s %-7s %-7s %-7s %s→%s%s",
                        r.id, f.format(Date(r.ts)), fmt(r.mult), a(r.bets), a(r.wins), s(r.net),
                        AmountParser.abbrev(r.before, true), AmountParser.abbrev(r.after, true), flag(r.status)
                    )
                )
            }
        }

        // ----- تشخيص الأموال -----
        houseView.text = buildString {
            appendLine("الخدمة         : " + if (d.running) "تعمل" else "متوقفة")
            appendLine("المناطق        : 1 نتائج ${if (h.regionStats) "✓" else "✗"}  2 رهانات ${if (h.regionBets) "✓" else "✗"}  3 أرباح ${if (h.regionWins) "✓" else "✗"}")
            appendLine("OCR 2 (رهانات): '${h.rawBets}' ← ${m(h.bets)}")
            appendLine("OCR 3 (أرباح) : '${h.rawWins}' ← ${m(h.wins)}")
            appendLine("جولة جارية: ${if (h.active) "نعم" else "لا"}  رهانات=${m(h.maxBets)}  أرباح=${m(h.maxWins)}")
            h.lastRecord?.let {
                appendLine("آخر سجل: ${fmt(it.mult)}  رهانات ${m(it.betsCents)}  مدفوع ${m(it.winsCents)}  ${AmountParser.abbrev(it.netCents ?: 0, true)}${flag(it.status)}")
            }
            if (h.error.isNotEmpty()) appendLine("تنبيه: ${h.error}")
        }
        h.crop1?.let { houseCrop1.setImageBitmap(it) }
        h.crop2?.let { houseCrop2.setImageBitmap(it) }

        // ----- تشخيص النتائج (القديم) -----
        diagView.text = buildString {
            appendLine("الخدمة         : " + if (d.running) "تعمل" else "متوقفة")
            appendLine("المنطقة        : ${d.region}")
            appendLine("نص OCR الخام   : '${d.rawText}'")
            appendLine("المضاعف المقروء: ${fmt(d.value)}")
            appendLine("حالة الجولة    : ${if (d.state == RoundState.FLYING) "طيران (FLYING)" else "انتظار (WAITING)"}")
            appendLine("سيُحفظ عند النهاية: ${fmt(d.pending)}")
            appendLine("آخر نتيجة محفوظة: ${fmt(d.lastSaved)}  (محفوظ في هذه الجلسة: ${d.savedCount})")
            appendLine("الإطارات: ${d.frames}   إطارات سوداء: ${d.blankFrames}")
            if (d.error.isNotEmpty()) appendLine("تنبيه: ${d.error}")
        }
        d.crop?.let { cropView.setImageBitmap(it) }

        val old = db.all()
        resultsView.text = if (old.isEmpty()) "(لا نتائج)" else {
            val ff = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            old.asReversed().take(200).joinToString("\n") { (mm, t) -> "${fmt(mm)}   ${ff.format(Date(t))}" } +
                "\n\nالمجموع: ${old.size}"
        }
    }

    // ---------- السجل الموحّد: نسخ / حذف / تصفير ----------
    private fun copyLog() {
        val rows = logDb.all()
        if (rows.isEmpty()) { Toast.makeText(this, "السجل فارغ", Toast.LENGTH_SHORT).show(); return }
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("رقم الجولة| الوقت| النتيجة| مجموع الرهانات| المدفوعات| صافي الجولة| الرصيد قبلها| الرصيد بعدها| الحالة\n")
        var session = -1
        for (r in rows) {
            if (r.session != session) { session = r.session; sb.append("--- جلسة $session ---\n") }
            val st = when (r.status) {
                RoundStatus.OK -> "مكتملة"
                RoundStatus.NO_RESULT -> "النتيجة غير مقروءة"
                RoundStatus.NO_MONEY -> "ناقصة (غير محتسبة)"
                else -> "مشكوك فيها (غير محتسبة)"
            }
            sb.append("${r.id}| ${f.format(Date(r.ts))}| ${if (r.mult == null) "?" else String.format(Locale.US, "%.2fx", r.mult)}| " +
                "${r.bets?.let { AmountParser.format(it) } ?: "?"}| ${r.wins?.let { AmountParser.format(it) } ?: "?"}| " +
                "${r.net?.let { AmountParser.format(it, true) } ?: "?"}| ${AmountParser.format(r.before, true)}| " +
                "${AmountParser.format(r.after, true)}| $st\n")
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("round-log", sb.toString()))
        Toast.makeText(this, "نُسخت ${rows.size} جولة", Toast.LENGTH_SHORT).show()
    }

    private fun confirmLogDelete() {
        AlertDialog.Builder(this)
            .setTitle("حذف السجل كاملًا؟")
            .setMessage("تُحذف كل الجولات وكل الجلسات ويصبح الرصيد صفرًا. لا يمكن التراجع.")
            .setPositiveButton("حذف") { _, _ -> logDb.clearAll(); HousePrefs.bump(this); render() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmBalanceReset() {
        AlertDialog.Builder(this)
            .setTitle("تصفير الرصيد؟")
            .setMessage("يبدأ رصيد جديد من صفر في جلسة جديدة. السجل القديم يبقى محفوظًا ولا يُحذف.")
            .setPositiveButton("تصفير") { _, _ -> HousePrefs.newSession(this); render() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------- سجل المضاعفات القديم (كما كان) ----------
    private fun copyAll() {
        val rows = db.all()
        if (rows.isEmpty()) { Toast.makeText(this, "لا نتائج", Toast.LENGTH_SHORT).show(); return }
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val text = rows.mapIndexed { i, (mm, t) -> "${i + 1}. ${fmt(mm)}  ${f.format(Date(t))}" }.joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("results", text))
        Toast.makeText(this, "نُسخت ${rows.size} نتيجة", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("حذف كل النتائج؟")
            .setMessage("لا يمكن التراجع عن هذا الإجراء.")
            .setPositiveButton("حذف") { _, _ -> db.clear(); render() }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
