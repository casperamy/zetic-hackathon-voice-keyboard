package com.aaryaharkare.voicekeyboard

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aaryaharkare.voicekeyboard.audio.AudioSampler
import com.aaryaharkare.voicekeyboard.whisper.WhisperFeature
import com.aaryaharkare.voicekeyboard.whisper.WhisperPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

class VoiceInputMethodService : InputMethodService() {

    private enum class ImeState {
        IDLE,
        RECORDING,
        PROCESSING,
        DISABLED,
    }

    private var currentState = ImeState.IDLE
    private var statusText: TextView? = null
    private var debugTranscript: TextView? = null
    private var micBubble: FrameLayout? = null
    private var backspaceBubble: FrameLayout? = null
    private var backspaceRepeatJob: Job? = null

    private var audioSampler: AudioSampler? = null

    private var lastTranscript: String? = null
    private var lastPerfSummary: String? = null
    private var lastRawOutput: String? = null
    private var lastWhisperOutput: String? = null
    private var currentInputType: Int = 0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionId = AtomicLong(0L)

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        statusText = view.findViewById(R.id.statusText)
        debugTranscript = view.findViewById(R.id.debugTranscript)
        micBubble = view.findViewById(R.id.micBubble)
        backspaceBubble = view.findViewById(R.id.backspaceBubble)

        audioSampler = AudioSampler(::onAudioReady)
        micBubble?.setOnClickListener { handleMicClick() }
        backspaceBubble?.setOnClickListener { handleBackspaceClick() }
        backspaceBubble?.setOnTouchListener { view, event -> handleBackspaceTouch(view, event) }

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
        stopBackspaceRepeater()
        if (currentState != ImeState.DISABLED) {
            currentState = ImeState.IDLE
        }
        updateUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopRecording()
        stopBackspaceRepeater()
        audioSampler?.release()
    }

    private fun resetToInitialState(info: EditorInfo?) {
        stopRecording()
        currentInputType = info?.inputType ?: 0
        lastRawOutput = null
        lastWhisperOutput = null
        currentState =
            if (isPasswordField(currentInputType)) {
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
            ImeState.IDLE -> startRecording()
            ImeState.RECORDING -> {
                currentState = ImeState.PROCESSING
                updateUi()
                stopRecording()
            }
            ImeState.PROCESSING, ImeState.DISABLED -> Unit
        }
    }

    private fun handleBackspaceClick(): Boolean {
        val inputConnection = currentInputConnection ?: return false
        val deleted = inputConnection.deleteSurroundingText(1, 0)
        if (!deleted) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        return true
    }

    private fun handleBackspaceTouch(
        view: View,
        event: MotionEvent,
    ): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.isPressed = true
                handleBackspaceClick()
                startBackspaceRepeater()
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                stopBackspaceRepeater()
                true
            }
            else -> false
        }
    }

    private fun startBackspaceRepeater() {
        stopBackspaceRepeater()
        backspaceRepeatJob =
            serviceScope.launch {
                delay(BACKSPACE_HOLD_DELAY_MS)
                var repeatStep = 0
                while (true) {
                    if (!handleBackspaceClick()) {
                        break
                    }
                    delay(nextBackspaceIntervalMs(repeatStep))
                    repeatStep += 1
                }
            }
    }

    private fun stopBackspaceRepeater() {
        backspaceRepeatJob?.cancel()
        backspaceRepeatJob = null
    }

    private fun nextBackspaceIntervalMs(repeatStep: Int): Long {
        val step = repeatStep.toLong()
        val reduction =
            (BACKSPACE_LINEAR_ACCELERATION_MS * step) +
                (BACKSPACE_QUADRATIC_ACCELERATION_MS * step * step)
        return (BACKSPACE_START_INTERVAL_MS - reduction).coerceAtLeast(BACKSPACE_MIN_INTERVAL_MS)
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

        if (audioSamples.isEmpty()) {
            serviceScope.launch {
                if (activeSession != sessionId.get()) return@launch
                currentState = ImeState.IDLE
                lastTranscript = "No audio captured"
                lastPerfSummary = null
                lastRawOutput = null
                lastWhisperOutput = null
                updateUi()
            }
            return
        }

        if (audioSamples.size < MIN_PROCESS_SAMPLES || isLikelySilence(audioSamples)) {
            serviceScope.launch {
                if (activeSession != sessionId.get()) return@launch
                currentState = ImeState.IDLE
                lastTranscript = "No speech detected"
                lastPerfSummary = "perf skipped (silence)"
                lastRawOutput = null
                lastWhisperOutput = null
                updateUi()
            }
            return
        }

        serviceScope.launch(Dispatchers.Default) {
            try {
                val runResult = WhisperPipeline.get(applicationContext).runWithMetrics(audioSamples)
                val rawOutput = runResult.transcript
                val whisperOutput = rawOutput.trim()

                withContext(Dispatchers.Main) {
                    if (activeSession != sessionId.get()) return@withContext

                    val commitStarted = System.nanoTime()
                    lastRawOutput = rawOutput
                    lastWhisperOutput = whisperOutput
                    if (whisperOutput.isNotBlank()) {
                        currentInputConnection?.commitText(whisperOutput, 1)
                        lastTranscript = whisperOutput
                        logWhisperTrace(
                            rawOutput = rawOutput,
                            whisperOutput = whisperOutput,
                        )
                    } else {
                        lastTranscript = "No speech detected"
                    }
                    val commitMs = nanosToMillis(System.nanoTime() - commitStarted)

                    lastPerfSummary =
                        "perf a->f ${runResult.metrics.audioReadyToFeatureExtractionMs}ms " +
                            "f->e ${runResult.metrics.featureExtractionToEncoderMs}ms " +
                            "d ${runResult.metrics.decoderTotalMs}ms/${runResult.metrics.decoderSteps} " +
                            "c ${commitMs}ms t ${runResult.metrics.totalPipelineMs}ms"

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, lastPerfSummary.orEmpty())
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
                    lastPerfSummary = null
                    lastRawOutput = null
                    lastWhisperOutput = null
                    updateUi()
                }
            }
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLikelySilence(audioSamples: FloatArray): Boolean {
        var sumSquares = 0.0
        var count = 0

        var i = 0
        while (i < audioSamples.size) {
            val s = audioSamples[i].toDouble()
            sumSquares += s * s
            count += 1
            i += SILENCE_CHECK_STRIDE
        }

        if (count == 0) return true
        val rms = sqrt(sumSquares / count)
        return rms < SILENCE_RMS_THRESHOLD
    }

    private fun updateUi() {
        val status = statusText ?: return
        val transcript = debugTranscript
        val mic = micBubble ?: return

        val debugText = buildString {
            val transcriptText = lastTranscript.orEmpty().trim()
            val perfText = lastPerfSummary.orEmpty().trim()
            val rawText = if (BuildConfig.DEBUG) lastRawOutput.orEmpty().trim() else ""
            val whisperText = if (BuildConfig.DEBUG) lastWhisperOutput.orEmpty().trim() else ""

            if (BuildConfig.DEBUG) {
                if (rawText.isNotBlank()) {
                    append("raw: ").append(rawText)
                }
                if (whisperText.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append("whisper: ").append(whisperText)
                }
                if (isEmpty() && transcriptText.isNotBlank()) {
                    append(transcriptText)
                }
            } else if (transcriptText.isNotBlank()) {
                append(transcriptText)
            }
            if (perfText.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(perfText)
            }
        }

        if (debugText.isBlank()) {
            transcript?.visibility = View.GONE
            transcript?.text = ""
        } else {
            transcript?.visibility = View.VISIBLE
            transcript?.text = debugText
        }

        when (currentState) {
            ImeState.IDLE -> {
                status.text = "Tap to speak"
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#2196F3"))
            }
            ImeState.RECORDING -> {
                status.text = "Listening..."
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
            }
            ImeState.PROCESSING -> {
                status.text = "Processing..."
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
            }
            ImeState.DISABLED -> {
                status.text = "Voice input unavailable"
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#BDBDBD"))
                transcript?.visibility = View.GONE
            }
        }
    }

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

    private fun logWhisperTrace(
        rawOutput: String,
        whisperOutput: String,
    ) {
        if (!BuildConfig.DEBUG) return

        Log.d(TAG, "whisper_trace")
        logLong(TAG, "whisper_trace raw", rawOutput)
        logLong(TAG, "whisper_trace final_commit", whisperOutput)
    }

    private fun logLong(
        tag: String,
        prefix: String,
        text: String,
    ) {
        val payload = text.trim()
        if (payload.isEmpty()) {
            Log.d(tag, "$prefix: <empty>")
            return
        }

        var start = 0
        var part = 1
        while (start < payload.length) {
            val end = (start + LOG_CHUNK_SIZE).coerceAtMost(payload.length)
            Log.d(tag, "$prefix[$part]: ${payload.substring(start, end)}")
            start = end
            part += 1
        }
    }

    companion object {
        private const val TAG = "VoiceKB"
        private const val MIN_PROCESS_SAMPLES = WhisperFeature.MIN_AUDIO_SAMPLES
        private const val SILENCE_CHECK_STRIDE = 2
        private const val SILENCE_RMS_THRESHOLD = 0.0085
        private const val LOG_CHUNK_SIZE = 3200
        private const val BACKSPACE_HOLD_DELAY_MS = 320L
        private const val BACKSPACE_START_INTERVAL_MS = 170L
        private const val BACKSPACE_LINEAR_ACCELERATION_MS = 4L
        private const val BACKSPACE_QUADRATIC_ACCELERATION_MS = 2L
        private const val BACKSPACE_MIN_INTERVAL_MS = 24L
    }
}
