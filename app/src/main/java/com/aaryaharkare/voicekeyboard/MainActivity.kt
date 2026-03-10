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
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zeticai.mlange.core.model.ModelMode
import com.zeticai.mlange.core.model.Target
import com.zeticai.mlange.core.model.ZeticMLangeModel

class MainActivity : AppCompatActivity() {

    private lateinit var statusEnabled: TextView
    private lateinit var statusSelected: TextView
    private lateinit var statusPermission: TextView
    private lateinit var statusModel: TextView

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

        findViewById<Button>(R.id.btnLoadModel).setOnClickListener {
            preloadModels()
        }

        statusModel.text = "Models: Not Loaded"
    }

    private fun preloadModels() {
        statusModel.text = "Loading Whisper Models..."
        
        Thread {
            try {
                // Aligned with official documentation for version 1.5.7
                // Use the keys from local.properties (via BuildConfig)
                val encoder = ZeticMLangeModel(
                    this, 
                    BuildConfig.PERSONAL_KEY, 
                    BuildConfig.WHISPER_ENCODER_MODEL, 
                    target = Target.ORT
                )
                encoder.close()
                
                val decoder = ZeticMLangeModel(
                    this, 
                    BuildConfig.PERSONAL_KEY, 
                    BuildConfig.WHISPER_DECODER_MODEL, 
                    target = Target.ORT
                )
                decoder.close()
                
                runOnUiThread {
                    statusModel.text = "Whisper Models Ready"
                    statusModel.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                    Toast.makeText(this, "Models Preloaded Successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusModel.text = "Model Load Failed: ${e.message}"
                    statusModel.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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
}
