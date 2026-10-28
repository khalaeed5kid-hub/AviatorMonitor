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
    private lateinit var diagView: TextView
    private lateinit var cropView: ImageView
    private lateinit var resultsView: TextView
    private lateinit var houseView: TextView
    private lateinit var houseModeBtn: Button
    private lateinit var houseCrop1: ImageView
    private lateinit var houseCrop2: ImageView
    private val houseDb by lazy { HouseDb(this) }
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

        root.addView(TextView(this).apply { text = "Aviator Tracker"; textSize = 22f; setTypeface(null, Typeface.BOLD) })
        btn("1) منح إذن الظهور فوق التطبيقات") { requestOverlay() }
        btn("2) بدء الخدمة (إذن التقاط الشاشة)") { startCapture() }
        btn("إيقاف الخدمة") {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        }

        // ===== أداة صافي أموال الشركة (مستقلة عن أداة النتائج) =====
        root.addView(TextView(this).apply { text = "\n— أداة صافي أموال الشركة —"; setTypeface(null, Typeface.BOLD) })
        btn("تشغيل أداة صافي الشركة") { startHouse() }
        btn("إيقاف أداة صافي الشركة") {
            startService(Intent(this, HouseService::class.java).setAction(HouseService.ACTION_STOP))
        }
        btn("إيقاف / استئناف المراقبة") {
            HousePrefs.setPaused(this, !HousePrefs.paused(this)); render()
        }
        houseModeBtn = btn("") {
            HousePrefs.setCumulative(this, !HousePrefs.cumulative(this)); render()
        }
        btn("تصفير الرصيد (جلسة جديدة)") { confirmHouseReset() }
        btn("نسخ سجل الشركة") { copyHouse() }
        houseView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f }
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

        root.addView(TextView(this).apply { text = "\nالتشخيص"; setTypeface(null, Typeface.BOLD) })
        diagView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 13f }
        root.addView(diagView)
        cropView = ImageView(this).apply {
            setBackgroundColor(Color.LTGRAY)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 220)
        }
        root.addView(cropView)

        btn("نسخ كل النتائج") { copyAll() }
        btn("حذف كل النتائج") { confirmDelete() }

        root.addView(TextView(this).apply { text = "\nالسجل"; setTypeface(null, Typeface.BOLD) })
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
            Toast.makeText(this, "بدأت الخدمة. اضغط الأيقونة العائمة لتحديد المنطقة", Toast.LENGTH_LONG).show()
        } else if (requestCode == 7 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, HouseService::class.java)
                .putExtra(HouseService.EXTRA_CODE, resultCode)
                .putExtra(HouseService.EXTRA_DATA, data)
            startForegroundService(i)
            Toast.makeText(this, "بدأت أداة الشركة. اضغط ① ثم ② على اللوحة العائمة لتحديد المنطقتين", Toast.LENGTH_LONG).show()
        }
    }

    private fun startHouse() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "امنح إذن الظهور فوق التطبيقات أولًا", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 7)
    }

    private fun confirmHouseReset() {
        AlertDialog.Builder(this)
            .setTitle("تصفير الرصيد؟")
            .setMessage("يبدأ رصيد جديد من صفر. السجل القديم لا يُحذف من الهاتف.")
            .setPositiveButton("تصفير") { _, _ -> HousePrefs.newSession(this); render() }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun copyHouse() {
        val rows = houseDb.rounds(HousePrefs.session(this))
        if (rows.isEmpty()) { Toast.makeText(this, "لا جولات", Toast.LENGTH_SHORT).show(); return }
        var bal = 0L
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val text = rows.mapIndexed { i, r ->
            bal += r.net
            "${i + 1}. رهانات ${AmountParser.format(r.bets)}  أرباح ${AmountParser.format(r.wins)}  " +
                "صافي ${AmountParser.format(r.net, true)}  رصيد ${AmountParser.format(bal, true)}  ${f.format(Date(r.ts))}"
        }.joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("house", text))
        Toast.makeText(this, "نُسخت ${rows.size} جولة", Toast.LENGTH_SHORT).show()
    }

    private fun renderHouse() {
        val h = HouseDiag
        val session = HousePrefs.session(this)
        val rows = houseDb.rounds(session)
        val balance = rows.sumOf { it.net }
        houseModeBtn.text = if (HousePrefs.cumulative(this)) "الأرباح في اللعبة: تراكمية (اضغط للتغيير)"
        else "الأرباح في اللعبة: تُصفَّر كل جولة (اضغط للتغيير)"
        fun m(v: Long?) = if (v == null) "—" else AmountParser.format(v)
        houseView.text = buildString {
            appendLine("الأداة          : " + if (h.running) (if (HousePrefs.paused(this@MainActivity)) "متوقفة مؤقتًا" else "تعمل") else "متوقفة")
            appendLine("المنطقة ① رهانات: " + if (HousePrefs.region(this@MainActivity, 1) != null) "محددة" else "غير محددة")
            appendLine("المنطقة ② أرباح : " + if (HousePrefs.region(this@MainActivity, 2) != null) "محددة" else "غير محددة")
            appendLine("OCR ① خام: '${h.rawBets}'  ← ${m(h.bets)}")
            appendLine("OCR ② خام: '${h.rawWins}'  ← ${m(h.wins)}")
            appendLine("جولة جارية: ${if (h.active) "نعم" else "لا"}  رهانات=${m(h.maxBets)}  أرباح=${m(h.maxWins)}")
            h.lastRound?.let {
                appendLine("آخر جولة: رهانات ${m(it.betsCents)} − أرباح ${m(it.winsCents)} = ${AmountParser.format(it.netCents, true)}")
            }
            appendLine("الرصيد التراكمي: ${AmountParser.format(balance, true)}   (جولات الجلسة: ${rows.size})")
            if (h.error.isNotEmpty()) appendLine("تنبيه: ${h.error}")
            var bal = 0L
            val f = SimpleDateFormat("HH:mm:ss", Locale.US)
            val lines = rows.map { r -> bal += r.net; "${AmountParser.format(r.net, true)}  → ${AmountParser.format(bal, true)}  ${f.format(Date(r.ts))}" }
            if (lines.isNotEmpty()) appendLine("\nآخر الجولات (صافي → رصيد):\n" + lines.takeLast(15).reversed().joinToString("\n"))
        }
        h.crop1?.let { houseCrop1.setImageBitmap(it) }
        h.crop2?.let { houseCrop2.setImageBitmap(it) }
    }

    private fun fmt(v: Double?) = if (v == null) "—" else String.format(Locale.US, "%.2fx", v)

    private fun render() {
        renderHouse()
        val d = Diag
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

        val rows = db.all()
        resultsView.text = if (rows.isEmpty()) "(لا نتائج)" else {
            val f = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            rows.asReversed().take(200).joinToString("\n") { (m, t) -> "${fmt(m)}   ${f.format(Date(t))}" } +
                "\n\nالمجموع: ${rows.size}"
        }
    }

    private fun copyAll() {
        val rows = db.all()
        if (rows.isEmpty()) { Toast.makeText(this, "لا نتائج", Toast.LENGTH_SHORT).show(); return }
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val text = rows.mapIndexed { i, (m, t) -> "${i + 1}. ${fmt(m)}  ${f.format(Date(t))}" }.joinToString("\n")
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
