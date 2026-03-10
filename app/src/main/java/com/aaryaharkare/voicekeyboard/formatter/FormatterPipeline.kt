package com.aaryaharkare.voicekeyboard.formatter

import android.content.Context
import com.aaryaharkare.voicekeyboard.BuildConfig

object FormatterPipeline {
    @Volatile
    private var formatterEngine: ZeticLlmFormatterEngine? = null

    fun get(context: Context): FormatterEngine {
        val existing = formatterEngine
        if (existing != null) return existing

        return synchronized(this) {
            val created =
                formatterEngine
                    ?: ZeticLlmFormatterEngine(
                        context = context.applicationContext,
                        personalKey = BuildConfig.PERSONAL_KEY,
                        formatterModel = BuildConfig.FORMATTER_LLM_MODEL,
                    ).also { formatterEngine = it }
            created
        }
    }

    fun release() {
        synchronized(this) {
            formatterEngine?.deinit()
            formatterEngine = null
        }
    }
}
