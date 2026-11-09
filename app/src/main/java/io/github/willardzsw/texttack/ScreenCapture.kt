package io.github.willardzsw.texttack

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.io.File
import java.util.concurrent.Executor

/**
 * 无障碍截屏（安卓 11+）。0.2.0 只用来验证能不能截、要多久、两次之间的最短间隔，
 * 截到的图存到 Android/data/<包名>/files/screenshots/last.png，方便用 adb 拉回电脑。
 */
class ScreenCapture(
    private val service: AccessibilityService,
    private val worker: Executor,
) {
    private var lastSuccessAt = 0L

    /** [onDone] 在后台线程回调，参数是给用户看的结果说明。 */
    fun capture(onDone: (String) -> Unit) {
        val start = SystemClock.elapsedRealtime()
        Log.d(TAG, "调用 takeScreenshot")
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            worker,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val tookMs = SystemClock.elapsedRealtime() - start
                    Log.d(TAG, "截屏成功回调 $tookMs ms")
                    val buffer = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.let { hw -> hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() } }
                    } finally {
                        buffer.close()
                    }
                    if (bitmap == null) {
                        onDone("截屏成功，但转换图片失败")
                        return
                    }

                    val sinceLast = if (lastSuccessAt == 0L) null else start - lastSuccessAt
                    lastSuccessAt = start

                    val width = bitmap.width
                    val height = bitmap.height
                    val saveStart = SystemClock.elapsedRealtime()
                    val file = save(bitmap)
                    val saveMs = SystemClock.elapsedRealtime() - saveStart
                    bitmap.recycle()

                    onDone(buildString {
                        append("截屏成功：${width}×${height}，用时 $tookMs ms")
                        if (sinceLast != null) append("，距上次 $sinceLast ms")
                        append(if (file != null) "\n已保存（$saveMs ms）" else "\n保存失败")
                    })
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "截屏失败回调 $errorCode")
                    onDone("截屏失败：${errorText(errorCode)}（错误码 $errorCode）")
                }
            },
        )
    }

    private fun save(bitmap: Bitmap): File? = runCatching {
        val dir = File(service.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val file = File(dir, "last.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file
    }.getOrNull()

    private fun errorText(code: Int) = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "系统内部错误"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "没有截屏权限"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "两次截屏间隔太短"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "屏幕无效"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "窗口无效"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前页面禁止截屏"
        else -> "未知错误"
    }
}
