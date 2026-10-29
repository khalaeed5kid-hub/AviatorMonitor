package com.aviatortracker

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    // ---------- ألوان التصميم ----------
    private val cBg = Color.parseColor("#0A1020")
    private val cCard = Color.parseColor("#111A2E")
    private val cStroke = Color.parseColor("#22314F")
    private val cText = Color.parseColor("#E8EEF9")
    private val cMuted = Color.parseColor("#8FA0BF")
    private val cBlue = Color.parseColor("#1E63D6")
    private val cGreen = Color.parseColor("#1E9E55")
    private val cRed = Color.parseColor("#C62F3A")
    private val cPurple = Color.parseColor("#6D3BE0")
    private val cAmber = Color.parseColor("#C98400")
    private val cUp = Color.parseColor("#2ECC71")
    private val cDown = Color.parseColor("#FF5252")

    private lateinit var db: ResultsDb
    private val logDb by lazy { RoundLogDb.get(this) }
    private val ui = Handler(Looper.getMainLooper())

    // عناصر تُحدَّث
    private val regionState = arrayOfNulls<TextView>(4)
    private val regionCard = arrayOfNulls<LinearLayout>(4)
    private lateinit var statsState: TextView
    private lateinit var statsToggle: TextView
    private lateinit var moneyState: TextView
    private lateinit var moneyToggle: TextView
    private lateinit var balanceBig: TextView
    private lateinit var balanceFull: TextView
    private lateinit var totalsView: TextView
    private lateinit var tableBody: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var modeBtn: TextView
    private lateinit var diagView: TextView
    private lateinit var cropA: ImageView
    private lateinit var cropB: ImageView
    private lateinit var cropC: ImageView
    private var lastSig = ""

    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 500)
        }
    }

    // ---------- أدوات بناء الواجهة ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(fill: Int, radius: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun tv(t: String, size: Float, color: Int = cText, bold: Boolean = false, grav: Int = Gravity.CENTER): TextView =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color); gravity = grav
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f, margin: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(margin), dp(margin), dp(margin), dp(margin)) }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(cCard, 16, cStroke)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun bigButton(icon: String, title: String, sub: String, fill: Int, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = shape(fill, 14, Color.argb(120, 255, 255, 255))
            setPadding(dp(4), dp(12), dp(4), dp(12))
            addView(tv(icon, 24f))
            addView(tv(title, 14f, Color.WHITE, true))
            addView(tv(sub, 10f, Color.argb(210, 255, 255, 255)))
            layoutParams = lp(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f, 4)
            setOnClickListener { onClick() }
        }

    private fun smallButton(label: String, fill: Int, onClick: () -> Unit) = tv(label, 12f, Color.WHITE, true).apply {
        background = shape(fill, 10)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
    }

    private fun realSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ResultsDb(this)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(cBg)
        }

        // ===== الترويسة =====
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val gear = tv("⚙", 26f, cText).apply { setOnClickListener { toggleSettings() }; setPadding(dp(8), 0, dp(8), 0) }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("مراقب الطائرة الذكي", 20f, cText, true, Gravity.START))
            addView(tv("تابع · احسب · سجّل", 12f, cMuted, false, Gravity.START))
        }
        header.addView(gear)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(titles)
        header.addView(tv("🚀", 30f).apply { setPadding(dp(8), 0, dp(10), 0) })
        root.addView(header)

        // ===== الأزرار الثلاثة =====
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        }
        row1.addView(bigButton("⏹", "إيقاف الخدمة", "(إخفاء الأيقونة)", cRed) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        })
        row1.addView(bigButton("▶", "بدء الخدمة", "(إظهار الأيقونة العائمة)", cGreen) { startCapture() })
        row1.addView(bigButton("🛡", "منح الإذن", "(الظهور فوق التطبيقات)", cBlue) { requestOverlay() })
        root.addView(row1)

        // ===== تحديد مناطق المراقبة =====
        val regions = card()
        regions.addView(tv("تحديد مناطق المراقبة", 15f, cText, true, Gravity.START))
        val rowR = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        fun regionBox(n: Int, color: Int, icon: String, title: String, sub: String): LinearLayout {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = shape(Color.parseColor("#0E1627"), 12, cStroke)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                layoutParams = lp(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f, 4)
                setOnClickListener { requestSelection(n) }
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            top.addView(tv("$n", 15f, Color.WHITE, true).apply {
                background = shape(color, 20); setPadding(dp(9), dp(2), dp(9), dp(2))
            })
            top.addView(tv(icon, 20f).apply { setPadding(dp(8), 0, 0, 0) })
            box.addView(top)
            box.addView(tv(title, 13f, cText, true))
            box.addView(tv(sub, 9.5f, cMuted))
            val st = tv("", 11f, cMuted, true)
            regionState[n] = st
            box.addView(st)
            regionCard[n] = box
            return box
        }
        rowR.addView(regionBox(1, cBlue, "📈", "منطقة النتائج", "(معامل الطائرة)"))
        rowR.addView(regionBox(2, cGreen, "💵", "منطقة الرهانات", "(مجموع ما وضعه اللاعبون)"))
        rowR.addView(regionBox(3, cPurple, "👛", "منطقة المدفوعات", "(مجموع أرباح اللاعبين)"))
        regions.addView(rowR)
        regions.addView(tv("اضغط الرقم ثم ارسم المستطيل فوق اللعبة (أو من الأيقونة العائمة)", 10f, cMuted, false, Gravity.START).apply {
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(regions)

        // ===== حالة المراقبة =====
        val status = card()
        status.addView(tv("حالة المراقبة", 15f, cText, true, Gravity.START))
        fun statusRow(label: String, onToggle: () -> Unit): Triple<LinearLayout, TextView, TextView> {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            }
            r.addView(tv(label, 13f, cText, false, Gravity.START))
            val state = tv("", 12f, cMuted, true, Gravity.START).apply { setPadding(dp(10), 0, 0, 0) }
            r.addView(state)
            r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            val toggle = smallButton("", cStroke) { onToggle() }
            r.addView(toggle)
            return Triple(r, state, toggle)
        }
        val s1 = statusRow("مراقبة النتائج") {
            HousePrefs.setStatsPaused(this, !HousePrefs.statsPaused(this)); render()
        }
        statsState = s1.second; statsToggle = s1.third; status.addView(s1.first)
        val s2 = statusRow("مراقبة الأموال") {
            HousePrefs.setMoneyPaused(this, !HousePrefs.moneyPaused(this)); render()
        }
        moneyState = s2.second; moneyToggle = s2.third; status.addView(s2.first)
        root.addView(status)

        // ===== نسخ / حذف / تصفير =====
        val actions = card()
        val rowA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowA.addView(bigButton("🗑", "حذف كل النتائج", "(إفراغ السجل)", cRed) { confirmLogDelete() })
        rowA.addView(bigButton("📋", "نسخ كل النتائج", "(السجل الكامل)", cPurple) { copyLog() })
        actions.addView(rowA)
        actions.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = shape(cAmber, 14)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = lp(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0f, 4)
            addView(tv("♻ تصفير رصيد الشركة (جلسة جديدة)", 14f, Color.WHITE, true))
            setOnClickListener { confirmBalanceReset() }
        })
        root.addView(actions)

        // ===== سجل الجولات =====
        val log = card()
        log.addView(tv("سجل الجولات", 15f, cText, true, Gravity.START))
        balanceBig = tv("", 22f, cUp, true, Gravity.START).apply { setPadding(0, dp(6), 0, 0) }
        balanceFull = tv("", 11f, cMuted, false, Gravity.START)
        totalsView = tv("", 11.5f, cText, false, Gravity.START).apply { setPadding(0, dp(2), 0, dp(8)) }
        log.addView(balanceBig); log.addView(balanceFull); log.addView(totalsView)
        tableBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        log.addView(tableBody)
        root.addView(log)

        // ===== الإعدادات والتشخيص (مخفي حتى الضغط على ⚙) =====
        settingsBox = card().apply { visibility = View.GONE }
        settingsBox.addView(tv("الإعدادات والتشخيص", 15f, cText, true, Gravity.START))
        modeBtn = smallButton("", cStroke) {
            HousePrefs.setCumulative(this, !HousePrefs.cumulative(this)); render()
        }.apply { layoutParams = lp(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0f, 6) }
        settingsBox.addView(modeBtn)
        diagView = tv("", 11f, cText, false, Gravity.START).apply { typeface = Typeface.MONOSPACE }
        settingsBox.addView(diagView)
        fun crop() = ImageView(this).apply {
            setBackgroundColor(Color.DKGRAY); adjustViewBounds = true
            layoutParams = lp(LinearLayout.LayoutParams.WRAP_CONTENT, dp(60), 0f, 2)
        }
        cropA = crop(); cropB = crop(); cropC = crop()
        settingsBox.addView(cropA); settingsBox.addView(cropB); settingsBox.addView(cropC)
        root.addView(settingsBox)

        setContentView(ScrollView(this).apply { setBackgroundColor(cBg); addView(root) })
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }

    private fun toggleSettings() {
        settingsBox.visibility = if (settingsBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // ---------- الصلاحية / البدء / اختيار المنطقة ----------
    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "الإذن ممنوح بالفعل ✓", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "بدأت الخدمة. استخدم الأرقام 1 و2 و3 لتحديد المناطق", Toast.LENGTH_LONG).show()
        }
    }

    /** الضغط على بطاقة منطقة: تبدأ الخدمة رسم المستطيل فوق اللعبة ويذهب التطبيق للخلفية. */
    private fun requestSelection(n: Int) {
        if (!Diag.running) {
            Toast.makeText(this, "شغّل الخدمة أولًا ثم اختر المنطقة", Toast.LENGTH_LONG).show()
            return
        }
        startService(
            Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_SELECT)
                .putExtra(CaptureService.EXTRA_REGION, n)
        )
        Toast.makeText(this, "افتح شاشة اللعبة وارسم المستطيل", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    // ---------- العرض ----------
    private fun fmtMult(v: Double?) = if (v == null) "—" else String.format(Locale.US, "%.2fx", v)
    private fun m(v: Long?) = if (v == null) "—" else AmountParser.grouped(v)

    private fun render() {
        val (w, h) = realSize()
        val svc = Diag.running
        val saved = BooleanArray(4) { it > 0 && RegionStore.get(this, it, w, h) != null }
        for (n in 1..3) {
            regionState[n]?.apply {
                text = if (saved[n]) "✓ محفوظة" else "غير محددة"
                setTextColor(if (saved[n]) cUp else cMuted)
            }
            regionCard[n]?.background = shape(Color.parseColor("#0E1627"), 12, if (saved[n]) cGreen else cStroke)
        }

        fun setState(state: TextView, toggle: TextView, ready: Boolean, paused: Boolean, needs: String) {
            val (label, color) = when {
                !svc -> "الخدمة متوقفة" to cMuted
                !ready -> needs to cAmber
                paused -> "متوقفة مؤقتًا" to cAmber
                else -> "تعمل ●" to cUp
            }
            state.text = label; state.setTextColor(color)
            toggle.text = if (paused) "استئناف" else "إيقاف مؤقت"
            toggle.background = shape(if (paused) cGreen else cStroke, 10)
        }
        setState(statsState, statsToggle, saved[1], HousePrefs.statsPaused(this), "حدّد المنطقة 1")
        setState(moneyState, moneyToggle, saved[2] && saved[3], HousePrefs.moneyPaused(this), "حدّد المنطقتين 2 و3")

        // ----- الملخص -----
        val session = HousePrefs.session(this)
        val sum = logDb.summary(session)
        val rows = logDb.recent(session, 100)
        val b = sum.balance
        balanceBig.text = "رصيد الشركة  " + (if (b > 0) "▲ " else if (b < 0) "▼ " else "") + AmountParser.abbrev(b, signed = true)
        balanceBig.setTextColor(if (b < 0) cDown else cUp)
        balanceFull.text = AmountParser.grouped(b, signed = true)
        totalsView.text = "الرهانات ${AmountParser.abbrev(sum.totalIn)}   |   المدفوعات ${AmountParser.abbrev(sum.totalOut)}   |   " +
            "الجولات ${sum.count}" + if (sum.flagged > 0) "   |   غير محتسبة ${sum.flagged}" else ""

        // ----- الجدول (يُعاد بناؤه فقط عند تغيّر البيانات) -----
        val sig = "${sum.count}|${sum.flagged}|${rows.firstOrNull()?.id}|$b"
        if (sig != lastSig) {
            lastSig = sig
            buildTable(rows, sum.count)
        }

        // ----- الإعدادات والتشخيص -----
        if (settingsBox.visibility == View.VISIBLE) renderDiag(saved)
    }

    private fun tableRow(cells: List<View>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(7), 0, dp(7))
        cells.forEach { addView(it) }
    }

    private fun cell(t: String, weight: Float, color: Int = cText, size: Float = 12f, bold: Boolean = false) =
        tv(t, size, color, bold).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight) }

    private fun buildTable(rows: List<RoundLogDb.Row>, total: Int) {
        tableBody.removeAllViews()
        // الترتيب في RTL: أول عنصر في أقصى اليمين (علامة الحالة، ثم #، ...، وأخيرًا الوقت في أقصى اليسار)
        tableBody.addView(tableRow(listOf(
            cell("", 0.5f), cell("#", 0.7f, cMuted, 11f), cell("النتيجة", 1.1f, cMuted, 11f),
            cell("الرهانات", 1.3f, cMuted, 11f), cell("المدفوعات", 1.3f, cMuted, 11f), cell("الوقت", 1.5f, cMuted, 11f)
        )))
        if (rows.isEmpty()) {
            tableBody.addView(tv("لا جولات بعد", 12f, cMuted).apply { setPadding(0, dp(12), 0, dp(6)) })
            return
        }
        val tf = SimpleDateFormat("HH:mm:ss", Locale.US)
        rows.forEachIndexed { idx, r ->
            val mark = when (r.status) {
                RoundStatus.OK -> cell("✔", 0.5f, cUp, 14f, true)
                RoundStatus.SUSPECT -> cell("⚠", 0.5f, cDown, 14f, true)
                else -> cell("!", 0.5f, cAmber, 14f, true)
            }
            val resColor = when { r.mult == null -> cMuted; r.mult < 2.0 -> cDown; else -> cUp }
            val result = if (r.mult == null) cell("غير متوفر", 1.1f, cMuted, 10f) else cell(fmtMult(r.mult), 1.1f, resColor, 12.5f, true)
            val bets = if (r.bets == null) cell("غير متوفر", 1.3f, cMuted, 10f) else cell(AmountParser.abbrev(r.bets), 1.3f)
            val wins = if (r.wins == null) cell("غير متوفر", 1.3f, cMuted, 10f) else cell(AmountParser.abbrev(r.wins), 1.3f)
            val row = tableRow(listOf(mark, cell("${total - idx}", 0.7f, cMuted, 12f), result, bets, wins, cell(tf.format(Date(r.ts)), 1.5f)))
            row.background = shape(if (idx % 2 == 0) Color.parseColor("#0E1627") else cCard, 6)
            tableBody.addView(row)
        }
        if (total > rows.size) {
            tableBody.addView(tv("… يُعرض آخر ${rows.size} من $total (النسخ يشمل الكل)", 10f, cMuted).apply { setPadding(0, dp(8), 0, 0) })
        }
    }

    private fun renderDiag(saved: BooleanArray) {
        val d = Diag
        val hd = HouseDiag
        modeBtn.text = if (HousePrefs.cumulative(this)) "رقم المدفوعات في اللعبة: تراكمي (اضغط للتغيير)"
        else "رقم المدفوعات في اللعبة: يبدأ من صفر كل جولة (اضغط للتغيير)"
        diagView.text = buildString {
            appendLine("الخدمة: " + if (d.running) "تعمل" else "متوقفة")
            appendLine("المناطق: 1 ${if (saved[1]) "✓" else "✗"}  2 ${if (saved[2]) "✓" else "✗"}  3 ${if (saved[3]) "✓" else "✗"}")
            appendLine("— النتائج —")
            appendLine("OCR: '${d.rawText}'  ← ${fmtMult(d.value)}")
            appendLine("الحالة: ${if (d.state == RoundState.FLYING) "طيران" else "انتظار"}   سيُحفظ: ${fmtMult(d.pending)}")
            appendLine("إطارات: ${d.frames}  سوداء: ${d.blankFrames}")
            if (d.error.isNotEmpty()) appendLine("تنبيه: ${d.error}")
            appendLine("— الأموال —")
            appendLine("رهانات OCR: '${hd.rawBets}' ← ${m(hd.bets)}")
            appendLine("مدفوعات OCR: '${hd.rawWins}' ← ${m(hd.wins)}")
            appendLine("جولة جارية: ${if (hd.active) "نعم" else "لا"}  رهانات=${m(hd.maxBets)}  مدفوعات=${m(hd.maxWins)}")
            hd.lastRecord?.let {
                appendLine("آخر تسجيل: ${fmtMult(it.mult)}  ${m(it.betsCents)} / ${m(it.winsCents)}")
            }
            if (hd.error.isNotEmpty()) appendLine("تنبيه: ${hd.error}")
        }
        d.crop?.let { cropA.setImageBitmap(it) }
        hd.crop1?.let { cropB.setImageBitmap(it) }
        hd.crop2?.let { cropC.setImageBitmap(it) }
    }

    // ---------- نسخ / حذف / تصفير ----------
    private fun copyLog() {
        val rows = logDb.all()
        if (rows.isEmpty()) { Toast.makeText(this, "السجل فارغ", Toast.LENGTH_SHORT).show(); return }
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sum = logDb.summary(HousePrefs.session(this))
        val sb = StringBuilder()
        sb.append("رصيد الشركة: ${AmountParser.format(sum.balance, true)} | الرهانات: ${AmountParser.format(sum.totalIn)} | المدفوعات: ${AmountParser.format(sum.totalOut)}\n")
        sb.append("رقم الجولة| الوقت| النتيجة| مجموع الرهانات| المدفوعات| الحالة\n")
        var session = -1
        for (r in rows) {
            if (r.session != session) { session = r.session; sb.append("--- جلسة $session ---\n") }
            val st = when (r.status) {
                RoundStatus.OK -> "مكتملة"
                RoundStatus.NO_RESULT -> "النتيجة غير متوفرة"
                RoundStatus.NO_MONEY -> "الأموال غير متوفرة (غير محتسبة)"
                else -> "مشكوك فيها (غير محتسبة)"
            }
            sb.append("${r.id}| ${f.format(Date(r.ts))}| ${if (r.mult == null) "غير متوفر" else String.format(Locale.US, "%.2fx", r.mult)}| " +
                "${r.bets?.let { AmountParser.format(it) } ?: "غير متوفر"}| ${r.wins?.let { AmountParser.format(it) } ?: "غير متوفر"}| $st\n")
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("round-log", sb.toString()))
        Toast.makeText(this, "نُسخت ${rows.size} جولة", Toast.LENGTH_SHORT).show()
    }

    private fun confirmLogDelete() {
        AlertDialog.Builder(this)
            .setTitle("حذف السجل كاملًا؟")
            .setMessage("تُحذف كل صفوف السجل (كل الجلسات). رصيد الشركة والمجاميع لا تتغير. لا يمكن التراجع.")
            .setPositiveButton("حذف") { _, _ ->
                logDb.clearRows(); db.clear(); HousePrefs.bump(this); lastSig = ""; render()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun confirmBalanceReset() {
        AlertDialog.Builder(this)
            .setTitle("تصفير رصيد الشركة؟")
            .setMessage("يبدأ رصيد جديد من صفر في جلسة جديدة. السجل السابق يبقى محفوظًا ولا يُحذف.")
            .setPositiveButton("تصفير") { _, _ ->
                logDb.resetTotals(); HousePrefs.newSession(this); lastSig = ""; render()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
