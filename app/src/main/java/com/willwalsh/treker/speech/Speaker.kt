package com.willwalsh.treker.speech

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.accessibility.AccessibilityManager

/**
 * Treker's own voice. Live exploration, What's Around and guidance speak through this so they
 * work with the screen off and in other apps. It uses the navigation audio channel, so music and
 * podcasts dip while it talks instead of stopping.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private var ready = false
    private val pending = ArrayList<Pair<String, Boolean>>()
    var rate: Float = 1f
        set(v) { field = v; tts.setSpeechRate(v) }

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            setUp()
            val queued = pending.toList(); pending.clear()
            queued.forEach { (t, i) -> say(t, i) }
        }
    }

    private fun setUp() {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setSpeechRate(rate)
    }

    /** Speaks [text]. [interrupt] cuts off whatever is being said; otherwise it waits its turn. */
    fun say(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        if (!ready) { pending.add(text to interrupt); return }
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, Bundle(), "t-${System.nanoTime()}")
    }

    fun stop() { if (ready) tts.stop() }

    val speaking: Boolean get() = ready && tts.isSpeaking

    @Suppress("DEPRECATION")
    fun tick(ms: Long = 30) {
        val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    fun shutdown() { tts.stop(); tts.shutdown() }
}

/** Short on-screen feedback: through the screen reader when one is on, otherwise Treker's voice. */
class Announcer(context: Context, private val speaker: Speaker) {
    private val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val screenReaderOn: Boolean get() = am.isEnabled && am.isTouchExplorationEnabled

    fun say(text: String, view: View?) {
        if (screenReaderOn && view != null) {
            @Suppress("DEPRECATION")
            view.announceForAccessibility(text)
        } else speaker.say(text, interrupt = true)
    }
}
