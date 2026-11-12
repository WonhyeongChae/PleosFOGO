package com.ssafy.fogo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor

/** Pixel icon drawn from rows of characters: '#' uses [ink], '.' stays empty. */
class PixelIconView(context: Context, private val rows: List<String>, var ink: Int) : View(context) {
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        val columns = rows.maxOf { it.length }
        val cell = floor(minOf(width.toFloat() / columns, height.toFloat() / rows.size))
        if (cell <= 0f) return
        val left = (width - cell * columns) / 2f
        val top = (height - cell * rows.size) / 2f
        paint.color = ink
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, mark ->
                if (mark == '#') canvas.drawRect(left + x * cell, top + y * cell,
                    left + (x + 1) * cell, top + (y + 1) * cell, paint)
            }
        }
    }

    companion object {
        val ATLAS = listOf(
            "##########",
            "#...##...#",
            "#..#..#..#",
            "#.#....#.#",
            "##..##..##",
            "#..#..#..#",
            "#.#....#.#",
            "##..##..##",
            "#...##...#",
            "##########",
        )
        val GARAGE = listOf(
            "..........",
            "...####...",
            "..#....#..",
            ".#......#.",
            "##########",
            "#.######.#",
            "##########",
            ".##....##.",
            ".##....##.",
            "..........",
        )
        val RECAP = listOf(
            "##########",
            "#........#",
            "#.######.#",
            "#........#",
            "#.##.###.#",
            "#........#",
            "#.#####..#",
            "#........#",
            "#.###....#",
            "##########",
        )
        val SETTINGS = listOf(
            "....##....",
            ".#..##..#.",
            "..######..",
            ".###..###.",
            "###....###",
            "###....###",
            ".###..###.",
            "..######..",
            ".#..##..#.",
            "....##....",
        )
    }
}

/** Ticket perforation: a dashed line along the long side of the view. */
class DashedLine(context: Context, private val color: Int, private val dash: Float, private val gap: Float) : View(context) {
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val vertical = height >= width
        val length = if (vertical) height else width
        var at = gap / 2
        while (at < length) {
            val end = minOf(at + dash, length.toFloat())
            if (vertical) canvas.drawRect(0f, at, width.toFloat(), end, paint)
            else canvas.drawRect(at, 0f, end, height.toFloat(), paint)
            at += dash + gap
        }
    }
}

/**
 * Parked-only animated version of the driving artwork: drifting clouds, mountains at two parallax
 * speeds, flowing lane dashes and a car that bobs one pixel. It steps at a fixed 8 fps so the motion
 * reads as pixel animation. The driving screen keeps the still [AtlasArtworkView] on purpose.
 *
 * The layers are split by the artwork palette used in atlas_art.json (drive_wide / drive_portrait).
 */
class DriveSceneView(
    context: Context, private val art: AtlasArt.Artwork, private val night: Boolean, private val fast: Boolean = false,
) : View(context) {
    private val paint = Paint()
    private val animate = ValueAnimator.areAnimatorsEnabled()
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, FRAME_MS)
        }
    }

    private val road = art.pixels.firstOrNull { it.color == ROAD }
    private val grass = art.pixels.firstOrNull { it.color == GRASS }
    private val roadTop = road?.y ?: art.height * .7f
    private val back = art.pixels.filter { it.color == MOUNTAIN_BACK }
    private val front = art.pixels.filter { it.color == MOUNTAIN_FRONT }
    private val dashes = art.pixels.filter { it.color == YELLOW && it.y > roadTop + 80 }
    private val sun = art.pixels.filter { it.color == YELLOW && it.y < roadTop - 200 }
    private val wheels = art.pixels.filter { it.color in WHEEL_COLORS }
    private val car = art.pixels.filter {
        it !== road && it !== grass && it.color !in setOf(MOUNTAIN_BACK, MOUNTAIN_FRONT) &&
            it !in dashes && it !in sun && it.color !in WHEEL_COLORS
    }
    private val carLeft = (car + wheels).minOfOrNull { it.x } ?: art.width * .3f
    private val carRight = (car + wheels).maxOfOrNull { it.x + it.w } ?: art.width * .6f
    private val carBottom = (car + wheels).maxOfOrNull { it.y + it.h } ?: roadTop + 200
    private val sceneTop = ((back + front).minOfOrNull { it.y } ?: 0f) - 60f
    private val dashY = dashes.firstOrNull()?.y ?: (carBottom + 20)
    private val dashH = dashes.firstOrNull()?.h ?: 14f
    private val dashW = dashes.firstOrNull()?.w ?: 70f
    private val dashPeriod = dashes.map { it.x }.sorted().zipWithNext { a, b -> b - a }.filter { it > 0 }.minOrNull() ?: 140f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animate) post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        // Frame from the clock, so a re-render of the screen does not restart the motion.
        val frame = if (animate) SystemClock.uptimeMillis() / FRAME_MS else 0L
        val speed = if (fast) 3f else 1f
        val sceneBottom = maxOf(carBottom, dashY + dashH) + 50f
        val scale = minOf(height / (sceneBottom - sceneTop), width / ((carRight - carLeft) + 240f))
        val center = (carLeft + carRight) / 2f
        val left = center - width / 2f / scale
        val right = center + width / 2f / scale

        canvas.drawColor(tint(art.background ?: SKY))
        canvas.save()
        canvas.translate(width / 2f - center * scale, -sceneTop * scale)
        canvas.scale(scale, scale)

        sun.forEach { draw(canvas, it) }
        clouds(canvas, frame, left, right, speed)
        layer(canvas, back, -(frame * 2f * speed), left, right)
        layer(canvas, front, -(frame * 5f * speed), left, right)
        grass?.let { paint.color = tint(it.color); canvas.drawRect(left, it.y, right, it.y + it.h, paint) }
        road?.let { paint.color = tint(it.color); canvas.drawRect(left, it.y, right, sceneBottom + art.height, paint) }

        paint.color = tint(YELLOW)
        val phase = (frame * 22f * speed) % dashPeriod
        var x = floor((left - dashPeriod) / dashPeriod) * dashPeriod - phase
        while (x < right) {
            canvas.drawRect(x, dashY, x + dashW, dashY + dashH, paint)
            x += dashPeriod
        }

        wheels.forEach { draw(canvas, it) }
        val bob = if (animate && (frame / 2) % 2 == 0L) -6f else 0f
        canvas.save()
        canvas.translate(0f, bob)
        car.forEach { draw(canvas, it) }
        canvas.restore()
        canvas.restore()
    }

    private fun draw(canvas: Canvas, pixel: AtlasArt.Pixel, dx: Float = 0f) {
        paint.color = tint(pixel.color)
        canvas.drawRect(pixel.x + dx, pixel.y, pixel.x + pixel.w + dx, pixel.y + pixel.h, paint)
    }

    /** Repeats a mountain layer every artwork width, shifted by [offset], across the visible range. */
    private fun layer(canvas: Canvas, pixels: List<AtlasArt.Pixel>, offset: Float, left: Float, right: Float) {
        if (pixels.isEmpty()) return
        val period = art.width
        val shift = ((offset % period) + period) % period
        val first = floor((left - art.width - shift) / period).toInt()
        val last = ceil((right - shift) / period).toInt()
        for (copy in first..last) {
            val dx = shift + copy * period
            pixels.forEach { pixel ->
                if (pixel.x + pixel.w + dx >= left && pixel.x + dx <= right) draw(canvas, pixel, dx)
            }
        }
    }

    private fun clouds(canvas: Canvas, frame: Long, left: Float, right: Float, speed: Float) {
        paint.color = if (night) 0xff2a3a4c.toInt() else 0xffffffff.toInt()
        val span = (right - left) + 400f
        val unit = 14f
        CLOUDS.forEachIndexed { index, (start, height) ->
            val travel = frame * (1.5f + index * .7f) * speed
            val x = left - 200f + (((start * span - travel) % span) + span) % span
            val y = sceneTop + 60f + height
            CLOUD.forEachIndexed { row, line ->
                line.forEachIndexed { column, mark ->
                    if (mark == '#') canvas.drawRect(x + column * unit, y + row * unit,
                        x + (column + 1) * unit, y + (row + 1) * unit, paint)
                }
            }
        }
    }

    private fun tint(color: Int) = if (night) NIGHT[color] ?: color else color

    companion object {
        private const val FRAME_MS = 125L
        private const val SKY = 0xffa7d8ff.toInt()
        private const val YELLOW = 0xffffd43b.toInt()
        private const val MOUNTAIN_BACK = 0xff6faf88.toInt()
        private const val MOUNTAIN_FRONT = 0xff8cc5a0.toInt()
        private const val GRASS = 0xffa8d672.toInt()
        private const val ROAD = 0xff3a3f48.toInt()
        private val WHEEL_COLORS = setOf(0xff2b2f36.toInt(), 0xff9aa3ae.toInt())
        private val CLOUDS = listOf(.1f to 0f, .45f to 70f, .8f to 20f)
        private val CLOUD = listOf(
            "..####....",
            ".######.#.",
            "##########",
        )
        private val NIGHT = mapOf(
            SKY to 0xff17283a.toInt(),
            YELLOW to 0xfff2f4f7.toInt(),
            MOUNTAIN_BACK to 0xff253f36.toInt(),
            MOUNTAIN_FRONT to 0xff33564b.toInt(),
            GRASS to 0xff1e2a1e.toInt(),
            ROAD to 0xff1a1d20.toInt(),
        )
    }
}
