package io.github.willardzsw.texttack

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.io.File
import java.util.concurrent.Executor

/** 无障碍截屏（安卓 11+），得到软件位图。 */
class ScreenCapture(
    private val service: AccessibilityService,
    private val worker: Executor,
) {
    class Shot(val bitmap: Bitmap, val tookMs: Long)

    /** 回调在 [worker] 线程：成功给位图，失败给中文原因。 */
    fun capture(onSuccess: (Shot) -> Unit, onFailure: (String) -> Unit) {
        service.takeScreenshot(Display.DEFAULT_DISPLAY, worker, callback(SystemClock.elapsedRealtime(), onSuccess, onFailure))
    }

    /**
     * 只截一个应用窗口（安卓 14+）：不包括我们自己的悬浮层，译文盖在原文上也不会被截进去。
     * 得到的图是窗口大小，左上角是窗口在屏幕上的位置。安卓 14 以下直接回调失败。
     */
    fun captureWindow(windowId: Int, onSuccess: (Shot) -> Unit, onFailure: (String) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            onFailure("系统版本低于安卓 14，不能只截应用窗口")
            return
        }
        service.takeScreenshotOfWindow(windowId, worker, callback(SystemClock.elapsedRealtime(), onSuccess, onFailure))
    }

    private fun callback(start: Long, onSuccess: (Shot) -> Unit, onFailure: (String) -> Unit) =
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val tookMs = SystemClock.elapsedRealtime() - start
                    val buffer = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.let { hw -> hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() } }
                    } finally {
                        buffer.close()
                    }
                    Log.d(TAG, "截屏 ${bitmap?.width}×${bitmap?.height}，$tookMs ms")
                    if (bitmap == null) onFailure("截屏成功，但转换图片失败") else onSuccess(Shot(bitmap, tookMs))
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "截屏失败 $errorCode")
                    onFailure("截屏失败：${errorText(errorCode)}（错误码 $errorCode）")
                }
            }

    /** 调试用：把最近一张截图存到 Android/data/<包名>/files/screenshots/last.png（覆盖）。 */
    fun saveForDebug(bitmap: Bitmap) {
        runCatching {
            val dir = File(service.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
            File(dir, "last.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

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
