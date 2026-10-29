package com.aviatortracker

object RoundStatus {
    const val OK = 0          // نتيجة + رهانات + مدفوعات مؤكدة
    const val NO_RESULT = 1   // الأموال مؤكدة والنتيجة (المضاعف) غير متوفرة → تُحتسب في الرصيد
    const val NO_MONEY = 2    // إحدى قيمتي الأموال غير متوفرة → لا يُضاف شيء للرصيد
    const val SUSPECT = 3     // المدفوعات أكبر مما يسمح به المضاعف (خطأ قراءة محتمل) → لا يُضاف للرصيد

    fun counted(s: Int) = s == OK || s == NO_RESULT
}

/** سجل جولة واحدة. كل المبالغ بالسنت (Long) — لا Int ولا Double في أي حساب. */
data class RoundRecord(
    val ts: Long,
    val mult: Double?,
    val betsCents: Long?,
    val winsCents: Long?,
    val status: Int
) {
    val netCents: Long? get() = if (betsCents != null && winsCents != null) betsCents - winsCents else null
}

object RoundClassifier {
    /**
     * رقم المدفوعات في اللعبة يشمل أصل الرهان (مجموع ما يستلمه اللاعبون = رهانهم × المضاعف الذي سحبوا عنده)،
     * لذلك لا يمكن أن يتجاوز (مجموع الرهانات × مضاعف الانفجار). تجاوزه = خطأ قراءة غالبًا.
     */
    fun classify(mult: Double?, bets: Long?, wins: Long?): Int {
        if (bets == null || wins == null) return RoundStatus.NO_MONEY
        if (mult != null && wins.toDouble() > bets.toDouble() * mult * 1.05 + 100.0) return RoundStatus.SUSPECT
        return if (mult == null) RoundStatus.NO_RESULT else RoundStatus.OK
    }
}

object Ledger {
    data class Step(val before: Long, val after: Long)

    /** الرصيد بعد الجولة. الجولات غير المؤكدة لا تغيّر الرصيد. الجمع مشبَّع (saturating) فلا التفاف أبدًا. */
    fun next(balance: Long, rec: RoundRecord): Step {
        val net = rec.netCents
        if (!RoundStatus.counted(rec.status) || net == null) return Step(balance, balance)
        return Step(balance, sat(balance, net))
    }

    fun sat(a: Long, b: Long): Long = try { Math.addExact(a, b) } catch (e: ArithmeticException) {
        if (b > 0) Long.MAX_VALUE else Long.MIN_VALUE
    }
}

/**
 * يربط نتيجة الجولة (من مراقبة المضاعف) بأموالها (من مراقبة الرهانات/المدفوعات).
 *
 * الربط يعتمد على الفترة الزمنية للجولة وليس على مهلة ثابتة بين الحدثين:
 *   مراقبة الأموال تعرف متى بدأت الجولة (أول رهانات مستقرة) ومتى انتهت (عودة الرهانات للصفر)،
 *   ووقت انفجار الطائرة يقع داخل هذه الفترة. فالنتيجة تُربط بالجولة المالية التي يقع انفجارها داخلها،
 *   بغض النظر عن أي الحدثين وصل أولًا (قراءة الأموال تُثبَّت أحيانًا قبل أن تقرر مراقبة النتائج أن المضاعف اختفى).
 *
 * كل حدث يُستهلك مرة واحدة. ما لا يجد شريكه يُسجَّل جولة ناقصة (قيمة غير متوفرة) ولا يُربط بجولة أخرى.
 */
class RoundCorrelator(
    private val slackAfterEndMs: Long = 1_500,
    private val resultFlushMs: Long = 25_000,
    private val moneyFlushMs: Long = 8_000,
    private val onRecord: (RoundRecord) -> Unit
) {
    private class PR(val m: Double, val t: Long)
    private class PM(val b: Long, val w: Long?, val s: Long, val e: Long)

    private val results = ArrayList<PR>()
    private val moneys = ArrayList<PM>()

    /** tCrash = وقت آخر إطار ظهر فيه المضاعف (أي لحظة الانفجار تقريبًا). */
    fun onResult(mult: Double, tCrash: Long) {
        results.add(PR(mult, tCrash))
        settle()
    }

    /** جولة مالية مكتملة: رهانات ومدفوعات (قد تكون null إن لم تُقرأ) وفترتها [tStart, tEnd]. */
    fun onMoney(bets: Long, wins: Long?, tStart: Long, tEnd: Long) {
        moneys.add(PM(bets, wins, tStart, tEnd))
        settle()
    }

    /**
     * يُستدعى كل دورة لتصريف الأحداث التي لن يأتيها شريك.
     * إن كانت مراقبة الأموال غير فعّالة تُسجَّل النتائج فورًا (لا شريك سيأتي)، والعكس.
     */
    fun tick(now: Long, statsActive: Boolean = true, moneyActive: Boolean = true) {
        settle()
        val rIt = results.iterator()
        val stale = ArrayList<PR>()
        while (rIt.hasNext()) {
            val r = rIt.next()
            if (!moneyActive || now - r.t > resultFlushMs) { stale.add(r); rIt.remove() }
        }
        val mIt = moneys.iterator()
        val staleM = ArrayList<PM>()
        while (mIt.hasNext()) {
            val m = mIt.next()
            if (!statsActive || now - m.e > moneyFlushMs) { staleM.add(m); mIt.remove() }
        }
        (stale.map { it.t to { emitResultOnly(it) } } + staleM.map { it.e to { emitMoneyOnly(it) } })
            .sortedBy { it.first }.forEach { it.second() }
    }

    private fun settle() {
        while (true) {
            val m = moneys.minByOrNull { it.s } ?: return
            // النتيجة التي يقع انفجارها داخل فترة هذه الجولة (الأقرب لنهايتها إن تعددت)
            val pick = results.filter { it.t >= m.s && it.t <= m.e + slackAfterEndMs }.maxByOrNull { it.t }
            if (pick != null) {
                // نتائج أقدم من المختارة: لا أموال لها
                results.filter { it.t < pick.t }.sortedBy { it.t }.forEach { emitResultOnly(it) }
                results.removeAll { it.t < pick.t }
                results.remove(pick)
                moneys.remove(m)
                emit(RoundRecord(pick.t, pick.m, m.b, m.w, RoundClassifier.classify(pick.m, m.b, m.w)))
                continue
            }
            // لا نتيجة داخل فترتها، لكن وصلت نتيجة أحدث بعد نهايتها: هذه الجولة المالية لن يأتيها شريك
            if (results.any { it.t > m.e + slackAfterEndMs }) {
                moneys.remove(m)
                emitMoneyOnly(m)
                continue
            }
            return
        }
    }

    private fun emit(r: RoundRecord) = onRecord(r)
    private fun emitResultOnly(r: PR) = emit(RoundRecord(r.t, r.m, null, null, RoundStatus.NO_MONEY))
    private fun emitMoneyOnly(m: PM) =
        emit(RoundRecord(m.e, null, m.b, m.w, RoundClassifier.classify(null, m.b, m.w)))
}
