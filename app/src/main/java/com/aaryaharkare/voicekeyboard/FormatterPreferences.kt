package com.aaryaharkare.voicekeyboard

import android.content.Context

object FormatterPreferences {
    private const val PREFS_NAME = "voice_keyboard_prefs"
    private const val KEY_FORMATTER_ENABLED = "formatter_enabled"

    fun isFormatterEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORMATTER_ENABLED, false)
    }

    fun setFormatterEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORMATTER_ENABLED, enabled)
            .apply()
    }
}
