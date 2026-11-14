package com.ssafy.fogo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.animation.ValueAnimator
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
 * - Figma look: the whole board is a grid of big square pixels. The sea is open blue water. Land not
 *   visited yet lies under an opaque pixel cloud (three close shades, still), which thins next to opened
 *   tiles, so the fog visibly lifts around where the car has been.
 * - Fog lifting: tiles that gained 1 km blocks since any board was last shown (a drive, an arrival) are fogged
 *   again and lose the fog pixel by pixel, one after another in the order the blocks were opened (the route),
 *   then flash once. Already opened tiles count too, so a drive through a known region still shows what it cleared. Shared by every board and
 *   kept across re-renders; nothing plays on the first launch or with animations turned off. A tile with an opened 1 km block
 *   is the flat, bright colour of its 시·도 (17 zones; 특별·광역시 a shade of the surrounding 도) with a
 *   pixel bevel. No coast line; hairline 시·도 borders, names only when zoomed in.
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
    /** Zoom buttons at the top-right corner, or halfway down the right edge when the corner is taken. */
    private val controlsAtTop: Boolean = true,
    /** Lift the fog over my newly opened tiles; off for boards that are not my own record (a friend's). */
    reveal: Boolean = true,
) : FrameLayout(context) {
    private val colors = ui.colors
    private val paint = Paint()
    private val text = Paint().apply { isAntiAlias = true; typeface = context.resources.getFont(R.font.galmuri_bold) }
    private val cells: Map<Cell, List<Block>> = open.mapNotNull { parse(it) }.groupBy { cellOf(it) }
    private val stickers: Map<Cell, List<BoardItem>> = placed.entries.mapNotNull { (block, item) ->
        parse(block)?.let { cellOf(it) to item }
    }.groupBy({ it.first }, { it.second })
    /** Reused by [sticker] so drawing allocates nothing. */
    private val box = RectF()
    private val pendingCell = pending?.let { parse(it) }?.let { cellOf(it) }
    private val view: ViewState = STATES.getOrPut(key) {
        // Zoom is settled once the size is known: -1 = focus at Figma A3 tile size, 0 = mainland fills the board.
        focus?.let { cellOf(it) }?.let { ViewState(-1f, it.column + .5f, it.row + .5f) }
            ?: ViewState(0f, LAND_CENTER, MAINLAND_ROWS / 2f)
    }
    private var base = 0f
    private val cell get() = base * view.zoom
    private val originX get() = width / 2f - view.x * cell
    private val originY get() = height / 2f - view.y * cell
    private val targets = mutableListOf<Pair<Cell, View>>()
    private val zoomIn: TextView
    private val zoomOut: TextView
    /** Tiles whose fog starts lifting with this board (new blocks since a board was last shown); panned into view. */
    private val fresh: List<Cell> = if (reveal) startReveal(context, open) else emptyList()
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (REVEALING.isNotEmpty()) postDelayed(this, REVEAL_FRAME_MS)
        }
    }

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
        view.zoom = (view.zoom * factor).coerceIn(1f, MAX_ZOOM)
        clampCenter()
        requestLayout()
        invalidate()
    }

    /**
     * Pans the least needed so every tile in [tiles] (with its sticker overhang) is on screen,
     * on each axis where they fit at the current zoom.
     */
    private fun keepInView(tiles: Collection<Cell>) {
        if (tiles.isEmpty() || cell <= 0f) return
        val margin = STICKER_OVERHANG
        fun shift(center: Float, half: Float, low: Float, high: Float): Float = when {
            high - low > half * 2 -> center
            low < center - half -> low + half
            high > center + half -> high - half
            else -> center
        }
        view.x = shift(view.x, width / 2f / cell, tiles.minOf { it.column } - margin, tiles.maxOf { it.column } + 1 + margin)
        view.y = shift(view.y, height / 2f / cell, tiles.minOf { it.row } - margin, tiles.maxOf { it.row } + 1 + margin)
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
        if (base > 0f && view.zoom == 0f) {
            // Figma A2: the mainland fills the board across as well as down (tiles ≈ 44 px).
            view.zoom = maxOf(h / MAINLAND_ROWS.toFloat(), w / LAND_COLUMNS.toFloat()).div(base).coerceIn(1f, MAX_ZOOM)
        }
        if (base > 0f && view.zoom < 0f) view.zoom = (ui.fx(FOCUS_TILE) / base).coerceIn(1f, MAX_ZOOM)
        // That fill crops the mainland's top and bottom on a near-square board: pan so stickers placed since
        // the last look are whole (all of them the first time), leaving the user's own pan alone otherwise.
        if (base > 0f && pick == null) {
            // By item, not tile: neighbouring blocks share a tile, so a new sticker may land on an occupied one.
            // Keyed by item and block, so a sticker moved to another block counts as new and is brought into view too.
            keepInView(placed.filter { (block, item) -> "${item.id}@$block" !in view.seen }.keys
                .mapNotNull { parse(it)?.let { block -> cellOf(block) } } + fresh)
            view.seen += placed.map { (block, item) -> "${item.id}@$block" }
        }
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
        val top = if (controlsAtTop) margin else (height - size * 2 - margin) / 2
        zoomIn.layout(width - margin - size, top, width - margin, top + size)
        zoomOut.layout(width - margin - size, top + margin + size, width - margin, top + margin + size * 2)
    }

    // Pinch and drag work anywhere on the board, including over tappable tiles.
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        track(event)
        return dragging || scaler.isInProgress
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tap = event.actionMasked == MotionEvent.ACTION_UP && !dragging && !scaler.isInProgress
        track(event)
        if (tap) performClick() // a tap on the board itself (not a tile) still reaches accessibility services
        return true
    }

    override fun performClick() = super.performClick()

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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (REVEALING.isNotEmpty()) post(tick)
    }

    /**
     * Compares the opened blocks with those of the last board shown and queues the tiles holding new ones, in the
     * order the blocks were opened (a drive's route). The first board ever shown (or with animations off) only records.
     */
    private fun startReveal(context: Context, open: Set<String>): List<Cell> {
        val store = context.getSharedPreferences(REVEAL_PREFS, Context.MODE_PRIVATE)
        val known = store.getStringSet(REVEAL_KEY, null)
        if (known == open) return emptyList()
        store.edit().putStringSet(REVEAL_KEY, HashSet(open)).apply()
        if (known == null || !ValueAnimator.areAnimatorsEnabled()) return emptyList()
        val added = open.asSequence().filter { it !in known }.mapNotNull { parse(it)?.let(::cellOf) }
            .distinct().filter { it in cells && it !in REVEALING }.toList()
        if (added.isEmpty()) return emptyList()
        val now = SystemClock.uptimeMillis()
        val step = minOf(REVEAL_STAGGER_MS, REVEAL_SPREAD_MS / added.size)
        added.forEachIndexed { index, tile -> REVEALING[tile] = now + REVEAL_DELAY_MS + index * step }
        return added
    }

    /** 0 → still fogged, 1 → clear; above 1 while the finishing flash plays. Null when not lifting. */
    private fun revealProgress(tile: Cell, now: Long): Float? =
        REVEALING[tile]?.let { (now - it).toFloat() / REVEAL_MS }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (cell <= 0f) return
        val night = colors.night
        // Open water: a clear blue with a few wave pixels, so the land under the fog keeps its shape.
        val sea = if (night) 0xff10263d.toInt() else 0xff6f9cc6.toInt()
        val wave = if (night) 0xff1b3858.toInt() else 0xff8bb3d8.toInt()
        // Fog: an opaque pixel cloud, darkest to lightest.
        val fogShades = if (night) NIGHT_FOG else DAY_FOG
        val border = if (night) 0xccd6deea.toInt() else 0xb30b1426.toInt()
        // Under the fog the 시·도 borders are only a hint, so the cloud reads as one sheet.
        val fogBorder = if (night) 0x40d6deea else 0x2a0b1426
        canvas.drawColor(sea)
        val gap = maxOf(1f, cell / 20f)
        val now = SystemClock.uptimeMillis()
        val first = floor(-originX / cell).toInt()
        val last = ceil((width - originX) / cell).toInt()
        val firstRow = floor(-originY / cell).toInt()
        val lastRow = ceil((height - originY) / cell).toInt()
        val zoomed = view.zoom >= LABEL_ZOOM
        val thin = maxOf(1f, cell / 30f)
        for (row in firstRow..lastRow) for (column in first..last) {
            val x = originX + column * cell
            val y = originY + row * cell
            val zone = zoneAt(column, row)
            if (zone == null) {
                if (noise(column, row) % 9 == 0) {
                    paint.color = wave
                    canvas.drawRect(x + cell * .2f, y + cell * .45f, x + cell * .6f, y + cell * .55f, paint)
                }
                continue
            }
            val tile = Cell(column, row)
            val opened = cells.containsKey(tile)
            when {
                tile == pendingCell -> {
                    paint.color = colors.lime
                    canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
                }
                opened -> {
                    // Pixel bevel: a darker cell under the flat 시·도 colour, so opened land looks raised out of the fog.
                    val color = shade(ZONE_COLORS.getValue(zone), if (night) .9f else 1f)
                    paint.color = shade(color, .78f)
                    canvas.drawRect(x, y, x + cell, y + cell, paint)
                    paint.color = color
                    canvas.drawRect(x, y, x + cell - gap * 2, y + cell - gap * 2, paint)
                    revealProgress(tile, now)?.let { lift(canvas, tile, x, y, it, fogShades) }
                }
                else -> {
                    val level = cloud(column.toFloat(), row.toFloat())
                    val index = if (level < .42f) 0 else if (level < .62f) 1 else 2
                    val near = nearOpened(column, row, now)
                    // Thin fog next to opened land: the 시·도 colour shows through, so the fog lifts at the edge.
                    paint.color = if (near) blend(ZONE_COLORS.getValue(zone), fogShades[2], if (night) .82f else .8f)
                        else fogShades[index]
                    canvas.drawRect(x, y, x + cell, y + cell, paint)
                }
            }
            // Hairline 시·도 borders at every zoom.
            run {
                paint.color = if (opened || tile == pendingCell) border else fogBorder
                val up = zoneAt(column, row - 1)
                val left = zoneAt(column - 1, row)
                if (up != null && up != zone) canvas.drawRect(x, y - thin / 2, x + cell, y + thin / 2, paint)
                if (left != null && left != zone) canvas.drawRect(x - thin / 2, y, x + thin / 2, y + cell, paint)
            }
            if (tile == pendingCell) {
                paint.color = colors.dark
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = gap * 3
                canvas.drawRect(x, y, x + cell - gap, y + cell - gap, paint)
                paint.style = Paint.Style.FILL
            }
        }
        // 시·도 names only once zoomed in (도 first, 특별·광역시 a step further): small text with a paper halo, no box.
        if (zoomed) {
            text.textSize = ui.dp(MIN_TEXT.toInt()).toFloat()
            LABELS.forEach { (name, column, row) ->
                if (name in METROS && view.zoom < METRO_LABEL_ZOOM) return@forEach
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
        stickers.forEach { (tile, items) ->
            sticker(canvas, tile, if (tile == pendingCell && preview != null) items + preview else items)
        }
        if (preview != null && pendingCell != null) {
            if (pendingCell !in stickers) sticker(canvas, pendingCell, listOf(preview))
            bubble(canvas, pendingCell)
        }
        if (pick != null) hint(canvas)
        REVEALING.entries.removeAll { (now - it.value).toFloat() / REVEAL_MS >= REVEAL_END }
    }

    /**
     * Fog over a tile that is lifting: a grid of fog pixels that vanish one by one at their own moment
     * (each glows white just before it goes), then a lime outline that fades.
     */
    private fun lift(canvas: Canvas, tile: Cell, x: Float, y: Float, progress: Float, fogShades: IntArray) {
        if (progress < 1f) {
            val parts = if (cell >= 40f) 5 else 3
            val size = cell / parts
            for (j in 0 until parts) for (i in 0 until parts) {
                val at = .05f + .85f * noise(tile.column * 11 + i, tile.row * 13 + j) / 65535f
                if (progress >= at) continue
                paint.color = if (progress > at - .12f) fogShades[3] else fogShades[1 + (i + j) % 2]
                canvas.drawRect(x + i * size, y + j * size, x + (i + 1) * size, y + (j + 1) * size, paint)
            }
        } else {
            val fade = ((REVEAL_END - progress) / (REVEAL_END - 1f)).coerceIn(0f, 1f)
            paint.color = (colors.lime and 0x00ffffff) or ((fade * 255).toInt() shl 24)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = maxOf(2f, cell / 12f)
            val inset = paint.strokeWidth / 2
            canvas.drawRect(x + inset, y + inset, x + cell - inset, y + cell - inset, paint)
            paint.style = Paint.Style.FILL
        }
    }

    /**
     * Draws a tile's stickers. Neighbouring 1 km blocks share a tile, so several can land on one:
     * they fan out sideways, a little smaller, so none hides another (a magnet under a sticker, say).
     */
    private fun sticker(canvas: Canvas, tile: Cell, items: List<BoardItem>) {
        val size = (cell * 1.25f).coerceIn(ui.dp(26).toFloat(), ui.fx(110).toFloat()) * if (items.size > 1) .8f else 1f
        items.forEachIndexed { index, item ->
            sticker(canvas, tile, item, index, size, (index - (items.size - 1) / 2f) * size * .55f)
        }
    }

    private fun sticker(canvas: Canvas, tile: Cell, item: BoardItem, index: Int, size: Float, offset: Float) {
        val cx = originX + (tile.column + .5f) * cell + offset
        val cy = originY + (tile.row + .5f) * cell
        if (cx < -size || cy < -size || cx > width + size || cy > height + size) return
        val box = this.box.apply { set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2) }
        val edge = maxOf(2f, size / 14f)
        canvas.save()
        canvas.rotate(if ((tile.column + tile.row + index) % 2 == 0) -6f else 5f, cx, cy)
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
        canvas.drawRoundRect(box.apply { set(pad, y, pad + w, y + h) }, h / 2, h / 2, paint)
        canvas.drawText(label, pad * 2, y + h * .66f, text)
    }

    private fun nearOpened(column: Int, row: Int, now: Long): Boolean {
        // Edge neighbours only: diagonal ones would thin most of the fog where routes criss-cross.
        // A lifting neighbour thins this fog only once it is mostly clear, so the edge follows the reveal.
        fun clear(tile: Cell) = cells.containsKey(tile) && (revealProgress(tile, now) ?: 1f) >= .6f
        return clear(Cell(column - 1, row)) || clear(Cell(column + 1, row)) ||
            clear(Cell(column, row - 1)) || clear(Cell(column, row + 1))
    }

    /** [seen]: placements (item@block) already brought into view; a pan the user made is kept until one is added or moved. */
    private class ViewState(var zoom: Float, var x: Float, var y: Float, val seen: MutableSet<String> = mutableSetOf())
    private data class Cell(val column: Int, val row: Int)

    companion object {
        private const val CELL_KM = 18.0
        private const val LON_LEFT = 125.95
        private const val LAT_TOP = 38.62
        private const val KM_PER_LAT = 111.195
        /** Jeju is drawn this far north so the empty strait does not stretch the board. */
        private const val JEJU_SHIFT_KM = 40.0
        private val kmPerLon = 111.195 * cos(Math.toRadians(36.0))
        private val JEJU_BELOW = (33.9 - 36.0) * KM_PER_LAT
        // Block x/y are km from (127°E, 36°N); see Point.block().
        private val ORIGIN_X = (LON_LEFT - 127.0) * kmPerLon
        private val ORIGIN_Y = (LAT_TOP - 36.0) * KM_PER_LAT
        private val STATES = mutableMapOf<String, ViewState>()
        private const val MAX_ZOOM = 5f
        /** How far stickers reach past their tile, in tiles: 1.25 tiles tilted with a shadow, or a fan of several. */
        private const val STICKER_OVERHANG = 1f
        /** Tile size when placing around a spot (Figma A3 ≈ 87 px, a dozen tiles across). */
        private const val FOCUS_TILE = 88
        private const val LABEL_ZOOM = 1.8f
        private const val METRO_LABEL_ZOOM = 2.8f

        /**
         * 18 km tiles (big pixels, as in the Figma board) rasterised from the KOSTAT 2012 시·도 boundaries (southkorea-maps, provinces-geo-simple):
         * each tile takes the 시·도 covering most of it (특별·광역시 win at 25% so they keep a shape); all 17 시·도.
         * Jeju rows are moved [JEJU_SHIFT_KM] north. Regenerate with scripts/raster_provinces.py.
         */
        private val GRID = listOf(
            "...................",
            "...........KK......",
            ".....GKKKKKKK......",
            "....GGGKKKKKKK.....",
            "....GGGGKKKKKKK....",
            "..IGGGGGKKKKKKK....",
            "..IISSGGGKKKKKKK...",
            "...IGGGGGKKKKKKK...",
            "...GGGGGGKKKKKKKK..",
            "....GGGGBBBBBKKKE..",
            "..NNNNGBBBBBBEEEEE.",
            ".NNNNNNBBBEEEEEEEE.",
            "..NNNNXBBBEEEEEEEE.",
            "..NNNNXDBBEEEEEEEE.",
            "...NNNDDBBEEEEEEE..",
            "...NNJNNBBEEEEEEE..",
            "...JJJJJJJEEETEEEE.",
            "...JJJJJJWWETTEEEE.",
            "...JJJJJWWWWTEEUUU.",
            "..JJJJJJWWWWWWWWU..",
            "..MMMMMMWWWWWWWPP..",
            "..MQQMMMMWWWWWPP...",
            "..MMMMMMMWWWWW.....",
            "M.MMMMMMMWW.WW.....",
            "MMMMMMMM...........",
            ".MMMM.MM...........",
            "...MM..............",
            "...................",
            "...................",
            "..ZZZ..............",
            ".ZZZZ..............",
            "...................",
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
        /** Rows down to the south coast; the first view fits these so the mainland fills the board (Jeju below). */
        private val MAINLAND_ROWS = GRID.indexOfLast { line -> line.any { it != '.' && it != 'Z' } } + 1
        private val LAND_LEFT = GRID.take(MAINLAND_ROWS).minOf { line -> line.indexOfFirst { it != '.' }.let { if (it < 0) COLUMNS else it } }
        private val LAND_RIGHT = GRID.take(MAINLAND_ROWS).maxOf { line -> line.indexOfLast { it != '.' } }
        private val LAND_COLUMNS = LAND_RIGHT - LAND_LEFT + 1
        private val LAND_CENTER = (LAND_LEFT + LAND_RIGHT + 1) / 2f

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

        /** Tiles lifting their fog → when they start (uptime ms). Shared by every board, so re-renders keep it going. */
        private val REVEALING = mutableMapOf<Cell, Long>()
        private const val REVEAL_PREFS = "fogo_board"
        private const val REVEAL_KEY = "shownBlocks"
        private const val REVEAL_FRAME_MS = 33L
        /** Wait for the screen to settle before the first tile starts. */
        private const val REVEAL_DELAY_MS = 700L
        /** One tile's fog takes this long to lift; the next starts [REVEAL_STAGGER_MS] later. */
        private const val REVEAL_MS = 1500L
        private const val REVEAL_STAGGER_MS = 400L
        /** All starts fit in this window however many tiles opened. */
        private const val REVEAL_SPREAD_MS = 2400L
        /** Progress at which the finishing flash is gone. */
        private const val REVEAL_END = 1.5f
        /** Cloud shades, darkest to lightest, then the white of fog about to lift. */
        private val DAY_FOG = intArrayOf(0xffd8dde4.toInt(), 0xffe3e7ec.toInt(), 0xffedeff3.toInt(), 0xffffffff.toInt())
        private val NIGHT_FOG = intArrayOf(0xff394150.toInt(), 0xff404858.toInt(), 0xff475061.toInt(), 0xff8a94a6.toInt())

        private fun noise(column: Int, row: Int): Int {
            var n = column * 374761393 + row * 668265263
            n = (n xor (n ushr 13)) * 1274126177
            return (n xor (n ushr 16)) and 0xffff
        }

        /** Smooth value noise in 0..1 with a finer second layer: soft cloud shapes a few tiles across. */
        private fun cloud(x: Float, y: Float): Float {
            fun layer(px: Float, py: Float): Float {
                val x0 = floor(px).toInt()
                val y0 = floor(py).toInt()
                val fx = px - x0
                val fy = py - y0
                val u = fx * fx * (3 - 2 * fx)
                val v = fy * fy * (3 - 2 * fy)
                fun h(i: Int, j: Int) = noise(i, j) / 65535f
                val a = h(x0, y0) + (h(x0 + 1, y0) - h(x0, y0)) * u
                val b = h(x0, y0 + 1) + (h(x0 + 1, y0 + 1) - h(x0, y0 + 1)) * u
                return a + (b - a) * v
            }
            return layer(x / 3.2f, y / 2.4f) * .65f + layer(x / 1.4f + 17f, y / 1.2f + 5f) * .35f
        }

        /** [a] mixed toward [b] by [amount] (0 = a, 1 = b), opaque. */
        private fun blend(a: Int, b: Int, amount: Float): Int {
            fun channel(shift: Int) = ((a shr shift and 0xff) * (1 - amount) + (b shr shift and 0xff) * amount).toInt()
            return (0xff shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        private fun shade(color: Int, factor: Float): Int {
            fun channel(shift: Int) = ((color shr shift and 0xff) * factor).toInt().coerceIn(0, 255)
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
