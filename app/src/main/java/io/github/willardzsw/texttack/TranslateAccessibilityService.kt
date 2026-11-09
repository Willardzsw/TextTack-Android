package io.github.willardzsw.texttack

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.willardzsw.texttack.ocr.OcrEngine
import io.github.willardzsw.texttack.ocr.OcrLanguage
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 贴译的核心服务。
 *
 * 0.3.x：
 * - 点按钮：读取界面节点文字，在原位置画框；一段都读不到时自动改用截屏识别
 * - 长按按钮：直接截屏 + 本地 OCR，在原位置画框
 */
class TranslateAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var worker: ExecutorService
    private lateinit var windowManager: WindowManager
    private var button: FloatingButton? = null
    private var overlay: ResultOverlay? = null
    private var capture: ScreenCapture? = null
    private var messageBar: MessageBar? = null
    private var ocr: OcrEngine? = null
    @Volatile private var busy = false

    /** 空闲一段时间后释放 OCR 模型（在 worker 线程上执行）。 */
    private val releaseOcr = Runnable {
        worker.execute {
            if (ocr?.isLoaded == true) {
                ocr?.close()
                Log.d(TAG, "OCR 模型空闲，已释放")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        worker = Executors.newSingleThreadExecutor()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = ResultOverlay(this, windowManager)
        capture = ScreenCapture(this, worker)
        messageBar = MessageBar(this, windowManager)
        ocr = OcrEngine(this)
        button = FloatingButton(this, windowManager, ::onButtonTap, ::onButtonLongPress).also { it.show() }
        if (BuildConfig.DEBUG) registerDebugReceiver()
    }

    /** 状态栏高度：识别时跳过这一条，不识别时间、电量。 */
    private fun statusBarHeight(): Int {
        val fromInsets = runCatching {
            windowManager.maximumWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        }.getOrDefault(0)
        if (fromInsets > 0) return fromInsets
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun onButtonTap() {
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
                blocks.onSuccess { list ->
                    if (list.isEmpty()) {
                        Log.d(TAG, "节点没有文字（$tookMs ms），改用截屏识别")
                        startOcr("界面读不到文字，已改用截屏识别。")
                    } else {
                        busy = false
                        overlay?.show(list)
                        toast("读到 ${list.size} 段文字，用时 $tookMs ms")
                        list.forEach { Log.d(TAG, "节点 ${it.bounds.toShortString()} ${it.text}") }
                    }
                }.onFailure {
                    busy = false
                    toast("读取失败：${it.message}")
                }
            }
        }
    }

    private fun onButtonLongPress() {
        if (busy) return
        busy = true
        startOcr(null)
    }

    /** 截屏 + OCR。调用前 busy 已设为 true。 */
    private fun startOcr(prefix: String?) {
        overlay?.dismiss()
        messageBar?.hide()
        button?.setVisible(false)
        mainHandler.removeCallbacks(releaseOcr)
        val language = OcrLanguage.fromKey(AppSettings.ocrLanguage(this))
        showProgressLater("正在识别（${language.label}）…")

        val fail = { message: String ->
            mainHandler.post {
                busy = false
                button?.setVisible(true)
                toast(message)
                scheduleRelease()
            }
            Unit
        }
        // 等按钮和提示从屏幕上消失后再截
        mainHandler.postDelayed({
            val cap = capture
            if (cap == null) {
                fail("截屏功能未就绪")
                return@postDelayed
            }
            try {
                cap.capture(
                    onSuccess = { shot -> runOcr(shot, language, prefix, cap) },
                    onFailure = fail,
                )
            } catch (e: RuntimeException) {
                fail("截屏失败：${e.message}")
            }
        }, 150)
    }

    /** 在 worker 线程上执行。 */
    private fun runOcr(shot: ScreenCapture.Shot, language: OcrLanguage, prefix: String?, cap: ScreenCapture) {
        val result = runCatching { ocr!!.recognize(shot.bitmap, language, statusBarHeight()) }
        mainHandler.post {
            busy = false
            button?.setVisible(true)
            result.onSuccess { r ->
                val blocks = r.lines.map { TextBlock(it.text, it.bounds, TextBlock.Source.OCR) }
                if (blocks.isNotEmpty()) overlay?.show(blocks)
                val summary = buildString {
                    if (prefix != null) append(prefix).append('\n')
                    append(if (blocks.isEmpty()) "没有识别到文字" else "识别 ${blocks.size} 行（检测到 ${r.boxCount} 个框）")
                    append("\n截屏 ${shot.tookMs} ms")
                    if (r.loadMs > 5) append("，加载模型 ${r.loadMs} ms")
                    append("，检测 ${r.detectMs} ms，识别 ${r.recognizeMs} ms")
                }
                toast(summary, 6000)
                Log.d(TAG, "OCR ${language.key} 检测输入 ${r.detectorWidth}×${r.detectorHeight}")
                r.lines.forEach { Log.d(TAG, "OCR 行 %.2f %s %s".format(it.score, it.bounds.toShortString(), it.text)) }
            }.onFailure {
                Log.e(TAG, "OCR 失败", it)
                toast("识别失败：${it.message}")
            }
            scheduleRelease()
        }
        // 结果显示后再存调试图，不拖慢识别
        cap.saveForDebug(shot.bitmap)
        shot.bitmap.recycle()
    }

    private fun scheduleRelease() {
        mainHandler.removeCallbacks(releaseOcr)
        mainHandler.postDelayed(releaseOcr, OCR_IDLE_RELEASE_MS)
    }

    /** 显示提示。不用系统 Toast：应用通知关闭时会被系统屏蔽。 */
    private fun toast(message: String, durationMs: Long = 4000) {
        Log.d(TAG, "提示：$message")
        messageBar?.show(message, durationMs)
    }

    /** 识别中的提示：等截完屏再显示，免得被截进去。 */
    private fun showProgressLater(message: String) {
        mainHandler.postDelayed({ if (busy) messageBar?.show(message, 15000) }, 400)
    }

    // ---------- 调试：用存好的截图测 OCR（只在 debug 版里有） ----------
    //
    // adb shell am broadcast -a io.github.willardzsw.texttack.DEBUG_OCR     //     --es file fixtures/google-th.png --ei side 1600 --es lang th
    // file 相对于 Android/data/<包名>/files/；结果看 adb logcat -s TextTack
    // 读当前屏幕节点：adb shell am broadcast -a io.github.willardzsw.texttack.DEBUG_NODES

    private var debugReceiver: BroadcastReceiver? = null

    private fun registerDebugReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == DEBUG_NODES_ACTION) {
                    worker.execute {
                        val list = NodeTextReader.read(this@TranslateAccessibilityService)
                        Log.d(TAG, "调试 节点：${list.size} 段")
                        list.forEach { Log.d(TAG, "调试 节点 ${it.bounds.toShortString()} ${it.text}") }
                    }
                    return
                }
                val path = intent.getStringExtra("file") ?: "screenshots/last.png"
                val side = intent.getIntExtra("side", OcrEngine.DEFAULT_MAX_DETECT_SIDE)
                val language = OcrLanguage.fromKey(intent.getStringExtra("lang"))
                worker.execute { runDebugOcr(File(getExternalFilesDir(null), path), side, language) }
            }
        }
        val filter = IntentFilter(DEBUG_OCR_ACTION).apply { addAction(DEBUG_NODES_ACTION) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        debugReceiver = receiver
    }

    private fun runDebugOcr(file: File, side: Int, language: OcrLanguage) {
        val engine = ocr ?: return
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        })
        if (bitmap == null) {
            Log.w(TAG, "调试 OCR：读不到 ${file.path}")
            return
        }
        val old = engine.maxDetectSide
        engine.maxDetectSide = side
        try {
            val r = engine.recognize(bitmap, language, statusBarHeight())
            Log.d(TAG, "调试 OCR ${file.name} 长边 $side ${language.key}：${r.lines.size} 行 / ${r.boxCount} 框，" +
                "检测输入 ${r.detectorWidth}×${r.detectorHeight}，加载 ${r.loadMs} 检测 ${r.detectMs} 识别 ${r.recognizeMs} ms")
            r.lines.forEach { Log.d(TAG, "调试 OCR 行 %.2f %s %s".format(it.score, it.bounds.toShortString(), it.text)) }
        } catch (e: Exception) {
            Log.e(TAG, "调试 OCR 失败", e)
        } finally {
            engine.maxDetectSide = old
            bitmap.recycle()
        }
        mainHandler.post { scheduleRelease() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只在用户点悬浮按钮时主动读取，这里暂不处理事件
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        debugReceiver?.let { runCatching { unregisterReceiver(it) } }
        messageBar?.hide()
        overlay?.dismiss()
        button?.hide()
        if (::worker.isInitialized) {
            worker.execute { ocr?.close() }
            worker.shutdown()
        }
        super.onDestroy()
    }

    private companion object {
        const val OCR_IDLE_RELEASE_MS = 60_000L
        const val DEBUG_OCR_ACTION = "io.github.willardzsw.texttack.DEBUG_OCR"
        const val DEBUG_NODES_ACTION = "io.github.willardzsw.texttack.DEBUG_NODES"
    }
}
