package com.ssafy.fogo

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import java.time.YearMonth
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * A friend's car as the demo shows it. There is no server yet, so friends come from assets/friends.json:
 * their board is drawn from a few trips, and a request to a known code is "accepted" a moment later.
 */
data class Friend(
    val id: String,
    val name: String,
    val code: String,
    val car: String,
    val mascot: String,
    val equipped: Map<String, String>,
    val spots: List<String>,
    val blocks: Set<String>,
    val stickers: Map<String, String>,
    val reaction: String,
    val reactionAbout: String,
    val monthly: Map<String, Int>,
)

fun loadFriends(context: Context): List<Friend> {
    val rows = JSONArray(context.assets.open("friends.json").bufferedReader().use { it.readText() })
    return List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        fun points(array: JSONArray) = List(array.length()) { i ->
            array.getJSONArray(i).let { Point(it.getDouble(0), it.getDouble(1)) }
        }
        val trips = row.getJSONArray("trips").let { list -> List(list.length()) { points(list.getJSONArray(it)) } }
        val equipped = row.getJSONObject("equipped").let { e -> e.keys().asSequence().associateWith(e::getString) }
        val stickers = row.getJSONArray("stickers").let { list ->
            List(list.length()) { i ->
                val sticker = list.getJSONObject(i)
                val at = sticker.getJSONArray("at")
                Point(at.getDouble(0), at.getDouble(1)).block().id to sticker.getString("item")
            }.toMap()
        }
        val reaction = row.getJSONObject("reaction")
        val monthly = row.getJSONObject("monthly").let { m -> m.keys().asSequence().associateWith(m::getInt) }
        Friend(
            row.getString("id"), row.getString("name"), row.getString("code"), row.getString("car"),
            row.getString("mascot"), equipped,
            row.getJSONArray("spots").let { list -> List(list.length(), list::getString) },
            trips.flatMap(::routeBlocks).toSet() + stickers.keys, stickers,
            reaction.getString("sticker"), reaction.getString("about"), monthly,
        )
    }
}

/** 1 km blocks along a polyline, sampled every 400 m (as the car would have opened them). */
fun routeBlocks(route: List<Point>): List<String> = route.zipWithNext().flatMap { (from, to) ->
    val km = from.distanceTo(to) / 1000
    val steps = max(1, (km / .4).toInt())
    List(steps + 1) { step ->
        val f = step.toDouble() / steps
        Point(from.latitude + (to.latitude - from.latitude) * f, from.longitude + (to.longitude - from.longitude) * f).block().id
    }
}

object Qr {
    /** A QR code of [text] (UTF-8) as a square bitmap of [size] px. */
    fun bitmap(text: String, size: Int, dark: Int = 0xff0b1426.toInt(), light: Int = 0xffffffff.toInt()): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) dark else light }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}

/** 친구 tab (Figma A4·A5): my code, friends, reactions, the regions opened this month; and one friend's board. */
class FriendScreens(private val host: MainActivity, state: Bundle?) {
    private val ui get() = host.ui
    private val c get() = ui.colors
    private val profile get() = host.profile
    private val fogo get() = host.fogo
    private val catalog get() = host.catalog
    private var friendId = state?.getString("friend")

    fun save(state: Bundle) {
        state.putString("friend", friendId)
    }

    fun show(page: Page, body: LinearLayout) {
        val friend = fogo.friends.firstOrNull { it.id == friendId && profile.isFriend(it.id) }
        if (page == Page.FRIEND && friend != null) friend(body, friend) else list(body)
    }

    private fun list(body: LinearLayout) {
        val friends = fogo.friends.filter { profile.isFriend(it.id) }
        ui.split(body, { left ->
            val mine = ui.card(left, c.yellow, shadow = true)
            mine.addView(ui.label("MY CODE", 11f, c.dark, pixel = true))
            val line = ui.row()
            mine.addView(line, LinearLayout.LayoutParams(-1, -2))
            val words = ui.column()
            line.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            words.addView(ui.label("내 친구 코드", 20f, c.dark, bold = true))
            words.addView(ui.label(profile.friendCode.chunked(3).joinToString(" "), 40f, c.dark, pixel = true))
            words.addView(ui.label("친구가 이 코드를 입력하고, 상대가 자기 차에서 수락하면 연결돼요", 15f, c.dark))
            line.addView(ImageView(host).apply {
                setImageBitmap(Qr.bitmap("FOGO 친구 코드 ${profile.friendCode}", ui.dp(132)))
                contentDescription = "내 친구 코드 QR"
            }, LinearLayout.LayoutParams(ui.dp(132), ui.dp(132)).apply { marginStart = ui.dp(12) })
            ui.button(mine, "친구 추가", true, click = ::addFriend)
            fogo.friends.filter { profile.requestedFriend(it.id) }.forEach {
                ui.text(mine, "${it.name}에게 요청을 보냈어요 · 상대 차에서 수락을 기다려요", 15f, c.dark, bold = true)
            }

            val got = ui.card(left)
            ui.text(got, "받은 스티커 반응", 20f, bold = true)
            if (friends.isEmpty()) ui.text(got, "친구를 추가하면 지도판에 스티커로 반응을 남겨요", 15f, c.muted)
            friends.forEach { friend ->
                val row = ui.row().apply { setPadding(0, ui.dp(8), 0, ui.dp(4)) }
                got.addView(row, LinearLayout.LayoutParams(-1, -2))
                row.addView(ui.chip(friend.reaction, c.lime, 15f), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(10) })
                row.addView(ui.label("${friend.name} → ${friend.reactionAbout}", 16f), LinearLayout.LayoutParams(0, -2, 1f))
            }
            ui.text(got, "글 대신 스티커로만 반응해요", 14f, c.muted)
        }, { right ->
            val list = ui.card(right)
            ui.text(list, "친구 ${friends.size}명", 22f, bold = true)
            if (friends.isEmpty()) ui.text(list, "아직 친구가 없어요", 16f, c.muted)
            friends.forEach { friend -> list.addView(friendRow(friend), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) }) }

            val rank = ui.card(right)
            ui.text(rank, "이번 달 많이 열린 지역", 22f, bold = true)
            ui.text(rank, "친구들과 함께 연 칸을 지역끼리 겨뤄요 · 사람 순위는 없어요", 15f, c.muted)
            val totals = regionTotals(friends).entries.sortedByDescending { it.value }.take(5)
            val top = totals.firstOrNull()?.value?.coerceAtLeast(1) ?: 1
            totals.forEachIndexed { index, (region, count) ->
                val row = ui.row().apply { setPadding(0, ui.dp(8), 0, 0) }
                rank.addView(row, LinearLayout.LayoutParams(-1, -2))
                row.addView(ui.label("${index + 1}", 20f, if (index == 0) c.ink else c.muted, pixel = true),
                    LinearLayout.LayoutParams(ui.dp(34), -2))
                row.addView(ui.label(region, 19f, bold = true), LinearLayout.LayoutParams(ui.dp(70), -2))
                val bar = ui.column()
                row.addView(bar, LinearLayout.LayoutParams(0, -2, 1f))
                ui.progress(bar, count / top.toFloat())
                row.addView(ui.label("${count}칸", 16f, c.muted), LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(10) })
            }
            ui.text(rank, "시연 데이터 · 친구 기록은 예시예요", 13f, c.muted)
        })
    }

    private fun friendRow(friend: Friend): View {
        val row = ui.row().apply {
            background = ui.box(c.paper)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(16), ui.dp(10))
            isClickable = true
            contentDescription = "${friend.name} 지도판 보기"
            setOnClickListener {
                friendId = friend.id
                host.go(Page.FRIEND)
            }
        }
        row.addView(garage(friend).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(ui.dp(150), ui.dp(116)))
        val words = ui.column().apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(14) })
        words.addView(ui.label(friend.name, 22f, bold = true))
        words.addView(ui.label("${friend.car} · ${mascotName(friend)}", 16f, c.muted))
        val shared = sharedPlaces(friend)
        words.addView(ui.label(if (shared.isEmpty()) "같이 가 본 곳 없음" else "같이 가 본 곳 ${shared.size}곳", 16f, bold = true))
        row.addView(ui.label("→", 26f, c.muted, bold = true))
        return row
    }

    private fun friend(body: LinearLayout, friend: Friend) {
        ui.split(body, { left ->
            val card = ui.card(left)
            ui.text(card, "${friend.name}의 지도판", 22f, bold = true)
            val placed = friend.stickers.mapNotNull { (block, id) -> catalog.boardItems.firstOrNull { it.id == id }?.let { block to it } }.toMap()
            card.addView(KoreaBoard(host, ui, host.art, friend.blocks, placed, key = "friend_${friend.id}", reveal = false).apply {
                contentDescription = "${friend.name}의 지도판"
            }, LinearLayout.LayoutParams(-1, ui.dp(if (ui.wide) 560 else 420)))
            ui.text(card, "친구에게는 지도판과 같이 가 본 곳만 보여요 · 동승·날씨 같은 여행 맥락은 나누지 않아요", 14f, c.muted)
        }, { right ->
            val car = ui.card(right)
            car.addView(garage(friend), LinearLayout.LayoutParams(-1, ui.dp(if (ui.wide) 300 else 240)))
            ui.text(car, friend.name, 30f, bold = true)
            ui.text(car, "${friend.car} · ${mascotName(friend)} · 스티커·자석 ${friend.stickers.size}개", 17f, c.muted)

            val shared = sharedPlaces(friend)
            val together = ui.card(right)
            ui.text(together, if (shared.isEmpty()) "같이 가 본 곳" else "같이 가 본 곳 ${shared.size}곳", 20f, bold = true)
            if (shared.isEmpty()) ui.text(together, "아직 없어요 · ${friendSpotNames(friend).firstOrNull() ?: "명소"}에 가면 첫 공통 장소가 돼요", 16f, c.muted)
            else ui.flow(together, shared.map { ui.chip(it, c.lime, 16f) },
                (host.resources.displayMetrics.widthPixels * if (ui.wide) .3f else .8f).toInt())

            val react = ui.card(right)
            ui.text(react, "스티커로 반응하기", 20f, bold = true)
            val sent = profile.reaction(friend.id)
            // Wrapping buttons, not a strip: three reactions do not fit one row of the narrow column.
            ui.flow(react, REACTIONS.map { label ->
                ui.action(label, selected = label == sent) {
                    if (fogo.parked && profile.react(friend.id, label)) {
                        host.message("${friend.name}에게 '$label' 스티커를 보냈어요")
                        host.render()
                    }
                }
            }, (host.resources.displayMetrics.widthPixels * if (ui.wide) .3f else .8f).toInt())
            ui.text(react, if (sent == null) "글 없이 스티커 하나로 반응해요" else "보낸 반응 · $sent", 15f, c.muted)
        })
    }

    private fun addFriend() {
        val field = EditText(host).apply {
            hint = "6자리 친구 코드"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 26f * ui.scale
            setTextColor(c.ink)
            setHintTextColor(c.muted)
            gravity = Gravity.CENTER
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        val demo = fogo.friends.firstOrNull { !profile.isFriend(it.id) && !profile.requestedFriend(it.id) }
        host.showSheet("ADD FRIEND", "친구 추가", "친구 차에 보이는 6자리 코드를 입력하세요." +
            (demo?.let { " 시연 코드 · ${it.code.chunked(3).joinToString(" ")}" } ?: ""), field,
            listOf(Act("취소") {}, Act("요청 보내기", true, weight = 1.4f) {
                val code = field.text.toString().filter(Char::isDigit)
                val target = fogo.friends.firstOrNull { it.code == code }
                when {
                    code == profile.friendCode -> host.message("내 코드예요")
                    target == null -> host.message("그 코드의 친구를 찾지 못했어요")
                    profile.isFriend(target.id) -> host.message("이미 친구예요")
                    profile.requestedFriend(target.id) -> host.message("수락을 기다리는 중이에요")
                    fogo.requestFriend(target) -> { host.message("${target.name}에게 친구 요청을 보냈어요"); host.render() }
                    else -> host.message("요청을 저장하지 못했어요")
                }
            }))
    }

    private fun garage(friend: Friend): GarageArtwork {
        fun color(slot: String) = friend.equipped[slot]?.let { id -> catalog.costumes.firstOrNull { it.id == id }?.color }
        return GarageArtwork(host, host.art, c, friend.mascot, friend.car == "그랜저",
            color("wrap"), color("hat"), color("clothes"), color("bg")).apply { background = ui.box(c.paper) }
    }

    private fun mascotName(friend: Friend) = catalog.mascots.firstOrNull { it.id == friend.mascot }?.name ?: "안개 구름"

    private fun friendSpotNames(friend: Friend) = friend.spots.mapNotNull { id -> fogo.cities.firstOrNull { it.spotId == id }?.spot }

    /** 시·군·구 both of us parked at (by 명소 visits), so the friend sees places, not blocks. */
    private fun sharedPlaces(friend: Friend) = fogo.cities
        .filter { it.spotId in friend.spots && profile.visited(it) }
        .map { it.city.removeSuffix("특별시").removeSuffix("광역시") }
        .distinct()

    /** Blocks opened this month by region: the friends' (demo) counts plus mine, each block given to the nearest 시·도 of the demo. */
    private fun regionTotals(friends: List<Friend>): Map<String, Int> {
        val totals = mutableMapOf<String, Int>()
        friends.forEach { friend -> friend.monthly.forEach { (region, count) -> totals[region] = (totals[region] ?: 0) + count } }
        val month = YearMonth.now().toString()
        val names = catalog.regions.associate { it.id to it.name }
        profile.blockDays().filter { it.value.startsWith(month) }.keys.forEach { id ->
            val point = blockCenter(id) ?: return@forEach
            val city = fogo.cities.minByOrNull { it.point.distanceTo(point) } ?: return@forEach
            if (city.point.distanceTo(point) > 120_000) return@forEach
            val region = names[city.provinceId] ?: return@forEach
            totals[region] = (totals[region] ?: 0) + 1
        }
        return totals
    }

    private fun blockCenter(id: String): Point? {
        val parts = id.split(',')
        val x = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val y = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val lat = 36.0 + (y + .5) / 111.195
        val lon = 127.0 + (x + .5) / (111.195 * cos(Math.toRadians(36.0)))
        return Point(lat, lon).takeIf { hypot(x.toDouble(), y.toDouble()) < 2000 }
    }

    companion object {
        val REACTIONS = listOf("좋아요", "가보고 싶어요", "나도 가 봤어요")
    }
}
