package com.ssafy.fogo

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

class AtlasProfileTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "atlas_test_${UUID.randomUUID()}"
    private val cities = loadCities(context)
    private val catalog = AtlasCatalog(context, cities)

    @After fun clean() { context.deleteSharedPreferences(key) }

    @Test fun thirdDistinctSpotGrantsOneMagnetAndRepeatsGrantNothing() {
        val profile = Profile(context, key)
        val trip = TripEngine(profile, cities)
        val region = catalog.regions.first { it.id == "GANGWON" }
        val now = System.currentTimeMillis()
        region.spots.forEachIndexed { index, city ->
            val time = now + index * 12_000
            trip.gear(Gear.DRIVE, time)
            assertNull(trip.location(Fix(city.point, time + 1000), time + 1000))
            val arrival = trip.gear(Gear.PARK, time + 1200)!!
            assertEquals(index == 2, arrival.magnet)
            assertEquals(index != 1, arrival.sticker)
        }
        assertTrue(profile.owns(region.magnet.id))
        assertEquals(3, profile.materialCount("MAT_GANGWON"))
        val first = region.spots.first()
        trip.gear(Gear.DRIVE, now + 38_000)
        trip.location(Fix(first.point, now + 39_000), now + 39_000)
        assertNull(trip.gear(Gear.PARK, now + 39_100))
        assertEquals(3, profile.materialCount("MAT_GANGWON"))
        assertTrue(profile.place(region.magnet.id, first.point.block().id))
        assertFalse(profile.place(first.stickerId, first.point.block().id))
        assertFalse(profile.place(first.stickerId, "99999,99999"))
    }

    @Test fun craftingEquipmentAndCarHistorySurviveRestart() {
        val profile = Profile(context, key)
        assertFalse(profile.onboarded)
        assertTrue(profile.completeOnboarding("안개 구름"))
        val lime = catalog.costumes.first { it.id == "CST_LIME_WRAP" }
        val seoul = catalog.regions.first { it.id == "SEOUL" }.spots
        assertFalse(profile.craft(lime))
        seoul.take(2).forEach { city ->
            assertTrue(profile.arrive(Arrival(city, true, true, "2026-11-01", TripTags()), city.point.block()))
        }
        assertTrue(profile.craft(lime))
        assertEquals(0, profile.materialCount("MAT_SEOUL"))
        assertEquals(8, profile.materialCount("MAT_COMMON"))
        assertFalse(profile.craft(lime))
        assertTrue(profile.equip(lime, "wrap"))
        assertTrue(profile.changeCar("그랜저"))
        val reloaded = Profile(context, key)
        assertTrue(reloaded.onboarded)
        assertEquals(lime.id, reloaded.equipped("wrap"))
        assertEquals("CST_PIXEL_BEANIE", reloaded.equipped("hat"))
        assertEquals(listOf("그랜저", "아반떼"), reloaded.garage)
    }

    @Test fun previousMonthClosesOnceAndReadsLegacyBoardWithoutInventingDates() {
        val city = cities.first()
        val block = city.point.block()
        val legacy = JSONObject()
            .put("car", "그랜저").put("costume", "픽셀 비니")
            .put("blocks", JSONObject().put(block.id, true))
            .put("stickers", JSONObject().put(city.stickerId, true))
            .put("board", JSONObject().put(city.stickerId, block.id))
            .put("spots", JSONObject().put(city.spotId, JSONObject().put("day", "2026-10-03").put("tags", "밤")))
        context.getSharedPreferences(key, 0).edit().putString("record", legacy.toString()).commit()
        val profile = Profile(context, key)
        assertTrue(profile.onboarded)
        assertEquals(block.id, profile.stickerBlock(city))
        assertEquals("CST_PIXEL_BEANIE", profile.equipped("hat"))
        val today = LocalDate.of(2026, 11, 1)
        assertTrue(profile.closePreviousMonth(cities, today))
        assertTrue(profile.closePreviousMonth(cities, today.plusDays(2)))
        val actual = profile.books(today).single { !it.example }
        assertEquals(1, actual.newRegions)
        assertEquals(1, actual.visits)
        assertEquals("밤 · ${city.spot}", actual.memory)
        assertNull(actual.stickers)
        assertNull(actual.boardAfter)
        assertNull(actual.commute)
        assertEquals("그랜저", Profile(context, key).car)
    }

    @Test fun monthlyMemoryNeverPresentsTheNoTagDefaultAsWeather() {
        val (tagged, untagged) = cities.take(2)
        val spots = JSONObject()
            .put(untagged.spotId, JSONObject().put("day", "2026-10-02").put("tags", TripTags().summary()))
            .put(tagged.spotId, JSONObject().put("day", "2026-10-03").put("tags", "밤"))
        context.getSharedPreferences(key, 0).edit()
            .putString("record", JSONObject().put("car", "그랜저").put("spots", spots).toString()).commit()
        val profile = Profile(context, key)
        val today = LocalDate.of(2026, 11, 1)
        assertTrue(profile.closePreviousMonth(cities, today))
        assertEquals("밤 · ${tagged.spot}", profile.books(today).single { !it.example }.memory)

        val onlyUntagged = JSONObject().put(untagged.spotId, JSONObject().put("day", "2026-10-02").put("tags", TripTags().summary()))
        context.getSharedPreferences(key, 0).edit()
            .putString("record", JSONObject().put("car", "그랜저").put("spots", onlyUntagged).toString()).commit()
        val plain = Profile(context, key)
        assertTrue(plain.closePreviousMonth(cities, today))
        assertEquals(untagged.spot, plain.books(today).single { !it.example }.memory)
    }

    @Test fun datedBoardUsesMonthBoundariesAndCountsAStickerOnce() {
        val city = cities.first()
        val profile = Profile(context, key)
        val start = LocalDate.of(2026, 10, 1)
        val previous = Block(1, 1)
        val opened = city.point.block()
        val moved = Block(opened.x + 1, opened.y)
        val nextMonth = Block(2, 1)
        assertTrue(profile.addBlock(previous, start.minusDays(1)))
        assertTrue(profile.arrive(Arrival(city, true, true, start.toString(), TripTags()), opened))
        assertTrue(profile.addBlock(moved, start.plusDays(1)))
        assertTrue(profile.place(city.stickerId, opened.id, start.plusDays(2)))
        assertTrue(profile.place(city.stickerId, moved.id, start.plusDays(3)))
        assertTrue(profile.addBlock(nextMonth, start.plusMonths(1)))
        assertTrue(profile.closePreviousMonth(cities, start.plusMonths(1)))
        val book = profile.books(start.plusMonths(1)).single { !it.example }
        assertEquals(1, book.stickers)
        assertEquals(1, book.boardBefore)
        assertEquals(3, book.boardAfter)
    }
}
