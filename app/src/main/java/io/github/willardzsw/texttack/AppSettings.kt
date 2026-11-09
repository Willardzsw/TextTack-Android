package io.github.willardzsw.texttack

import android.content.Context
import io.github.willardzsw.texttack.ocr.OcrLanguage

/** 应用设置（SharedPreferences）。 */
object AppSettings {
    private const val FILE = "settings"
    private const val KEY_OCR_LANGUAGE = "ocr_language"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun ocrLanguage(context: Context): String =
        prefs(context).getString(KEY_OCR_LANGUAGE, OcrLanguage.THAI.key) ?: OcrLanguage.THAI.key

    fun setOcrLanguage(context: Context, language: OcrLanguage) {
        prefs(context).edit().putString(KEY_OCR_LANGUAGE, language.key).apply()
    }
}
