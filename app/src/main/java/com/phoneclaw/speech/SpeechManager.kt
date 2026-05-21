package com.phoneclaw.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "SpeechManager"

/**
 * Manages both Speech-to-Text (STT) and Text-to-Speech (TTS).
 *
 * STT: Uses Android's built-in SpeechRecognizer (routes to Google's on-device
 * or cloud STT — no extra API key required).
 *
 * TTS: Uses Android's built-in TextToSpeech engine.
 *
 * Both are suspend-based so they integrate cleanly with the agent coroutine loop.
 */
class SpeechManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(0.9f)  // Slightly slower for clarity
                ttsReady = true
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }
        }
    }

    /**
     * Listen for a voice command. Returns the recognized text, or null if
     * recognition failed or timed out.
     *
     * Must be called from the main thread (SpeechRecognizer requires it).
     */
    suspend fun listen(): String? = withTimeoutOrNull(15_000L) {
        suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                speechRecognizer = it
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                // No prompt — we show our own UI
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull()
                    Log.d(TAG, "STT result: $text")
                    recognizer.destroy()
                    speechRecognizer = null
                    if (!continuation.isCompleted) continuation.resume(text)
                }

                override fun onError(error: Int) {
                    val msg = sttErrorMessage(error)
                    Log.w(TAG, "STT error: $msg ($error)")
                    recognizer.destroy()
                    speechRecognizer = null
                    if (!continuation.isCompleted) continuation.resume(null)
                }

                // Required but unused callbacks
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)

            continuation.invokeOnCancellation {
                recognizer.stopListening()
                recognizer.destroy()
                speechRecognizer = null
            }
        }
    }

    /** Stop any ongoing STT session. */
    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    /**
     * Speak text to the user. Suspends until speaking is complete.
     */
    suspend fun speak(text: String) {
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready, skipping: $text")
            return
        }

        val utteranceId = UUID.randomUUID().toString()

        suspendCancellableCoroutine { continuation ->
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (!continuation.isCompleted) continuation.resume(Unit)
                }
                override fun onError(utteranceId: String?) {
                    if (!continuation.isCompleted) continuation.resume(Unit)
                }
            })

            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

            continuation.invokeOnCancellation {
                tts?.stop()
            }
        }
    }

    /** Stop any ongoing TTS. */
    fun stopSpeaking() {
        tts?.stop()
    }

    /** Release all resources. Call from onDestroy. */
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun sttErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing microphone permission"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
        else -> "Unknown error"
    }
}
