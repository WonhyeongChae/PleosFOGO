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
    }

    fun gear(value: Gear, now: Long): Arrival? {
        if (value == Gear.DRIVE && gear != Gear.DRIVE) {
            accumulated = scene.copy(companion = false)
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
        scene = tags
        if (gear == Gear.DRIVE) accumulated += tags
        val current = arrival ?: return
        if (gear == Gear.PARK && now - arrivalTime in 0..60_000 && tags.companion && !current.tags.companion) {
            val updated = current.copy(tags = current.tags + TripTags(companion = true))
            if (profile.updateTags(updated)) arrival = updated else storageFailed = true
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
        return result
    }

    private fun fresh(fix: Fix, now: Long) = now - fix.time in -2_000..10_000
}
