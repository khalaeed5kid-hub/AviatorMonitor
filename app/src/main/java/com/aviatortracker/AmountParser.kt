package com.aviatortracker

/** تحويل نص OCR لمبلغ مالي إلى سنتات (Long) لتفادي أخطاء الفاصلة العائمة، وتنسيقات العرض. */
object AmountParser {
    private val candidate = Regex("\\d(?:[\\d., \\u00A0]*\\d)?")
    private const val MINUS = "−"

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

    /** نص دقيق للنسخ (علامة - عادية). */
    fun format(cents: Long, signed: Boolean = false): String {
        val sign = if (cents < 0) "-" else if (signed) "+" else ""
        val a = if (cents == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(cents)
        return "%s%d.%02d".format(java.util.Locale.US, sign, a / 100, a % 100)
    }

    /** عرض كامل بالتفصيل مع فواصل الآلاف: −1,234,567.89 */
    fun grouped(cents: Long, signed: Boolean = false): String {
        val a = if (cents == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(cents)
        val body = "%,d.%02d".format(java.util.Locale.US, a / 100, a % 100)
        return (if (cents < 0) MINUS else if (signed && cents > 0) "+" else "") + body
    }

    /** اختصار للعرض فقط: 1K، 125K، 1.5M، 2.5B، −1.5M. لا يُستخدم أبدًا في الحساب. */
    fun abbrev(cents: Long, signed: Boolean = false): String {
        val units = arrayOf("", "K", "M", "B")
        var v = (if (cents == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(cents)).toDouble() / 100.0
        var i = 0
        while (i < 3 && Math.round(v * 100) / 100.0 >= 1000.0) { v /= 1000.0; i++ }
        val rounded = Math.round(v * 100) / 100.0
        var s = "%.2f".format(java.util.Locale.US, rounded).trimEnd('0').trimEnd('.')
        if (s.isEmpty()) s = "0"
        val sign = if (s == "0") "" else if (cents < 0) MINUS else if (signed) "+" else ""
        return sign + s + units[i]
    }
}
