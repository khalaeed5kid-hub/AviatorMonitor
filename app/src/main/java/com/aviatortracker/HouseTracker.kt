package com.aviatortracker

/** winsCents = null تعني أن رقم الأرباح لم يُقرأ بثبات أبدًا خلال الجولة (جولة ناقصة، لا نخترع صفرًا). */
data class HouseRound(val betsCents: Long, val winsCents: Long?, val startMs: Long = 0, val endMs: Long = 0) {
    val netCents: Long? get() = winsCents?.let { betsCents - it }
}

/**
 * منطق أداة صافي الشركة (مستقل تمامًا عن RoundTracker).
 *
 * القراءة تُعتبر "مستقرة" فقط إذا تكررت بنفس القيمة في [stableFrames] إطارات متتالية (يُتجاهل خطأ OCR العابر).
 *
 * الجولة:
 *  - تبدأ عند أول قراءة مستقرة لمجموع الرهانات > 0.
 *  - أثناء الجولة: الرهانات والأرباح لا تنقص، فنحتفظ بأكبر قيمة مستقرة لكل منهما (القراءة الأقل تُعتبر خطأ OCR).
 *  - تنتهي عندما يعود مجموع الرهانات إلى 0 أو ينخفض لأقل من نصف أعلى قيمة (جولة جديدة بدأت، وقد يفوتنا الصفر بين إطارين).
 *    عندها تُثبَّت نتيجة الجولة السابقة مرة واحدة، وتبدأ الجديدة. تكرار القراءة نفسها لا ينتج جولة ثانية.
 *
 * الأرباح:
 *  - winsCumulative = false (الافتراضي): رقم الأرباح يبدأ من الصفر كل جولة، فأرباح الجولة = أعلى قيمة مستقرة.
 *  - winsCumulative = true: الرقم تراكمي عبر الجولات، فأرباح الجولة = الفرق عن قيمة الجولة السابقة.
 */
class HouseTracker(
    private val stableFrames: Int = 2,
    @Volatile var winsCumulative: Boolean = false,
    private val onRound: (HouseRound) -> Unit
) {
    var active = false
        private set
    var maxBets = 0L
        private set
    var maxWins = 0L
        private set
    var stableBets: Long? = null
        private set
    var stableWins: Long? = null
        private set

    private var lastB: Long? = null
    private var runB = 0
    private var lastW: Long? = null
    private var runW = 0
    private var baseline: Long? = null
    private var baselineUnset = false
    private var prevFinalWins: Long? = null
    private var winsSeen = false
    private var startMs = 0L

    /** now = وقت الإطار بالمللي ثانية (يُستخدم لتحديد الفترة الزمنية للجولة عند ربطها بنتيجتها). */
    fun onFrame(bets: Long?, wins: Long?, now: Long = 0L) {
        val sb = stableOfBets(bets)
        val sw = stableOfWins(wins)
        if (sb != null) {
            stableBets = sb
            handleBets(sb, now)
        }
        if (sw != null) {
            stableWins = sw
            handleWins(sw)
        }
    }

    /** يُستدعى عند الإيقاف المؤقت أو تغيير المنطقة: تُهمل الجولة الجارية غير المكتملة. */
    fun resetState() {
        active = false
        maxBets = 0; maxWins = 0
        lastB = null; runB = 0; lastW = null; runW = 0
        stableBets = null; stableWins = null
        baseline = null; baselineUnset = false; prevFinalWins = null; winsSeen = false
    }

    private fun stableOfBets(v: Long?): Long? {
        if (v == null) { lastB = null; runB = 0; return null }
        if (v == lastB) runB = minOf(runB + 1, 1000) else { lastB = v; runB = 1 }
        return if (runB >= stableFrames) v else null
    }

    private fun stableOfWins(v: Long?): Long? {
        if (v == null) { lastW = null; runW = 0; return null }
        if (v == lastW) runW = minOf(runW + 1, 1000) else { lastW = v; runW = 1 }
        return if (runW >= stableFrames) v else null
    }

    private fun handleBets(sb: Long, now: Long) {
        if (!active) {
            if (sb > 0) start(sb, now)
            return
        }
        if (sb == 0L || sb * 2 < maxBets) {
            finish(now)
            if (sb > 0) start(sb, now)
        } else if (sb > maxBets) {
            maxBets = sb
        }
    }

    private fun handleWins(sw: Long) {
        if (!active) return
        winsSeen = true
        if (baselineUnset) {
            baseline = sw
            baselineUnset = false
        }
        if (sw > maxWins) maxWins = sw
    }

    private fun start(sb: Long, now: Long) {
        active = true
        startMs = now
        maxBets = sb
        maxWins = 0
        winsSeen = false
        baseline = prevFinalWins
        baselineUnset = winsCumulative && prevFinalWins == null
    }

    private fun finish(now: Long) {
        val bets = maxBets
        var wins = maxWins
        if (winsCumulative) {
            val b = baseline ?: 0L
            wins = if (maxWins >= b) maxWins - b else maxWins
        }
        prevFinalWins = maxWins
        val seen = winsSeen
        active = false
        maxBets = 0
        maxWins = 0
        winsSeen = false
        if (bets > 0) onRound(HouseRound(bets, if (seen) wins else null, startMs, now))
    }
}
