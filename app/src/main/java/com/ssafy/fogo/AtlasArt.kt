package com.ssafy.fogo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.json.JSONObject

class AtlasArt(context: Context) {
    data class Pixel(val x: Float, val y: Float, val w: Float, val h: Float, val color: Int)
    data class Artwork(val width: Float, val height: Float, val background: Int?, val pixels: List<Pixel>)

    private val sprites = JSONObject(context.assets.open("atlas_art.json").bufferedReader().use { it.readText() })
    private val cache = mutableMapOf<String, Artwork>()

    operator fun get(name: String): Artwork = cache.getOrPut(name) {
        val shape = sprites.getJSONObject(name)
        val rects = shape.getJSONArray("rects")
        Artwork(shape.getDouble("w").toFloat(), shape.getDouble("h").toFloat(),
            shape.optString("bg").takeIf { it.startsWith("#") }?.let(Color::parseColor),
            List(rects.length()) { i ->
                val rect = rects.getJSONArray(i)
                Pixel(rect.getDouble(0).toFloat(), rect.getDouble(1).toFloat(),
                    rect.getDouble(2).toFloat(), rect.getDouble(3).toFloat(), Color.parseColor(rect.getString(4)))
            })
    }
}

class AtlasArtworkView(
    context: Context, private val artwork: AtlasArt.Artwork, private val night: Boolean = false,
    private val rain: Boolean = false,
) : View(context) {
    private val paint = Paint()
    private val nightColors = mapOf(
        0xffa7d8ff.toInt() to 0xff17283a.toInt(),
        0xffffd43b.toInt() to 0xfff2f4f7.toInt(),
        0xff6faf88.toInt() to 0xff253f36.toInt(),
        0xff8cc5a0.toInt() to 0xff33564b.toInt(),
        0xffa8d672.toInt() to 0xff1e2a1e.toInt(),
        0xff3a3f48.toInt() to 0xff1a1d20.toInt(),
    )

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width / artwork.width, height / artwork.height)
        fun tint(color: Int) = if (night) nightColors[color] ?: color else color
        artwork.background?.let { canvas.drawColor(tint(it)) }
        canvas.save()
        canvas.translate((width - artwork.width * scale) / 2, (height - artwork.height * scale) / 2)
        canvas.scale(scale, scale)
        paintArtwork(canvas, paint, artwork, ::tint)
        if (rain) {
            paint.color = if (night) 0xff879eae.toInt() else 0xff5b88aa.toInt()
            for (column in 0..17) {
                val x = artwork.width * (column + 1) / 19f
                for (band in 0..2) {
                    val y = artwork.height * ((column * 17 + band * 31) % 100) / 115f
                    canvas.drawRect(x, y, x + 4f, y + 20f, paint)
                }
            }
        }
        canvas.restore()
    }
}

class GarageArtwork(
    context: Context, private val art: AtlasArt, private val colors: AtlasColors, private val mascot: String,
    private val grande: Boolean, private val wrap: Int?, private val hat: Int?,
    private val scarf: Int?, private val scenery: Int?,
) : View(context) {
    private val paint = Paint()
    private val background = art["garage"]
    private val car = art["car"]
    private val face = art[mascot]
    private val hatArt = art["hat"]

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width / 820f, height / 640f)
        canvas.drawColor(colors.paper)
        canvas.save()
        canvas.translate((width - 820f * scale) / 2, (height - 640f * scale) / 2)
        canvas.scale(scale, scale)
        canvas.clipRect(0f, 0f, 820f, 640f)
        paintArtwork(canvas, paint, background) { color ->
            when (color) {
                0xffe4e6ea.toInt() -> scenery ?: colors.fog
                0xffebedf1.toInt() -> if (colors.night) colors.background else color
                else -> color
            }
        }
        canvas.save()
        canvas.translate(if (grande) 222f else 252f, if (grande) 301f else 293.2f)
        if (grande) canvas.scale(1.20f, .94f)
        paintArtwork(canvas, paint, car) { color ->
            if (color == 0xff1f5fae.toInt()) wrap ?: if (grande) 0xff6d7b90.toInt() else color else color
        }
        canvas.restore()
        canvas.save()
        canvas.translate(410f - face.width / 2f, 349f - face.height)
        paintArtwork(canvas, paint, face) { it }
        canvas.restore()
        if (hat != null) {
            canvas.save()
            canvas.translate(355f, 294f - face.height)
            paintArtwork(canvas, paint, hatArt) { if (it == 0xffb8f53c.toInt()) hat else it }
            canvas.restore()
        }
        if (scarf != null) {
            // Scarf on the mascot's pixel grid (11 px cells): a band over the bottom row of the body
            // and a tail on the right, outlined so a light scarf still reads on a white mascot.
            val u = 11f
            val x0 = 410f - face.width / 2f
            val y0 = 349f - face.height + u * 10
            drawScarf(canvas, paint, scarf, x0 + u, y0, u)
        }
        canvas.restore()
    }
}

private fun paintArtwork(canvas: Canvas, paint: Paint, art: AtlasArt.Artwork, color: (Int) -> Int) {
    art.background?.let {
        paint.color = color(it)
        canvas.drawRect(0f, 0f, art.width, art.height, paint)
    }
    for (pixel in art.pixels) {
        paint.color = color(pixel.color)
        canvas.drawRect(pixel.x, pixel.y, pixel.x + pixel.w, pixel.y + pixel.h, paint)
    }
}

/** Knit scarf in pixel cells of [u]: 14-cell band with a 2-cell tail hanging at cells 9–10. */
internal fun drawScarf(canvas: Canvas, paint: Paint, color: Int, x: Float, y: Float, u: Float) {
    val outline = 0xff0b1426.toInt()
    val knit = darker(color)
    paint.color = outline
    canvas.drawRect(x, y - u * .3f, x + u * 14, y + u * 1.3f, paint)
    canvas.drawRect(x + u * 9 - u * .3f, y + u, x + u * 11 + u * .3f, y + u * 3.3f, paint)
    for (cell in 0 until 14) {
        paint.color = if (cell % 2 == 0) color else knit
        canvas.drawRect(x + u * cell, y, x + u * (cell + 1), y + u, paint)
    }
    for (row in 1..2) for (cell in 9..10) {
        paint.color = if ((cell + row) % 2 == 0) color else knit
        canvas.drawRect(x + u * cell, y + u * row, x + u * (cell + 1), y + u * (row + 1), paint)
    }
}

private fun darker(color: Int): Int {
    fun channel(shift: Int) = ((color shr shift and 0xff) * .78f).toInt()
    return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** Pixel icon of a costume for the workshop and dress cards, drawn in the item's colour. */
class CostumeIcon(
    context: Context, private val art: AtlasArt, private val slot: String, private val color: Int,
    private val night: Boolean,
) : View(context) {
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(if (night) 0xff262930.toInt() else 0xfff3f4f6.toInt())
        when (slot) {
            "hat" -> fit(canvas, art["hat"]) { if (it == 0xffb8f53c.toInt()) color else it }
            "wrap" -> fit(canvas, art["car"]) { if (it == 0xff1f5fae.toInt()) color else it }
            "clothes" -> {
                val u = minOf(width / 16f, height / 6f)
                drawScarf(canvas, paint, color, (width - u * 14) / 2f, (height - u * 3) / 2f, u)
            }
            else -> {
                // Background: a little sky-and-ground tile in the item's colour.
                val u = minOf(width, height) / 8f
                val x = (width - u * 8) / 2f
                val y = (height - u * 8) / 2f
                paint.color = 0xff0b1426.toInt()
                canvas.drawRect(x, y, x + u * 8, y + u * 8, paint)
                paint.color = color
                canvas.drawRect(x + u * .5f, y + u * .5f, x + u * 7.5f, y + u * 5f, paint)
                paint.color = 0xffa8d672.toInt()
                canvas.drawRect(x + u * .5f, y + u * 5f, x + u * 7.5f, y + u * 7.5f, paint)
                paint.color = 0xffffd43b.toInt()
                canvas.drawRect(x + u * 5f, y + u * 1.2f, x + u * 6.4f, y + u * 2.6f, paint)
            }
        }
    }

    private fun fit(canvas: Canvas, artwork: AtlasArt.Artwork, recolor: (Int) -> Int) {
        val scale = minOf(width * .8f / artwork.width, height * .8f / artwork.height)
        canvas.save()
        canvas.translate((width - artwork.width * scale) / 2, (height - artwork.height * scale) / 2)
        canvas.scale(scale, scale)
        paintArtwork(canvas, paint, artwork, recolor)
        canvas.restore()
    }
}
