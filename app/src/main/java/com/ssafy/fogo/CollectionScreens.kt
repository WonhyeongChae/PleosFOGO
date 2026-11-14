package com.ssafy.fogo

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.time.YearMonth

class CollectionScreens(private val host: MainActivity, state: Bundle?) {
    private val ui get() = host.ui
    private val c get() = ui.colors
    private val profile get() = host.profile
    private val catalog get() = host.catalog
    private val fogo get() = host.fogo
    private val slots = linkedMapOf("wrap" to "래핑", "hat" to "모자", "clothes" to "옷", "bg" to "배경")
    private var workshopId = state?.getString("workshop") ?: "CST_PIXEL_BEANIE"
    private var slot = state?.getString("slot") ?: "wrap"
    private var previewId = state?.getString("preview")
    private var previewing = state?.getBoolean("previewing") == true
    private var bookMonth = state?.getString("book")
    private var bookPage = state?.getInt("bookPage") ?: 1
    private var previousCar = state?.getString("previousCar")

    fun save(state: Bundle) {
        state.putString("workshop", workshopId)
        state.putString("slot", slot)
        state.putString("preview", previewId)
        state.putBoolean("previewing", previewing)
        state.putString("book", bookMonth)
        state.putInt("bookPage", bookPage)
        state.putString("previousCar", previousCar)
    }

    fun show(page: Page, body: LinearLayout) {
        when (page) {
            Page.GARAGE -> garage(body)
            Page.WORKSHOP -> workshop(body)
            Page.DRESS -> dress(body)
            Page.RECAP -> recap(body)
            Page.BOOK -> book(body)
            Page.TRANSFER -> transfer(body)
            else -> Unit
        }
    }

    private fun garage(body: LinearLayout) {
        ui.split(body, { left -> garagePreview(left) }, { right ->
            val panel = ui.card(right)
            ui.text(panel, profile.car, 35f, bold = true)
            ui.text(panel, "${profile.mascot}와 함께 · 지도판 ${profile.blocks.size}블록", 17f, c.muted)
            ui.grid(panel, slots.map { (id, label) ->
                ui.column().apply {
                    background = ui.box(c.paper)
                    setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12))
                    addView(ui.label(label, 15f, c.muted))
                    addView(ui.label(costume(profile.equipped(id))?.name ?: defaultItem(id), 20f, bold = true))
                }
            }, 2)
            ui.text(panel, "가진 재료", 20f, bold = true)
            ui.flow(panel, catalog.materials.entries.map { (id, name) ->
                ui.chip("$name ${profile.materialCount(id)}", if (profile.materialCount(id) > 0) c.yellow else c.paper, 15f)
            }, (host.resources.displayMetrics.widthPixels * if (ui.wide) .3f else .8f).toInt())
            if (profile.garage.size > 1) ui.text(panel, "차고에 보관 중 · " +
                profile.garage.filter { it != profile.car }.joinToString(" · "), 17f)
            ui.text(panel, "지도판 배치 ${profile.placements.size}개", 15f, c.muted)
            ui.actions(panel, listOf(Act("입히기") { previewing = false; host.go(Page.DRESS) },
                Act("공방 가기", true) { host.go(Page.WORKSHOP) }))
            val extra = mutableListOf(Act("같이 달릴 친구") { host.go(Page.MASCOT) })
            if (fogo.replayAllowed) extra += Act("기록 이어받기(시연)", click = ::confirmTransfer)
            ui.actions(panel, extra, topMargin = 8)
        })
    }

    private fun confirmTransfer() {
        val target = if (profile.car == "아반떼") "그랜저" else "아반떼"
        host.showSheet("TRANSFER", "${target}로 이어받기", "이 기기의 시연 프로필을 새 차에서 이어봅니다.",
            actions = listOf(Act("취소") {}, Act("이어받기", true, weight = 1.4f) {
                if (!fogo.parked) return@Act
                val before = profile.car
                if (profile.changeCar(target)) {
                    previousCar = before
                    host.go(Page.TRANSFER)
                } else host.message("차량 전환을 저장하지 못했어요")
            }))
    }

    private fun garagePreview(parent: LinearLayout, usePreview: Boolean = false) {
        fun color(id: String): Int? {
            val selected = if (usePreview && previewing && slot == id) previewId else profile.equipped(id)
            return costume(selected)?.color
        }
        val view = GarageArtwork(host, host.art, c,
            catalog.mascots.firstOrNull { it.name == profile.mascot }?.id ?: "cloud",
            profile.car == "그랜저", color("wrap"), color("hat"), color("clothes"), color("bg")).apply {
            background = ui.box(c.paper)
            contentDescription = "${profile.car}와 ${profile.mascot} 미리보기"
        }
        parent.addView(view, LinearLayout.LayoutParams(-1, ui.dp(if (ui.wide) 410 else 310))
            .apply { bottomMargin = ui.dp(13) })
    }

    private fun workshop(body: LinearLayout) {
        val selected = catalog.costumes.first { it.id == workshopId }
        ui.split(body, { left ->
            ui.grid(left, catalog.costumes.map { item ->
                ui.column().apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    background = ui.box(if (item.id == selected.id) c.fog else c.paper,
                        if (item.id == selected.id) c.dark else c.border, shadow = item.id == selected.id)
                    setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
                    setOnClickListener { workshopId = item.id; host.render() }
                    addView(CostumeIcon(host, host.art, item.slot, item.color, c.night).apply {
                        background = ui.box(c.paper)
                    }, LinearLayout.LayoutParams(-1, ui.dp(84)).apply { bottomMargin = ui.dp(8) })
                    addView(ui.label(item.name, 19f, bold = true).apply { gravity = android.view.Gravity.CENTER })
                    addView(ui.label(costText(item), 14f, c.muted).apply { gravity = android.view.Gravity.CENTER })
                    val status = availability(item)
                    addView(ui.chip(status, when (status) { "소유 중" -> c.paper; "만들 수 있음" -> c.lime; else -> c.fog }, 13f),
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(8) })
                }
            }, if (ui.wide) 3 else 2)
        }, { right ->
            val panel = ui.card(right, shadow = true)
            ui.text(panel, selected.name, 29f, bold = true)
            ui.text(panel, slots.getValue(selected.slot), 16f, c.muted)
            val costs = ui.row()
            panel.addView(costs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
            selected.cost.forEach { (id, count) ->
                val enough = profile.materialCount(id) >= count
                costs.addView(ui.chip("${catalog.materials.getValue(id)}  ${profile.materialCount(id)} / $count",
                    if (enough) c.yellow else c.paper, 16f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
            }
            ui.text(panel, availability(selected), 16f, c.muted)
            catalog.regions.firstOrNull { region -> region.spots.firstOrNull()?.materialId?.let(selected.cost::containsKey) == true }?.let {
                ui.text(panel, "${it.name} 재료는 ${it.name} 명소에 도착해야 얻을 수 있어요", 15f, c.muted)
            }
            ui.button(panel, "만들기", true, profile.canCraft(selected)) {
                if (!fogo.parked) return@button
                if (profile.craft(selected)) {
                    host.message("${selected.name} 제작 완료")
                    host.render()
                } else host.message("제작할 수 없어요")
            }
            if (profile.ownsCostume(selected.id)) ui.button(panel, "입히기에서 보기") {
                slot = selected.slot
                previewing = true
                previewId = selected.id
                host.go(Page.DRESS)
            }
        })
    }

    private fun costText(item: Costume) =
        item.cost.entries.joinToString(" · ") { (id, count) -> "${catalog.materials.getValue(id)} $count" }

    private fun availability(item: Costume) = when {
        profile.ownsCostume(item.id) -> "소유 중"
        profile.canCraft(item) -> "만들 수 있음"
        else -> "재료 부족"
    }

    private fun dress(body: LinearLayout) {
        ui.split(body, { left -> garagePreview(left, true) }, { right ->
            val panel = ui.card(right)
            ui.strip(panel, slots.map { (id, name) -> name to {
                slot = id
                previewing = false
                host.render()
            } }, slots.getValue(slot))
            val candidate = if (previewing) previewId else profile.equipped(slot)
            val options = listOf<Costume?>(null) + catalog.costumes.filter { it.slot == slot }
            ui.grid(panel, options.map { item ->
                val owned = item == null || profile.ownsCostume(item.id)
                val name = item?.name ?: defaultItem(slot)
                val suffix = if (item == null) "" else if (!owned) " · 잠김" else if (profile.equipped(slot) == item.id) " · 장착 중" else ""
                val chosen = (item?.id) == candidate
                ui.column().apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    isClickable = owned
                    alpha = if (owned) 1f else .5f
                    background = ui.box(if (chosen) c.fog else c.paper, c.border, shadow = chosen)
                    setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
                    if (owned) setOnClickListener { preview(item?.id) }
                    addView((if (item == null) ui.label("없음", 16f, c.muted).apply { gravity = Gravity.CENTER }
                        else CostumeIcon(host, host.art, item.slot, item.color, c.night)).apply {
                        background = ui.box(c.paper)
                    }, LinearLayout.LayoutParams(-1, ui.dp(84)).apply { bottomMargin = ui.dp(8) })
                    addView(ui.label(name + suffix, 16f, bold = chosen).apply { gravity = android.view.Gravity.CENTER })
                }
            }, if (ui.wide) 3 else 2)
            ui.button(panel, "입히기", true, previewing) {
                if (!fogo.parked) return@button
                if (profile.equip(costume(previewId), slot)) {
                    previewing = false
                    host.render()
                } else host.message("장착을 저장하지 못했어요")
            }
        })
    }

    private fun preview(id: String?) {
        previewing = true
        previewId = id
        host.render()
    }

    private fun defaultItem(slot: String) = when (slot) {
        "wrap" -> "기본 블루"
        "bg" -> "차고"
        else -> "없음"
    }

    private fun costume(id: String?) = catalog.costumes.firstOrNull { it.id == id }

    private fun swatch(parent: LinearLayout, color: Int) {
        parent.addView(View(host).apply { background = ui.box(color) },
            LinearLayout.LayoutParams(-1, ui.dp(52)).apply { bottomMargin = ui.dp(7) })
    }

    private fun recap(body: LinearLayout) {
        val current = YearMonth.now()
        val currentMonthVisits = fogo.cities.count { profile.visitDate(it)?.startsWith(current.toString()) == true }
        val books = profile.books()
        ui.text(body, "매달 첫 주차 때 지난달 기록이 티켓북 한 권으로 묶여요.", 18f, c.muted)
        val covers = listOf(c.navy to c.white, c.violet to c.onViolet, c.lime to c.dark)
        val shelf = listOf<TicketBook?>(null) + books
        ui.grid(body, shelf.mapIndexed { index, book ->
            val month = book?.month ?: current
            val (fill, ink) = if (book == null) c.fog to c.muted else covers[(index - 1) % covers.size]
            ui.column().apply {
                background = ui.box(fill, shadow = book != null)
                setPadding(ui.dp(3), ui.dp(3), ui.dp(7), ui.dp(7))
                val band = ui.row().apply {
                    setBackgroundColor(if (book == null) c.fog else c.yellow)
                    setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                }
                addView(band, LinearLayout.LayoutParams(-1, -2))
                band.addView(ui.label("TICKET BOOK", 11f, c.dark, pixel = true), LinearLayout.LayoutParams(0, -2, 1f))
                band.addView(ui.chip(if (book == null) "모으는 중" else if (book.example) "예시" else "NEW",
                    if (book == null) c.paper else c.lime, 12f))
                val inner = ui.column().apply { setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(12)) }
                addView(inner, LinearLayout.LayoutParams(-1, -2))
                inner.addView(ui.label(month.month.name.take(3), 36f, if (fill == c.navy) c.lime else ink, pixel = true))
                inner.addView(ui.label(month.label(), 24f, ink, bold = true))
                inner.addView(View(host), LinearLayout.LayoutParams(1, ui.dp(if (ui.wide) 70 else 30)))
                inner.addView(ui.label(if (book == null) "도착 티켓 ${currentMonthVisits}장" else "티켓 ${book.visits}장", 17f, ink, bold = true))
                inner.addView(ui.label(if (book == null) "이번 달은 아직 모으는 중이에요" else "새 지역 ${book.newRegions}곳", 15f, ink))
            }
        }, if (ui.wide) 4 else 2, gap = 16)
        val stats = ui.row().apply {
            background = ui.box(c.paper, shadow = true)
            setPadding(ui.dp(3), ui.dp(3), ui.dp(7), ui.dp(7))
        }
        body.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(6) })
        // The totals add up the sample books too, so say so like each sample card does (예시) — never a real count.
        val sample = if (books.any { it.example }) " · 예시 포함" else ""
        listOf("TOTAL" to "지금까지 티켓 ${books.sumOf { it.visits } + currentMonthVisits}장",
            "BOOKS" to "티켓북 ${books.size}권",
            "REGIONS" to "새 지역 ${books.sumOf { it.newRegions }}곳").forEach { (key, value) ->
            val cell = ui.column().apply { setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12)) }
            stats.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            cell.addView(ui.label(key + sample, 11f, c.muted, pixel = true))
            cell.addView(ui.label(value, 20f, bold = true))
        }
        books.firstOrNull()?.let { latest ->
            val line = ui.row().apply { gravity = Gravity.END }
            body.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            line.addView(ui.action("${latest.month.monthValue}월 티켓북 열기", primary = true) { openBook(latest.month) },
                LinearLayout.LayoutParams(-2, -2))
            if (books.size > 1) ui.strip(body, books.drop(1).map { book ->
                "${book.month.monthValue}월 티켓북" to { openBook(book.month) } })
        }
    }

    private fun openBook(month: YearMonth) {
        bookMonth = month.toString()
        bookPage = 1
        host.go(Page.BOOK)
    }

    private fun book(body: LinearLayout) {
        val books = profile.books()
        val index = books.indexOfFirst { it.month.toString() == bookMonth }.coerceAtLeast(0)
        val book = books[index]
        val page = (if (ui.wide) ui.row().apply { gravity = Gravity.TOP } else ui.column()).apply {
            background = ui.box(c.paper, shadow = true)
            setPadding(ui.dp(3), ui.dp(3), ui.dp(7), ui.dp(7))
        }
        body.addView(page, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        fun half(): LinearLayout = ui.column().apply { setPadding(ui.dp(26), ui.dp(22), ui.dp(26), ui.dp(22)) }.also {
            page.addView(it, if (ui.wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        }
        fun seam() = page.addView(DashedLine(host, c.border, ui.dp(10).toFloat(), ui.dp(7).toFloat()),
            if (ui.wide) LinearLayout.LayoutParams(ui.dp(3), -1) else LinearLayout.LayoutParams(-1, ui.dp(3)))
        val tag = "${book.month.month.name.take(3)} ${book.month.year}" + if (book.example) " · 예시" else ""
        if (bookPage == 1) {
            val left = half()
            ui.text(left, tag, 14f, c.muted, pixel = true)
            ui.text(left, "처음 가 본 지역", 23f, bold = true)
            val hero = ui.row().apply { gravity = Gravity.BOTTOM }
            left.addView(hero)
            hero.addView(ui.label("${book.newRegions}", 64f, pixel = true))
            hero.addView(ui.label("곳", 28f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
            val memory = ui.row().apply { background = ui.box(c.paper); setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }
            left.addView(memory, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(14); bottomMargin = ui.dp(12) })
            val note = ui.column().apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)) }
            memory.addView(note, LinearLayout.LayoutParams(0, -2, 1f))
            note.addView(ui.label("MEMORY", 11f, c.muted, pixel = true))
            note.addView(ui.label("기억에 남은 티켓", 15f, c.muted))
            note.addView(ui.label(book.memory ?: "쉬어 가는 달이었어요", 21f, bold = true))
            memory.addView(DashedLine(host, c.border, ui.dp(8).toFloat(), ui.dp(6).toFloat()), LinearLayout.LayoutParams(ui.dp(2), -1))
            memory.addView(ui.label("TRIP", 14f, c.dark, pixel = true).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(c.yellow)
                setPadding(ui.dp(16), 0, ui.dp(16), 0)
            }, LinearLayout.LayoutParams(-2, -1))
            ui.text(left, "도착 티켓 ${book.visits}장", 15f, c.muted)
            if (book.boardBefore != null && book.boardAfter != null)
                ui.text(left, "이번 달 연 블록 +${book.boardAfter - book.boardBefore}", 14f, c.muted)
            seam()
            val right = half()
            ui.text(right, "NEW REGIONS", 14f, c.muted, pixel = true)
            ui.text(right, "${book.month.monthValue}월에 연 곳", 23f, bold = true)
            val visits = fogo.cities.filter { profile.visitDate(it)?.startsWith(book.month.toString()) == true }
            visits.forEach { city ->
                val row = ui.row().apply { background = ui.box(c.paper, shadow = true); setPadding(ui.dp(3), ui.dp(3), ui.dp(7), ui.dp(7)) }
                right.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
                val main = ui.row().apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)) }
                row.addView(main, LinearLayout.LayoutParams(0, -2, 1f))
                main.addView(ui.label("TO", 11f, c.muted, pixel = true), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
                main.addView(ui.label(city.region, 26f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
                main.addView(ui.label("${city.spot} · ${profile.visitDate(city)?.takeLast(5)?.replace('-', '.')}", 15f, c.muted))
                row.addView(ui.label("OPEN", 14f, c.dark, pixel = true).apply {
                    gravity = Gravity.CENTER
                    setBackgroundColor(c.yellow)
                    setPadding(ui.dp(14), 0, ui.dp(14), 0)
                }, LinearLayout.LayoutParams(-2, -1))
            }
            if (visits.isEmpty()) ui.text(right, "예시 달이라 도착 기록 대신 요약만 보여요", 16f, c.muted)
        } else {
            val left = half()
            ui.text(left, "$tag · 2쪽", 14f, c.muted, pixel = true)
            ui.text(left, "이번 달 기록", 23f, bold = true)
            fun stat(label: String, title: String, value: String, side: String) {
                val row = ui.row().apply { background = ui.box(c.paper); setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }
                left.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
                val note = ui.column().apply { setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)) }
                row.addView(note, LinearLayout.LayoutParams(0, -2, 1f))
                note.addView(ui.label(label, 11f, c.muted, pixel = true))
                note.addView(ui.label(title, 15f, c.muted))
                note.addView(ui.label(value, 22f, bold = true))
                row.addView(ui.label(side, 16f, c.dark, pixel = true).apply {
                    gravity = Gravity.CENTER
                    setBackgroundColor(c.yellow)
                    setPadding(ui.dp(16), 0, ui.dp(16), 0)
                }, LinearLayout.LayoutParams(-2, -1))
            }
            stat("STICKERS", "지도판에 붙인 스티커", book.stickers?.let { "${it}장" } ?: "집계 기록 없음", "${book.stickers ?: "-"}")
            book.commute?.let { stat("ROUTINE", "매일 같은 길도 기록이 됐어요", "출퇴근 ${it}번 · 예시", "$it") }
            seam()
            val right = half()
            ui.text(right, "BOARD", 14f, c.muted, pixel = true)
            ui.text(right, "지도판이 이만큼 넓어졌어요", 23f, bold = true)
            if (book.boardBefore != null && book.boardAfter != null) {
                val compare = ui.row().apply { gravity = Gravity.BOTTOM }
                right.addView(compare, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
                listOf("월초" to book.boardBefore, "월말" to book.boardAfter).forEach { (label, value) ->
                    val col = ui.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
                    compare.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
                    col.addView(View(host).apply { background = ui.box(if (label == "월초") c.fog else c.lime) },
                        LinearLayout.LayoutParams(ui.dp(90), ui.dp((40 + 160 * value / book.boardAfter.coerceAtLeast(1)))))
                    col.addView(ui.label("$label ${value}블록", 16f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(8) })
                }
                ui.text(right, "월초 ${book.boardBefore}블록  →  월말 ${book.boardAfter}블록", 15f, c.muted)
            } else ui.text(right, "이 달에는 블록 집계 기록이 없어요.", color = c.muted)
        }
        val controls = mutableListOf<Act>()
        books.getOrNull(index + 1)?.let { older -> controls += Act("← ${older.month.monthValue}월") { openBook(older.month) } }
        books.getOrNull(index - 1)?.let { newer -> controls += Act("${newer.month.monthValue}월 →") { openBook(newer.month) } }
        controls += Act(if (bookPage == 1) "다음 장 →" else "← 1쪽", true, weight = 1.6f) {
            bookPage = if (bookPage == 1) 2 else 1
            host.render()
        }
        ui.text(body, "$bookPage / 2", 14f, c.muted, pixel = true)
        ui.actions(body, controls, topMargin = 4)
    }

    private fun transfer(body: LinearLayout) {
        val sheet = popup(host, body)
        ui.text(sheet, "새 Pleos 차에 로그인했어요. 우리가 다닌 지도가 그대로 따라왔어요.", 19f, c.white, bold = true)
        val ticket = ticketPass(host, sheet, TicketSpec(
            pass = "TRANSFER PASS", tag = "NEW CAR · 새 차", accent = c.lime, stubColor = c.lime,
            stamp = "HELLO!", stampSub = "${profile.garage.size}대", code = "FOGO ACCOUNT"))
        val pass = ticket.body
        val origin = previousCar ?: profile.garage.firstOrNull { it != profile.car }
        ui.text(pass, "FROM → TO", 13f, c.muted, pixel = true)
        ui.text(pass, "${origin ?: "이전 차량"}  →  ${profile.car}", 35f, bold = true)
        ticketFields(host, pass, listOf("지도판" to "${profile.blocks.size}블록", "티켓북" to "${profile.books().size}권",
            "수집품" to "${profile.ownedCount}개", "코스튬" to "${profile.costumeCount}종"))
        ui.text(pass, "${profile.mascot}와 코스튬이 함께 왔어요", 19f, bold = true)
        ui.text(pass, "차고 ${profile.garage.size}대 · 이전 차 보관 완료", 17f, c.muted)
        ui.actions(sheet, listOf(Act("차고 보기") { host.go(Page.GARAGE) },
            Act("이어서 달리기", true, weight = 2f) { host.go(Page.JOURNEY) }))
    }

    private fun YearMonth.label() = "${year}년 ${monthValue}월"
}
