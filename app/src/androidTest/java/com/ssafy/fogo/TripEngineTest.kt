package com.ssafy.fogo

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TripEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cities = loadCities(context)
    private val key = "fogo_test_" + UUID.randomUUID()
    private val profile = Profile(context, key)
    private val engine = TripEngine(profile, cities)
    private val now = 1_000_000L

    @After fun clearTestProfile() {
        context.deleteSharedPreferences(key)
    }

    @Test fun rejectsOutOfRangeStaleAndDrivingFixes() {
        val city = cities.first()
        engine.gear(Gear.PARK, now)
        assertNull(engine.location(Fix(city.route.first(), now), now))
        assertNull(engine.location(Fix(city.point, now - 11_000), now))
        engine.gear(Gear.DRIVE, now)
        assertNull(engine.location(Fix(city.point, now), now))
        assertNull(engine.gear(Gear.PARK, now + 11_000))
        assertFalse(profile.visited(city))
        assertEquals(0, profile.materialCount(city))

        val result = engine.location(Fix(city.point, now + 12_000), now + 12_000)
        assertNotNull(result)
        assertTrue(result!!.sticker)
        assertEquals(1, profile.materialCount(city))
    }

    @Test fun passengerWhoGotInBeforeDepartureMakesTheTripTwoOfUs() {
        val city = cities.first()
        engine.gear(Gear.PARK, now)
        engine.tags(TripTags(companion = true), now)
        engine.tags(TripTags(), now + 1_000)
        engine.gear(Gear.DRIVE, now + 2_000)
        engine.location(Fix(city.point, now + 3_000), now + 3_000)
        val arrival = engine.gear(Gear.PARK, now + 4_000)
        assertNotNull(arrival)
        assertTrue(arrival!!.tags.companion)
    }

    @Test fun rearDoorBeforeDepartureMakesTheTripWithFamily() {
        val city = cities.first()
        engine.gear(Gear.PARK, now)
        engine.tags(TripTags(companion = true), now)
        engine.tags(TripTags(companion = true, family = true), now + 500)
        engine.tags(TripTags(), now + 1_000)
        engine.gear(Gear.DRIVE, now + 2_000)
        engine.location(Fix(city.point, now + 3_000), now + 3_000)
        val arrival = engine.gear(Gear.PARK, now + 4_000)
        assertNotNull(arrival)
        assertTrue(arrival!!.tags.family)
        assertEquals("가족과", arrival.tags.summary())
    }

    @Test fun doorOpenedWhileDrivingIsNotAPassenger() {
        val city = cities.last()
        engine.gear(Gear.PARK, now)
        engine.gear(Gear.DRIVE, now + 1_000)
        engine.tags(TripTags(companion = true), now + 2_000)
        engine.tags(TripTags(), now + 3_000)
        engine.location(Fix(city.point, now + 4_000), now + 4_000)
        val arrival = engine.gear(Gear.PARK, now + 5_000)
        assertNotNull(arrival)
        assertFalse(arrival!!.tags.together)
    }

    @Test fun duplicatesAndRestartKeepOneRewardAndTheSameBoard() {
        val city = cities.last()
        val location = Fix(city.point, now)
        engine.gear(Gear.DRIVE, now)
        engine.location(location, now)
        assertNotNull(engine.gear(Gear.PARK, now))
        assertNull(engine.location(location, now))
        assertTrue(profile.place(city, city.point.block().id))
        assertFalse(profile.place(city, "unopened"))
        assertTrue(profile.changeCar("그랜저"))

        val restored = Profile(context, key)
        val restarted = TripEngine(restored, cities)
        restarted.gear(Gear.PARK, now)
        assertNull(restarted.location(location, now))
        assertEquals(1, restored.materialCount(city))
        assertEquals(city.point.block().id, restored.stickerBlock(city))
        assertEquals("그랜저", restored.car)
    }
}
