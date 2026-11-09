package io.github.willardzsw.texttack

import android.content.Context
import io.github.willardzsw.texttack.ocr.OcrLanguage
import io.github.willardzsw.texttack.translate.TargetLanguage
import io.github.willardzsw.texttack.translate.TranslationProvider

/** 应用设置（SharedPreferences）。 */
object AppSettings {
    private const val FILE = "settings"
    private const val KEY_OCR_LANGUAGE = "ocr_language"
    private const val KEY_PROVIDER = "translation_provider"
    private const val KEY_TARGET = "target_language"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun ocrLanguage(context: Context): String =
        prefs(context).getString(KEY_OCR_LANGUAGE, OcrLanguage.THAI.key) ?: OcrLanguage.THAI.key

    fun setOcrLanguage(context: Context, language: OcrLanguage) {
        prefs(context).edit().putString(KEY_OCR_LANGUAGE, language.key).apply()
    }

    fun translationProvider(context: Context): String =
        prefs(context).getString(KEY_PROVIDER, TranslationProvider.GOOGLE.key) ?: TranslationProvider.GOOGLE.key

    fun setTranslationProvider(context: Context, provider: TranslationProvider) {
        prefs(context).edit().putString(KEY_PROVIDER, provider.key).apply()
    }

    fun targetLanguage(context: Context): String =
        prefs(context).getString(KEY_TARGET, TargetLanguage.SIMPLIFIED_CHINESE.key) ?: TargetLanguage.SIMPLIFIED_CHINESE.key

    fun setTargetLanguage(context: Context, language: TargetLanguage) {
        prefs(context).edit().putString(KEY_TARGET, language.key).apply()
    }
}
