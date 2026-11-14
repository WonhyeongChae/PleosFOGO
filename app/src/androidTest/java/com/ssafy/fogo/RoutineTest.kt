package com.ssafy.fogo

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** 친구, 차 생일, 출퇴근 루틴 records in [Profile]. */
class RoutineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "fogo_test_" + UUID.randomUUID()
    private val profile = Profile(context, key)
    private val day = LocalDate.of(2026, 11, 16)

    @After fun clearTestProfile() {
        context.deleteSharedPreferences(key)
    }

    @Test fun friendRequestBecomesAFriendOnlyWhenAccepted() {
        val friends = loadFriends(context)
        val newcomer = friends.first { !profile.isFriend(it.id) }
        assertTrue(friends.any { profile.isFriend(it.id) })
        assertFalse(profile.react(newcomer.id, "좋아요"))
        assertTrue(profile.requestFriend(newcomer.id))
        assertTrue(profile.requestedFriend(newcomer.id))
        assertFalse(profile.isFriend(newcomer.id))
        assertTrue(profile.acceptFriend(newcomer.id))
        assertTrue(profile.isFriend(newcomer.id))
        assertFalse(profile.requestedFriend(newcomer.id))
        assertTrue(profile.react(newcomer.id, "좋아요"))
        assertEquals("좋아요", Profile(context, key).reaction(newcomer.id))
        assertTrue(newcomer.blocks.isNotEmpty())
    }

    @Test fun birthdayOnlyForMilestonesPassedWhileFogoWatched() {
        assertNull(profile.noteOdometer(23_500.0, day))
        assertNull(profile.noteOdometer(29_999.0, day))
        val limited = profile.materialCount("MAT_LIMITED")
        assertEquals(30_000, profile.noteOdometer(30_002.5, day))
        assertNull(profile.noteOdometer(30_400.0, day))
        assertEquals(listOf(30_000), profile.birthdays())
        assertTrue(profile.owns(Profile.BIRTHDAY_STICKER))
        assertEquals(limited + 1, profile.materialCount("MAT_LIMITED"))
        assertEquals(40_000, profile.nextBirthday(30_400.0))
    }

    @Test fun fifthCommuteOfTheWeekPaysOnce() {
        val common = profile.materialCount("MAT_COMMON")
        repeat(Profile.COMMUTE_GOAL - 1) { assertFalse(profile.addCommute(day)) }
        assertTrue(profile.addCommute(day.plusDays(2)))
        assertFalse(profile.addCommute(day.plusDays(3)))
        assertEquals(Profile.COMMUTE_GOAL + 1, profile.commutes(day))
        assertEquals(common + Profile.COMMUTE_MATERIALS, profile.materialCount("MAT_COMMON"))
        assertTrue(profile.owns(Profile.COMMUTE_STICKER))
        assertTrue(profile.commuteDone)
        assertEquals(0, profile.commutes(day.plusDays(7)))
        assertTrue(profile.setPlace("home", Point(37.5, 127.0)))
        assertEquals(37.5, profile.place("home")!!.latitude, 1e-9)
    }
}
