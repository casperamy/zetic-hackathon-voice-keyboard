package com.aaryaharkare.voicekeyboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.aaryaharkare.voicekeyboard.formatter.FormatterLlmPipeline
import com.zeticai.mlange.core.model.ModelLoadingStatus
import com.aaryaharkare.voicekeyboard.whisper.WhisperPipeline
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {
    private lateinit var statusEnabled: TextView
    private lateinit var statusSelected: TextView
    private lateinit var statusPermission: TextView
    private lateinit var statusModel: TextView
    private lateinit var statusFormatter: TextView

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusEnabled = findViewById(R.id.statusEnabled)
        statusSelected = findViewById(R.id.statusSelected)
        statusPermission = findViewById(R.id.statusPermission)
        statusModel = findViewById(R.id.statusModel)
        statusFormatter = findViewById(R.id.statusFormatter)

        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnSelect).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        findViewById<Button>(R.id.btnPermission).setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        startAutoPreloadIfNeeded()
    }

    private fun startAutoPreloadIfNeeded() {
        if (FormatterLlmPipeline.snapshot().ready && WhisperPipeline.isWarmupDone()) {
            return
        }

        val shouldStart =
            synchronized(PRELOAD_LOCK) {
                if (autoPreloadInFlight) {
                    false
                } else {
                    autoPreloadInFlight = true
                    true
                }
            }
        if (!shouldStart) {
            return
        }

        Thread {
            try {
                if (!FormatterLlmPipeline.snapshot().ready) {
                    runOnUiThread { showFormatterLoadingStatus() }
                    try {
                        val warmupResult =
                            runBlocking {
                                FormatterLlmPipeline.preloadAndWarm(applicationContext)
                            }
                        runOnUiThread {
                            applyFormatterStatus(warmupMs = warmupResult.generationMs)
                        }
                    } catch (t: Throwable) {
                        runOnUiThread {
                            applyFormatterStatus(errorMessage = t.message)
                        }
                    }
                }

                if (!WhisperPipeline.isWarmupDone()) {
                    runOnUiThread { showModelLoadingStatus() }
                    try {
                        val warmupMetrics =
                            runBlocking {
                                WhisperPipeline.preloadAndWarm(applicationContext)
                            }
                        runOnUiThread {
                            applyModelStatus(whisperWarmupMs = warmupMetrics.totalPipelineMs)
                        }
                    } catch (t: Throwable) {
                        runOnUiThread {
                            applyModelStatus(errorMessage = t.message)
                        }
                    }
                }
            } finally {
                synchronized(PRELOAD_LOCK) {
                    autoPreloadInFlight = false
                }
                runOnUiThread { updateStatus() }
            }
        }.start()
    }

    private fun updateStatus() {
        val isEnabled = isInputMethodEnabled()
        val isSelected = isInputMethodSelected()
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        statusEnabled.text = "Keyboard Enabled: ${if (isEnabled) "YES" else "NO"}"
        statusSelected.text = "Keyboard Selected: ${if (isSelected) "YES" else "NO"}"
        statusPermission.text = "Mic Permission: ${if (hasPermission) "GRANTED" else "NOT GRANTED"}"

        applyModelStatus()
        applyFormatterStatus()
    }

    private fun applyModelStatus(
        whisperWarmupMs: Long? = null,
        errorMessage: String? = null,
    ) {
        val snapshot = WhisperPipeline.snapshot()
        val text =
            when {
                errorMessage != null -> "Whisper: error (${errorMessage})"
                snapshot.lastErrorMessage != null -> "Whisper: error (${snapshot.lastErrorMessage})"
                whisperWarmupMs != null -> "Whisper: ready (${whisperWarmupMs}ms)"
                snapshot.warmupDone -> "Whisper: ready"
                snapshot.loading -> "Whisper: loading..."
                else -> "Whisper: not loaded"
            }

        statusModel.text = text
        statusModel.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    text.contains("error") -> android.R.color.holo_red_dark
                    text.contains("ready") -> android.R.color.holo_green_dark
                    text.contains("loading") -> android.R.color.holo_blue_dark
                    else -> android.R.color.darker_gray
                },
            ),
        )
    }

    private fun applyFormatterStatus(
        warmupMs: Long? = null,
        errorMessage: String? = null,
    ) {
        val snapshot = FormatterLlmPipeline.snapshot()
        val text =
            when {
                errorMessage != null -> "Formatter LLM: error (${errorMessage})"
                snapshot.lastErrorMessage != null -> "Formatter LLM: error (${snapshot.lastErrorMessage})"
                warmupMs != null -> "Formatter LLM: ready (${warmupMs}ms)"
                snapshot.ready -> "Formatter LLM: ready"
                snapshot.loadingStatus in LOADING_STATUSES -> {
                    val progressPercent = (snapshot.loadingProgress * 100).toInt().coerceIn(0, 100)
                    "Formatter LLM: loading (${progressPercent}%)"
                }
                snapshot.loadingStatus in ERROR_STATUSES -> "Formatter LLM: error (${snapshot.loadingStatus.name.lowercase()})"
                else -> "Formatter LLM: not loaded"
            }

        statusFormatter.text = text
        statusFormatter.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    text.contains("error") -> android.R.color.holo_red_dark
                    text.contains("ready") -> android.R.color.holo_green_dark
                    text.contains("loading") -> android.R.color.holo_blue_dark
                    else -> android.R.color.darker_gray
                },
            ),
        )
    }

    private fun showModelLoadingStatus() {
        statusModel.text = "Whisper: loading..."
        statusModel.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
    }

    private fun showFormatterLoadingStatus() {
        statusFormatter.text = "Formatter LLM: loading..."
        statusFormatter.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
    }

    private fun isInputMethodEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val enabledMethods = imm.enabledInputMethodList
        return enabledMethods.any { it.packageName == packageName }
    }

    private fun isInputMethodSelected(): Boolean {
        val currentMethodId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        return currentMethodId?.contains(packageName) == true
    }

    private companion object {
        private val PRELOAD_LOCK = Any()
        @Volatile
        private var autoPreloadInFlight = false

        private val LOADING_STATUSES =
            setOf(
                ModelLoadingStatus.PENDING,
                ModelLoadingStatus.DOWNLOADING,
                ModelLoadingStatus.TRANSFERRING,
                ModelLoadingStatus.WAITING_FOR_WIFI,
                ModelLoadingStatus.REQUIRES_USER_CONFIRMATION,
            )

        private val ERROR_STATUSES =
            setOf(
                ModelLoadingStatus.FAILED,
                ModelLoadingStatus.CANCELED,
                ModelLoadingStatus.NOT_INSTALLED,
            )
    }
}
