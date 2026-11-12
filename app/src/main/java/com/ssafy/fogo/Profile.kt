package com.ssafy.fogo

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

data class TicketBook(
    val month: YearMonth,
    val visits: Int,
    val newRegions: Int,
    val memory: String?,
    val stickers: Int?,
    val boardBefore: Int?,
    val boardAfter: Int?,
    val commute: Int? = null,
    val example: Boolean = false,
)

class Profile(context: Context, preferenceName: String = "fogo_profile") {
    private val storage = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val seed = JSONObject(context.assets.open("demo_profile.json").bufferedReader().use { it.readText() })
    private var record = storage.getString("record", null)?.let(::JSONObject) ?: JSONObject(seed.toString())
    private val hadRecord get() = storage.contains("record")

    private fun entries(key: String): JSONObject = record.optJSONObject(key) ?: JSONObject()

    val car get() = record.optString("car", seed.getString("car"))
    val mascot get() = record.optString("mascot", seed.getString("mascot"))
    val onboarded get() = if (record.has("onboarded")) record.optBoolean("onboarded") else hadRecord
    val garage get() = (listOf(car) + entries("garage").keys().asSequence().toList()).distinct()
    val blocks get() = entries("blocks").keys().asSequence().toSet()
    val placements get() = entries("board").let { it.keys().asSequence().associateWith(it::getString) }
    val ownedCount get() = entries("stickers").length() + entries("magnets").length()
    val costumeCount get() = entries("costumes").length() + (if (legacyHat()) 1 else 0)

    private fun legacyHat() = !record.has("costumes") && record.optString("costume", seed.getString("costume")) == "픽셀 비니"
    fun ownsCostume(id: String) = entries("costumes").has(id) || (id == "CST_PIXEL_BEANIE" && legacyHat())
    fun equipped(slot: String): String? = entries("equipped").optString(slot).ifBlank {
        if (slot == "hat" && !record.has("equipped") && legacyHat()) "CST_PIXEL_BEANIE" else return null
    }

    fun opened(city: City) = owns(city.stickerId)
    fun owns(item: String) = entries("stickers").has(item) || entries("magnets").has(item)
    fun visited(city: City) = entries("spots").has(city.spotId)
    fun materialCount(city: City) = materialCount(city.materialId)
    fun materialCount(id: String) = entries("materials").optInt(id, seed.optJSONObject("materials")?.optInt(id) ?: 0)
    fun stickerBlock(city: City) = placements[city.stickerId]
    fun visitDate(city: City): String? = entries("spots").optJSONObject(city.spotId)?.optString("day")
    fun visitTags(city: City): String? = entries("spots").optJSONObject(city.spotId)?.optString("tags")
    fun newBlocksToday(day: LocalDate = LocalDate.now()) =
        entries("blockDays").let { table -> table.keys().asSequence().count { table.optString(it) == day.toString() } }

    fun completeOnboarding(name: String) = update { put("onboarded", true); put("mascot", name) }
    fun selectMascot(name: String) = update { put("mascot", name) }

    fun addBlock(block: Block, day: LocalDate = LocalDate.now()): Boolean {
        if (entries("blocks").has(block.id)) return true
        return update { recordBlock(block, day.toString()) }
    }

    fun arrive(arrival: Arrival, block: Block): Boolean {
        val city = arrival.city
        if (visited(city)) return true
        return update {
            recordBlock(block, arrival.day)
            table("spots").put(city.spotId, JSONObject().put("day", arrival.day).put("tags", arrival.tags.summary())
                .put("newRegion", arrival.sticker))
            if (arrival.sticker) table("stickers").put(city.stickerId, true)
            if (arrival.material) table("materials").put(city.materialId, materialCount(city) + 1)
            if (arrival.magnet) table("magnets").put(city.magnetId, true)
        }
    }

    fun updateTags(arrival: Arrival): Boolean = update {
        table("spots").getJSONObject(arrival.city.spotId).put("tags", arrival.tags.summary())
    }

    fun place(city: City, block: String) = place(city.stickerId, block)

    fun place(item: String, block: String, day: LocalDate = LocalDate.now()): Boolean {
        val board = placements
        if (!owns(item) || block !in blocks || board.any { it.key != item && it.value == block }) return false
        if (board[item] == block) return true
        return update {
            table("board").put(item, block)
            if (item.startsWith("STK_")) table("monthlyPlacements").table(YearMonth.from(day).toString()).put(item, true)
        }
    }

    fun canCraft(costume: Costume) = !ownsCostume(costume.id) && costume.cost.all { materialCount(it.key) >= it.value }

    fun craft(costume: Costume): Boolean {
        if (!canCraft(costume)) return false
        return update {
            val inventory = table("materials")
            costume.cost.forEach { (id, amount) -> inventory.put(id, materialCount(id) - amount) }
            table("costumes").put(costume.id, true)
        }
    }

    fun equip(costume: Costume?, slot: String): Boolean {
        if (costume != null && (costume.slot != slot || !ownsCostume(costume.id))) return false
        return update {
            val equipped = table("equipped")
            if (costume == null) equipped.remove(slot) else equipped.put(slot, costume.id)
        }
    }

    fun changeCar(name: String): Boolean {
        if (name == car) return true
        return update { table("garage").put(car, true); put("car", name) }
    }

    fun books(today: LocalDate = LocalDate.now()): List<TicketBook> {
        val reference = YearMonth.from(today)
        fun example(row: JSONObject, offset: Long) = TicketBook(
            reference.minusMonths(offset), row.optInt("visits", 6), row.optInt("regions"),
            row.optString("memory"), row.optInt("stickers"), row.optInt("before", 24),
            row.optInt("after", 48), row.optInt("commute", 18), true,
        )
        val samples = seed.optJSONArray("recapSamples")
        val examples = listOf(example(record.optJSONObject("recap") ?: seed.getJSONObject("recap"), 2)) +
            List(samples?.length() ?: 0) { index ->
                val row = checkNotNull(samples).getJSONObject(index)
                example(row, row.getLong("offset"))
            }
        val snapshots = entries("books")
        val actual = snapshots.keys().asSequence().map { key ->
            val row = snapshots.getJSONObject(key)
            TicketBook(YearMonth.parse(key), row.getInt("visits"), row.getInt("regions"),
                row.optString("memory").ifBlank { null }, row.optInt("stickers", -1).takeIf { it >= 0 },
                row.optInt("before", -1).takeIf { it >= 0 }, row.optInt("after", -1).takeIf { it >= 0 })
        }.toList()
        return (actual + examples.filter { demo -> actual.none { it.month == demo.month } })
            .sortedByDescending { it.month }
    }

    fun closePreviousMonth(cities: List<City>, today: LocalDate = LocalDate.now()): Boolean {
        val month = YearMonth.from(today).minusMonths(1)
        val key = month.toString()
        if (entries("books").has(key)) return true
        val visits = cities.filter { visitDate(it)?.startsWith(key) == true }
        val months = entries("monthlyPlacements")
        val placements = months.optJSONObject(key)?.length() ?: 0
        val datedStickers = months.keys().asSequence().flatMap { months.getJSONObject(it).keys().asSequence() }.toSet()
        val unknownDates = this.placements.keys.any { it.startsWith("STK_") && it !in datedStickers }
        val known = entries("blockDays")
        val days = known.keys().asSequence().map(known::getString).toList()
        if (visits.isEmpty() && placements == 0 && days.none { it.startsWith(key) }) return true
        val first = month.atDay(1).toString()
        val last = month.atEndOfMonth().toString()
        val memory = visits.maxByOrNull { visitTags(it)?.count { char -> char == '·' } ?: 0 }
        val summary = JSONObject().put("visits", visits.size)
            .put("regions", visits.groupBy(City::regionId).count { (_, matches) ->
                val firstDay = cities.filter { it.regionId == matches.first().regionId }.mapNotNull(::visitDate).minOrNull()
                firstDay?.startsWith(key) == true
            })
        if (placements > 0 || !unknownDates) summary.put("stickers", placements)
        if (memory != null) summary.put("memory", "${visitTags(memory)} · ${memory.spot}")
        if (days.isNotEmpty()) summary.put("before", days.count { it < first }).put("after", days.count { it <= last })
        return update { table("books").put(key, summary) }
    }

    fun reset(): Boolean {
        if (!storage.edit().remove("record").commit()) return false
        record = JSONObject(seed.toString())
        return true
    }

    private fun update(change: JSONObject.() -> Unit): Boolean {
        val copy = JSONObject(record.toString()).apply {
            if (!has("onboarded")) put("onboarded", onboarded)
            if (!has("mascot")) put("mascot", mascot)
            if (legacyHat()) {
                table("costumes").put("CST_PIXEL_BEANIE", true)
                table("equipped").put("hat", "CST_PIXEL_BEANIE")
            }
            change()
        }
        if (!storage.edit().putString("record", copy.toString()).commit()) return false
        record = copy
        return true
    }

    private fun JSONObject.recordBlock(block: Block, day: String) {
        val table = table("blocks")
        if (table.has(block.id)) return
        table.put(block.id, true)
        table("blockDays").put(block.id, day)
    }

    private fun JSONObject.table(key: String): JSONObject = optJSONObject(key)
        ?: JSONObject().also { put(key, it) }
}
