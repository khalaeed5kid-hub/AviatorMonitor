package com.aviatortracker

/** يحوّل نص OCR إلى مضاعف. يقبل فقط الصيغة ذات خانتين عشريتين (مثل 4.76x)،
 *  لذلك أرقام العد التنازلي (7، 6، 5 ...) لا تُقبل أبدًا كمضاعف. */
object MultiplierParser {
    private val pattern = Regex("^(\\d{1,5})\\.(\\d{2})[xX×х]?$")

    fun parse(raw: String): Double? {
        for (line in raw.split('\n')) {
            val s = line.replace(Regex("\\s+"), "").replace(',', '.')
            val m = pattern.find(s) ?: continue
            return (m.groupValues[1] + "." + m.groupValues[2]).toDoubleOrNull()
        }
        return null
    }
}
