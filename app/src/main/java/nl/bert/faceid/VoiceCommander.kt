package nl.bert.faceid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Always-listening voice commands captured through the glasses microphone.
 *
 * The glasses mic is a Bluetooth Hands-Free (HFP) device, so audio is routed to
 * the SCO channel before recognition starts. Android's SpeechRecognizer is not
 * built for continuous use, so it is restarted after every result or error.
 *
 * Inherent HFP caveats (not fixable in code): 8 kHz mono, mutually exclusive with
 * high-quality (A2DP) audio, and it shares the single Bluetooth link with the
 * camera stream — so this benefits from tuning on real hardware.
 *
 * Must be driven from the main thread: SpeechRecognizer requires it.
 */
class VoiceCommander(
    private val context: Context,
    private val onCommand: (Command) -> Unit,
) {
    enum class Command { WHO, PHOTO }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    var listening = false
        private set

    val isSupported: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    val hasMicPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns true once listening has started. */
    fun start(): Boolean {
        if (listening) return true
        if (!isSupported || !hasMicPermission) return false

        routeToGlassesMic()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            .apply { setRecognitionListener(listener) }
        listening = true
        scheduleListen(0)
        return true
    }

    fun stop() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        stopGlassesMicRoute()
    }

    private fun scheduleListen(delayMs: Long) {
        if (!listening) return
        handler.postDelayed({
            if (!listening) return@postDelayed
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            try {
                recognizer?.cancel()
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                scheduleListen(RETRY_MS)
            }
        }, delayMs)
    }

    private fun match(text: String) {
        val t = text.lowercase(Locale.getDefault())
        when {
            t.contains("picture") || t.contains("photo") || t.contains("foto") ||
                t.contains("take") || t.contains("maak") -> onCommand(Command.PHOTO)
            t.contains("who") || t.contains("wie") -> onCommand(Command.WHO)
        }
    }

    private fun routeToGlassesMic() {
        val am = audioManager ?: return
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?.let { am.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION") am.startBluetoothSco()
            @Suppress("DEPRECATION") am.isBluetoothScoOn = true
        }
    }

    private fun stopGlassesMicRoute() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION") am.isBluetoothScoOn = false
            @Suppress("DEPRECATION") am.stopBluetoothSco()
        }
        am.mode = AudioManager.MODE_NORMAL
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.let { match(it) }
            scheduleListen(0)
        }

        override fun onError(error: Int) = scheduleListen(RETRY_MS)
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        // Backoff after an error so a persistent failure can't hot-loop the recognizer.
        const val RETRY_MS = 600L
    }
}
