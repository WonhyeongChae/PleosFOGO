package com.ssafy.fogo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

class AtlasOverview(context: Context, private val colors: AtlasColors, private val progress: Map<String, Float>) : View(context) {
    private val paint = Paint()
    private val peninsula = listOf(9..14, 8..15, 8..15, 7..16, 7..17, 6..17, 6..18,
        6..18, 5..18, 5..17, 5..16, 6..15, 6..14, 5..14, 4..13, 4..11, 3..9, 3..7, 2..5)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colors.fog)
        val size = minOf(width / 24f, height / 22f)
        val left = (width - 22 * size) / 2f
        val top = (height - 20 * size) / 2f
        paint.color = if (colors.night) 0xff4a4f5a.toInt() else 0xffd2d6dd.toInt()
        for (y in 0..20 step 2) for (x in 0..22 step 2) {
            val px = left + (x + y % 3) * size
            val py = top + y * size
            canvas.drawRect(px, py, px + size / 5, py + size / 5, paint)
        }
        peninsula.forEachIndexed { y, row ->
            row.forEach { x ->
                val zone = when {
                    y in 3..6 && x in 7..9 -> "SEOUL"
                    y in 1..8 && x >= 12 -> "GANGWON"
                    y in 12..15 && x in 11..14 -> "BUSAN"
                    else -> null
                }
                val fraction = progress[zone] ?: 0f
                val marked = zone != null && fraction > 0f && ((x * 7 + y * 11) % 20) < maxOf(2f, fraction * 20)
                paint.color = if (marked) colors.lime else if (colors.night) 0xff2c3d40.toInt() else 0xffeef2e6.toInt()
                val px = left + x * size
                val py = top + y * size
                canvas.drawRect(px, py, px + size - 1, py + size - 1, paint)
            }
        }
        paint.color = if (colors.night) 0xff2c3d40.toInt() else 0xffdcebc9.toInt()
        canvas.drawRect(left + size * 4, top + size * 20, left + size * 7, top + size * 21, paint)
    }
}
