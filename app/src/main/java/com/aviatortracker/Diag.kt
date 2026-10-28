package com.aviatortracker

import android.graphics.Bitmap

/** حالة التشخيص المشتركة بين الخدمة والواجهة (في الذاكرة فقط). */
object Diag {
    @Volatile var running = false
    @Volatile var rawText = ""
    @Volatile var value: Double? = null
    @Volatile var state: RoundState = RoundState.WAITING
    @Volatile var pending: Double? = null
    @Volatile var lastSaved: Double? = null
    @Volatile var savedCount = 0
    @Volatile var frames = 0L
    @Volatile var blankFrames = 0L
    @Volatile var region = "غير محددة"
    @Volatile var error = ""
    @Volatile var crop: Bitmap? = null
}
