package io.github.willardzsw.texttack

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
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
import io.github.willardzsw.texttack.ocr.OcrParagraphs
import io.github.willardzsw.texttack.translate.GoogleTranslator
import io.github.willardzsw.texttack.translate.OpenAiOptions
import io.github.willardzsw.texttack.translate.OpenAiSettings
import io.github.willardzsw.texttack.translate.OpenAiTranslator
import io.github.willardzsw.texttack.translate.TargetLanguage
import io.github.willardzsw.texttack.translate.TranslationException
import io.github.willardzsw.texttack.translate.TranslationProvider
import io.github.willardzsw.texttack.translate.Translator
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 贴译的核心服务（无感翻译：只在出错时提示）。
 * - 点按钮：读界面节点文字 → 翻译 → 在原位置盖译文；一段都读不到时自动截屏识别
 * - 长按按钮：弹出菜单（框选、截图）；截图 = 截屏 + 本地 OCR → 合并成段落 → 翻译
 * - 译文显示时，点任意处关闭
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
    private var menu: ActionMenu? = null
    private var selector: RegionSelector? = null
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
        menu = ActionMenu(this, windowManager)
        selector = RegionSelector(this, windowManager)
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
        if (menu?.isShowing == true) {
            menu?.dismiss()
            return
        }
        if (overlay?.isShowing == true) {
            overlay?.dismiss()
            return
        }
        if (busy) return
        begin()
        worker.execute {
            val start = SystemClock.elapsedRealtime()
            val blocks = runCatching { NodeTextReader.read(this) }
                .onFailure { Log.e(TAG, "读节点失败", it) }
                .getOrDefault(emptyList())
            Log.d(TAG, "读节点 ${blocks.size} 段，${SystemClock.elapsedRealtime() - start} ms")
            if (blocks.isEmpty()) {
                mainHandler.post { captureAndOcr() }
            } else {
                translateAndShow(blocks.map { ResultOverlay.Shown(it.bounds, it.text) })
            }
        }
    }

    /** 长按：弹出菜单（框选、截图）。 */
    private fun onButtonLongPress() {
        if (busy) return
        val anchor = button?.boundsOnScreen() ?: return
        overlay?.dismiss()
        menu?.show(
            anchor,
            listOf(
                ActionMenu.Item("框选") { startRegionSelect() },
                ActionMenu.Item("截图") { if (!busy) { begin(); captureAndOcr() } },
            ),
        )
    }

    private fun startRegionSelect() {
        if (busy) return
        selector?.show(onSelected = ::translateRegion, onCancel = {})
    }

    /** 框选翻译：先读框里的界面文字，读不到再截屏、只识别框里的部分。 */
    private fun translateRegion(region: Rect) {
        if (busy) return
        begin()
        worker.execute {
            val blocks = runCatching { NodeTextReader.read(this) }.getOrDefault(emptyList())
                .filter { region.contains(it.bounds.centerX(), it.bounds.centerY()) }
            Log.d(TAG, "框选 ${region.toShortString()}：节点 ${blocks.size} 段")
            if (blocks.isEmpty()) {
                mainHandler.post { captureAndOcr(region) }
            } else {
                translateAndShow(blocks.map { ResultOverlay.Shown(it.bounds, it.text) })
            }
        }
    }

    /** 开始处理：收起旧译文，按钮变半透明。 */
    private fun begin() {
        busy = true
        overlay?.dismiss()
        messageBar?.hide()
        button?.setBusy(true)
    }

    /** 结束处理（主线程）。只有出错时才显示一行提示。 */
    private fun finish(error: String? = null) {
        busy = false
        button?.setVisible(true)
        button?.setBusy(false)
        if (error != null) {
            Log.w(TAG, "出错提示：$error")
            messageBar?.show(error, ERROR_SHOW_MS)
        }
    }

    /** 截屏 + 本地 OCR + 翻译。[region] 不为空时只识别这个区域。主线程调用。 */
    private fun captureAndOcr(region: Rect? = null) {
        button?.setVisible(false)
        mainHandler.removeCallbacks(releaseOcr)
        val language = OcrLanguage.fromKey(AppSettings.ocrLanguage(this))
        val fail = { message: String ->
            mainHandler.post {
                finish(message)
                scheduleRelease()
            }
            Unit
        }
        // 等按钮从屏幕上消失后再截
        mainHandler.postDelayed({
            val cap = capture
            if (cap == null) {
                fail("截屏功能未就绪")
                return@postDelayed
            }
            try {
                cap.capture(
                    onSuccess = { shot ->
                        mainHandler.post { button?.setVisible(true) }
                        runOcr(shot, language, cap, region)
                    },
                    onFailure = fail,
                )
            } catch (e: RuntimeException) {
                fail("截屏失败：${e.message}")
            }
        }, 150)
    }

    /** 在 worker 线程上执行。 */
    private fun runOcr(shot: ScreenCapture.Shot, language: OcrLanguage, cap: ScreenCapture, region: Rect?) {
        val full = shot.bitmap
        val area = region?.let { Rect(it).apply { if (!intersect(0, 0, full.width, full.height)) setEmpty() } }
        if (area != null && area.isEmpty) {
            full.recycle()
            mainHandler.post { finish("框选区域不在屏幕内") }
            return
        }
        val image = if (area != null) Bitmap.createBitmap(full, area.left, area.top, area.width(), area.height()) else full
        val ignoreTop = (statusBarHeight() - (area?.top ?: 0)).coerceAtLeast(0)
        val result = runCatching { ocr!!.recognize(image, language, ignoreTop) }
            .map { r -> if (area == null) r else r.copy(lines = r.lines.map { it.offset(area.left, area.top) }) }
        if (BuildConfig.DEBUG) cap.saveForDebug(image)
        if (image !== full) image.recycle()
        full.recycle()
        mainHandler.post { scheduleRelease() }

        val r = result.getOrElse {
            Log.e(TAG, "OCR 失败", it)
            mainHandler.post { finish("识别失败：${it.message}") }
            return
        }
        Log.d(TAG, "OCR ${language.key}：${r.lines.size} 行 / ${r.boxCount} 框，截屏 ${shot.tookMs} 加载 ${r.loadMs} " +
            "检测 ${r.detectMs} 识别 ${r.recognizeMs} ms")
        val paragraphs = OcrParagraphs.group(r.lines)
        if (paragraphs.isEmpty()) {
            mainHandler.post { finish("没有找到可翻译的文字") }
            return
        }
        translateAndShow(paragraphs.map { ResultOverlay.Shown(it.bounds, it.text) })
    }

    /** 翻译并在原位置显示译文。在 worker 线程上执行。 */
    private fun translateAndShow(items: List<ResultOverlay.Shown>) {
        val start = SystemClock.elapsedRealtime()
        val target = TargetLanguage.fromKey(AppSettings.targetLanguage(this))
        val provider = TranslationProvider.fromKey(AppSettings.translationProvider(this))
        val translations = try {
            translatorFor(provider).translate(items.map { it.text }, target)
        } catch (e: TranslationException) {
            mainHandler.post { finish(e.message) }
            return
        } catch (e: Exception) {
            Log.e(TAG, "翻译失败", e)
            mainHandler.post { finish("翻译失败：${e.message}") }
            return
        }
        Log.d(TAG, "翻译 ${items.size} 段（${provider.label} → ${target.label}），${SystemClock.elapsedRealtime() - start} ms")
        val shown = items.mapIndexed { i, item -> item.copy(text = translations.getOrNull(i) ?: item.text) }
        shown.forEachIndexed { i, s -> Log.d(TAG, "译文 ${s.bounds.toShortString()} ${items[i].text} → ${s.text}") }
        mainHandler.post {
            finish()
            overlay?.show(shown)
        }
    }

    private fun translatorFor(provider: TranslationProvider): Translator = when (provider) {
        TranslationProvider.GOOGLE -> GoogleTranslator()
        TranslationProvider.OPENAI -> OpenAiTranslator(
            OpenAiOptions(
                baseUrl = OpenAiSettings.baseUrl(this),
                apiKey = OpenAiSettings.apiKey(this),
                timeoutSeconds = OpenAiSettings.timeoutSeconds(this),
                profile = OpenAiSettings.selectedProfile(this),
            )
        )
    }

    private fun scheduleRelease() {
        mainHandler.removeCallbacks(releaseOcr)
        mainHandler.postDelayed(releaseOcr, OCR_IDLE_RELEASE_MS)
    }

    // ---------- 调试：用存好的截图测 OCR（只在 debug 版里有） ----------
    //
    // adb shell am broadcast -a io.github.willardzsw.texttack.DEBUG_OCR     //     --es file fixtures/google-th.png --ei side 1600 --es lang th
    // file 相对于 Android/data/<包名>/files/；结果看 adb logcat -s TextTack
    // 读当前屏幕节点：adb shell am broadcast -a io.github.willardzsw.texttack.DEBUG_NODES
    // 两个都可以加 --ez translate true，按当前设置翻译并写日志；DEBUG_OCR 可加 --es region l,t,r,b 只识别一块

    private var debugReceiver: BroadcastReceiver? = null

    private fun registerDebugReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == DEBUG_NODES_ACTION) {
                    worker.execute {
                        val list = NodeTextReader.read(this@TranslateAccessibilityService)
                        Log.d(TAG, "调试 节点：${list.size} 段")
                        list.forEach { Log.d(TAG, "调试 节点 ${it.bounds.toShortString()} ${it.text}") }
                        if (intent.getBooleanExtra("translate", false)) debugTranslate(list.map { it.text })
                    }
                    return
                }
                val path = intent.getStringExtra("file") ?: "screenshots/last.png"
                val side = intent.getIntExtra("side", OcrEngine.DEFAULT_MAX_DETECT_SIDE)
                val language = OcrLanguage.fromKey(intent.getStringExtra("lang"))
                val translate = intent.getBooleanExtra("translate", false)
                val region = intent.getStringExtra("region")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                    ?.takeIf { it.size == 4 }?.let { Rect(it[0], it[1], it[2], it[3]) }
                worker.execute { runDebugOcr(File(getExternalFilesDir(null), path), side, language, translate, region) }
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

    private fun runDebugOcr(file: File, side: Int, language: OcrLanguage, translate: Boolean, region: Rect?) {
        val engine = ocr ?: return
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
        val bitmap = if (decoded != null && region != null) {
            Bitmap.createBitmap(decoded, region.left, region.top, region.width(), region.height()).also { decoded.recycle() }
        } else decoded
        if (bitmap == null) {
            Log.w(TAG, "调试 OCR：读不到 ${file.path}")
            return
        }
        val old = engine.maxDetectSide
        engine.maxDetectSide = side
        try {
            val r = engine.recognize(bitmap, language, if (region == null) statusBarHeight() else 0)
            Log.d(TAG, "调试 OCR ${file.name} 长边 $side ${language.key}：${r.lines.size} 行 / ${r.boxCount} 框，" +
                "检测输入 ${r.detectorWidth}×${r.detectorHeight}，加载 ${r.loadMs} 检测 ${r.detectMs} 识别 ${r.recognizeMs} ms")
            r.lines.forEach { Log.d(TAG, "调试 OCR 行 %.2f %s %s".format(it.score, it.bounds.toShortString(), it.text)) }
            val paragraphs = OcrParagraphs.group(r.lines)
            paragraphs.forEach { Log.d(TAG, "调试 OCR 段落 ${it.bounds.toShortString()} ${it.text}") }
            if (translate) debugTranslate(paragraphs.map { it.text })
        } catch (e: Exception) {
            Log.e(TAG, "调试 OCR 失败", e)
        } finally {
            engine.maxDetectSide = old
            bitmap.recycle()
        }
        mainHandler.post { scheduleRelease() }
    }

    /** 调试：按当前设置翻译并写日志，不显示在屏幕上。 */
    private fun debugTranslate(texts: List<String>) {
        val start = SystemClock.elapsedRealtime()
        val target = TargetLanguage.fromKey(AppSettings.targetLanguage(this))
        val provider = TranslationProvider.fromKey(AppSettings.translationProvider(this))
        try {
            val out = translatorFor(provider).translate(texts, target)
            Log.d(TAG, "调试 翻译 ${texts.size} 段（${provider.label} → ${target.label}）${SystemClock.elapsedRealtime() - start} ms")
            texts.forEachIndexed { i, t -> Log.d(TAG, "调试 翻译 $t → ${out[i]}") }
        } catch (e: Exception) {
            Log.e(TAG, "调试 翻译失败：${e.message}", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只在用户点悬浮按钮时主动读取，这里暂不处理事件
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        menu?.dismiss()
        selector?.dismiss()
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
        const val ERROR_SHOW_MS = 2500L
        const val DEBUG_OCR_ACTION = "io.github.willardzsw.texttack.DEBUG_OCR"
        const val DEBUG_NODES_ACTION = "io.github.willardzsw.texttack.DEBUG_NODES"
    }
}
