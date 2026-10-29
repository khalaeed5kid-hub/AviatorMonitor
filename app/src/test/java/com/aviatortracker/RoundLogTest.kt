package com.aviatortracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class RoundLogTest {

    // ---------- الربط بين النتيجة والأموال ----------
    private val out = mutableListOf<RoundRecord>()
    private fun corr() = RoundCorrelator { out.add(it) }

    @Test
    fun normalOrder_resultThenMoney_oneRecord() {
        val c = corr()
        c.onResult(1.25, 1_000)
        c.onMoney(10_000_00, 3_000_00, 4_000)
        c.tick(60_000)
        assertEquals(1, out.size)
        assertEquals(RoundStatus.OK, out[0].status)
        assertEquals(1.25, out[0].mult!!, 0.0)
        assertEquals(7_000_00L, out[0].netCents)
        assertEquals(1_000L, out[0].ts)
    }

    @Test
    fun moneyFirstThenResult_isPaired() {
        val c = corr()
        c.onMoney(100_00, 30_00, 5_000)
        c.onResult(2.0, 7_000)
        assertEquals(1, out.size)
        assertEquals(RoundStatus.OK, out[0].status)
        assertEquals(2.0, out[0].mult!!, 0.0)
    }

    @Test
    fun missingMoney_recordedAsIncomplete_andNextRoundNotMixed() {
        val c = corr()
        c.onResult(1.5, 1_000)                       // الجولة 1: لم تصل أموالها
        c.onResult(3.0, 20_000)                      // الجولة 2
        c.onMoney(500_00, 400_00, 22_000)            // أموال الجولة 2
        assertEquals(2, out.size)
        assertEquals(RoundStatus.NO_MONEY, out[0].status)
        assertEquals(1.5, out[0].mult!!, 0.0)
        assertNull(out[0].netCents)
        assertEquals(RoundStatus.OK, out[1].status)
        assertEquals(3.0, out[1].mult!!, 0.0)
        assertEquals(100_00L, out[1].netCents)
    }

    @Test
    fun missingResult_moneyStillCounted_resultUnknown() {
        val c = corr()
        c.onMoney(200_00, 50_00, 1_000)
        c.tick(20_000)
        assertEquals(1, out.size)
        assertEquals(RoundStatus.NO_RESULT, out[0].status)
        assertNull(out[0].mult)
        assertEquals(150_00L, out[0].netCents)
    }

    @Test
    fun timeoutFlush_resultOnly() {
        val c = corr()
        c.onResult(1.1, 1_000)
        c.tick(5_000); assertEquals(0, out.size)
        c.tick(14_000); assertEquals(1, out.size)
        c.tick(99_000); assertEquals(1, out.size)    // لا تكرار
    }

    @Test
    fun suspectPayout_exceedsBetsTimesMultiplier() {
        val c = corr()
        c.onResult(1.25, 1_000)
        c.onMoney(100_00, 5_000_00, 2_000)           // مدفوعات 5000 من رهانات 100 عند 1.25x: مستحيل
        assertEquals(RoundStatus.SUSPECT, out[0].status)
    }

    @Test
    fun legitBigPayout_isNotSuspect() {
        val c = corr()
        c.onResult(50.0, 1_000)
        c.onMoney(100_00, 4_000_00, 2_000)           // 40x من الرهانات ≤ 50x
        assertEquals(RoundStatus.OK, out[0].status)
    }

    @Test
    fun eachRoundRecordedOnce_manyRounds() {
        val c = corr()
        var t = 0L
        repeat(20) { i ->
            t += 15_000
            c.onResult(1.0 + i, t)
            c.onMoney(100_00, 10_00, t + 3_000)
            c.tick(t + 4_000)
        }
        c.tick(t + 100_000)
        assertEquals(20, out.size)
        assertEquals(true, out.all { it.status == RoundStatus.OK })
    }

    // ---------- الرصيد التراكمي والأرقام الكبيرة ----------
    private fun rec(bets: Long?, wins: Long?, mult: Double? = 2.0, status: Int = RoundClassifier.classify(mult, bets, wins)) =
        RoundRecord(0, mult, bets, wins, status)

    @Test
    fun ledger_specExample() {
        var bal = 200_000_00L
        val rounds = listOf(
            rec(100_000_00, 30_000_00, 1.25), rec(100_000_00, 90_000_00, 2.0), rec(100_000_00, 200_000_00, 5.4)
        )
        val expected = listOf(270_000_00L, 280_000_00L, 180_000_00L)
        rounds.forEachIndexed { i, r ->
            val s = Ledger.next(bal, r)
            assertEquals(bal, s.before)
            assertEquals(expected[i], s.after)
            bal = s.after
        }
    }

    @Test
    fun ledger_millionsAndBeyondIntRange_noOverflow() {
        // 100 مليون دولار = 10^10 سنت > حد Int (2.1×10^9)
        var bal = 0L
        var big = BigInteger.ZERO
        val data = listOf(
            5_000_000_00L to 1_000_000_00L,
            90_000_000_00L to 10_000_000_00L,
            250_000_000_00L to 700_000_000_00L,   // ينزل لسالب
            100_000_000_00L to 20_000_000_00L,
            2_000_000_000_00L to 100_000_000_00L
        )
        for ((b, w) in data) {
            bal = Ledger.next(bal, rec(b, w, 1000.0)).after
            big = big.add(BigInteger.valueOf(b - w))
            assertEquals(big.toLong(), bal)
        }
        assertEquals(true, bal > 0)
    }

    @Test
    fun ledger_positiveToNegativeAndBack() {
        var bal = 0L
        bal = Ledger.next(bal, rec(100_000_00, 30_000_00)).after
        assertEquals(70_000_00L, bal)
        bal = Ledger.next(bal, rec(100_000_00, 190_000_00, 5.0)).after
        assertEquals(-20_000_00L, bal)                         // سالب فعلي
        bal = Ledger.next(bal, rec(100_000_00, 50_000_00)).after
        assertEquals(30_000_00L, bal)                          // عاد موجبًا
    }

    @Test
    fun ledger_incompleteAndSuspectRounds_doNotChangeBalance() {
        val bal = 50_000_00L
        assertEquals(bal, Ledger.next(bal, rec(100_00, null)).after)
        assertEquals(bal, Ledger.next(bal, RoundRecord(0, 1.2, 100_00, 9_999_999_00, RoundStatus.SUSPECT)).after)
        assertEquals(bal, Ledger.next(bal, RoundRecord(0, 1.2, null, null, RoundStatus.NO_MONEY)).after)
    }

    @Test
    fun ledger_saturatesInsteadOfWrapping() {
        val s = Ledger.next(Long.MAX_VALUE - 5, rec(100, 0, 2.0))
        assertEquals(Long.MAX_VALUE, s.after)
    }

    // ---------- الاختصار للعرض فقط ----------
    @Test
    fun abbrev_examples() {
        assertEquals("1K", AmountParser.abbrev(1_000_00))
        assertEquals("10K", AmountParser.abbrev(10_000_00))
        assertEquals("125K", AmountParser.abbrev(125_000_00))
        assertEquals("1M", AmountParser.abbrev(1_000_000_00))
        assertEquals("1.5M", AmountParser.abbrev(1_500_000_00))
        assertEquals("2.5M", AmountParser.abbrev(2_500_000_00))
        assertEquals("3B", AmountParser.abbrev(3_000_000_000_00))
        assertEquals("−1.5M", AmountParser.abbrev(-1_500_000_00))
        assertEquals("+70K", AmountParser.abbrev(70_000_00, signed = true))
        assertEquals("−100K", AmountParser.abbrev(-100_000_00, signed = true))
        assertEquals("1M", AmountParser.abbrev(999_999_00))       // ترحيل التقريب
        assertEquals("950", AmountParser.abbrev(950_00))
        assertEquals("12.5", AmountParser.abbrev(12_50))
        assertEquals("0", AmountParser.abbrev(0))
        assertEquals("−0.01", AmountParser.abbrev(-1))
    }

    @Test
    fun abbrevDoesNotAffectExactValues() {
        val exact = 1_234_567_89L
        assertEquals("1.23M", AmountParser.abbrev(exact))
        assertEquals("1,234,567.89", AmountParser.grouped(exact))
        assertEquals("1234567.89", AmountParser.format(exact))
        assertEquals("−1,234,567.89", AmountParser.grouped(-exact))
        assertEquals("+1,234,567.89", AmountParser.grouped(exact, signed = true))
    }

    // ---------- حفظ المناطق واستعادتها ----------
    @Test
    fun regionCodec_roundTripAndBounds() {
        val s = RegionCodec.encode(100, 200, 400, 300)
        val a = RegionCodec.decode(s)!!
        assertEquals(listOf(100, 200, 400, 300), a.toList())
        assertEquals(true, RegionCodec.fits(a, 1080, 2400))
        assertEquals(false, RegionCodec.fits(a, 300, 2400))        // أبعاد شاشة مختلفة → غير صالحة
        assertNull(RegionCodec.decode(null))
        assertNull(RegionCodec.decode("1,2,3"))
        assertNull(RegionCodec.decode("10,10,5,5"))
        assertEquals("2_1080x2400", RegionCodec.key(2, 1080, 2400))
    }
}
