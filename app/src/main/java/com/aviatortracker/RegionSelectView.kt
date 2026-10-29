package com.aviatortracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View

/** شاشة شفافة كاملة لرسم مستطيل حول منطقة المضاعف. */
class RegionSelectView(
    ctx: Context,
    private val prompt: String = "ارسم مستطيلًا حول رقم المضاعف فقط",
    private val onDone: (Rect?) -> Unit
) : View(ctx) {

    private val border = Paint().apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val text = Paint().apply { color = Color.WHITE; textSize = 44f; isAntiAlias = true }
    private var sx = 0f
    private var sy = 0f
    private var ex = 0f
    private var ey = 0f
    private var drawing = false

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0x66000000)
        canvas.drawText(prompt, 40f, 160f, text)
        if (drawing) canvas.drawRect(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey), border)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = e.x; ey = e.y; drawing = true }
            MotionEvent.ACTION_MOVE -> { ex = e.x; ey = e.y }
            MotionEvent.ACTION_UP -> {
                ex = e.x; ey = e.y; drawing = false
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                val r = Rect(
                    (minOf(sx, ex) + loc[0]).toInt(), (minOf(sy, ey) + loc[1]).toInt(),
                    (maxOf(sx, ex) + loc[0]).toInt(), (maxOf(sy, ey) + loc[1]).toInt()
                )
                onDone(if (r.width() > 20 && r.height() > 20) r else null)
            }
        }
        invalidate()
        return true
    }
}
