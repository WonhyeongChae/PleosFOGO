package com.ssafy.fogo

import android.media.AudioManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalAudioTest {
    @Test fun failedSpeechServicePlaysBundledAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as FogoApplication
        val audioManager = app.getSystemService(AudioManager::class.java)
        var speech: ArrivalSpeech? = null
        try {
            instrumentation.runOnMainSync {
                val platform = object : SpeechEngine {
                    override fun speak(text: String, hybrid: Boolean, onStarted: () -> Unit, onFailure: () -> Unit) {
                        onFailure()
                    }

                    override fun stopSpeech() = Unit
                }
                speech = ArrivalSpeech(app, platform)
                speech?.play(app.cities.first(), hybrid = false)
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!audioManager.isMusicActive && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
            }
            assertTrue("Expected Korean arrival audio output", audioManager.isMusicActive)
        } finally {
            instrumentation.runOnMainSync { speech?.stop() }
        }
    }
}
