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
            paint.color = scarf
            canvas.drawRect(353f, 331f, 465f, 342f, paint)
            canvas.drawRect(439f, 342f, 454f, 365f, paint)
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
