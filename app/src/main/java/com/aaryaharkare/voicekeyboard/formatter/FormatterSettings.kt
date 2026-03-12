package com.aaryaharkare.voicekeyboard.formatter

import android.content.Context

interface FormatterModeStore {
    fun read(): String?

    fun write(value: String)
}

class FormatterSettings(
    private val store: FormatterModeStore,
) {
    fun getMode(): FormatterMode = FormatterMode.fromStorageValue(store.read())

    fun setMode(mode: FormatterMode) {
        store.write(mode.toStorageValue())
    }

    companion object {
        fun fromContext(context: Context): FormatterSettings {
            return FormatterSettings(SharedPreferencesFormatterModeStore(context.applicationContext))
        }
    }
}

private class SharedPreferencesFormatterModeStore(
    context: Context,
) : FormatterModeStore {
    private val sharedPreferences =
        context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE)

    override fun read(): String? = sharedPreferences.getString(KEY_MODE, null)

    override fun write(value: String) {
        sharedPreferences.edit().putString(KEY_MODE, value).apply()
    }

    private companion object {
        private const val PREFERENCE_FILE = "formatter_settings"
        private const val KEY_MODE = "formatter_mode"
    }
}
