package com.aviatortracker

enum class RoundState { WAITING, FLYING }

/**
 * آلة حالات الجولة. تُستدعى onFrame مرة لكل إطار (قيمة المضاعف أو null إن لم يُقرأ مضاعف صالح).
 *  - WAITING: انتظار جولة جديدة. تبدأ الجولة بعد قراءتين صالحتين متتاليتين غير متناقصتين وقيمتهما ≤ startMaxValue
 *    (الجولة تبدأ من 1.00x، فلا نبدأ في منتصف جولة).
 *  - FLYING: نحتفظ بآخر قيمة موثوقة (غير متناقصة، بلا قفزات مشبوهة).
 *    عند غياب المضاعف missFramesToEnd إطارات متتالية (ظهور العد التنازلي) → تُسجَّل آخر قيمة مرة واحدة ثم نعود إلى WAITING.
 */
class RoundTracker(
    private val missFramesToEnd: Int = 3,
    private val startMaxValue: Double = 1.5,
    private val onResult: (Double) -> Unit
) {
    var state: RoundState = RoundState.WAITING
        private set
    var lastValid: Double? = null
        private set

    private var misses = 0
    private var startCandidate: Double? = null
    private var suspect: Double? = null

    fun onFrame(v: Double?) {
        when (state) {
            RoundState.WAITING -> waiting(v)
            RoundState.FLYING -> flying(v)
        }
    }

    private fun waiting(v: Double?) {
        if (v == null || v > startMaxValue) {
            startCandidate = null
            return
        }
        val c = startCandidate
        if (c != null && v >= c) {
            state = RoundState.FLYING
            lastValid = v
            misses = 0
            suspect = null
            startCandidate = null
        } else {
            startCandidate = v
        }
    }

    private fun flying(v: Double?) {
        if (v == null) {
            misses++
            if (misses >= missFramesToEnd) finish()
            return
        }
        misses = 0
        val last = lastValid ?: return
        when {
            v >= last && v <= last * 2 + 1 -> {
                lastValid = v
                suspect = null
            }
            v > last * 2 + 1 -> {
                // قفزة كبيرة: لا نقبلها إلا إذا تأكدت بقراءة تالية متقاربة
                val s = suspect
                if (s != null && v >= s && v <= s * 1.5 + 0.5) {
                    lastValid = v
                    suspect = null
                } else {
                    suspect = v
                }
            }
            // v < last: قراءة OCR خاطئة، تُتجاهل
        }
    }

    private fun finish() {
        val r = lastValid
        state = RoundState.WAITING
        lastValid = null
        misses = 0
        suspect = null
        startCandidate = null
        if (r != null) onResult(r)
    }
}
