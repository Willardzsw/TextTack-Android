package io.github.willardzsw.texttack

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 贴译的核心服务。
 *
 * 0.2.0：验证系统能力
 * - 悬浮按钮（无障碍悬浮层）
 * - 点按钮：读取界面节点文字，在原位置画框显示原文
 * - 长按按钮：无障碍截屏并保存，提示尺寸和耗时
 */
class TranslateAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var worker: ExecutorService
    private lateinit var windowManager: WindowManager
    private var button: FloatingButton? = null
    private var overlay: ResultOverlay? = null
    private var capture: ScreenCapture? = null
    private var messageBar: MessageBar? = null
    @Volatile private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        worker = Executors.newSingleThreadExecutor()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = ResultOverlay(this, windowManager)
        capture = ScreenCapture(this, worker)
        messageBar = MessageBar(this, windowManager)
        button = FloatingButton(this, windowManager, ::readScreenText, ::takeTestScreenshot).also { it.show() }
    }

    private fun readScreenText() {
        if (overlay?.isShowing == true) {
            overlay?.dismiss()
            return
        }
        if (busy) return
        busy = true
        worker.execute {
            val start = SystemClock.elapsedRealtime()
            val blocks = runCatching { NodeTextReader.read(this) }
            val tookMs = SystemClock.elapsedRealtime() - start
            mainHandler.post {
                busy = false
                blocks.onSuccess { list ->
                    if (list.isEmpty()) {
                        toast("没有读到文字（$tookMs ms）。以后这种情况会改用截屏识别。")
                    } else {
                        overlay?.show(list)
                        val described = list.count { it.fromDescription }
                        toast("读到 ${list.size} 段文字（其中说明文字 $described 段），用时 $tookMs ms")
                    }
                }.onFailure { toast("读取失败：${it.message}") }
            }
        }
    }

    private fun takeTestScreenshot() {
        Log.d(TAG, "长按处理 busy=$busy")
        if (busy) return
        busy = true
        overlay?.dismiss()
        button?.setVisible(false)
        // 等按钮从屏幕上消失后再截
        val finish = { message: String ->
            mainHandler.post {
                busy = false
                button?.setVisible(true)
                toast(message)
            }
        }
        mainHandler.postDelayed({
            try {
                capture?.capture { finish(it) } ?: finish("截屏功能未就绪")
            } catch (e: RuntimeException) {
                finish("截屏失败：${e.message}")
            }
        }, 150)
    }

    /** 显示提示。不用系统 Toast：应用通知关闭时会被系统屏蔽。 */
    private fun toast(message: String) {
        Log.d(TAG, "提示：$message")
        messageBar?.show(message)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只在用户点悬浮按钮时主动读取，这里暂不处理事件
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        messageBar?.hide()
        overlay?.dismiss()
        button?.hide()
        if (::worker.isInitialized) worker.shutdownNow()
        super.onDestroy()
    }
}
