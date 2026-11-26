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
class Speaker(context: Context, private val settings: com.willwalsh.treker.Settings) {
    private val app = context.applicationContext
    private var ready = false
    private val pending = ArrayList<Pair<String, Boolean>>()
    /** Engines installed on the phone: package name to the name people see. */
    val engines = androidx.compose.runtime.mutableStateListOf<Pair<String, String>>()
    /** Voices in the current engine, by name, with a readable description. */
    val voices = androidx.compose.runtime.mutableStateListOf<Pair<String, String>>()

    var rate: Float = settings.speechRate
        set(v) { field = v; if (ready) tts.setSpeechRate(v) }
    var pitch: Float = settings.speechPitch
        set(v) { field = v; if (ready) tts.setPitch(v) }

    private var tts: TextToSpeech = create()

    private fun create(): TextToSpeech {
        ready = false
        val listener = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                setUp()
                val queued = pending.toList(); pending.clear()
                queued.forEach { (t, i) -> say(t, i) }
            }
        }
        val engine = settings.speechEngine
        return if (engine.isBlank()) TextToSpeech(app, listener) else TextToSpeech(app, listener, engine)
    }

    private fun setUp() {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        engines.clear()
        engines.addAll(tts.engines.map { it.name to it.label })
        val all = runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { !it.isNetworkConnectionRequired }
            .sortedWith(compareBy({ it.locale.displayName }, { it.name }))
        voices.clear()
        voices.addAll(all.map { v ->
            val quality = when {
                v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "very high quality"
                v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "high quality"
                else -> "normal quality"
            }
            v.name to "${v.locale.displayName}, ${v.name}, $quality"
        })
        val wanted = settings.speechVoice
        if (wanted.isNotBlank()) all.firstOrNull { it.name == wanted }?.let { tts.voice = it }
    }

    /** Switch to the engine and voice chosen in settings. */
    fun reload() {
        runCatching { tts.stop(); tts.shutdown() }
        tts = create()
    }

    fun setVoice(name: String) {
        settings.speechVoice = name
        if (ready) runCatching { tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } }
    }

    /** Speaks [text]. [interrupt] cuts off whatever is being said; otherwise it waits its turn. */
    fun say(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        if (!ready) { pending.add(text to interrupt); return }
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.speechVolume) }
        tts.speak(text, mode, params, "t-${System.nanoTime()}")
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
