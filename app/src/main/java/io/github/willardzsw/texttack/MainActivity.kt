package io.github.willardzsw.texttack

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

/** 启动/设置页：显示无障碍服务状态，跳转到系统设置。 */
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
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.status).setText(
            if (isServiceEnabled()) R.string.status_enabled else R.string.status_disabled
        )
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
