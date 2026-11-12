package com.ssafy.fogo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/**
 * Whole-country pixel board (Figma "내 지도판"): South Korea as a coarse grid of [CELL_KM] tiles.
 * Fog covers every tile until a 1 km block inside it is opened; opened tiles take their province
 * colour, and stickers / magnets sit on the tile that holds their block.
 *
 * Placement still stores a 1 km block id in [Profile.place]: picking a tile picks its first free
 * opened block, so the stored data stays compatible with the old zoomed board.
 */
class KoreaBoard(
    context: Context,
    private val ui: AtlasUi,
    private val art: AtlasArt,
    private val cities: List<City>,
    private val open: Set<String>,
    /** block id → item placed on it */
    private val placed: Map<String, BoardItem>,
    /** Block id of the tile picked for placement, if any. */
    private val pending: String?,
    /** Item shown on the pending tile while choosing. */
    private val preview: BoardItem?,
    /** Called with a free opened block id when an editable tile is tapped; null when not editing. */
    private val pick: ((String) -> Unit)?,
) : FrameLayout(context) {
    private val colors = ui.colors
    private val paint = Paint()
    private val cells: Map<Cell, List<Block>> = open.mapNotNull { parse(it) }.groupBy { cellOf(it) }
    private val stickers: Map<Cell, BoardItem> = placed.entries.mapNotNull { (block, item) ->
        parse(block)?.let { cellOf(it) to item }
    }.toMap()
    private val pendingCell = pending?.let { parse(it) }?.let { cellOf(it) }
    private var cell = 0f
    private var left = 0f
    private var top = 0f
    private val targets = mutableListOf<Pair<Cell, View>>()

    init {
        setWillNotDraw(false)
        if (pick != null) {
            // One small view per pickable tile so the tiles are tappable and visible to accessibility
            // services and UI tests ("열린 칸").
            cells.forEach { (tile, blocks) ->
                val free = blocks.firstOrNull { it.id !in placed } ?: return@forEach
                val target = View(context).apply {
                    contentDescription = if (tile == pendingCell) "배치할 칸" else "열린 칸"
                    isClickable = true
                    setOnClickListener { pick.invoke(free.id) }
                }
                targets += tile to target
                addView(target, LayoutParams(1, 1))
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cell = floor(minOf(w / COLUMNS.toFloat(), h / ROWS.toFloat()))
        left = (w - cell * COLUMNS) / 2f
        top = (h - cell * ROWS) / 2f
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        targets.forEach { (tile, view) ->
            val x = (left + tile.column * cell).toInt()
            val y = (top + tile.row * cell).toInt()
            view.layout(x, y, (x + cell).toInt(), (y + cell).toInt())
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (cell <= 0f) return
        canvas.drawColor(if (colors.night) 0xff17283a.toInt() else 0xffcde2f4.toInt())
        val gap = maxOf(1f, cell / 12f)
        for (row in 0 until ROWS) for (column in 0 until COLUMNS) {
            val tile = Cell(column, row)
            if (!LAND[row][column]) continue
            val x = left + column * cell
            val y = top + row * cell
            val opened = cells.containsKey(tile)
            paint.color = when {
                tile == pendingCell -> colors.lime
                opened -> provinceColor(tile)
                else -> colors.fog
            }
            canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
            if (!opened) {
                paint.color = if (colors.night) 0xff4a4f5a.toInt() else 0xffd2d6dd.toInt()
                val dot = cell / 5f
                canvas.drawRect(x + cell / 2 - dot / 2, y + cell / 2 - dot / 2, x + cell / 2 + dot / 2, y + cell / 2 + dot / 2, paint)
            }
            if (tile == pendingCell) {
                paint.color = colors.dark
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = gap * 2
                canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
                paint.style = Paint.Style.FILL
            }
        }
        LABELS.forEach { (name, lon, lat) ->
            val tile = cellOf(Point(lat, lon).block())
            paint.color = colors.muted
            paint.textSize = cell * .62f
            paint.isFakeBoldText = true
            canvas.drawText(name, left + tile.column * cell, top + tile.row * cell - cell * .2f, paint)
        }
        paint.isFakeBoldText = false
        stickers.forEach { (tile, item) -> sticker(canvas, tile, item) }
        if (preview != null && pendingCell != null && pendingCell !in stickers) sticker(canvas, pendingCell, preview)
    }

    private fun sticker(canvas: Canvas, tile: Cell, item: BoardItem) {
        val size = cell * 1.9f
        val cx = left + (tile.column + .5f) * cell
        val cy = top + (tile.row + .5f) * cell
        val box = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        val edge = maxOf(2f, cell / 9f)
        paint.color = colors.dark
        canvas.drawRect(box.left + edge, box.top + edge, box.right + edge, box.bottom + edge, paint)
        paint.color = 0xffffffff.toInt()
        canvas.drawRect(box, paint)
        paint.color = if (item.magnet) colors.violet else item.color
        canvas.drawRect(box.left + edge, box.top + edge, box.right - edge, box.bottom - edge, paint)
        val artwork = art[artKey(item)]
        val inner = size - edge * 4
        val scale = minOf(inner / artwork.width, inner / artwork.height)
        val ox = cx - artwork.width * scale / 2
        val oy = cy - artwork.height * scale / 2
        artwork.pixels.forEach { pixel ->
            paint.color = pixel.color
            canvas.drawRect(ox + pixel.x * scale, oy + pixel.y * scale,
                ox + (pixel.x + pixel.w) * scale, oy + (pixel.y + pixel.h) * scale, paint)
        }
    }

    private fun provinceColor(tile: Cell): Int {
        val center = Point(LAT_TOP - (tile.row + .5) * CELL_KM / KM_PER_LAT, LON_LEFT + (tile.column + .5) * CELL_KM / kmPerLon)
        val nearest = cities.minByOrNull { it.point.distanceTo(center) }
            ?.takeIf { it.point.distanceTo(center) < 90_000 }
        return PROVINCE[nearest?.provinceId] ?: (if (colors.night) 0xff6f8f3a.toInt() else 0xffc8ee8a.toInt())
    }

    private data class Cell(val column: Int, val row: Int)

    companion object {
        private const val CELL_KM = 18.0
        private const val LON_LEFT = 124.9
        private const val LAT_TOP = 38.7
        private const val KM_PER_LAT = 111.195
        private val kmPerLon = 111.195 * cos(Math.toRadians(36.0))
        // Block x/y are km from (127°E, 36°N); see Point.block().
        private val ORIGIN_X = (LON_LEFT - 127.0) * kmPerLon
        private val ORIGIN_Y = (LAT_TOP - 36.0) * KM_PER_LAT
        private val COLUMNS = ceil(((129.8 - LON_LEFT) * kmPerLon) / CELL_KM).toInt()
        private val ROWS = ceil(((LAT_TOP - 33.0) * KM_PER_LAT) / CELL_KM).toInt()

        private val PROVINCE = mapOf(
            "SEOUL" to 0xffb69cff.toInt(),
            "GANGWON" to 0xff7fd890.toInt(),
            "BUSAN" to 0xff6ec6ff.toInt(),
        )
        private val LABELS = listOf(
            Triple("서울", 126.98, 37.75), Triple("강원", 128.2, 38.0), Triple("부산", 129.0, 35.4), Triple("제주", 126.3, 33.75),
        )

        /** Rough South Korea outline (lon, lat), enough for an 18 km pixel board. */
        private val OUTLINE = listOf(
            126.1 to 37.75, 126.7 to 37.95, 127.1 to 38.3, 127.6 to 38.33, 128.0 to 38.3, 128.35 to 38.62,
            128.6 to 38.2, 128.9 to 37.8, 129.1 to 37.5, 129.35 to 37.0, 129.45 to 36.5, 129.4 to 36.0,
            129.55 to 35.95, 129.45 to 35.5, 129.3 to 35.3, 129.05 to 35.08, 128.6 to 34.85, 128.0 to 34.85,
            127.6 to 34.7, 127.3 to 34.45, 126.8 to 34.3, 126.3 to 34.35, 126.25 to 34.7, 126.4 to 35.1,
            126.45 to 35.6, 126.6 to 36.0, 126.4 to 36.5, 126.2 to 36.8, 126.55 to 37.0, 126.7 to 37.3, 126.55 to 37.6,
        ).map { (lon, lat) -> (lon - 127.0) * kmPerLon to (lat - 36.0) * KM_PER_LAT }

        private val LAND: List<List<Boolean>> = (0 until ROWS).map { row ->
            (0 until COLUMNS).map { column ->
                val x = ORIGIN_X + (column + .5) * CELL_KM
                val y = ORIGIN_Y - (row + .5) * CELL_KM
                inside(x, y) || jeju(x, y)
            }
        }

        private fun inside(x: Double, y: Double): Boolean {
            var result = false
            for (i in OUTLINE.indices) {
                val (x1, y1) = OUTLINE[i]
                val (x2, y2) = OUTLINE[(i + 1) % OUTLINE.size]
                if ((y1 > y) != (y2 > y) && x < (x2 - x1) * (y - y1) / (y2 - y1) + x1) result = !result
            }
            return result
        }

        private fun jeju(x: Double, y: Double): Boolean {
            val dx = (x - (126.55 - 127.0) * kmPerLon) / (0.4 * kmPerLon)
            val dy = (y - (33.38 - 36.0) * KM_PER_LAT) / (0.15 * KM_PER_LAT)
            return dx * dx + dy * dy <= 1.0
        }

        private fun parse(id: String): Block? {
            val parts = id.split(',')
            return if (parts.size == 2) Block(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null) else null
        }

        private fun cellOf(block: Block) = Cell(
            floor((block.x + .5 - ORIGIN_X) / CELL_KM).toInt().coerceIn(0, COLUMNS - 1),
            floor((ORIGIN_Y - (block.y + .5)) / CELL_KM).toInt().coerceIn(0, ROWS - 1),
        )

        internal fun artKey(item: BoardItem) = when {
            item.magnet -> "mtn"
            item.id.startsWith("STK_BS") -> "wave"
            item.id.startsWith("STK_GW") -> "cup"
            else -> "star"
        }
    }
}
