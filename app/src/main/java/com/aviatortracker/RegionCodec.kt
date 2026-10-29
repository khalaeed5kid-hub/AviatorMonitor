package com.aviatortracker

/** ترميز/فك ترميز المناطق وفحص صلاحيتها لأبعاد الشاشة (منطق صرف قابل للاختبار). */
object RegionCodec {
    fun encode(l: Int, t: Int, r: Int, b: Int) = "$l,$t,$r,$b"

    fun decode(s: String?): IntArray? {
        val p = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        return if (p.size == 4 && p[2] > p[0] && p[3] > p[1]) p.toIntArray() else null
    }

    fun fits(a: IntArray, w: Int, h: Int) = a[0] >= 0 && a[1] >= 0 && a[2] <= w && a[3] <= h

    fun key(id: Int, w: Int, h: Int) = "${id}_${w}x$h"
}
