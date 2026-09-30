package com.sosina.terefe.budgetingapp.data.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

enum class Sound { KA_CHING, BLIP, SAD_TROMBONE, FANFARE, NOM }

/**
 * Plays short sound effects, generated from simple tones instead of audio files.
 * Respects the Sound effects setting and the phone's silent/vibrate mode.
 */
@Singleton
class SoundPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Each sound is generated once, then reused.
    private val cache = ConcurrentHashMap<Sound, ShortArray>()

    fun play(sound: Sound) {
        scope.launch {
            try {
                if (!settingsRepository.settings.first().soundEffects) return@launch
                val audio = context.getSystemService(AudioManager::class.java)
                if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return@launch

                val samples = cache.getOrPut(sound) { toPcm(generate(sound)) }
                playPcm(samples)
            } catch (e: Exception) {
                Log.w("Sound", "Couldn't play $sound", e) // a missing sound is never a problem
            }
        }
    }

    private suspend fun playPcm(samples: ShortArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()

        track.write(samples, 0, samples.size)
        track.play()
        // Wait for it to finish, then free the audio resources.
        delay(samples.size * 1000L / SAMPLE_RATE + 100)
        track.release()
    }

    // ======================= The synthesizer =======================

    private enum class Wave { SINE, SAW }

    private fun generate(sound: Sound): FloatArray = when (sound) {
        // A bright bell-like double "ding", like a cash register.
        Sound.KA_CHING -> concat(
            noise(ms = 18, volume = 0.25f),
            mix(bell(1319.0, 110), bell(2637.0, 110, volume = 0.15f)),
            mix(bell(1760.0, 420), bell(3520.0, 420, volume = 0.12f))
        )

        // A soft, short "blip".
        Sound.BLIP -> concat(
            note(880.0, 60, volume = 0.35f, decay = 5.0),
            note(1320.0, 70, volume = 0.3f, decay = 6.0)
        )

        // "Wah, wah, wah, waaaah": four falling notes, the last one wobbling.
        Sound.SAD_TROMBONE -> lowPass(
            concat(
                note(233.08, 330, Wave.SAW, volume = 0.3f, decay = 0.8),
                note(220.00, 330, Wave.SAW, volume = 0.3f, decay = 0.8),
                note(207.65, 330, Wave.SAW, volume = 0.3f, decay = 0.8),
                note(196.00, 1000, Wave.SAW, volume = 0.3f, decay = 1.2, vibrato = 0.02)
            ),
            smoothing = 0.12f
        )

        // A quick rising "ta-da" arpeggio.
        Sound.FANFARE -> lowPass(
            concat(
                note(523.25, 110, Wave.SAW, volume = 0.25f, decay = 1.5),
                note(659.25, 110, Wave.SAW, volume = 0.25f, decay = 1.5),
                note(783.99, 110, Wave.SAW, volume = 0.25f, decay = 1.5),
                note(1046.50, 500, Wave.SAW, volume = 0.3f, decay = 2.5, vibrato = 0.008)
            ),
            smoothing = 0.25f
        )

        // Two low "nom" bites.
        Sound.NOM -> concat(
            sweep(from = 320.0, to = 140.0, ms = 110, volume = 0.45f),
            silence(70),
            sweep(from = 300.0, to = 120.0, ms = 130, volume = 0.45f)
        )
    }

    /** A single tone that starts quickly and fades out. */
    private fun note(
        freq: Double,
        ms: Int,
        wave: Wave = Wave.SINE,
        volume: Float = 0.4f,
        decay: Double = 3.0,
        vibrato: Double = 0.0
    ): FloatArray {
        val count = SAMPLE_RATE * ms / 1000
        val seconds = ms / 1000.0
        var phase = 0.0
        return FloatArray(count) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val f = freq * (1 + vibrato * sin(2 * PI * 6 * t))   // 6 wobbles per second
            phase += 2 * PI * f / SAMPLE_RATE
            val raw = when (wave) {
                Wave.SINE -> sin(phase)
                Wave.SAW -> 2 * ((phase / (2 * PI)) % 1.0) - 1
            }
            val attack = (i / (SAMPLE_RATE * 0.005)).coerceAtMost(1.0)   // 5 ms fade-in, avoids clicks
            val envelope = attack * exp(-decay * t / seconds)
            (raw * envelope * volume).toFloat()
        }
    }

    /** A bell: a pure tone that rings out longer. */
    private fun bell(freq: Double, ms: Int, volume: Float = 0.35f) =
        note(freq, ms, Wave.SINE, volume = volume, decay = 4.0)

    /** A tone that slides from one pitch to another (for "nom"). */
    private fun sweep(from: Double, to: Double, ms: Int, volume: Float): FloatArray {
        val count = SAMPLE_RATE * ms / 1000
        var phase = 0.0
        return FloatArray(count) { i ->
            val progress = i.toDouble() / count
            phase += 2 * PI * (from + (to - from) * progress) / SAMPLE_RATE
            val envelope = sin(PI * progress)   // swells up, then down
            (sin(phase) * envelope * volume).toFloat()
        }
    }

    /** A tiny burst of static, like a register drawer clicking. */
    private fun noise(ms: Int, volume: Float): FloatArray {
        val count = SAMPLE_RATE * ms / 1000
        return FloatArray(count) { i -> (Random.nextFloat() * 2 - 1) * volume * (1f - i.toFloat() / count) }
    }

    private fun silence(ms: Int) = FloatArray(SAMPLE_RATE * ms / 1000)

    private fun concat(vararg parts: FloatArray): FloatArray {
        val result = FloatArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach { part -> part.copyInto(result, offset); offset += part.size }
        return result
    }

    /** Plays two sounds at the same time. */
    private fun mix(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(maxOf(a.size, b.size)) { i -> a.getOrElse(i) { 0f } + b.getOrElse(i) { 0f } }

    /** Softens harsh sounds (lower smoothing = more muffled). */
    private fun lowPass(input: FloatArray, smoothing: Float): FloatArray {
        var previous = 0f
        return FloatArray(input.size) { i ->
            previous += smoothing * (input[i] - previous)
            previous
        }
    }

    /** Turns -1..1 values into 16-bit audio samples. */
    private fun toPcm(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { i -> (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
