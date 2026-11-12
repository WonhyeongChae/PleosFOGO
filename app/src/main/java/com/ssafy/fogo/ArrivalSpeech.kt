package com.ssafy.fogo

import android.media.MediaPlayer
import android.util.Log

interface SpeechEngine {
    fun speak(text: String, hybrid: Boolean, onStarted: () -> Unit, onFailure: () -> Unit)
    fun stopSpeech()
}

class ArrivalSpeech(private val app: FogoApplication, private val platform: SpeechEngine) {
    private var player: MediaPlayer? = null
    private var timeout: Runnable? = null
    private var generation = 0

    fun play(city: City, hybrid: Boolean) {
        stop()
        val request = generation
        var resolved = false
        fun fallback() {
            if (generation != request || resolved) return
            resolved = true
            cancelTimeout()
            platform.stopSpeech()
            val id = when (city.audio) {
                "arrival_gangneung" -> R.raw.arrival_gangneung
                "arrival_seoul" -> R.raw.arrival_seoul
                "arrival_busan" -> R.raw.arrival_busan
                "arrival_jeongdongjin" -> R.raw.arrival_jeongdongjin
                "arrival_sheepfarm" -> R.raw.arrival_sheepfarm
                "arrival_yeouido" -> R.raw.arrival_yeouido
                "arrival_lottetower" -> R.raw.arrival_lottetower
                "arrival_gwangalli" -> R.raw.arrival_gwangalli
                "arrival_taejongdae" -> R.raw.arrival_taejongdae
                else -> return
            }
            runCatching {
                player = MediaPlayer.create(app, id)?.apply {
                    setOnCompletionListener { completed ->
                        if (player === completed) player = null
                        completed.release()
                    }
                    start()
                }
                if (player != null) Log.i("FOGO", "Bundled Korean arrival audio: ${city.cityId}")
            }.onFailure { Log.w("FOGO", "Arrival audio unavailable", it) }
        }

        timeout = Runnable { fallback() }.also { app.handler.postDelayed(it, 2400) }
        platform.speak(city.speech, hybrid, onStarted = {
            if (generation == request && !resolved) {
                resolved = true
                cancelTimeout()
                Log.i("FOGO", "Pleos Korean TTS started: ${city.cityId}")
            }
        }, onFailure = { fallback() })
    }

    fun stop() {
        ++generation
        cancelTimeout()
        platform.stopSpeech()
        player?.release()
        player = null
    }

    private fun cancelTimeout() {
        timeout?.let(app.handler::removeCallbacks)
        timeout = null
    }
}
