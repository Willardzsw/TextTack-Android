package io.github.willardzsw.texttack

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import io.github.willardzsw.texttack.ocr.OcrEngine
import io.github.willardzsw.texttack.ocr.OcrLanguage
import io.github.willardzsw.texttack.ocr.OcrParagraphs
import io.github.willardzsw.texttack.translate.Translator
import io.github.willardzsw.texttack.translate.TranslationException
import java.util.concurrent.ExecutorService
import kotlin.math.abs

/**
 * 实时翻译（跟随屏幕变化）。两种模式：
 *
 * 界面文字模式（能读到节点文字的应用）：
 * - 不截图，监听无障碍事件。滑动/切换页面时先收起译文；画面停下 [SETTLE_MS] 后重新读文字；
 *   内容一直在变但没在滑动时，最多 [MAX_WAIT_MS] 强制读一次
 * - 文字和位置都没变就不动；翻过的句子记在缓存里，只翻新出现的
 * - 译文层不挡手指
 *
 * 截图模式（游戏、视频、图片，必须先框一个区域）：
 * - 每轮截一次图，只取区域；和上次识别的画面一样就跳过；变了要等连续两轮不再变（画面停住）才 OCR + 翻译
 * - 译文显示在区域外的横栏里（[RealtimePanel]），不会被下一次截图截到
 */
class RealtimeTranslator(private val host: Host) {

    /** 服务提供给实时翻译用的东西。都在主线程调用，除非注明。 */
    interface Host {
        val mainHandler: Handler
        val worker: ExecutorService
        val overlay: ResultOverlay
        val panel: RealtimePanel
        val ocr: OcrEngine
        val capture: ScreenCapture
        val packageName: String
        fun readNodes(): List<TextBlock>
        fun newTranslator(): Translator
        fun cacheKey(): String
        fun ocrLanguage(): OcrLanguage
        fun statusBarHeight(): Int
        fun buttonBounds(): Rect?
        fun setButtonVisible(visible: Boolean)
        fun showError(message: String)
        fun onStopped()
    }

    private enum class Mode { OFF, NODES, SCREEN }

    @Volatile private var mode = Mode.OFF
    private var region: Rect? = null
    private var generation = 0

    val isActive: Boolean get() = mode != Mode.OFF

    // ---------- 公共 ----------

    fun startNodes() {
        stop(notify = false)
        mode = Mode.NODES
        generation++
        Log.d(TAG, "实时翻译开始：界面文字模式")
        scheduleNodeRefresh(0)
    }

    fun startScreen(area: Rect) {
        stop(notify = false)
        mode = Mode.SCREEN
        region = Rect(area)
        generation++
        lastFingerprint = null
        processedFingerprint = null
        host.panel.show(area)
        Log.d(TAG, "实时翻译开始：截图模式 ${area.toShortString()}")
        val gen = generation
        host.mainHandler.postDelayed({ screenCycle(gen) }, 200)
    }

    fun stop(notify: Boolean = true) {
        if (mode == Mode.OFF) return
        Log.d(TAG, "实时翻译停止")
        mode = Mode.OFF
        generation++
        host.mainHandler.removeCallbacks(nodeRefresh)
        host.overlay.dismiss()
        host.panel.dismiss()
        host.setButtonVisible(true)
        shownSignature = null
        if (notify) host.onStopped()
    }

    // ---------- 翻译缓存（两种模式共用） ----------

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
    }

    /** 在 worker 线程调用。只翻缓存里没有的。失败返回 null。 */
    private fun translateCached(texts: List<String>): List<String>? {
        val prefix = host.cacheKey() + "\u0000"
        val missing = synchronized(cache) { texts.distinct().filter { cache[prefix + it] == null } }
        if (missing.isNotEmpty()) {
            val start = SystemClock.elapsedRealtime()
            val out = try {
                host.newTranslator().translate(missing, currentTarget())
            } catch (e: TranslationException) {
                reportError(e.message ?: "翻译失败")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "实时翻译失败", e)
                reportError("翻译失败：${e.message}")
                return null
            }
            synchronized(cache) {
                missing.forEachIndexed { i, t -> out.getOrNull(i)?.let { cache[prefix + t] = it } }
            }
            Log.d(TAG, "实时翻译：新翻 ${missing.size} 段，${SystemClock.elapsedRealtime() - start} ms")
        }
        return synchronized(cache) { texts.map { cache[prefix + it] ?: it } }
    }

    private var targetProvider: () -> io.github.willardzsw.texttack.translate.TargetLanguage = {
        io.github.willardzsw.texttack.translate.TargetLanguage.SIMPLIFIED_CHINESE
    }

    fun setTargetProvider(p: () -> io.github.willardzsw.texttack.translate.TargetLanguage) {
        targetProvider = p
    }

    private fun currentTarget() = targetProvider()

    private var lastErrorAt = 0L

    /** 出错提示最多 10 秒一次，免得实时模式下一直弹。 */
    private fun reportError(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastErrorAt < 10_000) return
        lastErrorAt = now
        host.mainHandler.post { host.showError(message) }
    }

    // ---------- 界面文字模式 ----------

    private var firstEventAt = 0L
    private var lastScrollAt = 0L
    private var refreshing = false
    private var refreshPending = false
    private var shownSignature: String? = null

    private val nodeRefresh = Runnable { refreshNodes() }

    /** 服务收到无障碍事件时调用（主线程）。 */
    fun onEvent(event: AccessibilityEvent) {
        if (mode != Mode.NODES) return
        val pkg = event.packageName?.toString()
        if (pkg == host.packageName || pkg == "com.android.systemui") return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // 位置已经变了，旧译文先收起
                lastScrollAt = SystemClock.elapsedRealtime()
                if (host.overlay.isShowing) {
                    host.overlay.dismiss()
                    shownSignature = null
                }
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (!host.mainHandler.hasCallbacks(nodeRefresh)) firstEventAt = now
        // 内容一直在变（轮播、动画）、事件停不下来时，最多等 MAX_WAIT_MS 也刷新一次；
        // 但还在滑动时不强制刷新，免得读到滑动途中的位置和只露出一半的段落
        val waited = now - firstEventAt
        val scrolling = now - lastScrollAt < SETTLE_MS
        scheduleNodeRefresh(if (waited >= MAX_WAIT_MS && !scrolling) 0 else SETTLE_MS)
    }

    private fun scheduleNodeRefresh(delay: Long) {
        host.mainHandler.removeCallbacks(nodeRefresh)
        host.mainHandler.postDelayed(nodeRefresh, delay)
    }

    private fun refreshNodes() {
        if (mode != Mode.NODES) return
        if (refreshing) {
            refreshPending = true
            return
        }
        refreshing = true
        val gen = generation
        host.worker.execute {
            val blocks = runCatching { host.readNodes() }.getOrDefault(emptyList())
            val signature = blocks.joinToString("\n") { it.text + "@" + it.bounds.flattenToString() }
            val translations = if (signature == shownSignature || blocks.isEmpty()) null
            else translateCached(blocks.map { it.text })
            host.mainHandler.post {
                refreshing = false
                if (gen != generation || mode != Mode.NODES) return@post
                when {
                    blocks.isEmpty() -> {
                        host.overlay.dismiss()
                        shownSignature = null
                    }
                    signature == shownSignature && host.overlay.isShowing -> Unit
                    translations != null -> {
                        host.overlay.show(
                            blocks.mapIndexed { i, b -> ResultOverlay.Shown(b.bounds, translations[i]) },
                            touchable = false,
                        )
                        shownSignature = signature
                    }
                }
                if (refreshPending) {
                    refreshPending = false
                    scheduleNodeRefresh(SETTLE_MS)
                }
            }
        }
    }

    // ---------- 截图模式 ----------

    /** 上一轮截到的画面 / 上一次识别过的画面。 */
    private var lastFingerprint: IntArray? = null
    private var processedFingerprint: IntArray? = null

    private fun screenCycle(gen: Int) {
        if (gen != generation || mode != Mode.SCREEN) return
        val area = region ?: return
        val hideButton = host.buttonBounds()?.let { Rect.intersects(it, area) } == true
        if (hideButton) host.setButtonVisible(false)
        val start = SystemClock.elapsedRealtime()
        val next = { delay: Long ->
            host.mainHandler.post {
                if (hideButton) host.setButtonVisible(true)
                if (gen == generation && mode == Mode.SCREEN) {
                    host.mainHandler.postDelayed({ screenCycle(gen) }, delay)
                }
            }
            Unit
        }
        val doCapture = {
            try {
                host.capture.capture(
                    onSuccess = { shot ->
                        val changed = processShot(shot, area, gen)
                        Log.d(TAG, "实时截图一轮 ${SystemClock.elapsedRealtime() - start} ms，${if (changed) "已识别" else "跳过"}")
                        next(INTERVAL_MS)
                    },
                    onFailure = { message ->
                        Log.w(TAG, "实时截图失败：$message")
                        // 安全页面等：提示一次，放慢重试
                        reportError(message)
                        next(INTERVAL_MS * 3)
                    },
                )
            } catch (e: RuntimeException) {
                reportError("截屏失败：${e.message}")
                next(INTERVAL_MS * 3)
            }
        }
        if (hideButton) host.mainHandler.postDelayed({ doCapture() }, 120) else doCapture()
    }

    /** worker 线程。返回区域内容是否变化。 */
    private fun processShot(shot: ScreenCapture.Shot, area: Rect, gen: Int): Boolean {
        val full = shot.bitmap
        try {
            val crop = Rect(area)
            if (!crop.intersect(0, 0, full.width, full.height)) return false
            val image = Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height())
            try {
                // 画面变了以后，要等它停住（连续两轮一样）才识别：字幕、对话框出来后通常会停一会儿，
                // 正在动的画面不白跑识别。第一轮直接识别。
                val fp = fingerprint(image)
                val previous = lastFingerprint
                lastFingerprint = fp
                val processed = processedFingerprint
                if (processed != null) {
                    if (previous == null || differs(previous, fp)) return false   // 还在变，等下一轮
                    if (!differs(processed, fp)) return false                    // 和上次识别的一样
                }
                processedFingerprint = fp

                val ignoreTop = (host.statusBarHeight() - crop.top).coerceAtLeast(0)
                val r = host.ocr.recognize(image, host.ocrLanguage(), ignoreTop)
                val paragraphs = OcrParagraphs.group(r.lines)
                Log.d(TAG, "实时 OCR ${r.lines.size} 行：检测 ${r.detectMs} 识别 ${r.recognizeMs} ms")
                val text = if (paragraphs.isEmpty()) "" else {
                    val translated = translateCached(paragraphs.map { it.text }) ?: return true
                    translated.joinToString("\n")
                }
                host.mainHandler.post { if (gen == generation && mode == Mode.SCREEN) host.panel.setText(text) }
                return true
            } finally {
                if (image !== full) image.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "实时 OCR 失败", e)
            reportError("识别失败：${e.message}")
            return true
        } finally {
            full.recycle()
        }
    }

    /** 缩到 64 像素宽的灰度图，用来判断画面有没有变化。 */
    private fun fingerprint(bitmap: Bitmap): IntArray {
        val w = 64
        val h = (bitmap.height * w / bitmap.width.toFloat()).toInt().coerceIn(4, 256)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        return IntArray(px.size) { val c = px[it]; ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10 }
    }

    private fun differs(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size) return true
        var changed = 0
        for (i in a.indices) if (abs(a[i] - b[i]) > 24) changed++
        return changed >= maxOf(3, a.size / 300)
    }

    private companion object {
        const val SETTLE_MS = 500L
        const val MAX_WAIT_MS = 1500L
        const val INTERVAL_MS = 800L
        const val CACHE_SIZE = 500
    }
}
