package com.ssafy.fogo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Whole-country pixel board (Figma A1 지도 / A2 내 지도판 / A3 스티커 붙이기): South Korea as a grid
 * of [CELL_KM] tiles.
 *
 * - Land is split into 시·도 zones: every land tile carries a faint zone tint, zones are separated by
 *   border lines and named, and the coast has an outline. A tile with an opened 1 km block shows the
 *   zone colour with a pixel bevel; fog tiles keep a dot.
 * - Pinch, drag and the +/- buttons zoom and pan. The zoom of each [key] survives re-renders.
 * - Stickers / magnets sit on the tile that holds their block as tilted tiles.
 * - When [pick] is set (placement) every tappable opened tile gets a small view ("열린 칸") so it works
 *   with touch, accessibility and UI tests; picking a tile picks its first free opened block, so
 *   [Profile.place] still stores 1 km block ids.
 *
 * Jeju is drawn a few tiles north of its real place so the board is closer to square.
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
    /** Block to centre on the first time this [key] is shown; null shows the whole country. */
    focus: Block? = null,
    /** Remembers zoom and pan per screen across re-renders. */
    private val key: String = "board",
) : FrameLayout(context) {
    private val colors = ui.colors
    private val paint = Paint()
    private val text = Paint().apply { isAntiAlias = true; typeface = context.resources.getFont(R.font.galmuri_bold) }
    private val cells: Map<Cell, List<Block>> = open.mapNotNull { parse(it) }.groupBy { cellOf(it) }
    private val stickers: Map<Cell, BoardItem> = placed.entries.mapNotNull { (block, item) ->
        parse(block)?.let { cellOf(it) to item }
    }.toMap()
    private val pendingCell = pending?.let { parse(it) }?.let { cellOf(it) }
    private val view: ViewState = STATES.getOrPut(key) {
        focus?.let { cellOf(it) }?.let { ViewState(3f, it.column + .5f, it.row + .5f) }
            ?: ViewState(1f, COLUMNS / 2f, ROWS / 2f)
    }
    private var base = 0f
    private val cell get() = base * view.zoom
    private val originX get() = width / 2f - view.x * cell
    private val originY get() = height / 2f - view.y * cell
    private val targets = mutableListOf<Pair<Cell, View>>()
    private val zoomIn: TextView
    private val zoomOut: TextView

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomBy(detector.scaleFactor)
            return true
        }
    })

    init {
        setWillNotDraw(false)
        clipChildren = true
        pick?.let { choose ->
            cells.forEach { (tile, blocks) ->
                val free = blocks.firstOrNull { it.id !in placed } ?: return@forEach
                val target = View(context).apply {
                    contentDescription = if (tile == pendingCell) "배치할 칸" else "열린 칸"
                    isClickable = true
                    setOnClickListener { choose(free.id) }
                }
                targets += tile to target
                addView(target, LayoutParams(1, 1))
            }
        }
        zoomIn = zoomButton("+", "지도 확대") { zoomBy(1.6f) }
        zoomOut = zoomButton("-", "지도 축소") { zoomBy(1 / 1.6f) }
    }

    private fun zoomButton(label: String, description: String, click: () -> Unit) =
        // Press Start 2P: Galmuri has no U+2212 and its own +/- sit off-centre in a square button.
        ui.label(label, 18f, colors.ink, pixel = true).apply {
            gravity = android.view.Gravity.CENTER
            background = ui.box(colors.paper, shadow = true)
            contentDescription = description
            isClickable = true
            setOnClickListener { click() }
            this@KoreaBoard.addView(this, LayoutParams(ui.dp(48), ui.dp(48)))
        }

    private fun zoomBy(factor: Float) {
        view.zoom = (view.zoom * factor).coerceIn(1f, 5f)
        clampCenter()
        requestLayout()
        invalidate()
    }

    private fun clampCenter() {
        if (base <= 0f) return
        val halfW = width / 2f / cell
        val halfH = height / 2f / cell
        view.x = if (halfW * 2 >= COLUMNS) COLUMNS / 2f else view.x.coerceIn(halfW, COLUMNS - halfW)
        view.y = if (halfH * 2 >= ROWS) ROWS / 2f else view.y.coerceIn(halfH, ROWS - halfH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        base = minOf(w / COLUMNS.toFloat(), h / ROWS.toFloat())
        clampCenter()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        targets.forEach { (tile, target) ->
            val x = (originX + tile.column * cell).toInt()
            val y = (originY + tile.row * cell).toInt()
            val size = cell.toInt().coerceAtLeast(1)
            val shown = x >= 0 && y >= 0 && x + size <= width && y + size <= height && size >= ui.dp(8)
            val visibility = if (shown) View.VISIBLE else View.GONE
            if (target.visibility != visibility) target.visibility = visibility
            target.layout(x, y, x + size, y + size)
        }
        val size = ui.dp(48)
        val margin = ui.dp(12)
        zoomIn.layout(width - margin - size, margin, width - margin, margin + size)
        zoomOut.layout(width - margin - size, margin * 2 + size, width - margin, margin * 2 + size * 2)
    }

    // Pinch and drag work anywhere on the board, including over tappable tiles.
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        track(event)
        return dragging || scaler.isInProgress
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        track(event)
        return true
    }

    private fun track(event: MotionEvent) {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !scaler.isInProgress && hypot(event.x - downX, event.y - downY) > slop) dragging = true
                if ((dragging || scaler.isInProgress) && cell > 0f) {
                    view.x -= (event.x - lastX) / cell
                    view.y -= (event.y - lastY) / cell
                    clampCenter()
                    requestLayout()
                    invalidate()
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (cell <= 0f) return
        val sea = if (colors.night) 0xff15263a.toInt() else 0xffcfe4f5.toInt()
        val wave = if (colors.night) 0xff1d334b.toInt() else 0xffbdd7ee.toInt()
        val coast = if (colors.night) 0xff7d8fa6.toInt() else 0xff5b7a99.toInt()
        val border = if (colors.night) 0x99b7c2d3.toInt() else 0x8c0b1426.toInt()
        canvas.drawColor(sea)
        val gap = maxOf(1f, cell / 16f)
        val bevel = maxOf(1f, cell / 7f)
        val first = floor(-originX / cell).toInt().coerceAtLeast(0)
        val last = ceil((width - originX) / cell).toInt().coerceAtMost(COLUMNS - 1)
        val firstRow = floor(-originY / cell).toInt().coerceAtLeast(0)
        val lastRow = ceil((height - originY) / cell).toInt().coerceAtMost(ROWS - 1)
        for (row in firstRow..lastRow) for (column in first..last) {
            val x = originX + column * cell
            val y = originY + row * cell
            val zone = ZONES[row][column]
            if (zone == null) {
                // Sparse pixel waves on the sea.
                if ((column * 7 + row * 3) % 11 == 0) {
                    paint.color = wave
                    canvas.drawRect(x + cell * .2f, y + cell * .45f, x + cell * .55f, y + cell * .45f + gap * 2, paint)
                }
                continue
            }
            val tile = Cell(column, row)
            val tint = ZONE_COLORS.getValue(zone)
            val opened = cells.containsKey(tile)
            when {
                tile == pendingCell -> {
                    paint.color = colors.lime
                    canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
                }
                opened -> {
                    paint.color = shade(tint, if (colors.night) .8f else 1f)
                    canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
                    paint.color = shade(tint, if (colors.night) 1f else 1.12f)
                    canvas.drawRect(x, y, x + cell - gap, y + bevel, paint)
                    paint.color = shade(tint, if (colors.night) .6f else .82f)
                    canvas.drawRect(x, y + cell - gap - bevel, x + cell - gap, y + cell - gap, paint)
                }
                else -> {
                    paint.color = mix(colors.fog, tint, if (colors.night) .12f else .18f)
                    canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
                    paint.color = mix(colors.fog, colors.ink, .14f)
                    val dot = cell / 9f
                    canvas.drawRect(x + cell / 2 - dot, y + cell / 2 - dot, x + cell / 2 + dot, y + cell / 2 + dot, paint)
                }
            }
            // Coast outline and 시·도 borders on the tile edges.
            // Coast a little heavier than the 시·도 lines; province borders are hairlines so the colours lead.
            val line = maxOf(1.5f, cell / 14f)
            val thin = maxOf(1f, cell / 28f)
            fun edge(neighbour: String?, x1: Float, y1: Float, x2: Float, y2: Float) {
                when {
                    neighbour == null -> { paint.color = coast; canvas.drawRect(x1, y1, x2, y2, paint) }
                    neighbour != zone -> {
                        paint.color = border
                        // Shrink the band across its thin side to the hairline width.
                        if (x2 - x1 < y2 - y1) {
                            val mid = (x1 + x2) / 2
                            canvas.drawRect(mid - thin / 2, y1, mid + thin / 2, y2, paint)
                        } else {
                            val mid = (y1 + y2) / 2
                            canvas.drawRect(x1, mid - thin / 2, x2, mid + thin / 2, paint)
                        }
                    }
                }
            }
            edge(zoneAt(column, row - 1), x, y - line / 2, x + cell, y + line / 2)
            edge(zoneAt(column - 1, row), x - line / 2, y, x + line / 2, y + cell)
            if (zoneAt(column, row + 1) == null) edge(null, x, y + cell - line / 2, x + cell, y + cell + line / 2)
            if (zoneAt(column + 1, row) == null) edge(null, x + cell - line / 2, y, x + cell + line / 2, y + cell)
            if (tile == pendingCell) {
                paint.color = colors.dark
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = gap * 3
                canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
                paint.style = Paint.Style.FILL
            }
        }
        // 시·도 names only once zoomed in (도 from 2×, 특별·광역시 from 3×): small text with a paper halo, no box.
        if (view.zoom >= 2f) {
            text.textSize = ui.dp(MIN_TEXT.toInt()).toFloat()
            LABELS.forEach { (name, column, row) ->
                if (name in METROS && view.zoom < 3f) return@forEach
                val cx = originX + column * cell
                val cy = originY + row * cell + text.textSize * .35f
                val w = text.measureText(name)
                if (cx + w < 0 || cy < 0 || cx - w > width || cy - text.textSize > height) return@forEach
                text.style = Paint.Style.STROKE
                text.strokeWidth = text.textSize * .22f
                text.color = colors.paper
                canvas.drawText(name, cx - w / 2, cy, text)
                text.style = Paint.Style.FILL
                text.color = colors.ink
                canvas.drawText(name, cx - w / 2, cy, text)
            }
        }
        stickers.forEach { (tile, item) -> sticker(canvas, tile, item) }
        if (preview != null && pendingCell != null && pendingCell !in stickers) {
            sticker(canvas, pendingCell, preview)
            bubble(canvas, pendingCell)
        }
        if (pick != null) hint(canvas)
    }

    private fun sticker(canvas: Canvas, tile: Cell, item: BoardItem) {
        val size = (cell * 2.1f).coerceIn(ui.dp(26).toFloat(), ui.dp(84).toFloat())
        val cx = originX + (tile.column + .5f) * cell
        val cy = originY + (tile.row + .5f) * cell
        if (cx < -size || cy < -size || cx > width + size || cy > height + size) return
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
        text.textSize = ui.dp(MIN_TEXT.toInt()).toFloat()
        text.color = colors.dark
        val w = text.measureText(label) + ui.dp(16)
        val h = ui.dp(30).toFloat()
        val cx = originX + (tile.column + .5f) * cell
        val y = originY + (tile.row + 1f) * cell + ui.dp(18)
        paint.color = colors.dark
        canvas.drawRect(cx - w / 2 + 3, y + 3, cx + w / 2 + 3, y + h + 3, paint)
        paint.color = colors.lime
        canvas.drawRect(cx - w / 2, y, cx + w / 2, y + h, paint)
        canvas.drawText(label, cx - text.measureText(label) / 2, y + h * .7f, text)
    }

    private fun hint(canvas: Canvas) {
        val label = "안개 칸은 아직 안 간 곳이라 붙일 수 없어요"
        text.textSize = ui.dp(MIN_TEXT.toInt()).toFloat()
        text.color = 0xffffffff.toInt()
        val pad = ui.dp(12).toFloat()
        val w = text.measureText(label) + pad * 2
        val h = ui.dp(34).toFloat()
        val y = height - h - pad
        paint.color = 0xe644464e.toInt()
        canvas.drawRoundRect(RectF(pad, y, pad + w, y + h), h / 2, h / 2, paint)
        canvas.drawText(label, pad * 2, y + h * .66f, text)
    }

    private class ViewState(var zoom: Float, var x: Float, var y: Float)
    private data class Cell(val column: Int, val row: Int)

    companion object {
        private const val CELL_KM = 10.0
        private const val LON_LEFT = 126.0
        private const val LAT_TOP = 38.62
        private const val KM_PER_LAT = 111.195
        /** Jeju is drawn this far north so the empty strait does not stretch the board. */
        private const val JEJU_SHIFT_KM = 62.0
        private val kmPerLon = 111.195 * cos(Math.toRadians(36.0))
        private val JEJU_BELOW = (33.9 - 36.0) * KM_PER_LAT
        // Block x/y are km from (127°E, 36°N); see Point.block().
        private val ORIGIN_X = (LON_LEFT - 127.0) * kmPerLon
        private val ORIGIN_Y = (LAT_TOP - 36.0) * KM_PER_LAT
        private val STATES = mutableMapOf<String, ViewState>()

        /**
         * 10 km tiles rasterised from the KOSTAT 2012 시·도 boundaries (southkorea-maps, provinces-geo-simple):
         * each tile takes the 시·도 covering most of it (특별·광역시 win at 25% so they keep a shape); all 17 시·도.
         * Jeju rows are moved [JEJU_SHIFT_KM] north. Regenerate with scripts/raster_provinces.py.
         */
        private val GRID = listOf(
            ".....................K...........",
            ".....................K...........",
            "....................KKK..........",
            "..........KKKKKKKKKKKKK..........",
            "........GGKKKKKKKKKKKKKK.........",
            ".......GGGGGKKKKKKKKKKKK.........",
            "........GGGGGKKKKKKKKKKKK........",
            "......GGGGGGGGKKKKKKKKKKK........",
            "......GGGGGGGGKKKKKKKKKKKK.......",
            "..IIIGGGGGGGGGKKKKKKKKKKKKK......",
            "...IIGGGGSGGGGKKKKKKKKKKKKKK.....",
            "....IIISSSGGGGGGKKKKKKKKKKKK.....",
            "...IIIISSSSGGGGGKKKKKKKKKKKK.....",
            ".....IIGGGGGGGGGKKKKKKKKKKKKK....",
            "......GGGGGGGGGGKKKKKKKKKKKKKK...",
            ".....GGGGGGGGGGGKKKBKKKKKKKKKK...",
            "......GGGGGGGGGBBBBBBBKKKKKKKK...",
            "....N..GGGGGGGBBBBBBBBBEKEEEEEE..",
            "...NNNNGGGNGBBBBBBBBBBEEEEEEEEE..",
            "..NNNNNNNNNNBBBBBBBBBBEEEEEEEEE..",
            ".NNNNNNNNNNNBBBBBBBEEEEEEEEEEEE..",
            "..NNNNNNNNXXBBBBBEEEEEEEEEEEEEE..",
            "...NNNNNNNXXBBBBBEEEEEEEEEEEEEE..",
            "...NNNNNNNXXXBBBBEEEEEEEEEEEEEE..",
            "...NNNNNNNNXDDBBBEEEEEEEEEEEEEE..",
            ".....NNNNNNDDDBBBEEEEEEEEEEEEEE..",
            ".....NNNNNNDDDBBBBEEEEEEEEEEEEE..",
            ".....NNNNNNNNNBBBBEEEEEEEEEEEEE..",
            "......NNJJJJNNNBBEEEEEEEEEEEEEE..",
            ".....JJJJJJJJJJJJEEEEEETTEEEEEEE.",
            "......JJJJJJJJJJJEEEETTTTEEEEEEE.",
            "......JJJJJJJJJWWWWEETTTTEEEEEEE.",
            ".....JJJJJJJJJJWWWWWETTEEEEEUEE..",
            "....JJJJJJJJJJJWWWWWWWWEEEWUUUU..",
            "....JJJJJJJJJJJWWWWWWWWWWWWUUUU..",
            "....JJJMJJJJJJJWWWWWWWWWWWWWUU...",
            "...MMJMMMMJJJMWWWWWWWWWWWWWWPP...",
            "...MMMMQQMMMMMWWWWWWWWWWWWWPP....",
            "...MMMQQQMMMMMMWWWWWWWWWWPPPP....",
            "MMM.MMMQQMMMMMMMWWWWWWW..PPP.....",
            ".M.MMMMMMMMMMMMMWWWWWW.WW........",
            "..MMMMMMMMMMMMMMWWWW.WWWW........",
            ".M.MMMMMMMMMM.MMWWW..WWW.........",
            "MMMMMMMMMMMMM.MM.................",
            "M.MMMMMMM..MMM.M.................",
            "..MMMMMMM.MMM....................",
            ".MM.MMMM..M......................",
            ".M..MMMMM........................",
            ".................................",
            ".....M...........................",
            "....ZZZZ.........................",
            "..ZZZZZZ.........................",
            ".ZZZZZZZ.........................",
            "..ZZZZ...........................",
            ".................................",
        )
        private val LETTERS = mapOf(
            'S' to "서울", 'I' to "인천", 'G' to "경기", 'K' to "강원", 'X' to "세종", 'D' to "대전",
            'B' to "충북", 'N' to "충남", 'Q' to "광주", 'J' to "전북", 'M' to "전남", 'T' to "대구",
            'E' to "경북", 'U' to "울산", 'W' to "경남", 'P' to "부산", 'Z' to "제주",
        )
        /** 특별·광역시: coloured as a shade of the province around them, named only when zoomed in further. */
        private val METROS = setOf("서울", "인천", "세종", "대전", "광주", "대구", "울산", "부산")
        private val COLUMNS = GRID.maxOf { it.length }
        private val ROWS = GRID.size

        private val ZONES: List<List<String?>> = GRID.map { line -> (0 until COLUMNS).map { LETTERS[line.getOrElse(it) { '.' }] } }

        private fun zoneAt(column: Int, row: Int) = ZONES.getOrNull(row)?.getOrNull(column)

        private val ZONE_COLORS = mapOf(
            "경기" to 0xff7fd890.toInt(), "서울" to 0xff4fb86a.toInt(), "인천" to 0xffb6ecc1.toInt(),
            "강원" to 0xff6ec6ff.toInt(),
            "충남" to 0xffffb347.toInt(), "대전" to 0xffe58e1f.toInt(), "세종" to 0xffffd49c.toInt(),
            "충북" to 0xffffe08a.toInt(),
            "경북" to 0xffff9f8a.toInt(), "대구" to 0xffeb6f55.toInt(),
            "전북" to 0xff9ee6df.toInt(),
            "전남" to 0xffb4e197.toInt(), "광주" to 0xff82c45f.toInt(),
            "경남" to 0xffffc46b.toInt(), "울산" to 0xffe9a23a.toInt(), "부산" to 0xffffe0a6.toInt(),
            "제주" to 0xffffa64d.toInt(),
        )

        /** Each zone's name sits on its most inland tile (ties broken towards the zone's centre). */
        private val LABELS: List<Triple<String, Float, Float>> = ZONE_COLORS.keys.mapNotNull { name ->
            val tiles = ZONES.flatMapIndexed { row, line -> line.mapIndexedNotNull { column, zone -> if (zone == name) column to row else null } }
            if (tiles.isEmpty()) return@mapNotNull null
            val cx = tiles.map { it.first }.average()
            val cy = tiles.map { it.second }.average()
            fun depth(column: Int, row: Int): Int {
                for (d in 1..6) for (dy in -d..d) for (dx in -d..d) {
                    if (maxOf(abs(dx), abs(dy)) == d && zoneAt(column + dx, row + dy) != name) return d
                }
                return 7
            }
            val best = tiles.maxWith(compareBy<Pair<Int, Int>> { minOf(depth(it.first, it.second), 3) }
                .thenByDescending { (it.first - cx) * (it.first - cx) + (it.second - cy) * (it.second - cy) })
            Triple(name, best.first + .5f, best.second + .5f)
        }

        private fun shade(color: Int, factor: Float): Int {
            fun channel(shift: Int) = ((color shr shift and 0xff) * factor).toInt().coerceIn(0, 255)
            return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        private fun mix(a: Int, b: Int, amount: Float): Int {
            fun channel(shift: Int) = ((a shr shift and 0xff) * (1 - amount) + (b shr shift and 0xff) * amount).toInt()
            return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        private fun parse(id: String): Block? {
            val parts = id.split(',')
            if (parts.size != 2) return null
            val x = parts[0].toIntOrNull() ?: return null
            val y = parts[1].toIntOrNull() ?: return null
            return Block(x, y)
        }

        /** Tile of a block; a block on a coastal sea tile snaps to the nearest land tile so it still shows. */
        private fun cellOf(block: Block): Cell {
            val y = if (block.y < JEJU_BELOW) block.y + .5 + JEJU_SHIFT_KM else block.y + .5
            val column = floor((block.x + .5 - ORIGIN_X) / CELL_KM).toInt().coerceIn(0, COLUMNS - 1)
            val row = floor((ORIGIN_Y - y) / CELL_KM).toInt().coerceIn(0, ROWS - 1)
            if (zoneAt(column, row) != null) return Cell(column, row)
            for (d in 1..3) for (dy in -d..d) for (dx in -d..d) {
                if (maxOf(abs(dx), abs(dy)) == d && zoneAt(column + dx, row + dy) != null) return Cell(column + dx, row + dy)
            }
            return Cell(column, row)
        }

        internal fun artKey(item: BoardItem) = when {
            item.magnet -> "mtn"
            item.id.startsWith("STK_BS") -> "wave"
            item.id.startsWith("STK_GW") -> "cup"
            else -> "star"
        }
    }
}
