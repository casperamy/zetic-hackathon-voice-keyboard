package com.aaryaharkare.voicekeyboard

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aaryaharkare.voicekeyboard.audio.AudioSampler
import com.aaryaharkare.voicekeyboard.formatter.FormatterLlmPipeline
import com.aaryaharkare.voicekeyboard.formatter.FormatterResult
import com.aaryaharkare.voicekeyboard.formatter.ZeticLlmFormatter
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
        PROCESSING_STT,
        PROCESSING_FORMAT,
        COMMITTING,
        ERROR,
        DISABLED_FOR_FIELD,
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
    private var lastFormattedOutput: String? = null
    private var lastErrorMessage: String? = null
    private var currentInputType: Int = 0
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sessionId = AtomicLong(0L)
    private val zeticLlmFormatter by lazy { ZeticLlmFormatter(applicationContext) }

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
        if (currentState != ImeState.DISABLED_FOR_FIELD) {
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
        lastTranscript = null
        lastPerfSummary = null
        lastRawOutput = null
        lastWhisperOutput = null
        lastFormattedOutput = null
        lastErrorMessage = null
        currentState =
            if (isPasswordField(currentInputType)) {
                ImeState.DISABLED_FOR_FIELD
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
        if (
            currentState == ImeState.DISABLED_FOR_FIELD ||
            currentState == ImeState.PROCESSING_STT ||
            currentState == ImeState.PROCESSING_FORMAT ||
            currentState == ImeState.COMMITTING
        ) {
            return
        }

        if (!hasMicPermission()) {
            statusText?.text = "Missing Mic Permission"
            return
        }

        val blockingReason = currentModelBlockingReason()
        if (blockingReason != null) {
            currentState = ImeState.IDLE
            updateUi()
            return
        }

        when (currentState) {
            ImeState.IDLE, ImeState.ERROR -> startRecording()
            ImeState.RECORDING -> {
                currentState = ImeState.PROCESSING_STT
                updateUi()
                stopRecording()
            }
            ImeState.PROCESSING_STT,
            ImeState.PROCESSING_FORMAT,
            ImeState.COMMITTING,
            ImeState.DISABLED_FOR_FIELD,
            -> Unit
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
            lastErrorMessage = null
            currentState = ImeState.RECORDING
            updateUi()
            audioSampler?.startRecording()
        } catch (e: Exception) {
            currentState = ImeState.ERROR
            lastErrorMessage = "Recording failed"
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
                lastFormattedOutput = null
                lastErrorMessage = null
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
                lastFormattedOutput = null
                lastErrorMessage = null
                updateUi()
            }
            return
        }

        serviceScope.launch(Dispatchers.Default) {
            var rawOutput = ""
            var whisperOutput = ""
            try {
                val runResult = WhisperPipeline.get(applicationContext).runWithMetrics(audioSamples)
                rawOutput = runResult.transcript
                whisperOutput = rawOutput.trim()

                withContext(Dispatchers.Main) {
                    if (activeSession != sessionId.get()) return@withContext
                    currentState = ImeState.PROCESSING_FORMAT
                    updateUi()
                }

                val formattingStartedAt = System.nanoTime()
                val formattingExecution = formatForCurrentField(whisperOutput)
                val formatterMs = nanosToMillis(System.nanoTime() - formattingStartedAt)

                withContext(Dispatchers.Main) {
                    if (activeSession != sessionId.get()) return@withContext

                    currentState = ImeState.COMMITTING
                    updateUi()

                    val commitStarted = System.nanoTime()
                    lastRawOutput = rawOutput
                    lastWhisperOutput = whisperOutput
                    val formattedOutput = formattingExecution.formattedOutput
                    lastFormattedOutput = formattedOutput
                    lastErrorMessage = null

                    if (formattedOutput.isNotBlank()) {
                        currentInputConnection?.commitText(formattedOutput, 1)
                        lastTranscript = formattedOutput
                        logWhisperTrace(
                            rawOutput = rawOutput,
                            whisperOutput = whisperOutput,
                            formattingExecution = formattingExecution,
                        )
                    } else {
                        lastTranscript = "No speech detected"
                    }
                    val commitMs = nanosToMillis(System.nanoTime() - commitStarted)

                    lastPerfSummary =
                        "perf trim ${runResult.metrics.trimmedAudioMs}ms " +
                            "a->f ${runResult.metrics.audioReadyToFeatureExtractionMs}ms " +
                            "f->e ${runResult.metrics.featureExtractionToEncoderMs}ms " +
                            "d ${runResult.metrics.decoderTotalMs}ms/${runResult.metrics.decoderSteps}/${runResult.metrics.decoderCap}/${runResult.metrics.decoderStopReason.name.lowercase()} " +
                            "fmt ${formattingExecution.modeLabel()}/${formatterMs}ms/${formattingExecution.generatedTokensLabel()} " +
                            "c ${commitMs}ms t ${runResult.metrics.totalPipelineMs}ms"

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, lastPerfSummary.orEmpty())
                    }

                    currentState = ImeState.IDLE
                    updateUi()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice processing failed", e)
                withContext(Dispatchers.Main) {
                    if (activeSession != sessionId.get()) return@withContext
                    currentState = ImeState.ERROR
                    lastErrorMessage = e.message ?: "Processing failed"
                    lastTranscript = "Error: ${e.message ?: "Processing failed"}"
                    lastPerfSummary = null
                    lastRawOutput = rawOutput.takeIf { it.isNotBlank() }
                    lastWhisperOutput = whisperOutput.takeIf { it.isNotBlank() }
                    lastFormattedOutput = null
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

    private fun shouldApplyFormatting(inputType: Int): Boolean {
        val maskClass = inputType and InputType.TYPE_MASK_CLASS
        val maskVariation = inputType and InputType.TYPE_MASK_VARIATION

        return when (maskClass) {
            InputType.TYPE_CLASS_TEXT -> {
                maskVariation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
                    maskVariation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS &&
                    maskVariation != InputType.TYPE_TEXT_VARIATION_URI &&
                    maskVariation != InputType.TYPE_TEXT_VARIATION_FILTER
            }
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> false
            else -> false
        }
    }

    private fun currentModelBlockingReason(): String? {
        val whisperSnapshot = WhisperPipeline.snapshot()
        if (!whisperSnapshot.warmupDone) {
            return when {
                whisperSnapshot.loading -> "Whisper loading..."
                whisperSnapshot.lastErrorMessage != null -> "Whisper error"
                else -> "Open setup app to auto-load Whisper"
            }
        }

        if (!shouldApplyFormatting(currentInputType)) {
            return null
        }

        val snapshot = FormatterLlmPipeline.snapshot()
        return when {
            snapshot.ready -> null
            snapshot.lastErrorMessage != null -> "Formatter LLM error"
            snapshot.loadingStatus in FORMATTER_LOADING_STATUSES -> "Formatter LLM loading..."
            else -> "Open setup app to auto-load Formatter LLM"
        }
    }

    private fun updateUi() {
        val status = statusText ?: return
        val transcript = debugTranscript
        val mic = micBubble ?: return
        val modelBlockingReason = currentModelBlockingReason()

        val debugText = buildString {
            val transcriptText = lastTranscript.orEmpty().trim()
            val perfText = lastPerfSummary.orEmpty().trim()
            val rawText = if (BuildConfig.DEBUG) lastRawOutput.orEmpty().trim() else ""
            val whisperText = if (BuildConfig.DEBUG) lastWhisperOutput.orEmpty().trim() else ""
            val formattedText = if (BuildConfig.DEBUG) lastFormattedOutput.orEmpty().trim() else ""
            val errorText = lastErrorMessage.orEmpty().trim()

            if (BuildConfig.DEBUG) {
                if (rawText.isNotBlank()) {
                    append("raw: ").append(rawText)
                }
                if (whisperText.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append("whisper: ").append(whisperText)
                }
                if (formattedText.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append("formatted: ").append(formattedText)
                }
                if (isEmpty() && transcriptText.isNotBlank()) {
                    append(transcriptText)
                } else if (isEmpty() && errorText.isNotBlank()) {
                    append(errorText)
                }
            } else if (transcriptText.isNotBlank()) {
                append(transcriptText)
            } else if (errorText.isNotBlank()) {
                append(errorText)
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
                status.text = modelBlockingReason ?: "Tap to speak"
                mic.backgroundTintList =
                    ColorStateList.valueOf(
                        Color.parseColor(if (modelBlockingReason == null) "#2196F3" else "#BDBDBD"),
                    )
            }
            ImeState.RECORDING -> {
                status.text = "Listening..."
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
            }
            ImeState.PROCESSING_STT -> {
                status.text = "Transcribing..."
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
            }
            ImeState.PROCESSING_FORMAT -> {
                status.text = "Formatting..."
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
            }
            ImeState.COMMITTING -> {
                status.text = "Committing..."
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            }
            ImeState.ERROR -> {
                status.text = lastErrorMessage ?: "Voice input failed"
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
            }
            ImeState.DISABLED_FOR_FIELD -> {
                status.text = "Voice input unavailable"
                mic.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#BDBDBD"))
                transcript?.visibility = View.GONE
            }
        }
    }

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

    private suspend fun formatForCurrentField(text: String): FormattingExecutionResult {
        if (!shouldApplyFormatting(currentInputType)) {
            return FormattingExecutionResult(formattedOutput = text, formatterResult = null)
        }

        val formatterResult = zeticLlmFormatter.format(text)
        return FormattingExecutionResult(
            formattedOutput = formatterResult.formattedText,
            formatterResult = formatterResult,
        )
    }

    private fun logWhisperTrace(
        rawOutput: String,
        whisperOutput: String,
        formattingExecution: FormattingExecutionResult,
    ) {
        if (!BuildConfig.DEBUG) return

        Log.d(TAG, "whisper_trace")
        logLong(TAG, "whisper_trace raw", rawOutput)
        logLong(TAG, "whisper_trace whisper", whisperOutput)
        logLong(TAG, "whisper_trace final_commit", formattingExecution.formattedOutput)
        logLong(TAG, "formatter_trace summary", formattingExecution.traceSummary())
        formattingExecution.formatterResult?.debugTrace?.forEachIndexed { index, line ->
            logLong(TAG, "formatter_trace ${index + 1}", line)
        }
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
        private val FORMATTER_LOADING_STATUSES =
            setOf(
                com.zeticai.mlange.core.model.ModelLoadingStatus.PENDING,
                com.zeticai.mlange.core.model.ModelLoadingStatus.DOWNLOADING,
                com.zeticai.mlange.core.model.ModelLoadingStatus.TRANSFERRING,
                com.zeticai.mlange.core.model.ModelLoadingStatus.WAITING_FOR_WIFI,
                com.zeticai.mlange.core.model.ModelLoadingStatus.REQUIRES_USER_CONFIRMATION,
            )
    }

    private data class FormattingExecutionResult(
        val formattedOutput: String,
        val formatterResult: FormatterResult?,
    ) {
        fun modeLabel(): String = formatterResult?.mode?.name?.lowercase() ?: "bypass"

        fun generatedTokensLabel(): String = formatterResult?.generatedTokens?.toString() ?: "-"

        fun traceSummary(): String {
            val result = formatterResult ?: return "mode=bypass"
            return buildString {
                append("mode=").append(result.mode.name.lowercase())
                result.modelKey?.let {
                    append(" model_key=").append(it)
                }
                result.promptTokens?.let {
                    append(" prompt_tokens=").append(it)
                }
                result.generatedTokens?.let {
                    append(" generated_tokens=").append(it)
                }
                result.generationMs?.let {
                    append(" generation_ms=").append(it)
                }
            }
        }
    }
}
