package com.aviatortracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoundTrackerTest {

    private fun run(frames: List<Double?>): List<Double> {
        val out = mutableListOf<Double>()
        val t = RoundTracker { out.add(it) }
        frames.forEach { t.onFrame(it) }
        return out
    }

    @Test
    fun exampleFromSpec_recordsLastMultiplierOnce() {
        // 1.18 → 1.42 → 2.35 → 4.76 → اختفاء المضاعف (العد التنازلي "7" يُقرأ null)
        val r = run(listOf(1.18, 1.42, 2.35, 4.76, null, null, null, null, null))
        assertEquals(listOf(4.76), r)
    }

    @Test
    fun heldFinalValueAcrossFrames_isNotDuplicated() {
        val r = run(listOf(1.10, 1.30, 3.00, 3.00, 3.00, 3.00, null, null, null, null, null, null))
        assertEquals(listOf(3.00), r)
    }

    @Test
    fun countdownNumbersAreNeverResults() {
        val parsed = listOf("7", "6", "5", "4", "3", "2", "1").map { MultiplierParser.parse(it) }
        parsed.forEach { assertNull(it) }
        assertEquals(emptyList<Double>(), run(parsed))
    }

    @Test
    fun twoRoundsBackToBack() {
        val r = run(
            listOf(1.05, 1.20, 2.00, null, null, null, null, null, null, null) +
                listOf(1.02, 1.15, 2.40, 5.00, 9.00, 12.40, null, null, null)
        )
        assertEquals(listOf(2.00, 12.40), r)
    }

    @Test
    fun shortOcrGlitchDuringFlight_doesNotEndRound() {
        val r = run(listOf(1.10, 1.40, null, null, 1.90, 2.50, null, null, null))
        assertEquals(listOf(2.50), r)
    }

    @Test
    fun ocrMisreadsAreRejected() {
        // 4.76 قُرئت خطأً 47.6 (نقطة مفقودة → لا تطابق) أو 1.76 (انخفاض) → تُتجاهل
        val r = run(listOf(1.10, 1.20, 2.00, 4.00, 1.76, 4.76, 47.60, 4.90, null, null, null))
        assertEquals(listOf(4.90), r)
    }

    @Test
    fun startingMidRound_isIgnoredUntilNewRoundStarts() {
        val r = run(listOf(8.50, 9.10, null, null, null, 1.00, 1.20, 1.80, null, null, null))
        assertEquals(listOf(1.80), r)
    }

    @Test
    fun instantCrashAtOne_isRecorded() {
        val r = run(listOf(1.00, 1.00, 1.00, null, null, null))
        assertEquals(listOf(1.00), r)
    }

    @Test
    fun parser_variants() {
        assertEquals(4.76, MultiplierParser.parse("4.76x")!!, 0.0)
        assertEquals(20.20, MultiplierParser.parse(" 20.20 X ")!!, 0.0)
        assertEquals(1.18, MultiplierParser.parse("1,18x")!!, 0.0)
        assertEquals(2.35, MultiplierParser.parse("FLEW AWAY\n2.35x")!!, 0.0)
        assertNull(MultiplierParser.parse(""))
        assertNull(MultiplierParser.parse("7"))
        assertNull(MultiplierParser.parse("1.2x"))
    }
}
