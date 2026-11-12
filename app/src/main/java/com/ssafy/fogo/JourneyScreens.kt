package com.ssafy.fogo

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

internal data class GridWindow(val left: Int, val right: Int, val bottom: Int, val top: Int) {
    fun blocks() = (bottom..top).flatMap { y -> (left..right).map { x -> Block(x, y) } }

    companion object {
        fun around(city: City): GridWindow {
            val route = city.route.map(Point::block)
            return GridWindow(route.minOf { it.x } - 1, route.maxOf { it.x } + 1,
                route.minOf { it.y } - 1, route.maxOf { it.y } + 1)
        }
    }
}

class JourneyScreens(private val host: MainActivity, state: Bundle?) {
    private val ui get() = host.ui
    private val c get() = ui.colors
    private val profile get() = host.profile
    private val catalog get() = host.catalog
    private val fogo get() = host.fogo
    private val windows = fogo.cities.associate { it.spotId to GridWindow.around(it) }
    private val regionCells = catalog.regions.associate { region ->
        region.id to region.spots.flatMap { windows.getValue(it.spotId).blocks() }.map(Block::id).toSet()
    }
    private var spotId = state?.getString("spot") ?: fogo.cities.first().spotId
    private var regionId = state?.getString("region") ?: catalog.regions.first().id
    private var itemId = state?.getString("item")
    private var pendingCell = state?.getString("cell")
    private var itemCategory = state?.getInt("category") ?: 0
    private var selectedMascot = state?.getString("mascot") ?: (catalog.mascots.firstOrNull { it.name == profile.mascot }?.id ?: "cloud")
    private val spot get() = fogo.cities.first { it.spotId == spotId }
    private val region get() = catalog.regions.first { it.id == regionId }

    fun save(state: Bundle) {
        state.putString("spot", spotId)
        state.putString("region", regionId)
        state.putString("item", itemId)
        state.putString("cell", pendingCell)
        state.putInt("category", itemCategory)
        state.putString("mascot", selectedMascot)
    }

    fun focus(city: City) {
        spotId = city.spotId
        regionId = city.provinceId
        pendingCell = null
    }

    fun show(page: Page, body: LinearLayout) {
        when (page) {
            Page.INTRO -> intro(body)
            Page.CONSENT -> consent(body)
            Page.MASCOT -> mascots(body)
            Page.ATLAS -> atlas(body)
            Page.JOURNEY -> journey(body)
            Page.ARRIVAL -> arrival(body)
            Page.BOARD -> board(body)
            Page.PLACE -> placement(body)
            Page.REGION -> regionDetails(body)
            else -> Unit
        }
    }

    private fun intro(body: LinearLayout) {
        ui.split(body, { left ->
            val view = AtlasArtworkView(host, host.art[if (ui.wide) "drive_wide" else "drive_portrait"], c.night)
            left.addView(view, LinearLayout.LayoutParams(-1, ui.dp(if (ui.wide) 470 else 260)))
        }, { right ->
            val panel = ui.card(right, shadow = true)
            ui.text(panel, "FOGO", 35f, pixel = true)
            ui.text(panel, "도착할 때마다\n지도가 열려요", 40f, bold = true)
            ui.button(panel, "시작하기", true) { host.go(Page.CONSENT) }
        })
    }

    private fun consent(body: LinearLayout) {
        val panel = ui.card(body, shadow = true)
        ui.text(panel, "FOGO는 블록만 기억해요", 34f, bold = true)
        ui.text(panel, "지나간 곳은 1km 블록으로만 이 기기에 저장해요.")
        ui.text(panel, "정확한 경로와 시간은 남기지 않아요.", color = c.muted)
        ui.button(panel, "동의하고 시작", true) { host.requestRecording(true) }
        ui.button(panel, "기록 없이 둘러보기") { host.go(Page.MASCOT) }
    }

    private fun mascots(body: LinearLayout) {
        val choices = ui.card(body)
        ui.text(choices, "같이 달릴 친구를 골라요", 28f, bold = true)
        for (mascot in catalog.mascots) {
            val unlocked = catalog.mascotUnlocked(mascot, profile)
            val row = ui.row()
            choices.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
            row.addView(AtlasArtworkView(host, host.art[mascot.id]), LinearLayout.LayoutParams(ui.dp(70), ui.dp(56))
                .apply { marginEnd = ui.dp(10) })
            val title = if (mascot.id == selectedMascot && unlocked) "${mascot.name} · 선택됨" else mascot.name
            val panel = ui.column()
            row.addView(panel, LinearLayout.LayoutParams(0, -2, 1f))
            ui.button(panel, title, primary = mascot.id == selectedMascot && unlocked, enabled = unlocked) {
                selectedMascot = mascot.id
                host.render()
            }
            if (!unlocked) ui.text(panel, mascot.condition, 15f, c.muted)
        }
        host.recordingNotice()?.let { ui.text(choices, it, 16f, c.muted) }
        ui.button(choices, if (profile.onboarded) "친구 바꾸기" else "이 친구로 시작", true) {
            if (!fogo.parked) return@button
            val mascot = catalog.mascots.first { it.id == selectedMascot }
            if (!catalog.mascotUnlocked(mascot, profile)) return@button
            val wasOnboarded = profile.onboarded
            val saved = if (wasOnboarded) profile.selectMascot(mascot.name) else profile.completeOnboarding(mascot.name)
            if (saved) host.go(if (wasOnboarded) Page.GARAGE else Page.ATLAS)
            else host.message("친구를 저장하지 못했어요")
        }
    }

    private fun atlasTabs(body: LinearLayout, active: String) {
        ui.strip(body, listOf("지도" to { host.go(Page.ATLAS) }, "지도판" to { host.go(Page.BOARD) }), active)
    }

    private fun atlas(body: LinearLayout) {
        atlasTabs(body, "지도")
        ui.split(body, { left ->
            val panel = ui.card(left, c.fog)
            ui.text(panel, "블록 ${profile.blocks.size}     오늘 +${profile.newBlocksToday()}", 20f, bold = true)
            panel.addView(AtlasOverview(host, c, catalog.regions.associate { it.id to progress(it) }),
                LinearLayout.LayoutParams(-1, ui.dp(if (ui.wide) 385 else 245)))
            val destination = fogo.destination
            if (destination != null) ui.text(panel, "현재 목적지 · ${destination.name}", 17f, bold = true)
            ui.button(panel, "목적지 미리보기") { host.go(Page.JOURNEY) }
            if (fogo.canOpenReplay || fogo.replayCity != null) {
                ui.button(panel, if (fogo.replayCity == null) "도시 예시 재생" else "다른 도시 예시", true,
                    !fogo.replayRunning, host::selectReplay)
            }
        }, { right ->
            val panel = ui.card(right)
            ui.text(panel, "지역별 진행", 25f, bold = true)
            ui.text(panel, "시·도마다 명소 3곳에 도착하면 자석이 열려요.", 17f, c.muted)
            catalog.regions.forEach { item ->
                val percent = progress(item)
                ui.text(panel, "${item.name}  ·  ${(percent * 100).toInt()}%  ·  명소 ${item.opened(profile)}/3", 19f, bold = true)
                ui.progress(panel, percent)
                ui.button(panel, "${item.name} 지역 카드 보기") {
                    regionId = item.id
                    host.go(Page.REGION)
                }
            }
        })
    }

    private fun progress(region: AtlasRegion): Float {
        val cells = regionCells.getValue(region.id)
        val open = profile.blocks
        return cells.count { it in open }.toFloat() / cells.size
    }

    private fun regionDetails(body: LinearLayout) {
        val selected = region
        val cells = regionCells.getValue(selected.id)
        val panel = ui.card(body)
        ui.text(panel, selected.name, 36f, bold = true)
        val open = profile.blocks
        ui.text(panel, "${cells.count { it in open }} / ${cells.size} 블록 · ${selected.opened(profile)} / 3 명소")
        ui.progress(panel, progress(selected))
        ui.text(panel, "${selected.name} 재료 ${profile.materialCount(selected.spots.first())}개")
        ui.text(panel, if (profile.owns(selected.magnet.id)) "${selected.magnet.name} 획득" else "명소 3곳에 처음 도착하면 ${selected.magnet.name}을 받아요.", bold = true)
        val stickers = selected.spots.distinctBy(City::stickerId).filter(profile::opened)
        if (stickers.isNotEmpty()) ui.text(panel, "모은 스티커 · " + stickers.joinToString(" · ") { it.region })
        ui.text(panel, "명소 미션", 24f, bold = true)
        selected.spots.forEach { city ->
            val state = if (profile.visited(city)) "도착 완료" else "미방문"
            ui.text(panel, "${city.spot}  ·  $state", 19f, bold = true)
            ui.text(panel, city.description, 16f, c.muted)
            if (fogo.canOpenReplay || fogo.replayCity != null) ui.button(panel, "${city.spot} 시연 준비", enabled = !fogo.replayRunning) {
                focus(city)
                host.go(Page.JOURNEY)
                fogo.prepareReplay(city)
            }
        }
        ui.button(panel, "지도판에서 보기", true) {
            focus(selected.spots.first())
            host.go(Page.BOARD)
        }
    }

    private fun journey(body: LinearLayout) {
        val preview = ui.card(body, shadow = true)
        val destination = fogo.destination
        val matched = destination?.let { nav ->
            fogo.cities.minByOrNull { it.point.distanceTo(nav.point) }?.takeIf { it.point.distanceTo(nav.point) <= maxOf(it.radius, 750) }
        }
        ui.text(preview, destination?.name ?: "어디로 가볼까요?", 35f, bold = true)
        if (matched == null) {
            ui.text(preview, if (destination == null) "Pleos 내비에서 목적지를 설정한 뒤 돌아오세요."
                else "이 목적지는 FOGO 시연 명소에 등록되어 있지 않아요.", color = c.muted)
        } else {
            ui.text(preview, "${matched.city} · ${matched.region}")
            ui.text(preview, matched.description, color = c.muted)
            if (matched.nearby.isNotEmpty()) ui.text(preview, "같은 시·도 명소 · ${matched.nearby.joinToString(" · ")}", 16f)
            if (profile.visited(matched)) ui.text(preview, "첫 도착 보상 수령 완료", bold = true)
        }
        preview.addView(AtlasArtworkView(host, host.art["drive_wide"], c.night), LinearLayout.LayoutParams(-1, ui.dp(230)))
        if (fogo.replayCity == null) {
            ui.button(body, if (fogo.recording) "위치 기록 중지" else "위치 기록 시작", true) {
                if (fogo.recording) host.stopRecording() else host.explainLocation()
            }
            host.recordingNotice()?.let { ui.text(body, it, 16f, c.muted) }
            if (fogo.canOpenReplay) {
                if (destination == null) ui.button(body, "경포대를 내비로 열기") {
                    if (!fogo.routeTo(fogo.cities.first())) host.message("Pleos 내비에서 경포대를 설정해 주세요")
                }
                ui.button(body, "도시 예시 재생", click = host::selectReplay)
            }
        } else {
            ui.button(body, "주행 재생", true, !fogo.replayRunning, fogo::startReplay)
            replayControls(body)
        }
    }

    private fun replayControls(body: LinearLayout) {
        ui.button(body, "다른 도시 예시", enabled = !fogo.replayRunning, click = host::selectReplay)
        ui.button(body, "실제 차량 모드로 돌아가기", enabled = !fogo.replayRunning, click = fogo::leaveReplay)
    }

    private fun arrival(body: LinearLayout) {
        val arrival = fogo.trip.arrival ?: return journey(body)
        val city = arrival.city
        val panel = ticket(body, "ARRIVAL", "도착", city.color)
        ui.text(panel, "${city.spot} 도착", 39f, bold = true)
        ui.text(panel, "${city.city}  ·  ${arrival.day}  ·  P 주차", 17f, c.muted)
        ui.text(panel, arrival.tags.summary(), 19f, bold = true)
        ui.text(panel, city.description, 17f, c.muted)
        val rewards = listOfNotNull("${city.region} 스티커 +1".takeIf { arrival.sticker },
            "${city.material} +1".takeIf { arrival.material },
            "${region.name} 자석 +1".takeIf { arrival.magnet })
        ui.text(panel, "받은 것", 20f, bold = true)
        rewards.forEach { ui.text(panel, it, 18f, bold = true) }
        fogo.ambientStatus?.let { ui.text(panel, it, 16f, c.muted) }
        ui.button(body, "지도판에 스티커 붙이기", true) {
            focus(city)
            val reward = if (arrival.sticker) city.stickerId else if (arrival.magnet) city.magnetId else city.stickerId
            startPlacement(reward)
        }
        if (fogo.replayCity != null) replayControls(body)
    }

    private fun ticket(body: LinearLayout, title: String, subtitle: String, accent: Int) =
        atlasTicket(host, body, title, subtitle, accent)

    private fun board(body: LinearLayout) {
        atlasTabs(body, "지도판")
        regionStrip(body)
        val stored = profile.placements
        ui.split(body, { left -> mapCard(left, false) }, { right ->
            val panel = ui.card(right)
            val owned = catalog.boardItems.filter { profile.owns(it.id) }
            ui.text(panel, "스티커 ${owned.count { !it.magnet }}   자석 ${owned.count { it.magnet }}", 23f, bold = true)
            if (owned.isEmpty()) ui.text(panel, "첫 도착을 기록하면 스티커가 열려요.", color = c.muted)
            owned.forEach { item ->
                ui.text(panel, item.name + if (stored.containsKey(item.id)) " · 배치 완료" else "", 18f)
            }
            ui.button(panel, "붙이기", true, owned.isNotEmpty()) { startPlacement(null) }
        })
    }

    private fun startPlacement(preferred: String?) {
        val item = catalog.boardItems.firstOrNull { it.id == preferred && profile.owns(it.id) }
            ?: catalog.boardItems.firstOrNull { profile.owns(it.id) } ?: return
        itemCategory = if (item.magnet) 1 else 0
        itemId = item.id
        pendingCell = null
        host.go(Page.PLACE)
    }

    private fun regionStrip(body: LinearLayout) {
        ui.strip(body, catalog.regions.map { item -> item.name to {
            focus(item.spots.first())
            host.render()
        } }, region.name)
        ui.strip(body, region.spots.map { city -> city.spot to {
            focus(city)
            host.render()
        } }, spot.spot)
    }

    private fun placement(body: LinearLayout) {
        regionStrip(body)
        val owned = catalog.boardItems.filter { profile.owns(it.id) }
        val selected = owned.firstOrNull { it.id == itemId }
        ui.split(body, { left -> mapCard(left, true) }, { right ->
            val panel = ui.card(right)
            ui.strip(panel, listOf("스티커" to { selectCategory(false) }, "자석" to { selectCategory(true) }),
                if (itemCategory == 0) "스티커" else "자석")
            val filtered = owned.filter { it.magnet == (itemCategory == 1) }
            if (filtered.isEmpty()) ui.text(panel, "보유한 아이템이 없어요.", color = c.muted)
            filtered.chunked(if (ui.wide) 3 else 2).forEach { items ->
                val row = ui.row().apply { gravity = Gravity.TOP }
                panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(9) })
                items.forEach { item ->
                    val tile = ui.column().apply {
                        background = ui.box(c.paper, if (selected?.id == item.id) c.lime else c.border)
                        setPadding(ui.dp(7), ui.dp(8), ui.dp(7), ui.dp(8))
                    }
                    row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(7) })
                    tile.addView(stickerArt(item), LinearLayout.LayoutParams(-1, ui.dp(54)))
                    ui.button(tile, item.name, item.id == selected?.id) {
                        itemId = item.id
                        pendingCell = null
                        host.render()
                    }
                }
                repeat((if (ui.wide) 3 else 2) - items.size) {
                    row.addView(View(host), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = ui.dp(7) })
                }
            }
            ui.text(panel, if (pendingCell == null) "열린 빈 칸을 선택하세요." else "선택한 칸에 ${selected?.name}을 붙일 수 있어요.", 16f, c.muted)
            ui.button(panel, "여기 붙이기", true, pendingCell != null && selected != null) {
                if (!fogo.parked || selected == null) return@button
                val block = pendingCell ?: return@button
                if (profile.place(selected.id, block)) {
                    pendingCell = null
                    host.go(Page.BOARD)
                } else host.message("이 칸에 붙일 수 없어요")
            }
        })
    }

    private fun selectCategory(magnet: Boolean) {
        itemCategory = if (magnet) 1 else 0
        itemId = catalog.boardItems.firstOrNull { it.magnet == magnet && profile.owns(it.id) }?.id
        pendingCell = null
        host.render()
    }

    private fun mapCard(body: LinearLayout, editing: Boolean) {
        val window = windows.getValue(spotId)
        val panel = ui.card(body, c.fog)
        ui.text(panel, spot.spot, 23f, bold = true)
        val owned = catalog.boardItems.associateBy(BoardItem::id)
        val placed = profile.placements.entries.associate { it.value to owned[it.key] }
        val open = profile.blocks
        val selected = owned[itemId]
        val screenDp = host.resources.configuration.screenWidthDp
        val columns = window.right - window.left + 1
        val gridDp = if (ui.wide) ((screenDp - if (editing) 72 else 224) * .56).toInt() else screenDp - 72
        val rowHeight = (gridDp / columns).coerceIn(34, 90)
        val grid = ui.column()
        panel.addView(grid, LinearLayout.LayoutParams(-1, -2))
        for (y in window.top downTo window.bottom) {
            val line = ui.row()
            grid.addView(line, LinearLayout.LayoutParams(-1, ui.dp(rowHeight)))
            for (x in window.left..window.right) {
                val block = Block(x, y).id
                val unlocked = block in open
                val item = placed[block]
                val preview = editing && pendingCell == block
                val color = when {
                    preview -> c.lime
                    item != null -> item.color
                    unlocked -> if (c.night) 0xff214a62.toInt() else 0xff71c4ed.toInt()
                    else -> c.fog
                }
                val tile = FrameLayout(host).apply {
                    val icon = item ?: selected.takeIf { preview }
                    if (icon != null) {
                        addView(stickerArt(icon), FrameLayout.LayoutParams(ui.dp(rowHeight - 10), ui.dp(rowHeight - 10), Gravity.CENTER))
                    } else if (!unlocked) addView(ui.label("·", 17f, c.muted, true),
                        FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                    background = ui.box(color, if (preview) c.dark else if (c.night) 0xff47515d.toInt() else 0xffb5c2cf.toInt())
                    contentDescription = item?.name ?: when {
                        preview -> "배치할 칸"
                        unlocked -> "열린 칸"
                        else -> "안개 칸"
                    }
                    if (editing && unlocked && item == null) setOnClickListener {
                        if (fogo.parked && itemId != null) {
                            pendingCell = block
                            host.render()
                        }
                    }
                }
                line.addView(tile, LinearLayout.LayoutParams(0, -1, 1f))
            }
        }
        if (editing) ui.text(panel, "안개 칸과 이미 붙인 칸은 선택할 수 없어요.", 15f, c.muted)
    }
    private fun stickerArt(item: BoardItem): View {
        val key = when {
            item.magnet -> "mtn"
            item.id.startsWith("STK_BS") -> "wave"
            item.id.startsWith("STK_GW") -> "cup"
            else -> "star"
        }
        val tint = when (key) {
            "mtn" -> 0xffb9e6ff.toInt()
            "wave" -> 0xff9edcff.toInt()
            "cup" -> 0xffffe08a.toInt()
            else -> 0xffe7e1ff.toInt()
        }
        return FrameLayout(host).apply {
            background = ui.box(tint, c.white, shadow = true)
            addView(AtlasArtworkView(host, host.art[key]), FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER))
        }
    }
}

internal fun atlasTicket(host: MainActivity, body: LinearLayout, title: String, subtitle: String, accent: Int): LinearLayout {
    val ui = host.ui
    val c = ui.colors
    val panel = ui.card(body, shadow = true)
    val decoration = FrameLayout(host).apply { setBackgroundColor(c.navy) }
    panel.addView(decoration, LinearLayout.LayoutParams(-1, ui.dp(57)).apply { bottomMargin = ui.dp(16) })
    val banner = ui.row().apply { setPadding(ui.dp(14), 0, ui.dp(16), 0) }
    decoration.addView(banner, FrameLayout.LayoutParams(-1, -1))
    banner.addView(ui.label(title, if (ui.wide) 19f else 13f, c.white, pixel = true), LinearLayout.LayoutParams(0, -2, 1f))
    banner.addView(ui.label(subtitle, 17f, c.lime, true))
    val notch = ImageView(host).apply {
        setImageResource(if (ui.wide) R.drawable.atlas_ticket_day else R.drawable.atlas_ticket_narrow)
    }
    decoration.addView(notch, FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.BOTTOM or Gravity.END)
        .apply { marginEnd = ui.dp(4); bottomMargin = -ui.dp(11) })
    panel.addView(View(host).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(-1, ui.dp(5))
        .apply { bottomMargin = ui.dp(12) })
    return panel
}
