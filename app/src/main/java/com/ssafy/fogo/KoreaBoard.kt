package com.ssafy.fogo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/**
 * Whole-country pixel board (Figma A2 "내 지도판" / A3 "스티커 붙이기"): South Korea as a grid of
 * [CELL_KM] tiles. Fog covers every tile until a 1 km block inside it is opened; opened tiles take
 * their province colour, and stickers / magnets sit on the tile that holds their block as tilted
 * tiles. Jeju is drawn a few tiles north of its real place so the board is closer to square.
 *
 * With [zoom] set (placement) the board shows a 13×7 tile window around that block, a "여기에
 * 붙여요" bubble under the picked tile and a fog hint. Placement still stores a 1 km block id in
 * [Profile.place]: picking a tile picks its first free opened block, so saved data is unchanged.
 */
class KoreaBoard(
    context: Context,
    private val ui: AtlasUi,
    private val art: AtlasArt,
    open: Set<String>,
    /** block id → item placed on it */
    private val placed: Map<String, BoardItem>,
    /** Block id of the tile picked for placement, if any. */
    pending: String? = null,
    /** Item shown on the pending tile while choosing. */
    private val preview: BoardItem? = null,
    /** Called with a free opened block id when an editable tile is tapped; null when not editing. */
    private val pick: ((String) -> Unit)? = null,
    /** Block to centre a zoomed window on; null shows the whole country. */
    zoom: Block? = null,
) : FrameLayout(context) {
    private val colors = ui.colors
    private val paint = Paint().apply { isAntiAlias = false }
    private val text = Paint().apply { isAntiAlias = true; isFakeBoldText = true }
    private val cells: Map<Cell, List<Block>> = open.mapNotNull { parse(it) }.groupBy { cellOf(it) }
    private val stickers: Map<Cell, BoardItem> = placed.entries.mapNotNull { (block, item) ->
        parse(block)?.let { cellOf(it) to item }
    }.toMap()
    private val pendingCell = pending?.let { parse(it) }?.let { cellOf(it) }
    private val center = zoom?.let { cellOf(it) }
    private var cell = 0f
    private var left = 0f
    private var top = 0f
    private val targets = mutableListOf<Pair<Cell, View>>()

    init {
        setWillNotDraw(false)
        if (pick != null) {
            // One small view per pickable tile so tiles are tappable and visible to accessibility
            // services and UI tests ("열린 칸"). Only tiles inside the zoomed window get one.
            cells.forEach { (tile, blocks) ->
                if (center != null && (abs(tile.column - center.column) > ZOOM_HALF_COLUMNS ||
                        abs(tile.row - center.row) > ZOOM_HALF_ROWS)) return@forEach
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
        if (center == null) {
            cell = floor(minOf(w / COLUMNS.toFloat(), h / ROWS.toFloat()))
            left = (w - cell * COLUMNS) / 2f
            top = (h - cell * ROWS) / 2f
        } else {
            cell = floor(minOf(w / (ZOOM_HALF_COLUMNS * 2 + 1f), h / (ZOOM_HALF_ROWS * 2 + 1f)))
            left = w / 2f - (center.column + .5f) * cell
            top = h / 2f - (center.row + .5f) * cell
        }
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
        val sea = if (colors.night) 0xff17283a.toInt() else 0xffcde2f4.toInt()
        val seaLine = if (colors.night) 0xff1f3449.toInt() else 0xffbcd6ec.toInt()
        canvas.drawColor(sea)
        val gap = maxOf(1f, cell / 14f)
        for (row in 0 until ROWS) for (column in 0 until COLUMNS) {
            val x = left + column * cell
            val y = top + row * cell
            if (x + cell < 0 || y + cell < 0 || x > width || y > height) continue
            val tile = Cell(column, row)
            if (!LAND[row][column]) {
                paint.color = seaLine
                canvas.drawRect(x, y, x + cell, y + gap, paint)
                canvas.drawRect(x, y, x + gap, y + cell, paint)
                continue
            }
            val opened = cells.containsKey(tile)
            paint.color = when {
                tile == pendingCell -> colors.lime
                opened -> tint(provinceColor(column, row))
                else -> colors.fog
            }
            canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
            if (!opened) {
                paint.color = if (colors.night) 0xff4a4f5a.toInt() else 0xffd2d6dd.toInt()
                val dot = cell / 6f
                canvas.drawRect(x + cell / 2 - dot, y + cell / 2 - dot, x + cell / 2 + dot, y + cell / 2 + dot, paint)
            }
            if (tile == pendingCell) {
                paint.color = colors.dark
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = gap * 2.5f
                canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
                paint.style = Paint.Style.FILL
            }
        }
        if (center == null) LABELS.forEach { (name, lon, lat) ->
            val tile = cellOf(Point(lat, lon).block())
            text.color = colors.muted
            text.textSize = cell * .55f
            canvas.drawText(name, left + tile.column * cell, top + tile.row * cell - cell * .15f, text)
        }
        stickers.forEach { (tile, item) -> sticker(canvas, tile, item) }
        if (preview != null && pendingCell != null && pendingCell !in stickers) {
            sticker(canvas, pendingCell, preview)
            bubble(canvas, pendingCell)
        }
        if (pick != null) hint(canvas)
    }

    private fun sticker(canvas: Canvas, tile: Cell, item: BoardItem) {
        val size = cell * if (center == null) 2.3f else 1.5f
        val cx = left + (tile.column + .5f) * cell
        val cy = top + (tile.row + .5f) * cell
        val box = RectF(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        val edge = maxOf(2f, size / 14f)
        canvas.save()
        canvas.rotate(if ((tile.column + tile.row) % 2 == 0) -6f else 5f, cx, cy)
        paint.color = colors.dark
        canvas.drawRect(box.left + edge, box.top + edge, box.right + edge, box.bottom + edge, paint)
        paint.color = 0xffffffff.toInt()
        canvas.drawRect(box, paint)
        paint.color = if (item.magnet) colors.violet else item.color
        canvas.drawRect(box.left + edge, box.top + edge, box.right - edge, box.bottom - edge, paint)
        if (item.magnet) {
            // Magnet: a fridge-magnet bar on top and the province name, as in the Figma board.
            paint.color = 0xffffffff.toInt()
            canvas.drawRect(box.left + edge * 2, box.top + edge * 2, box.right - edge * 2, box.top + edge * 3.5f, paint)
            text.color = colors.onViolet
            text.textSize = size * .3f
            val name = item.name.substringBefore(' ')
            canvas.drawText(name, cx - text.measureText(name) / 2, cy + size * .22f, text)
        } else {
            val artwork = art[artKey(item)]
            val inner = size - edge * 5
            val scale = minOf(inner / artwork.width, inner / artwork.height)
            val ox = cx - artwork.width * scale / 2
            val oy = cy - artwork.height * scale / 2
            artwork.pixels.forEach { pixel ->
                paint.color = pixel.color
                canvas.drawRect(ox + pixel.x * scale, oy + pixel.y * scale,
                    ox + (pixel.x + pixel.w) * scale, oy + (pixel.y + pixel.h) * scale, paint)
            }
        }
        canvas.restore()
    }

    private fun bubble(canvas: Canvas, tile: Cell) {
        val label = "여기에 붙여요"
        text.textSize = cell * .32f
        text.color = colors.dark
        val w = text.measureText(label) + cell * .5f
        val h = cell * .55f
        val cx = left + (tile.column + .5f) * cell
        val y = top + (tile.row + 1.35f) * cell
        paint.color = colors.dark
        canvas.drawRect(cx - w / 2 + 3, y + 3, cx + w / 2 + 3, y + h + 3, paint)
        paint.color = colors.lime
        canvas.drawRect(cx - w / 2, y, cx + w / 2, y + h, paint)
        canvas.drawText(label, cx - text.measureText(label) / 2, y + h * .7f, text)
    }

    private fun hint(canvas: Canvas) {
        val label = "안개 칸은 아직 안 간 곳이라 붙일 수 없어요"
        text.textSize = ui.dp(13).toFloat()
        text.color = 0xffffffff.toInt()
        val pad = ui.dp(12).toFloat()
        val w = text.measureText(label) + pad * 2
        val h = ui.dp(32).toFloat()
        val y = height - h - pad
        paint.color = 0xe644464e.toInt()
        canvas.drawRoundRect(RectF(pad, y, pad + w, y + h), h / 2, h / 2, paint)
        canvas.drawText(label, pad * 2, y + h * .66f, text)
    }

    private fun tint(color: Int): Int {
        if (!colors.night) return color
        val r = (color shr 16 and 0xff) * 3 / 4
        val g = (color shr 8 and 0xff) * 3 / 4
        val b = (color and 0xff) * 3 / 4
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    private data class Cell(val column: Int, val row: Int)

    companion object {
        private const val CELL_KM = 18.0
        private const val ZOOM_HALF_COLUMNS = 6
        private const val ZOOM_HALF_ROWS = 3
        private const val LON_LEFT = 124.9
        private const val LAT_TOP = 38.7
        private const val KM_PER_LAT = 111.195
        /** Jeju is drawn this far north so the empty strait does not stretch the board. */
        private const val JEJU_SHIFT_KM = 60.0
        private val kmPerLon = 111.195 * cos(Math.toRadians(36.0))
        private val JEJU_BELOW = (33.9 - 36.0) * KM_PER_LAT
        // Block x/y are km from (127°E, 36°N); see Point.block().
        private val ORIGIN_X = (LON_LEFT - 127.0) * kmPerLon
        private val ORIGIN_Y = (LAT_TOP - 36.0) * KM_PER_LAT
        private val COLUMNS = ceil(((129.8 - LON_LEFT) * kmPerLon) / CELL_KM).toInt()
        private val ROWS = ceil((((LAT_TOP - 33.0) * KM_PER_LAT) - JEJU_SHIFT_KM) / CELL_KM).toInt()

        private val LABELS = listOf(
            Triple("경기", 126.75, 37.45), Triple("강원", 128.25, 37.95), Triple("충남", 126.6, 36.55),
            Triple("경북", 128.6, 36.45), Triple("전남", 126.6, 34.95), Triple("부산", 129.0, 35.35), Triple("제주", 126.3, 33.75),
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
                val (x, y) = centerOf(column, row)
                inside(x, y) || jeju(x, y - JEJU_SHIFT_KM)
            }
        }

        private fun centerOf(column: Int, row: Int) =
            ORIGIN_X + (column + .5) * CELL_KM to ORIGIN_Y - (row + .5) * CELL_KM

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

        /** Province colour for a land tile, from rough lon/lat boundaries (Figma board palette). */
        private fun provinceColor(column: Int, row: Int): Int {
            val (x, shifted) = centerOf(column, row)
            val jejuTile = jeju(x, shifted - JEJU_SHIFT_KM) && !inside(x, shifted)
            val lon = 127.0 + x / kmPerLon
            val lat = 36.0 + shifted / KM_PER_LAT
            val name = when {
                jejuTile -> "제주"
                lat >= 37.42 && lat <= 37.72 && lon in 126.8..127.2 -> "서울"
                lat >= 37.0 -> if (lon < 127.55) "경기" else "강원"
                lat >= 36.0 -> when { lon < 127.0 -> "충남"; lon < 127.9 || (lat >= 36.75 && lon < 128.3) -> "충북"; else -> "경북" }
                lat >= 35.6 -> if (lon < 127.6) "전북" else "경북"
                lat >= 35.25 -> if (lon < 127.6) (if (lat >= 35.4) "전북" else "전남") else if (lon > 128.95) "부산" else "경남"
                else -> if (lon < 127.6) "전남" else if (lon > 128.95) "부산" else "경남"
            }
            return PALETTE.getValue(name)
        }

        private val PALETTE = mapOf(
            "서울" to 0xffb69cff.toInt(), "경기" to 0xff7fd890.toInt(), "강원" to 0xff6ec6ff.toInt(),
            "충남" to 0xffffb347.toInt(), "충북" to 0xffffe08a.toInt(), "경북" to 0xffff9f8a.toInt(),
            "전북" to 0xff9ee6df.toInt(), "전남" to 0xffb4e197.toInt(), "경남" to 0xffffc46b.toInt(),
            "부산" to 0xff8fd4ff.toInt(), "제주" to 0xffffa64d.toInt(),
        )

        private fun parse(id: String): Block? {
            val parts = id.split(',')
            if (parts.size != 2) return null
            val x = parts[0].toIntOrNull() ?: return null
            val y = parts[1].toIntOrNull() ?: return null
            return Block(x, y)
        }

        private fun cellOf(block: Block): Cell {
            val y = if (block.y < JEJU_BELOW) block.y + .5 + JEJU_SHIFT_KM else block.y + .5
            return Cell(
                floor((block.x + .5 - ORIGIN_X) / CELL_KM).toInt().coerceIn(0, COLUMNS - 1),
                floor((ORIGIN_Y - y) / CELL_KM).toInt().coerceIn(0, ROWS - 1),
            )
        }

        internal fun artKey(item: BoardItem) = when {
            item.magnet -> "mtn"
            item.id.startsWith("STK_BS") -> "wave"
            item.id.startsWith("STK_GW") -> "cup"
            else -> "star"
        }
    }
}
