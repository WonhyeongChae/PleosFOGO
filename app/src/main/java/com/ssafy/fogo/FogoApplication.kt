package com.ssafy.fogo

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.LocalTime

class FogoApplication : Application() {
    lateinit var cities: List<City>
        private set
    lateinit var profile: Profile
        private set
    lateinit var catalog: AtlasCatalog
        private set
    lateinit var art: AtlasArt
        private set
    lateinit var trip: TripEngine
        private set
    lateinit var speech: ArrivalSpeech
        private set
    private lateinit var car: CarSignals
    lateinit var friends: List<Friend>
        private set
    /** Sheets waiting for the next parked screen (차 생일, 출퇴근 완료, 친구 수락). */
    val notices = ArrayDeque<Notice>()
    /** Last odometer reading (km), null until the car reports one. */
    var odometerKm: Double? = null
        private set
    private var lastPoint: Point? = null
    private var lastPointAt = 0L
    private var driveStart: Point? = null
    private var commuteCheckUntil = 0L
    /** The car's position if a fix came in the last two minutes (registering 집·회사). */
    val currentPoint get() = lastPoint?.takeIf { System.currentTimeMillis() - lastPointAt < 120_000 }
    private lateinit var sdk: PlatformServices
    val handler = Handler(Looper.getMainLooper())
    private val replayHandler = Handler(Looper.getMainLooper())
    var redraw: (() -> Unit)? = null
    /** Short on-screen line from the visible screen (toasts are hidden for the car user on Pleos Connect). */
    var say: ((String) -> Unit)? = null
    var liveDestination: Destination? = null
        private set
    var replayCity: City? = null
        private set
    var replayRunning = false
        private set
    var recording = false
        private set
    var locationIssue: String? = null
        private set
    var ambientStatus: String? = null
        private set
    private var realGear = Gear.UNKNOWN
    private var realRestricted: Boolean? = null
    private var realTags = TripTags()
    private var locationSession = 0
    private var pendingReaction: Arrival? = null
    val replayAllowed get() = BuildConfig.DEBUG && (
        Build.HARDWARE.contains("ranchu", true) || Build.HARDWARE.contains("goldfish", true) ||
            Build.PRODUCT.contains("sdk", true) || Build.FINGERPRINT.contains("generic", true)
        )
    private val realParked get() = realGear == Gear.PARK && realRestricted == false
    private val realDriving get() = realRestricted == true || realGear == Gear.DRIVE || realGear == Gear.OTHER
    val parked get() = if (replayCity != null) !realDriving && trip.gear == Gear.PARK else realParked
    val canOpenReplay get() = replayAllowed && !realDriving && replayCity == null
    val destination get() = replayCity?.let { Destination(it.spot, it.point) } ?: liveDestination
    val hybrid get() = sdk.hybridConfigured

    override fun onCreate() {
        super.onCreate()
        cities = loadCities(this)
        profile = Profile(this)
        catalog = AtlasCatalog(this, cities)
        art = AtlasArt(this)
        trip = TripEngine(profile, cities)
        sdk = PlatformServices(this)
        speech = ArrivalSpeech(this, sdk)
        friends = loadFriends(this)
        car = CarSignals(this)
        car.connect()
        // Night (19시–6시) was only re-evaluated when a vehicle signal changed, so the theme stayed 주간 and a trip
        // arriving after 19:00 missed its 밤 tag. The minute tick re-checks; applyLiveTags redraws only on a change.
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) = applyLiveTags()
        }, android.content.IntentFilter(Intent.ACTION_TIME_TICK))
    }

    fun refreshDestination() {
        if (replayCity == null) sdk.refreshDestination {
            liveDestination = it
            redraw?.invoke()
        }
    }

    fun routeTo(city: City) = parked && replayCity == null && sdk.routeTo(city)

    fun startLocation() {
        if (recording || replayCity != null) return
        recording = true
        locationIssue = null
        val session = ++locationSession
        try {
            sdk.startLocation(
                onFix = { fix ->
                    if (session == locationSession && recording && replayCity == null) {
                        lastPoint = fix.point
                        lastPointAt = System.currentTimeMillis()
                        checkCommute()
                        val recovered = locationIssue != null
                        locationIssue = null
                        arrived(trip.location(fix, System.currentTimeMillis()))
                        if (trip.storageFailed || recovered) redraw?.invoke()
                    }
                },
                onError = { error -> if (session == locationSession) locationError(error) },
            )
        } catch (error: Exception) {
            recording = false
            locationError(error)
            stopService(Intent(this, RecordingService::class.java))
        }
        redraw?.invoke()
    }

    fun stopLocation() {
        ++locationSession
        recording = false
        sdk.stopLocation()
        if (replayCity == null) trip.clearFix()
        redraw?.invoke()
    }

    fun locationError(error: Exception) {
        if (locationIssue == null) Log.w("FOGO", "Location unavailable: " + error.javaClass.simpleName)
        trip.clearFix()
        locationIssue = "차량 위치를 받지 못했어요"
        redraw?.invoke()
    }

    fun updateGear(gear: Gear) {
        if (realGear == gear) return
        val wasDriving = realGear == Gear.DRIVE
        realGear = gear
        if (replayCity == null) {
            if (gear == Gear.DRIVE && !wasDriving) driveStart = currentPoint
            // The fix that shows where the car stopped can arrive a little after P, so keep looking for a while.
            if (gear == Gear.PARK && wasDriving) commuteCheckUntil = System.currentTimeMillis() + COMMUTE_WINDOW_MS
            checkCommute()
        }
        if (replayCity == null) arrived(trip.gear(gear, System.currentTimeMillis()))
        if (realDriving && replayRunning) interruptReplay()
        if (!parked) speech.stop() else {
            profile.closePreviousMonth(cities)
            reactToArrival()
        }
        redraw?.invoke()
    }

    fun updateRestrictions(restricted: Boolean?) {
        if (realRestricted == restricted) return
        realRestricted = restricted
        if (realDriving && replayRunning) interruptReplay()
        if (!parked) speech.stop() else {
            profile.closePreviousMonth(cities)
            reactToArrival()
        }
        redraw?.invoke()
    }

    fun updateVehicleTags(rain: Boolean? = null, night: Boolean? = null, companion: Boolean? = null) {
        realTags = realTags.copy(rain = rain ?: realTags.rain, night = night ?: realTags.night,
            companion = companion ?: realTags.companion)
        if (replayCity != null) return
        applyLiveTags()
    }

    fun applyLiveTags() {
        if (replayCity != null) return
        val previous = trip.scene
        val previousArrival = trip.arrival
        val hour = LocalTime.now().hour
        trip.tags(realTags.copy(night = realTags.night || hour >= 19 || hour < 6), System.currentTimeMillis())
        if (previous != trip.scene || previousArrival != trip.arrival) redraw?.invoke()
    }

    fun setHybrid(id: String? = null, secret: String? = null) {
        if (!parked) return
        if (id.isNullOrBlank() || secret.isNullOrBlank()) sdk.clearHybrid() else sdk.configureHybrid(id, secret)
        redraw?.invoke()
    }

    fun prepareReplay(city: City) {
        if (!canOpenReplay && !(parked && replayCity != null && !replayRunning)) return
        replayHandler.removeCallbacksAndMessages(null)
        stopService(Intent(this, RecordingService::class.java))
        stopLocation()
        speech.stop()
        replayCity = city
        ambientStatus = null
        pendingReaction = null
        trip.resetTrip()
        trip.gear(Gear.PARK, System.currentTimeMillis())
        redraw?.invoke()
    }

    fun startReplay() {
        val city = replayCity ?: return
        if (!parked || replayRunning) return
        replayRunning = true
        speech.stop()
        ambientStatus = null
        replayHandler.postDelayed({
            val now = System.currentTimeMillis()
            // A passenger gets in before the departure: the passenger door opens and closes.
            if (city.companion) trip.tags(TripTags(city.rain, city.night, true), now)
            trip.tags(TripTags(city.rain, city.night, false), now)
            trip.gear(Gear.DRIVE, now)
            redraw?.invoke()
        }, 350)
        val route = city.route.zipWithNext().flatMap { (from, to) ->
            List(3) { step ->
                val fraction = step / 3.0
                Point(from.latitude + (to.latitude - from.latitude) * fraction,
                    from.longitude + (to.longitude - from.longitude) * fraction)
            }
        } + city.route.last()
        route.forEachIndexed { index, point ->
            replayHandler.postDelayed({
                val now = System.currentTimeMillis()
                trip.location(Fix(point, now), now)
            }, 600L + index * 280)
        }
        replayHandler.postDelayed({
            val now = System.currentTimeMillis()
            replayRunning = false
            arrived(trip.gear(Gear.PARK, now))
            profile.closePreviousMonth(cities)
            redraw?.invoke()
        }, 1000L + route.size * 280)
        redraw?.invoke()
    }

    fun leaveReplay() {
        if (!parked || replayRunning) return
        replayHandler.removeCallbacksAndMessages(null)
        speech.stop()
        replayCity = null
        ambientStatus = null
        pendingReaction = null
        trip.resetTrip()
        trip.gear(realGear, System.currentTimeMillis())
        applyLiveTags()
        refreshDestination()
        redraw?.invoke()
    }

    fun resetDemo() {
        if (!parked || replayRunning || !profile.reset()) return
        profile.rebaseOdometer(odometerKm)
        notices.clear()
        val city = replayCity
        if (city != null) prepareReplay(city) else {
            stopService(Intent(this, RecordingService::class.java))
            stopLocation()
            speech.stop()
            ambientStatus = null
            pendingReaction = null
            trip.resetTrip()
            trip.gear(realGear, System.currentTimeMillis())
            applyLiveTags()
            redraw?.invoke()
        }
    }

    /** Odometer from Vehicle SDK: queues the 차 생일 sheet when a 10,000 km milestone is passed. */
    fun updateOdometer(km: Double) {
        odometerKm = km
        val milestone = profile.noteOdometer(km)
        if (milestone != null) {
            val count = milestone / Profile.BIRTHDAY_KM
            notices.add(Notice("BIRTHDAY", "${profile.car}의 ${count}만 km 생일", "오늘은 내 차의 ${count}만 km 생일이에요. " +
                "생일 스티커와 한정 재료 1개를 받았어요. 한정 재료로 고래 모자를 만들 수 있어요.",
                "지도판에 붙이기", Page.BOARD, "오늘은 ${profile.car}의 ${count}만 킬로미터 생일이에요"))
        }
        redraw?.invoke()
    }

    /** Demo friends: a request to a known code is accepted from the friend's car a few seconds later. */
    fun requestFriend(friend: Friend): Boolean {
        if (!profile.requestFriend(friend.id)) return false
        handler.postDelayed({
            if (profile.requestedFriend(friend.id) && profile.acceptFriend(friend.id)) {
                notices.add(Notice("FRIEND", "${friend.name.particle("과", "와")} 친구가 됐어요", "${friend.name.particle("이", "가")} 자기 차에서 요청을 수락했어요. " +
                    "이제 서로의 지도판과 차고를 구경하고 스티커로 반응할 수 있어요. (시연)", "친구 보기", Page.FRIENDS))
                redraw?.invoke()
            }
        }, FRIEND_ACCEPT_MS)
        return true
    }

    fun speak(text: String) {
        if (parked) sdk.speak(text, hybrid, {}, {})
    }

    /** 출퇴근: a drive from near one registered place that stops near the other counts once. */
    private fun checkCommute() {
        if (System.currentTimeMillis() > commuteCheckUntil || realGear != Gear.PARK) return
        val home = profile.place("home") ?: return
        val work = profile.place("work") ?: return
        val start = driveStart ?: return
        val end = currentPoint ?: return
        fun near(a: Point, b: Point) = a.distanceTo(b) <= COMMUTE_RADIUS_M
        val commute = (near(start, home) && near(end, work)) || (near(start, work) && near(end, home))
        if (!commute) return
        commuteCheckUntil = 0
        driveStart = null
        if (profile.addCommute()) {
            notices.add(Notice("COMMUTE", "이번 주 출퇴근 ${Profile.COMMUTE_GOAL}/${Profile.COMMUTE_GOAL} 완료",
                "같은 길도 쌓였어요. 공용 재료 +${Profile.COMMUTE_MATERIALS}, 출퇴근 스티커를 받았어요.", "공방 가기", Page.WORKSHOP))
        } else {
            say?.invoke("출퇴근 루틴 ${profile.commutes()}/${Profile.COMMUTE_GOAL}")
        }
        redraw?.invoke()
    }

    private fun arrived(arrival: Arrival?) {
        if (arrival == null) return
        val simulated = replayCity != null
        ambientStatus = if (simulated) "조명 연출 · ${arrival.city.colorName}" else "차량 조명 확인 중"
        redraw?.invoke()
        pendingReaction = arrival
        reactToArrival()
    }

    private fun interruptReplay() {
        replayHandler.removeCallbacksAndMessages(null)
        replayRunning = false
        pendingReaction = null
        trip.resetTrip()
        trip.gear(Gear.PARK, System.currentTimeMillis())
    }

    private fun reactToArrival() {
        val arrival = pendingReaction ?: return
        if (!parked) return
        pendingReaction = null
        if (trip.arrival?.city != arrival.city) return
        val simulated = replayCity != null
        speech.play(arrival.city, hybrid)
        if (!simulated) car.ambient(arrival.city.color) { applied ->
            if (trip.arrival?.city == arrival.city && replayCity == null) {
                ambientStatus = if (applied) "차량 조명 · ${arrival.city.colorName}" else "화면 조명 · ${arrival.city.colorName}"
                redraw?.invoke()
            }
        }
    }

    private companion object {
        const val COMMUTE_RADIUS_M = 500.0
        const val COMMUTE_WINDOW_MS = 20_000L
        const val FRIEND_ACCEPT_MS = 4_000L
    }
}

/** A sheet shown on the next parked screen; [target] is where its main button goes, [speak] is read aloud. */
data class Notice(val tag: String, val title: String, val message: String, val action: String? = null,
                  val target: Page? = null, val speak: String? = null)
