package com.aviatortracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HouseTrackerTest {

    private val rounds = mutableListOf<HouseRound>()
    private fun tracker(cumulative: Boolean = false) =
        HouseTracker(winsCumulative = cumulative) { rounds.add(it) }

    /** كل قيمة تُكرَّر إطارين حتى تصبح "مستقرة" (بالدولار → سنت). */
    private fun HouseTracker.feed(bets: Long?, wins: Long?, times: Int = 2) {
        repeat(times) { onFrame(bets?.times(100), wins?.times(100)) }
    }

    private fun balance() = rounds.sumOf { it.netCents ?: 0L }

    @Test
    fun winsNeverRead_roundIsIncomplete_notZero() {
        val t = tracker()
        repeat(2) { t.onFrame(10000, null) }
        repeat(2) { t.onFrame(0, null) }
        assertEquals(1, rounds.size)
        assertEquals(10000L, rounds[0].betsCents)
        assertNull(rounds[0].winsCents)
        assertNull(rounds[0].netCents)
    }

    @Test
    fun specExample_twoRoundsThenNegativeRound() {
        val t = tracker()
        // الجولة 1: رهانات 100، أرباح 30
        t.feed(40, 0); t.feed(100, 0); t.feed(100, 10); t.feed(100, 30)
        t.feed(0, 0)                         // بدأت جولة جديدة (الرهانات عادت للصفر) → تثبيت الجولة 1
        assertEquals(1, rounds.size)
        assertEquals(7000L, rounds[0].netCents)
        // الجولة 2: رهانات 100، أرباح 90
        t.feed(100, 0); t.feed(100, 90)
        t.feed(0, 0)
        assertEquals(2, rounds.size)
        assertEquals(1000L, rounds[1].netCents)
        assertEquals(8000L, balance())       // +80
        // الجولة 3: رهانات 100، أرباح 200 → صافي −100 → الرصيد −20
        t.feed(100, 0); t.feed(100, 200)
        t.feed(0, 0)
        assertEquals(3, rounds.size)
        assertEquals(-10000L, rounds[2].netCents)
        assertEquals(-2000L, balance())
    }

    @Test
    fun staticScreenAfterFinish_doesNotCountRoundTwice() {
        val t = tracker()
        t.feed(100, 30); t.feed(0, 0)
        t.feed(0, 0, 50)                     // شاشة ثابتة عشرات الإطارات
        t.feed(0, 30, 50)
        assertEquals(1, rounds.size)
    }

    @Test
    fun heldValuesAcrossManyFrames_doNotDuplicate() {
        val t = tracker()
        t.feed(100, 30, 100)
        assertEquals(0, rounds.size)         // الجولة لم تنتهِ بعد
        t.feed(0, 0)
        assertEquals(1, rounds.size)
    }

    @Test
    fun singleFrameOcrGlitch_isIgnored() {
        val t = tracker()
        t.feed(100, 30)
        t.onFrame(1000_00, 5_00)             // قراءة خاطئة لإطار واحد فقط
        t.feed(100, 30)
        t.feed(0, 0)
        assertEquals(1, rounds.size)
        assertEquals(10000L, rounds[0].betsCents)
        assertEquals(3000L, rounds[0].winsCents)
    }

    @Test
    fun nullReadsBreakStability() {
        val t = tracker()
        t.feed(100, 30)
        t.onFrame(null, null); t.onFrame(0, null)   // صفر واحد غير مستقر بعد إطار null
        t.onFrame(null, null)
        assertEquals(0, rounds.size)
    }

    @Test
    fun lowerStableReadingInsideRound_isTreatedAsMisread() {
        val t = tracker()
        t.feed(1000, 0)
        t.feed(700, 0)                       // ≥ نصف الأعلى → خطأ OCR، لا جولة جديدة
        assertEquals(0, rounds.size)
        t.feed(0, 0)
        assertEquals(1, rounds.size)
        assertEquals(100000L, rounds[0].betsCents)
    }

    @Test
    fun missedZero_bigDropStartsNewRound() {
        val t = tracker()
        t.feed(1000, 400)
        t.feed(50, 0)                        // لم نلتقط الصفر؛ انخفاض حاد = جولة جديدة
        assertEquals(1, rounds.size)
        assertEquals(60000L, rounds[0].netCents)
        t.feed(300, 100); t.feed(0, 0)
        assertEquals(2, rounds.size)
        assertEquals(300_00L - 100_00L, rounds[1].netCents)
    }

    @Test
    fun cumulativeWins_usesDeltaBetweenRounds() {
        val t = tracker(cumulative = true)
        t.feed(100, 0); t.feed(100, 30); t.feed(0, 30)       // الأرباح التراكمية تبقى 30 عند بدء الجولة الجديدة
        assertEquals(1, rounds.size)
        assertEquals(3000L, rounds[0].winsCents)
        t.feed(100, 30); t.feed(100, 120); t.feed(0, 120)
        assertEquals(2, rounds.size)
        assertEquals(9000L, rounds[1].winsCents)             // 120 − 30
        assertEquals(1000L, rounds[1].netCents)
    }

    @Test
    fun resetState_discardsPartialRound() {
        val t = tracker()
        t.feed(100, 30)
        t.resetState()
        t.feed(0, 0)
        assertEquals(0, rounds.size)
    }

    @Test
    fun noBetsAtAll_neverRecordsEmptyRound() {
        val t = tracker()
        t.feed(0, 0, 10)
        assertEquals(0, rounds.size)
    }

    @Test
    fun amountParser_formats() {
        assertEquals(12345600L, AmountParser.parseCents("123,456.00"))
        assertEquals(12345600L, AmountParser.parseCents("\$123 456,00"))
        assertEquals(123456L, AmountParser.parseCents("1,234.56"))
        assertEquals(123456L, AmountParser.parseCents("1.234,56 USD"))
        assertEquals(150L, AmountParser.parseCents("1,5"))
        assertEquals(123400L, AmountParser.parseCents("1,234"))
        assertEquals(0L, AmountParser.parseCents("0.00"))
        assertEquals(0L, AmountParser.parseCents("0"))
        assertEquals(5000L, AmountParser.parseCents("Total\n50"))
        assertNull(AmountParser.parseCents(""))
        assertNull(AmountParser.parseCents("abc"))
        assertNull(AmountParser.parseCents("1.2.34"))
    }

    @Test
    fun moneyFormat_signs() {
        assertEquals("+80.00", AmountParser.format(8000, signed = true))
        assertEquals("-20.00", AmountParser.format(-2000, signed = true))
        assertEquals("0.05", AmountParser.format(5))
    }
}
