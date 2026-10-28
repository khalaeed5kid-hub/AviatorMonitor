package com.aviatortracker

/** تحويل نص OCR لمبلغ مالي إلى سنتات (Long) لتفادي أخطاء الفاصلة العائمة. */
object AmountParser {
    private val candidate = Regex("\\d(?:[\\d., \\u00A0]*\\d)?")

    fun parseCents(raw: String): Long? {
        for (line in raw.split('\n')) {
            val m = candidate.find(line) ?: continue
            return normalize(m.value.replace(" ", "").replace(" ", ""))
        }
        return null
    }

    private fun normalize(s: String): Long? {
        if (s.isEmpty() || s.count { it.isDigit() } > 13) return null
        val idx = s.lastIndexOfAny(charArrayOf('.', ','))
        if (idx < 0) return s.toLongOrNull()?.times(100)
        val after = s.length - idx - 1
        val sep = s[idx]
        return when (after) {
            1, 2 -> {
                val intPart = s.substring(0, idx)
                if (intPart.contains(sep)) return null // 1.2.34 غير منطقي
                val whole = intPart.replace(".", "").replace(",", "")
                if (whole.isEmpty()) return null
                val frac = s.substring(idx + 1).padEnd(2, '0')
                (whole + frac).toLongOrNull()
            }
            3 -> s.replace(".", "").replace(",", "").toLongOrNull()?.times(100) // فاصل آلاف
            else -> null
        }
    }

    fun format(cents: Long, signed: Boolean = false): String {
        val sign = if (cents < 0) "-" else if (signed) "+" else ""
        val a = Math.abs(cents)
        return "%s%d.%02d".format(java.util.Locale.US, sign, a / 100, a % 100)
    }
}
