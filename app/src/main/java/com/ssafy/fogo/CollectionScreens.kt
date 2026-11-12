package com.ssafy.fogo

import android.app.AlertDialog
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
            ui.text(panel, profile.mascot, 19f, c.muted)
            slots.forEach { (id, label) ->
                ui.text(panel, "$label  ·  ${costume(profile.equipped(id))?.name ?: defaultItem(id)}", 17f)
            }
            ui.text(panel, "가진 재료", 22f, bold = true)
            ui.text(panel, catalog.materials.entries.joinToString("   ") { (id, name) -> "$name ${profile.materialCount(id)}" })
            if (profile.garage.size > 1) ui.text(panel, "차고에 보관 중 · " +
                profile.garage.filter { it != profile.car }.joinToString(" · "), 17f)
            ui.text(panel, "지도판 배치 ${profile.placements.size}개", 16f, c.muted)
            ui.button(panel, "공방 가기", true) { host.go(Page.WORKSHOP) }
            ui.button(panel, "입히기") { previewing = false; host.go(Page.DRESS) }
            ui.button(panel, "같이 달릴 친구") { host.go(Page.MASCOT) }
            if (fogo.replayAllowed) ui.button(panel, "기록 이어받기(시연)", click = ::confirmTransfer)
        })
    }

    private fun confirmTransfer() {
        val target = if (profile.car == "아반떼") "그랜저" else "아반떼"
        host.showDialog(AlertDialog.Builder(host).setTitle("${target}로 이어받기")
            .setMessage("이 기기의 시연 프로필을 새 차에서 이어봅니다.")
            .setNegativeButton("취소", null).setPositiveButton("이어받기") { _, _ ->
                if (!fogo.parked) return@setPositiveButton
                val before = profile.car
                if (profile.changeCar(target)) {
                    previousCar = before
                    host.go(Page.TRANSFER)
                } else host.message("차량 전환을 저장하지 못했어요")
            })
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
            val grid = ui.column()
            left.addView(grid)
            catalog.costumes.chunked(2).forEach { items ->
                val row = ui.row().apply { gravity = Gravity.TOP }
                grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(9) })
                items.forEach { item ->
                    val cell = ui.column().apply {
                        background = ui.box(c.paper, if (item.id == selected.id) c.lime else c.border)
                        setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
                    }
                    row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(8) })
                    swatch(cell, item.color)
                    ui.button(cell, item.name, item.id == selected.id) {
                        workshopId = item.id
                        host.render()
                    }
                    ui.text(cell, availability(item), 15f, c.muted)
                }
            }
        }, { right ->
            val panel = ui.card(right, shadow = true)
            ui.text(panel, selected.name, 29f, bold = true)
            ui.text(panel, slots.getValue(selected.slot), 17f, c.muted)
            selected.cost.forEach { (id, count) ->
                ui.text(panel, "${catalog.materials.getValue(id)}  ${profile.materialCount(id)} / $count", 19f, bold = true)
            }
            ui.text(panel, availability(selected), 17f, c.muted)
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
            ui.button(panel, defaultItem(slot), candidate == null) { preview(null) }
            for (item in catalog.costumes.filter { it.slot == slot }) {
                val owned = profile.ownsCostume(item.id)
                val suffix = if (!owned) " · 잠김" else if (profile.equipped(slot) == item.id) " · 장착 중" else ""
                ui.button(panel, item.name + suffix, item.id == candidate, owned) { preview(item.id) }
            }
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
        ui.text(body, "매달 첫 주차 때 지난달 기록이 티켓북 한 권으로 묶여요.", 19f, c.muted)
        val shelf = listOf<TicketBook?>(null) + books
        val columns = if (ui.wide) 4 else 2
        shelf.chunked(columns).forEach { items ->
            val row = ui.row().apply { gravity = Gravity.TOP }
            body.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(16) })
            items.forEach { book ->
                val month = book?.month ?: current
                val tile = ui.column().apply {
                    background = ui.box(c.paper, shadow = true)
                    setPadding(ui.dp(14), ui.dp(13), ui.dp(14), ui.dp(14))
                }
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) })
                ui.text(tile, "TICKET BOOK", 16f, c.muted, pixel = true).typeface = host.resources.getFont(R.font.silkscreen)
                val band = ui.label("%02d".format(month.monthValue), 36f, if (book == null) c.dark else c.white, pixel = true)
                band.gravity = Gravity.CENTER
                band.background = ui.box(if (book == null) c.yellow else c.navy)
                tile.addView(band, LinearLayout.LayoutParams(-1, ui.dp(116)).apply { bottomMargin = ui.dp(12) })
                ui.text(tile, month.label(), 23f, bold = true)
                ui.text(tile, if (book == null) "모으는 중" else if (book.example) "예시" else "지난달 기록", 16f, c.muted)
                ui.text(tile, if (book == null) "도착 티켓 ${currentMonthVisits}장" else
                    "티켓 ${book.visits}장 · 새 지역 ${book.newRegions}곳", 17f)
                if (book != null) ui.button(tile, "${month.monthValue}월 티켓북 열기", true) { openBook(month) }
            }
            repeat(columns - items.size) {
                row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = ui.dp(10) })
            }
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
        ui.text(body, book.month.label() + if (book.example) " · 예시" else "", 35f, bold = true)
        ui.text(body, "$bookPage / 2", 15f, c.muted, pixel = true)
        if (bookPage == 1) {
            ui.split(body, { left ->
                val memory = ui.card(left, shadow = true)
                ui.text(memory, "MEMORY", 13f, c.muted, pixel = true)
                ui.text(memory, "기억에 남은 티켓", 25f, bold = true)
                ui.text(memory, book.memory ?: "이 달의 방문 기록이 없어요.")
            }, { right ->
                val visits = ui.card(right)
                ui.text(visits, "처음 가 본 지역 ${book.newRegions}곳", 23f, bold = true)
                ui.text(visits, "도착 티켓 ${book.visits}장")
                fogo.cities.filter { profile.visitDate(it)?.startsWith(book.month.toString()) == true }.forEach {
                    ui.text(visits, "${it.spot} · ${profile.visitDate(it)}", 17f)
                }
            })
        } else {
            ui.split(body, { left ->
                val stickers = ui.card(left, shadow = true)
                ui.text(stickers, "STICKERS", 13f, c.muted, pixel = true)
                ui.text(stickers, "지도판에 붙인 스티커", 25f, bold = true)
                ui.text(stickers, book.stickers?.let { "${it}장" } ?: "집계 기록 없음", 22f)
                book.commute?.let { ui.text(stickers, "출퇴근 ${it}번 · 예시") }
            }, { right ->
                val board = ui.card(right)
                ui.text(board, "지도판 변화", 25f, bold = true)
                if (book.boardBefore != null && book.boardAfter != null) {
                    ui.text(board, "월초 ${book.boardBefore}블록  →  월말 ${book.boardAfter}블록")
                    ui.progress(board, book.boardBefore.toFloat() / book.boardAfter.coerceAtLeast(1))
                } else ui.text(board, "이 달에는 블록 집계 기록이 없어요.", color = c.muted)
            })
        }
        ui.button(body, if (bookPage == 1) "다음 장 →" else "← 1쪽", true) {
            bookPage = if (bookPage == 1) 2 else 1
            host.render()
        }
        val neighbors = listOfNotNull(books.getOrNull(index + 1)?.month, books.getOrNull(index - 1)?.month)
        if (neighbors.isNotEmpty()) ui.strip(body, neighbors.map { month -> "${month.monthValue}월 티켓북" to { openBook(month) } })
    }

    private fun transfer(body: LinearLayout) {
        val pass = atlasTicket(host, body, "TRANSFER PASS", "새 차", c.lime)
        val origin = previousCar ?: profile.garage.firstOrNull { it != profile.car }
        ui.text(pass, "${origin ?: "이전 차량"}  →  ${profile.car}", 35f, bold = true)
        ui.text(pass, "같은 시연 프로필의 기록을 이어받았어요.", 18f, c.muted)
        ui.text(pass, "지도판 ${profile.blocks.size}블록  ·  티켓북 ${profile.books().size}권", 22f, bold = true)
        ui.text(pass, "수집품 ${profile.ownedCount}개  ·  ${profile.mascot}와 코스튬 ${profile.costumeCount}종", 19f)
        ui.text(pass, "차고 ${profile.garage.size}대 · 이전 차 보관 완료", 17f)
        ui.button(body, "차고 보기") { host.go(Page.GARAGE) }
        ui.button(body, "이어서 달리기", true) { host.go(Page.JOURNEY) }
    }

    private fun YearMonth.label() = "${year}년 ${monthValue}월"
}
