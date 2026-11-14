package com.ssafy.fogo

import java.time.LocalDate

class TripEngine(private val profile: Profile, private val cities: List<City>) {
    var gear = Gear.UNKNOWN
        private set
    var scene = TripTags()
        private set
    var arrival: Arrival? = null
        private set
    private var accumulated = TripTags()
    private var lastFix: Fix? = null
    private var arrivalTime = 0L
    /**
     * 둘이서: the passenger door opened while parked (not just after an arrival, when someone gets out) is a passenger
     * getting in. It counts for the next departure within [BOARDING_MS] and stays with the trip until the arrival.
     */
    private var boardedAt: Long? = null
    private var companionAboard = false
    var storageFailed = false
        private set

    fun resetTrip() {
        gear = Gear.UNKNOWN
        scene = TripTags()
        accumulated = TripTags()
        lastFix = null
        arrival = null
        arrivalTime = 0
        storageFailed = false
        boardedAt = null
        companionAboard = false
    }

    fun gear(value: Gear, now: Long): Arrival? {
        if (value == Gear.DRIVE && gear != Gear.DRIVE) {
            if (boardedAt?.let { now - it in 0..BOARDING_MS } == true) companionAboard = true
            boardedAt = null
            accumulated = scene.copy(companion = companionAboard)
            arrival = null
        }
        gear = value
        return detectArrival(now)
    }

    fun clearFix() { lastFix = null }

    fun location(fix: Fix, now: Long): Arrival? {
        if (!fix.point.valid() || !fresh(fix, now) || (lastFix?.time ?: 0) > fix.time) return null
        lastFix = fix
        if (gear == Gear.DRIVE && !profile.addBlock(fix.point.block())) storageFailed = true
        return detectArrival(now)
    }

    fun tags(tags: TripTags, now: Long) {
        val doorOpened = tags.companion && !scene.companion
        scene = tags
        // A door opened on the road is not a passenger: only the weather and the time of day add up while driving.
        if (gear == Gear.DRIVE) accumulated += tags.copy(companion = false)
        if (!doorOpened || gear == Gear.DRIVE) return
        val current = arrival
        if (current != null && gear == Gear.PARK && now - arrivalTime in 0..EXIT_MS) {
            // Someone getting out right after the arrival rode along: confirm 둘이서 on this ticket.
            if (!current.tags.companion) {
                val updated = current.copy(tags = current.tags + TripTags(companion = true))
                if (profile.updateTags(updated)) arrival = updated else storageFailed = true
            }
        } else {
            boardedAt = now
        }
    }

    private fun detectArrival(now: Long): Arrival? {
        val fix = lastFix ?: return null
        if (gear != Gear.PARK || !fresh(fix, now)) return null
        val city = cities.filter { fix.point.distanceTo(it.point) <= it.radius }
            .minByOrNull { fix.point.distanceTo(it.point) } ?: return null
        if (profile.visited(city)) return null
        val magnet = !profile.owns(city.magnetId) &&
            cities.count { it.provinceId == city.provinceId && profile.visited(it) } == 2
        val result = Arrival(city, !profile.opened(city), true, LocalDate.now().toString(), accumulated + scene, magnet)
        if (!profile.arrive(result, fix.point.block())) {
            storageFailed = true
            return null
        }
        arrival = result
        arrivalTime = now
        companionAboard = false
        return result
    }

    private fun fresh(fix: Fix, now: Long) = now - fix.time in -2_000..10_000

    private companion object {
        /** A passenger who got in counts for a departure this soon after. */
        const val BOARDING_MS = 30 * 60_000L
        /** A door opened this soon after an arrival is someone getting out. */
        const val EXIT_MS = 60_000L
    }
}
