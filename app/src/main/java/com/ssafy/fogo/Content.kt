package com.ssafy.fogo

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import kotlin.math.*

data class Point(val latitude: Double, val longitude: Double) {
    fun valid() = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        (latitude != 0.0 || longitude != 0.0)

    fun distanceTo(other: Point): Double {
        val lat = Math.toRadians(latitude)
        val otherLat = Math.toRadians(other.latitude)
        val deltaLon = Math.toRadians(other.longitude - longitude)
        val half = sin((otherLat - lat) / 2).pow(2) +
            cos(lat) * cos(otherLat) * sin(deltaLon / 2).pow(2)
        return 12_742_000 * asin(sqrt(half.coerceIn(0.0, 1.0)))
    }

    fun block(): Block {
        // Fixed local projection for the Korean demo region, independent of travel direction.
        val x = (longitude - 127.0) * 111.195 * cos(Math.toRadians(36.0))
        val y = (latitude - 36.0) * 111.195
        return Block(floor(x).toInt(), floor(y).toInt())
    }
}

data class Block(val x: Int, val y: Int) {
    val id get() = "$x,$y"
}

data class City(
    val cityId: String,
    val city: String,
    val regionId: String,
    val region: String,
    val spotId: String,
    val spot: String,
    val point: Point,
    val radius: Int,
    val color: Int,
    val colorName: String,
    val stickerId: String,
    val materialId: String,
    val material: String,
    val speech: String,
    val audio: String,
    val nearby: List<String>,
    val description: String,
    val rain: Boolean,
    val night: Boolean,
    val companion: Boolean,
    val route: List<Point>,
) {
    val provinceId get() = materialId.removePrefix("MAT_")
    val magnetId get() = "MAG_$provinceId"
}

fun loadCities(context: Context): List<City> {
    val rows = JSONArray(context.assets.open("cities.json").bufferedReader().use { it.readText() })
    return List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        val route = row.getJSONArray("route")
        val nearby = row.getJSONArray("nearby")
        City(
            row.getString("cityId"), row.getString("city"), row.getString("regionId"),
            row.getString("region"), row.getString("spotId"), row.getString("spot"),
            Point(row.getDouble("latitude"), row.getDouble("longitude")), row.getInt("radiusM"),
            Color.parseColor(row.getString("color")), row.getString("colorName"),
            row.getString("stickerId"), row.getString("materialId"), row.getString("material"),
            row.getString("speech"), row.getString("audio"),
            List(nearby.length()) { nearby.getString(it) }, row.getString("description"),
            row.getString("weather") == "rain", row.getBoolean("night"), row.getBoolean("companion"),
            List(route.length()) { offset ->
                val pair = route.getJSONArray(offset)
                Point(pair.getDouble(0), pair.getDouble(1))
            },
        )
    }
}

data class Destination(val name: String, val point: Point)
data class Fix(val point: Point, val time: Long)
data class TripTags(val rain: Boolean = false, val night: Boolean = false, val companion: Boolean = false) {
    operator fun plus(other: TripTags) = TripTags(rain || other.rain, night || other.night, companion || other.companion)
    fun summary() = listOfNotNull("비 오는 날".takeIf { rain }, "밤".takeIf { night }, "둘이서".takeIf { companion })
        .joinToString(" · ").ifBlank { "맑은 날" }
}

enum class Gear { UNKNOWN, PARK, DRIVE, OTHER }

data class Arrival(val city: City, val sticker: Boolean, val material: Boolean, val day: String, val tags: TripTags, val magnet: Boolean = false)

/**
 * This word with the Korean particle its last syllable takes: [consonant] after a final consonant (받침),
 * [vowel] otherwise, e.g. "안개 구름".particle("과", "와") → "안개 구름과", "평창 스티커".particle("을", "를") → "평창 스티커를".
 * "으로"/"로" also takes "로" after ㄹ. A word that does not end in Hangul gets [vowel].
 */
fun String.particle(consonant: String, vowel: String): String {
    val last = lastOrNull()?.takeIf { it in '\uAC00'..'\uD7A3' } ?: return this + vowel
    val jong = (last - '\uAC00') % 28
    return this + if (jong == 0 || (consonant == "으로" && jong == 8)) vowel else consonant
}
