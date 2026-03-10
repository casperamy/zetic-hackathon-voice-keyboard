package com.aaryaharkare.voicekeyboard

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.media.MediaPlayer
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aaryaharkare.voicekeyboard.audio.AudioSampler
import com.aaryaharkare.voicekeyboard.audio.WavWriter
import com.aaryaharkare.voicekeyboard.whisper.WhisperFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

class VoiceInputMethodService : InputMethodService() {

    companion object {
        private const val TAG = "VoiceKB"
    }

    private enum class ImeState {
        IDLE,
        RECORDING,
        PROCESSING,
        PLAYING,
        DISABLED,
    }

    private var currentState = ImeState.IDLE
    private var statusText: TextView? = null
    private var debugTranscript: TextView? = null
    private var micBubble: FrameLayout? = null
    private var playBubble: FrameLayout? = null
    private var playIcon: ImageView? = null

    private var mediaPlayer: MediaPlayer? = null
    private var audioFile: File? = null
    private var audioSampler: AudioSampler? = null
    private var whisperFeature: WhisperFeature? = null

    private var lastTranscript: String? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionId = AtomicLong(0L)

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        statusText = view.findViewById(R.id.statusText)
        debugTranscript = view.findViewById(R.id.debugTranscript)
        micBubble = view.findViewById(R.id.micBubble)
        playBubble = view.findViewById(R.id.playBubble)
        playIcon = view.findViewById(R.id.playIcon)

        audioFile = File(cacheDir, "latest_recording.wav")
        audioSampler = AudioSampler(::onAudioReady)

        micBubble?.setOnClickListener { handleMicClick() }
        playBubble?.setOnClickListener { handlePlayClick() }

        updateUi()
        return view
    }

    override fun onStartInputView(
        info: EditorInfo?,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        sessionId.incrementAndGet()
        resetToInitialState(info)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        sessionId.incrementAndGet()
        stopRecording()
        stopPlayback()
        if (currentState != ImeState.DISABLED) {
            currentState = ImeState.IDLE
        }
        updateUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopRecording()
        stopPlayback()
        audioSampler?.release()
        whisperFeature?.close()
    }

    private fun resetToInitialState(info: EditorInfo?) {
        stopRecording()
        stopPlayback()
        currentState =
            if (isPasswordField(info?.inputType ?: 0)) {
                ImeState.DISABLED
            } else {
                ImeState.IDLE
            }
        updateUi()
    }

    private fun isPasswordField(inputType: Int): Boolean {
        val maskClass = inputType and EditorInfo.TYPE_MASK_CLASS
        val maskVariation = inputType and EditorInfo.TYPE_MASK_VARIATION
        return when (maskClass) {
            EditorInfo.TYPE_CLASS_TEXT ->
                maskVariation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
                    maskVariation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    maskVariation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
            EditorInfo.TYPE_CLASS_NUMBER -> maskVariation == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun handleMicClick() {
        if (currentState == ImeState.DISABLED || currentState == ImeState.PROCESSING) {
            return
        }

        if (!hasMicPermission()) {
            statusText?.text = "Missing Mic Permission"
            return
        }

        when (currentState) {
            ImeState.IDLE, ImeState.PLAYING -> {
                stopPlayback()
                startRecording()
            }
            ImeState.RECORDING -> {
                currentState = ImeState.PROCESSING
                updateUi()
                stopRecording()
            }
            ImeState.PROCESSING, ImeState.DISABLED -> Unit
        }
    }

    private fun handlePlayClick() {
        when (currentState) {
            ImeState.IDLE -> startPlayback()
            ImeState.PLAYING -> stopPlayback()
            else -> Unit
        }
    }

    private fun startRecording() {
        try {
            currentState = ImeState.RECORDING
            updateUi()
            audioSampler?.startRecording()
        } catch (e: Exception) {
            currentState = ImeState.IDLE
            statusText?.text = "Recording failed"
            updateUi()
        }
    }

    private fun stopRecording() {
        audioSampler?.stopRecording()
    }

    private fun onAudioReady(audioSamples: FloatArray) {
        val activeSession = sessionId.get()
        Log.d(TAG, "onAudioReady: ${audioSamples.size} samples")

        if (audioSamples.isEmpty()) {
            serviceScope.launch {
                if (activeSession != sessionId.get()) return@launch
                currentState = ImeState.IDLE
                statusText?.text = "No audio captured"
                updateUi()
            }
            return
        }

        serviceScope.launch(Dispatchers.Default) {
            try {
                audioFile?.let { WavWriter.writeMono16BitPcm(it, audioSamples, AudioSampler.SAMPLE_RATE) }
                Log.d(TAG, "WAV written, starting Whisper pipeline")
                val transcript = getWhisperFeature().run(audioSamples)
                Log.d(TAG, "Whisper result: '$transcript'")

                withContext(Dispatchers.Main) {
                    if (activeSession != sessionId.get()) return@withContext
                    lastTranscript = transcript
                    if (transcript.isNotBlank()) {
                        currentInputConnection?.commitText(transcript, 1)
                    } else {
                        lastTranscript = "No speech detected"
                    }
                    currentState = ImeState.IDLE
                    updateUi()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                withContext(Dispatchers.Main) {
                    if (activeSession != sessionId.get()) return@withContext
                    currentState = ImeState.IDLE
                    lastTranscript = "Error: ${e.message ?: "Transcription failed"}"
                    updateUi()
                }
            }
        }
    }

    private fun getWhisperFeature(): WhisperFeature {
        val existing = whisperFeature
        if (existing != null) return existing

        val created =
            WhisperFeature(
                applicationContext,
                BuildConfig.PERSONAL_KEY,
                BuildConfig.WHISPER_ENCODER_MODEL,
                BuildConfig.WHISPER_DECODER_MODEL,
            )
        whisperFeature = created
        return created
    }

    private fun startPlayback() {
        val file = audioFile ?: return
        if (!file.exists()) return

        mediaPlayer =
            MediaPlayer().apply {
                try {
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                    currentState = ImeState.PLAYING
                    updateUi()
                    setOnCompletionListener { stopPlayback() }
                } catch (e: IOException) {
                    statusText?.text = "Playback failed"
                    release()
                    mediaPlayer = null
                    currentState = ImeState.IDLE
                    updateUi()
                }
            }
    }

    private fun stopPlayback() {
        mediaPlayer?.apply {
            try {
                stop()
            } catch (_: Exception) {
            }
            release()
        }
        mediaPlayer = null
        if (currentState == ImeState.PLAYING) {
            currentState = ImeState.IDLE
            updateUi()
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateUi() {
        val status = statusText ?: return
        val transcript = debugTranscript
        val mic = micBubble ?: return
        val play = playBubble ?: return
        val playIconView = playIcon ?: return

        val hasRecording = audioFile?.exists() == true
        val transcriptText = lastTranscript.orEmpty()

        if (transcriptText.isBlank()) {
            transcript?.visibility = View.GONE
            transcript?.text = ""
        } else {
            transcript?.visibility = View.VISIBLE
            transcript?.text = transcriptText
        }

        when (currentState) {
            ImeState.IDLE -> {
                status.text = "Tap to speak"
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3"))
                play.visibility = if (hasRecording) View.VISIBLE else View.GONE
                playIconView.setImageResource(android.R.drawable.ic_media_play)
            }
            ImeState.RECORDING -> {
                status.text = "Listening..."
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
                play.visibility = View.GONE
            }
            ImeState.PROCESSING -> {
                status.text = "Processing..."
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
                play.visibility = View.GONE
            }
            ImeState.PLAYING -> {
                status.text = "Playing latest audio"
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3"))
                play.visibility = View.VISIBLE
                playIconView.setImageResource(android.R.drawable.ic_media_pause)
            }
            ImeState.DISABLED -> {
                status.text = "Voice input unavailable"
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#BDBDBD"))
                play.visibility = View.GONE
                transcript?.visibility = View.GONE
            }
        }
    }
}
