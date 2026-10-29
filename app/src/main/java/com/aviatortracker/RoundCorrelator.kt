package com.aviatortracker

object RoundStatus {
    const val OK = 0          // نتيجة + رهانات + مدفوعات مؤكدة
    const val NO_RESULT = 1   // الأموال مؤكدة والنتيجة (المضاعف) لم تُقرأ → يُحتسب في الرصيد
    const val NO_MONEY = 2    // إحدى قيمتي الأموال لم تُقرأ → لا يُضاف شيء للرصيد
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
    /** مجموع المدفوعات في أي جولة لا يتجاوز (مجموع الرهانات × مضاعف الانفجار)؛ تجاوزه = خطأ قراءة غالبًا. */
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
        val after = try { Math.addExact(balance, net) } catch (e: ArithmeticException) {
            if (net > 0) Long.MAX_VALUE else Long.MIN_VALUE
        }
        return Step(balance, after)
    }
}

/**
 * يربط نتيجة الجولة (من مراقبة المضاعف) بأموالها (من مراقبة الرهانات/الأرباح) بالترتيب الزمني.
 * عادةً تصل النتيجة أولًا (الطائرة تنفجر) ثم تصل الأموال عند بدء الجولة التالية.
 * كل حدث يُستهلك مرة واحدة فقط. الحدث الذي لا يجد شريكه خلال المهلة يُسجَّل كجولة ناقصة بدل أن يُربط بجولة أخرى.
 */
class RoundCorrelator(
    private val moneyAfterResultMs: Long = 12_000,
    private val resultAfterMoneyMs: Long = 6_000,
    private val onRecord: (RoundRecord) -> Unit
) {
    private class PR(val m: Double, val t: Long)
    private class PM(val b: Long, val w: Long?, val t: Long)

    private var pr: PR? = null
    private var pm: PM? = null

    fun onResult(mult: Double, t: Long) {
        pr?.let { emitResultOnly(it) }
        pr = null
        val m = pm
        if (m != null) {
            if (t - m.t in 0..resultAfterMoneyMs) {
                emit(RoundRecord(t, mult, m.b, m.w, RoundClassifier.classify(mult, m.b, m.w)))
                pm = null
                return
            }
            emitMoneyOnly(m)
            pm = null
        }
        pr = PR(mult, t)
    }

    fun onMoney(bets: Long, wins: Long?, t: Long) {
        val r = pr
        if (r != null) {
            if (t - r.t in -1000..moneyAfterResultMs) {
                emit(RoundRecord(r.t, r.m, bets, wins, RoundClassifier.classify(r.m, bets, wins)))
                pr = null
                return
            }
            emitResultOnly(r)
            pr = null
        }
        pm?.let { emitMoneyOnly(it) }
        pm = PM(bets, wins, t)
    }

    /** يُستدعى باستمرار لتصريف الأحداث التي انتهت مهلتها دون شريك. */
    fun tick(now: Long) {
        pr?.let { if (now - it.t > moneyAfterResultMs) { emitResultOnly(it); pr = null } }
        pm?.let { if (now - it.t > resultAfterMoneyMs) { emitMoneyOnly(it); pm = null } }
    }

    private fun emit(r: RoundRecord) = onRecord(r)
    private fun emitResultOnly(r: PR) = emit(RoundRecord(r.t, r.m, null, null, RoundStatus.NO_MONEY))
    private fun emitMoneyOnly(m: PM) =
        emit(RoundRecord(m.t, null, m.b, m.w, RoundClassifier.classify(null, m.b, m.w)))
}
