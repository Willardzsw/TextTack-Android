package io.github.willardzsw.texttack

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import io.github.willardzsw.texttack.ocr.OcrLanguage
import kotlin.math.roundToInt

/** 启动/设置页：无障碍服务状态、识别语言、开源许可。 */
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
