package io.github.willardzsw.texttack

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import io.github.willardzsw.texttack.ocr.OcrLanguage
import io.github.willardzsw.texttack.translate.TargetLanguage
import io.github.willardzsw.texttack.translate.TranslationProvider
import kotlin.math.roundToInt

/** 启动/设置页：无障碍服务状态、翻译服务、目标语言、识别语言、开源许可。 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.version).text =
            getString(R.string.version_format, BuildConfig.VERSION_NAME)

        findViewById<Button>(R.id.open_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.open_app_settings).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }

        val languageGroup = findViewById<RadioGroup>(R.id.ocr_language)
        languageGroup.check(
            when (OcrLanguage.fromKey(AppSettings.ocrLanguage(this))) {
                OcrLanguage.THAI -> R.id.ocr_language_th
                OcrLanguage.CJK -> R.id.ocr_language_cjk
            }
        )
        languageGroup.setOnCheckedChangeListener { _, id ->
            AppSettings.setOcrLanguage(this, if (id == R.id.ocr_language_cjk) OcrLanguage.CJK else OcrLanguage.THAI)
        }

        val providerGroup = findViewById<RadioGroup>(R.id.provider)
        providerGroup.check(
            when (TranslationProvider.fromKey(AppSettings.translationProvider(this))) {
                TranslationProvider.GOOGLE -> R.id.provider_google
                TranslationProvider.OPENAI -> R.id.provider_openai
            }
        )
        providerGroup.setOnCheckedChangeListener { _, id ->
            AppSettings.setTranslationProvider(this, if (id == R.id.provider_openai) TranslationProvider.OPENAI else TranslationProvider.GOOGLE)
        }
        findViewById<Button>(R.id.open_openai_settings).setOnClickListener {
            startActivity(Intent(this, OpenAiSettingsActivity::class.java))
        }

        val targets = TargetLanguage.entries
        val spinner = findViewById<Spinner>(R.id.target_language)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, targets.map { it.label })
        spinner.setSelection(targets.indexOf(TargetLanguage.fromKey(AppSettings.targetLanguage(this))))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                AppSettings.setTargetLanguage(this@MainActivity, targets[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.open_licenses).setOnClickListener { showLicenses() }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.status).setText(
            if (isServiceEnabled()) R.string.status_enabled else R.string.status_disabled
        )
    }

    private fun showLicenses() {
        val text = assets.open("licenses/THIRD-PARTY-NOTICES.txt").use { it.readBytes().toString(Charsets.UTF_8) }
        val pad = (16 * resources.displayMetrics.density).roundToInt()
        val content = TextView(this).apply {
            this.text = text
            textSize = 12f
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.licenses_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, TranslateAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == expected
        }
    }
}
