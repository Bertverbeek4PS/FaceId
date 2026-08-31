package nl.bert.faceid

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Everything the app says or buzzes goes through here.
 *
 * Audio uses USAGE_MEDIA on purpose: that is the stream Bluetooth A2DP picks up,
 * so speech comes out of the glasses' speakers rather than the phone earpiece
 * whenever the glasses are connected.
 */
class Speaker(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var configured = false
    private var pending: String? = null

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    init {
        engine = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                pending?.let { say(it, interrupt = true) }
                pending = null
            }
        }
    }

    /**
     * Configuration happens on first use rather than in the init callback,
     * because that callback can in principle fire before the field holding the
     * engine has been assigned.
     */
    private fun configure(tts: TextToSpeech) {
        if (configured) return
        configured = true

        val locale = Locale.getDefault()
        tts.language =
            if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) locale
            else Locale.ENGLISH

        tts.setSpeechRate(1.05f)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
    }

    /** Speaks [text]. [interrupt] cuts off whatever is currently being said. */
    fun say(text: String, interrupt: Boolean = false) {
        val tts = engine
        if (!ready || tts == null) {
            pending = text
            return
        }
        configure(tts)
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, null)
    }

    /** One short tap — confirms a button press without words. */
    fun tapFeedback() = vibrate(longArrayOf(0, 25))

    /** Two taps — a person was recognised. */
    fun recognisedFeedback() = vibrate(longArrayOf(0, 30, 70, 30))

    /** One long buzz — a face was seen but not recognised. */
    fun unknownFeedback() = vibrate(longArrayOf(0, 180))

    private fun vibrate(pattern: LongArray) {
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
