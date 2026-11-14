package com.ssafy.fogo

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

data class BoardItem(val id: String, val name: String, val color: Int, val magnet: Boolean)
data class Costume(val id: String, val name: String, val slot: String, val color: Int, val cost: Map<String, Int>)
data class Mascot(val id: String, val name: String, val condition: String)
data class AtlasRegion(val id: String, val name: String, val magnet: BoardItem, val spots: List<City>) {
    fun opened(profile: Profile) = spots.count(profile::visited)
}

class AtlasCatalog(context: Context, cities: List<City>) {
    private val config = JSONObject(context.assets.open("atlas.json").bufferedReader().use { it.readText() })
    val materials = config.getJSONObject("materials").let { names ->
        names.keys().asSequence().associateWith { names.getString(it) }
    }
    val regions = config.getJSONArray("regions").let { rows ->
        List(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            val id = row.getString("id")
            val name = row.getString("name")
            val spots = cities.filter { it.provinceId == id }
            AtlasRegion(id, name, BoardItem("MAG_$id", "$name 자석", spots.first().color, true), spots)
        }
    }
    val costumes = config.getJSONArray("costumes").let { rows ->
        List(rows.length()) { i ->
            val row = rows.getJSONObject(i)
            val cost = row.getJSONObject("cost")
            Costume(row.getString("id"), row.getString("name"), row.getString("slot"),
                Color.parseColor(row.getString("color")), cost.keys().asSequence().associateWith { cost.getInt(it) })
        }
    }
    val mascots = listOf(
        Mascot("cloud", "안개 구름", ""),
        Mascot("duck", "오리", "첫 명소에 도착하면 열려요"),
        Mascot("dog", "강아지", "첫 출퇴근 루틴을 완료하면 열려요"),
        Mascot("cat", "고양이", "첫 시·도 자석을 모으면 열려요"),
        Mascot("capybara", "카피바라", "세 시·도에 도착하면 열려요"),
    )

    val boardItems = cities.distinctBy(City::stickerId).map {
        BoardItem(it.stickerId, "${it.region} 스티커", it.color, false)
    } + listOf(
        BoardItem(Profile.COMMUTE_STICKER, "출퇴근 스티커", Color.parseColor("#7C9CFF"), false),
        BoardItem(Profile.BIRTHDAY_STICKER, "차 생일 스티커", Color.parseColor("#FF8FB1"), false),
    ) + regions.map(AtlasRegion::magnet)

    fun mascotUnlocked(mascot: Mascot, profile: Profile) = when (mascot.id) {
        "duck" -> regions.any { it.opened(profile) > 0 }
        "dog" -> profile.commuteDone
        "cat" -> regions.any { profile.owns(it.magnet.id) }
        "capybara" -> regions.all { it.opened(profile) > 0 }
        else -> true
    }
}
