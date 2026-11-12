package com.ssafy.fogo

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import android.util.Log
import java.util.Locale

class PlatformServices(private val app: FogoApplication) : SpeechEngine {
    private var location: FusedLocation? = null
    private var locationListener: FusedLocationListener? = null
    private var navigation: NaviHelper? = null
    private var navigationReady = false
    private var destinationResult: ((Destination?) -> Unit)? = null
    private var tts: TextToSpeech? = null
    private var ttsListener: EventListener? = null
    private var credentials: Pair<String, String>? = null
    val hybridConfigured get() = credentials != null

    private val navigationListener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            app.handler.post {
                val point = Point(destinationInfo.latitude, destinationInfo.longitude)
                val destination = Destination(destinationInfo.name.orEmpty().ifBlank { "내비 목적지" }, point)
                destinationResult?.invoke(destination.takeIf { point.valid() })
            }
        }

        override fun onDestinationChanged() {
            app.handler.post { if (navigationReady) requestDestination() }
        }
    }

    fun refreshDestination(result: (Destination?) -> Unit) {
        destinationResult = result
        if (navigationReady) {
            requestDestination()
            return
        }
        if (navigation != null) return
        val helper = NaviHelper(app).also { navigation = it }
        runCatching {
            helper.initialize(onConnected = {
                app.handler.post {
                    if (navigation !== helper) return@post
                    runCatching {
                        helper.addListener(navigationListener)
                        navigationReady = true
                        requestDestination()
                    }.onFailure { navigationFailed(helper) }
                }
            }, onFailed = { app.handler.post { navigationFailed(helper) } })
        }.onFailure { navigationFailed(helper) }
        app.handler.postDelayed({ if (navigation === helper && !navigationReady) navigationFailed(helper) }, 5000)
    }

    fun routeTo(city: City): Boolean {
        val helper = navigation?.takeIf { navigationReady } ?: return false
        return runCatching {
            helper.requestRoute(RouteInfo(
                latitude = city.point.latitude,
                longitude = city.point.longitude,
                displayLatitude = null,
                displayLongitude = null,
                poiName = city.spot,
                poiId = "0",
                poiSubId = "0",
                address = city.city,
            ))
        }.isSuccess
    }

    private fun requestDestination() {
        runCatching { navigation?.getDestinationInfo() }.onFailure {
            navigation?.let(::navigationFailed)
        }
    }

    private fun navigationFailed(helper: NaviHelper) {
        if (navigation !== helper) return
        navigation = null
        navigationReady = false
        runCatching { helper.release() }
        destinationResult?.invoke(null)
        Log.w("FOGO", "NaviHelper connection unavailable")
    }

    fun startLocation(onFix: (Fix) -> Unit, onError: (Exception) -> Unit) {
        stopLocation()
        val sdk = FusedLocation(app)
        check(sdk.isSupported()) { "Vehicle location unsupported" }
        location = sdk
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                app.handler.post {
                    if (this@PlatformServices.location !== sdk) return@post
                    if (location == null) onError(IllegalStateException("No location"))
                    else onFix(Fix(Point(location.latitude, location.longitude), location.timestampMillis))
                }
            }

            override fun onError(exception: Exception) {
                app.handler.post { if (this@PlatformServices.location === sdk) onError(exception) }
            }
        }
        locationListener = listener
        try {
            sdk.initialize()
            sdk.registerFusedLocationCallback(listener)
        } catch (error: Exception) {
            stopLocation()
            throw error
        }
    }

    fun stopLocation() {
        val sdk = location ?: return
        location = null
        locationListener?.let { listener -> runCatching { sdk.unregisterFusedLocationCallback(listener) } }
        locationListener = null
        runCatching { sdk.release() }
    }

    fun configureHybrid(id: String, secret: String) { credentials = id to secret }
    fun clearHybrid() { credentials = null }

    override fun speak(text: String, hybrid: Boolean, onStarted: () -> Unit, onFailure: () -> Unit) {
        stopSpeech()
        val keys = credentials.takeIf { hybrid }
        try {
            val sdk = TextToSpeech(app, if (keys == null) Mode.ON_DEVICE else Mode.HYBRID)
            tts = sdk
            fun whenActive(block: () -> Unit) {
                app.handler.post {
                    if (tts === sdk) runCatching(block).onFailure { onFailure() }
                }
            }
            val listener = object : EventListener {
                override fun onReady() = Unit
                override fun onStart() = whenActive(onStarted)
                override fun onUpdatedRms(rms: Double) = Unit
                override fun onDone() = whenActive { stopSpeech() }
                override fun onStop() = Unit
                override fun onError(errorType: ErrorType) = whenActive {
                    Log.w("FOGO", "Pleos TTS: $errorType")
                    onFailure()
                    if (tts === sdk) stopSpeech()
                }
            }
            ttsListener = listener
            sdk.initialize(object : OnInitializeListener {
                override fun onSuccess() = whenActive {
                    sdk.addEventListener(listener)
                    sdk.setLocale(Locale.KOREA)
                    if (keys == null) sdk.speak(text) else sdk.registerApp(keys.first, keys.second,
                        object : OnServerConnectionListener {
                            override fun onConnected() = whenActive { sdk.speak(text) }
                            override fun onFailed(errorType: ErrorType) = whenActive(onFailure)
                        })
                }

                override fun onFailure(errorType: ErrorType) = whenActive {
                    Log.w("FOGO", "Pleos TTS initialization: $errorType")
                    onFailure()
                }
            })
        } catch (error: Exception) {
            Log.w("FOGO", "Pleos TTS unavailable: ${error.javaClass.simpleName}")
            onFailure()
        }
    }

    override fun stopSpeech() {
        val sdk = tts ?: return
        tts = null
        val listener = ttsListener
        ttsListener = null
        if (listener != null) runCatching { sdk.removeEventListener(listener) }
        runCatching { sdk.stop() }
        runCatching { sdk.release() }
    }
}
