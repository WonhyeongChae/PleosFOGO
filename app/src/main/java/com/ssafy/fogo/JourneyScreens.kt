package com.ssafy.fogo

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
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
            val view = DriveSceneView(host, host.art[if (ui.wide) "drive_wide" else "drive_portrait"], c.night)
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
        ui.text(panel, "필수 · 위치 기록과 계정 저장", 22f, bold = true)
        listOf("지나간 곳은 1km 블록으로만 저장해요", "정확한 경로와 시간은 남기지 않아요",
            "기록은 내 계정에 남아 다음 차에서도 이어져요", "설정에서 언제든 끄고 지울 수 있어요").forEach {
            ui.text(panel, "✓  $it", 19f)
        }
        val optional = ui.card(body)
        ui.text(optional, "선택 · 친구와 지역 순위 참여", 22f, bold = true)
        ui.text(optional, "친구에게는 시·군·구 단위 지도판과 둘 다 가 본 곳만 보여요", 18f, c.muted)
        ui.text(body, "선택 항목은 꺼 두어도 혼자 기록할 수 있어요. 집·회사로 보이는 곳은 공유 이미지에서 가려요.", 18f, c.ink)
        ui.button(body, "동의하고 시작", true) { host.requestRecording(true) }
        ui.button(body, "기록 없이 둘러보기") { host.go(Page.MASCOT) }
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
            val counts = ui.row()
            panel.addView(counts, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
            counts.addView(ui.chip("블록 ${profile.blocks.size}", c.paper, 13f, pixel = true))
            counts.addView(ui.chip("오늘 +${profile.newBlocksToday()}", c.lime, 13f, pixel = true),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
            panel.addView(KoreaBoard(host, ui, host.art, profile.blocks, emptyMap(), key = "atlas").apply {
                contentDescription = "안개 지도"
            }, LinearLayout.LayoutParams(-1, screenPart(if (ui.wide) .5f else .4f)))
            // Floating bar at the bottom of the map, like the Figma "nearby" card.
            val bar = ui.column().apply {
                background = ui.box(c.paper, shadow = true)
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(12))
            }
            panel.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
            val destination = fogo.destination
            ui.text(bar, "DESTINATION", 11f, c.muted, pixel = true)
            ui.text(bar, destination?.let { "현재 목적지 · ${it.name}" } ?: "내비에서 목적지를 정하면 미리보기가 열려요", 18f, bold = true)
            val actions = mutableListOf(Act("목적지 미리보기") { host.go(Page.JOURNEY) })
            if (fogo.canOpenReplay || fogo.replayCity != null) {
                actions += Act(if (fogo.replayCity == null) "도시 예시 재생" else "다른 도시 예시", true, !fogo.replayRunning,
                    click = host::selectReplay)
            }
            ui.actions(bar, actions, topMargin = 6)
        }, { right ->
            val panel = ui.card(right)
            ui.text(panel, "지역별 진행", 25f, bold = true)
            ui.text(panel, "시·도마다 명소 3곳에 도착하면 자석이 열려요.", 16f, c.muted)
            catalog.regions.forEach { item ->
                val percent = progress(item)
                val line = ui.row().apply {
                    isClickable = true
                    contentDescription = "${item.name} 지역 카드 보기"
                    background = if (item.id == regionId) ui.box(c.fog) else null
                    setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                    setOnClickListener { regionId = item.id; host.go(Page.REGION) }
                }
                panel.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
                line.addView(ui.label(item.name, 19f, bold = true), LinearLayout.LayoutParams(ui.dp(60), -2))
                val bar = ui.column()
                line.addView(bar, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) })
                ui.progress(bar, percent)
                line.addView(ui.label("${(percent * 100).toInt()}%", 15f, c.muted, pixel = true))
                if (profile.owns(item.magnet.id)) line.addView(ui.chip("자석!", c.violet, 13f, c.onViolet),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
                else line.addView(ui.label("명소 ${item.opened(profile)}/3", 14f, c.muted),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
            }
            ui.button(panel, "시·도 상세 보기", true) { host.go(Page.REGION) }
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
        val open = profile.blocks
        ui.split(body, { left ->
            val panel = ui.card(left, shadow = true)
            ui.text(panel, selected.name, 36f, bold = true)
            val chips = ui.row()
            panel.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
            chips.addView(ui.chip("${(progress(selected) * 100).toInt()}% 열림", c.lime, 15f))
            chips.addView(ui.label("${cells.count { it in open }} / ${cells.size} 블록", 16f, c.muted),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(12) })
            ui.progress(panel, progress(selected))
            val tags = ui.row()
            panel.addView(tags)
            tags.addView(ui.chip("${selected.name} 재료 ${profile.materialCount(selected.spots.first())}개", c.yellow, 15f))
            tags.addView(ui.chip("명소 ${selected.opened(profile)} / 3", c.paper, 15f),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(10) })

            val owned = profile.owns(selected.magnet.id)
            val magnet = ui.card(left, if (owned) c.violet else c.fog, shadow = owned)
            ui.text(magnet, "MAGNET", 11f, if (owned) c.onViolet else c.muted, pixel = true)
            ui.text(magnet, if (owned) "${selected.magnet.name} 획득" else selected.magnet.name, 24f,
                if (owned) c.onViolet else c.ink, bold = true)
            if (!owned) ui.text(magnet, "명소 3곳에 처음 도착하면 받아요", 16f, c.muted)

            val stickers = selected.spots.distinctBy(City::stickerId).filter(profile::opened)
            val shelf = ui.card(left)
            ui.text(shelf, "모은 스티커", 20f, bold = true)
            val row = ui.row()
            shelf.addView(row)
            stickers.forEach { city ->
                catalog.boardItems.firstOrNull { it.id == city.stickerId }?.let {
                    row.addView(stickerArt(it), LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)).apply { marginEnd = ui.dp(10) })
                }
            }
            if (stickers.isEmpty()) ui.text(shelf, "아직 없어요", 16f, c.muted)
            else ui.text(shelf, stickers.joinToString(" · ") { it.region }, 15f, c.muted)
        }, { right ->
            val panel = ui.card(right)
            ui.text(panel, "명소 미션", 24f, bold = true)
            selected.spots.forEach { city ->
                val visited = profile.visited(city)
                val day = profile.visitDate(city)
                val line = ui.row().apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) }
                panel.addView(line, LinearLayout.LayoutParams(-1, -2))
                line.addView(ui.label(if (visited) "✓ ${city.spot}  ·  ${day?.takeLast(5)?.replace('-', '.') ?: ""} 주차"
                    else "□ ${city.spot} 도착", 19f, if (visited) c.ink else c.muted, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(ui.chip(if (visited) "기록됨" else "아직", if (visited) c.lime else c.fog, 14f))
                ui.text(panel, city.description, 15f, c.muted)
                if (fogo.canOpenReplay || fogo.replayCity != null) ui.button(panel, "${city.spot} 시연 준비", enabled = !fogo.replayRunning) {
                    focus(city)
                    host.go(Page.JOURNEY)
                    fogo.prepareReplay(city)
                }
            }
            ui.button(right, "지도판에서 보기", true) {
                focus(selected.spots.first())
                host.go(Page.BOARD)
            }
        })
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
        // Animated while parked; runs faster during the replay so the trip reads as "on the way".
        preview.addView(DriveSceneView(host, host.art["drive_wide"], c.night, speed = if (fogo.replayRunning) 3f else 1f),
            LinearLayout.LayoutParams(-1, ui.dp(if (ui.wide) 300 else 240)))
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
            ui.actions(body, listOf(
                Act("다른 도시 예시", enabled = !fogo.replayRunning, click = host::selectReplay),
                Act("실제 차량 모드로", enabled = !fogo.replayRunning, click = fogo::leaveReplay),
                Act("주행 재생", true, !fogo.replayRunning, 1.6f, fogo::startReplay)))
        }
    }

    private fun arrival(body: LinearLayout) {
        val arrival = fogo.trip.arrival ?: return journey(body)
        val city = arrival.city
        val visitedCount = fogo.cities.count(profile::visited).coerceAtLeast(1)
        val sheet = popup(host, body)
        val pass = ticketPass(host, sheet, TicketSpec(
            pass = "FOGO PASS", tag = "ARRIVAL · 도착", accent = city.color, stubColor = c.yellow,
            stamp = if (arrival.sticker) "OPEN!" else "ARRIVED!", stampSub = city.region,
            code = "FG-" + arrival.day.filter(Char::isDigit).takeLast(8)))
        val panel = pass.body
        val route = ui.row().apply { gravity = Gravity.BOTTOM }
        panel.addView(route, LinearLayout.LayoutParams(-1, -2))
        val to = ui.column()
        route.addView(to)
        to.addView(ui.label("TO", 13f, c.muted, pixel = true))
        to.addView(ui.label(city.region, 64f, bold = true))
        route.addView(ui.chip(if (arrival.sticker) "OPEN!" else "AGAIN", c.lime, 18f, pixel = true),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(16); bottomMargin = ui.dp(14) })
        ui.text(panel, "${city.spot} 도착", 22f, bold = true)
        ui.text(panel, "${city.city}  ·  ${city.description}", 15f, c.muted)
        ticketFields(host, panel, listOf(
            "DATE" to arrival.day, "GATE" to "P 주차",
            "TRIP" to arrival.tags.summary(), "NO." to "${visitedCount}번째 명소"))
        val rewards = listOfNotNull("${city.region} 스티커 +1".takeIf { arrival.sticker },
            "${city.material} +1".takeIf { arrival.material },
            "${region.name} 자석 +1".takeIf { arrival.magnet })
        val gift = ui.row()
        panel.addView(gift, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        catalog.boardItems.firstOrNull { it.id == city.stickerId }?.let {
            gift.addView(stickerArt(it), LinearLayout.LayoutParams(ui.dp(78), ui.dp(78)).apply { marginEnd = ui.dp(16) })
        }
        val list = ui.column()
        gift.addView(list, LinearLayout.LayoutParams(0, -2, 1f))
        list.addView(ui.label(if (rewards.isEmpty()) "이미 받은 곳이에요" else "받은 것", 13f, c.muted, pixel = rewards.isNotEmpty()))
        val chipRow = ui.row()
        list.addView(chipRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        val chips = rewards.mapIndexed { index, reward ->
            ui.chip(reward, if (index == 0) c.yellow else c.lime, 18f).also {
                chipRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
            }
        }
        fogo.ambientStatus?.let { ui.text(panel, it, 15f, c.muted) }
        if (host.entering && ValueAnimator.areAnimatorsEnabled()) {
            // Ticket drops in, the stub stamp lands, then each reward pops like a stamp.
            pass.root.alpha = 0f
            pass.root.translationY = -ui.dp(48).toFloat()
            pass.root.animate().alpha(1f).translationY(0f).setDuration(420)
                .setInterpolator(OvershootInterpolator(1.3f)).start()
            pass.stamp.scaleX = 2.2f
            pass.stamp.scaleY = 2.2f
            pass.stamp.alpha = 0f
            pass.stamp.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(420)
                .setDuration(200).setInterpolator(OvershootInterpolator(2.5f)).start()
            chips.forEachIndexed { index, chip ->
                chip.alpha = 0f
                chip.scaleX = 1.6f
                chip.scaleY = 1.6f
                chip.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(640L + index * 160L)
                    .setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
            }
        }
        val actions = mutableListOf<Act>()
        if (fogo.replayCity != null) actions += Act("다른 도시 예시", enabled = !fogo.replayRunning, click = host::selectReplay)
        actions += Act("지도판에 스티커 붙이기", true, weight = 2f) {
            focus(city)
            val reward = if (arrival.sticker) city.stickerId else if (arrival.magnet) city.magnetId else city.stickerId
            startPlacement(reward)
        }
        ui.actions(sheet, actions)
        if (fogo.replayCity != null) ui.actions(sheet, listOf(
            Act("실제 차량 모드로 돌아가기", enabled = !fogo.replayRunning, click = fogo::leaveReplay)))
    }

    private fun board(body: LinearLayout) {
        val stored = profile.placements
        val owned = catalog.boardItems.filter { profile.owns(it.id) }
        val tabs = ui.row()
        body.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
        tabs.addView(ui.action("지도") { host.go(Page.ATLAS) })
        tabs.addView(ui.action("지도판", selected = true) {}, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) })
        tabs.addView(ui.chip("스티커 ${owned.count { !it.magnet }}", c.yellow, 15f),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(16) })
        tabs.addView(ui.chip("자석 ${owned.count { it.magnet }}", c.violet, 15f, c.onViolet),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        ui.split(body, { left -> mapCard(left, false) }, { right ->
            val panel = ui.card(right, shadow = true)
            ui.text(panel, "붙일 수 있는 것", 23f, bold = true)
            ui.text(panel, "열린 블록이면 어디든 붙일 수 있어요", 15f, c.muted)
            if (owned.isEmpty()) ui.text(panel, "첫 도착을 기록하면 스티커가 열려요.", color = c.muted)
            fun tile(item: BoardItem): View {
                val placed = stored.containsKey(item.id)
                return FrameLayout(host).apply {
                    contentDescription = item.name + if (placed) " · 배치 완료" else ""
                    addView(if (item.magnet) magnetArt(item) else stickerArt(item), FrameLayout.LayoutParams(-1, -1))
                    if (placed) addView(ui.label("✓", 13f, c.dark, bold = true).apply {
                        background = ui.box(c.lime, c.dark)
                        setPadding(ui.dp(5), 0, ui.dp(5), 0)
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
                }
            }
            val stickerRow = ui.row()
            panel.addView(stickerRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
            owned.filter { !it.magnet }.forEach {
                stickerRow.addView(tile(it), LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)).apply { marginEnd = ui.dp(10) })
            }
            ui.text(panel, "자석", 16f, c.muted)
            val magnetRow = ui.row()
            panel.addView(magnetRow)
            catalog.regions.forEach { region ->
                val magnet = region.magnet
                if (profile.owns(magnet.id)) magnetRow.addView(tile(magnet), LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)).apply { marginEnd = ui.dp(10) })
                else magnetRow.addView(View(host).apply {
                    background = ui.box(c.fog)
                    contentDescription = "${magnet.name} · 아직"
                }, LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)).apply { marginEnd = ui.dp(10) })
            }
            ui.text(panel, "시·도 하나를 다 채우면 그 지역 자석이 생겨요", 14f, c.muted)
            panel.addView(View(host), LinearLayout.LayoutParams(1, ui.dp(if (ui.wide) 120 else 20)))
            ui.actions(panel, listOf(
                Act("공유") { host.message("폰으로 보내기는 준비 중이에요") },
                Act("붙이기", true, owned.isNotEmpty()) { startPlacement(null) }))
        })
    }

    private fun magnetArt(item: BoardItem): View = ui.label(item.name.substringBefore(' '), 17f, c.onViolet, bold = true).apply {
        gravity = Gravity.CENTER
        background = ui.box(c.violet, c.white, shadow = true)
    }

    private fun startPlacement(preferred: String?) {
        val item = catalog.boardItems.firstOrNull { it.id == preferred && profile.owns(it.id) }
            ?: catalog.boardItems.firstOrNull { profile.owns(it.id) } ?: return
        itemCategory = if (item.magnet) 1 else 0
        itemId = item.id
        pendingCell = null
        host.go(Page.PLACE)
    }

    private fun placement(body: LinearLayout) {
        val owned = catalog.boardItems.filter { profile.owns(it.id) }
        val selected = owned.firstOrNull { it.id == itemId }
        val stored = profile.placements
        ui.split(body, { left -> mapCard(left, true) }, { right ->
            ui.strip(right, listOf("스티커" to { selectCategory(false) }, "자석" to { selectCategory(true) }),
                if (itemCategory == 0) "스티커" else "자석")
            val filtered = owned.filter { it.magnet == (itemCategory == 1) }
            if (filtered.isEmpty()) ui.text(right, "보유한 아이템이 없어요.", color = c.muted)
            ui.grid(right, filtered.map { item ->
                ui.column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    isClickable = true
                    setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                    if (item.id == selected?.id) background = ui.box(c.paper, c.lime)
                    setOnClickListener {
                        itemId = item.id
                        pendingCell = null
                        host.render()
                    }
                    addView(if (item.magnet) magnetArt(item) else stickerArt(item), LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
                    val count = if (stored.containsKey(item.id)) "붙임" else "×1"
                    addView(ui.label("${item.name.substringBefore(' ')} $count", 14f).apply {
                        gravity = Gravity.CENTER
                        contentDescription = item.name
                    }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(4) })
                }
            }, if (ui.wide) 4 else 4, gap = 8)
            ui.text(right, if (pendingCell == null) "지도에서 열린 칸을 골라요." else "선택한 칸에 ${selected?.name}을 붙일 수 있어요.", 15f, c.muted)
            right.addView(View(host), LinearLayout.LayoutParams(1, ui.dp(if (ui.wide) 200 else 10)))
            ui.button(right, "여기 붙이기", true, pendingCell != null && selected != null) {
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
        val panel = ui.card(body, c.fog)
        val owned = catalog.boardItems.associateBy(BoardItem::id)
        val placed = profile.placements.entries.mapNotNull { (item, block) -> owned[item]?.let { block to it } }.toMap()
        val pick: ((String) -> Unit)? = if (!editing) null else fun(block: String) {
            if (fogo.parked && itemId != null) {
                pendingCell = block
                host.render()
            }
        }
        // Placement zooms in around the spot just reached (kept still while picking); the board shows it all.
        val zoom = if (!editing) null else spot.point.block()
        val board = KoreaBoard(host, ui, host.art, profile.blocks, placed,
            pendingCell.takeIf { editing }, owned[itemId].takeIf { editing }, pick, zoom,
            key = if (editing) "place:${spot.spotId}" else "board").apply {
            contentDescription = if (editing) null else "내 지도판"
        }
        panel.addView(board, LinearLayout.LayoutParams(-1, screenPart(if (ui.wide) .66f else .5f)))
    }

    /** Height as a share of the screen, so maps fill the page whatever the UI scale. */
    private fun screenPart(fraction: Float) = (host.resources.displayMetrics.heightPixels * fraction).toInt()

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

/**
 * Figma shows tickets (arrival, new car) as a popup over a dimmed screen: dim the page and centre a
 * column about two thirds wide. Returns the column to build into.
 */
internal fun popup(host: MainActivity, body: LinearLayout): LinearLayout {
    val ui = host.ui
    val c = ui.colors
    body.setBackgroundColor((if (c.night) 0xff000000.toInt() else c.navy) and 0x00ffffff or (0x8c shl 24))
    body.gravity = Gravity.CENTER_VERTICAL
    if (!ui.wide) return body
    val line = ui.row()
    body.addView(line, LinearLayout.LayoutParams(-1, -2))
    val sheet = ui.column()
    line.addView(View(host), LinearLayout.LayoutParams(0, 1, 0.12f))
    line.addView(sheet, LinearLayout.LayoutParams(0, -2, 0.76f))
    line.addView(View(host), LinearLayout.LayoutParams(0, 1, 0.12f))
    return sheet
}

internal data class TicketSpec(
    val pass: String, val tag: String, val accent: Int, val stubColor: Int,
    val stamp: String, val stampSub: String, val code: String,
)

internal class TicketParts(val root: View, val body: LinearLayout, val stamp: View)

/**
 * Boarding-pass card from the Figma ticket screens: navy header band and content on the left (top
 * when narrow), a dashed perforation, and a coloured stub with a tilted stamp and a pixel barcode.
 */
internal fun ticketPass(host: MainActivity, parent: LinearLayout, spec: TicketSpec): TicketParts {
    val ui = host.ui
    val c = ui.colors
    val root = (if (ui.wide) ui.row().apply { gravity = Gravity.TOP } else ui.column()).apply {
        background = ui.box(c.paper, shadow = true)
        setPadding(ui.dp(3), ui.dp(3), ui.dp(7), ui.dp(7))
    }
    parent.addView(root, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(16) })

    val main = ui.column()
    root.addView(main, if (ui.wide) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
    val header = ui.row().apply {
        setBackgroundColor(c.navy)
        setPadding(ui.dp(20), ui.dp(14), ui.dp(20), ui.dp(14))
    }
    main.addView(header, LinearLayout.LayoutParams(-1, -2))
    header.addView(ui.label(spec.pass, 19f, c.white, pixel = true), LinearLayout.LayoutParams(0, -2, 1f))
    header.addView(ui.label(spec.tag, 17f, c.lime, bold = true))
    main.addView(View(host).apply { setBackgroundColor(spec.accent) }, LinearLayout.LayoutParams(-1, ui.dp(5)))
    val body = ui.column().apply { setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(14)) }
    main.addView(body, LinearLayout.LayoutParams(-1, -2))

    val seam = DashedLine(host, c.border, ui.dp(10).toFloat(), ui.dp(7).toFloat())
    root.addView(seam, if (ui.wide) LinearLayout.LayoutParams(ui.dp(3), -1) else LinearLayout.LayoutParams(-1, ui.dp(3)))

    val stub = ui.column().apply {
        gravity = Gravity.CENTER
        setBackgroundColor(spec.stubColor)
        setPadding(ui.dp(18), ui.dp(20), ui.dp(18), ui.dp(18))
    }
    root.addView(stub, if (ui.wide) LinearLayout.LayoutParams(ui.dp(230), -1) else LinearLayout.LayoutParams(-1, -2))
    val content = if (ui.wide) stub else ui.row().apply { gravity = Gravity.CENTER }.also { stub.addView(it) }
    val stampBox = ui.column().apply {
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply { setStroke(ui.dp(4), c.dark) }
        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        rotation = -8f
    }
    stampBox.addView(ui.label(spec.stamp, 20f, c.dark, pixel = true).apply { gravity = Gravity.CENTER })
    stampBox.addView(ui.label(spec.stampSub, 17f, c.dark, bold = true).apply { gravity = Gravity.CENTER })
    content.addView(stampBox, LinearLayout.LayoutParams(-2, -2).apply {
        if (ui.wide) bottomMargin = ui.dp(22) else marginEnd = ui.dp(28)
    })
    val code = ui.column().apply { gravity = Gravity.CENTER }
    content.addView(code)
    code.addView(ui.label("STAMP", 11f, c.dark, pixel = true).apply { gravity = Gravity.CENTER })
    code.addView(PixelIconView(host, BARCODE_ROWS, c.dark), LinearLayout.LayoutParams(ui.dp(150), ui.dp(44)).apply {
        topMargin = ui.dp(6); bottomMargin = ui.dp(6)
    })
    code.addView(ui.label(spec.code, 9f, c.dark, pixel = true).apply { gravity = Gravity.CENTER })
    return TicketParts(root, body, stampBox)
}

/** DATE / GATE / TRIP / NO. style field strip between dashed rules. */
internal fun ticketFields(host: MainActivity, parent: LinearLayout, fields: List<Pair<String, String>>) {
    val ui = host.ui
    val c = ui.colors
    parent.addView(DashedLine(host, c.muted, ui.dp(8).toFloat(), ui.dp(6).toFloat()), LinearLayout.LayoutParams(-1, ui.dp(2)))
    fields.chunked(if (ui.wide) 4 else 2).forEach { line ->
        val row = ui.row().apply { gravity = Gravity.TOP; setPadding(0, ui.dp(12), 0, ui.dp(12)) }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        line.forEach { (label, value) ->
            val cell = ui.column()
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            cell.addView(ui.label(label, 11f, c.muted, pixel = true))
            cell.addView(ui.label(value, 20f, c.ink, bold = true))
        }
    }
    parent.addView(DashedLine(host, c.muted, ui.dp(8).toFloat(), ui.dp(6).toFloat()),
        LinearLayout.LayoutParams(-1, ui.dp(2)).apply { bottomMargin = ui.dp(12) })
}

private val BARCODE_ROWS = listOf("#.##.#.###.#.##.#.##.#.#", "#.##.#.###.#.##.#.##.#.#", "#.##.#.###.#.##.#.##.#.#")
