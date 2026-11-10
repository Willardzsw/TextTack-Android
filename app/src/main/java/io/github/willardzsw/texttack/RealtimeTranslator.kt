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
 * - 约每 [INTERVAL_MS] 截一次图，只取区域；画面变了就识别，识别出新文字才在后台翻译，识别不等翻译
 * - 译文栏保留最近 [HISTORY_SIZE] 句
 * - 译文显示在区域外的横栏里（[RealtimePanel]），不会被下一次截图截到；框可以拖动、缩放
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
        fun scanNodes(): NodeTextReader.Scan
        fun newTranslator(): Translator
        fun cacheKey(): String
        fun ocrLanguage(): OcrLanguage
        fun statusBarHeight(): Int
        fun screenBounds(): Rect
        /** 最上层应用窗口的 id 和屏幕位置；没有返回 null。 */
        fun topAppWindow(): Pair<Int, Rect>?
        fun targetLanguage(): io.github.willardzsw.texttack.translate.TargetLanguage
        fun buttonBounds(): Rect?
        fun setButtonVisible(visible: Boolean)
        fun showError(message: String)
        fun onStopped()
    }

    /** NODES = 实时读取（只读界面文字）；FULL = 实时截屏（只截应用窗口整屏识别）；SCREEN = 实时框选（横带）。 */
    private enum class Mode { OFF, NODES, FULL, SCREEN }

    @Volatile private var mode = Mode.OFF
    private var region: Rect? = null
    private var generation = 0

    val isActive: Boolean get() = mode != Mode.OFF

    // ---------- 公共 ----------

    fun startNodes() {
        stop(notify = false)
        mode = Mode.NODES
        generation++
        Log.i(TAG, "实时翻译开始：界面文字模式")
        scheduleNodeRefresh(0)
    }

    /** 横带上下边在屏幕高度中的比例，转屏时按比例换算。 */
    private var bandTop = 0f
    private var bandBottom = 0f

    /** 截图模式。区域是全宽横带（只选上下范围）。 */
    fun startScreen(area: Rect) {
        stop(notify = false)
        mode = Mode.SCREEN
        region = Rect(area)
        rememberBand(area)
        generation++
        resetScreenState()
        host.panel.show(area, band = true) { moved -> moveRegion(moved) }
        Log.i(TAG, "实时翻译开始：截图模式 ${area.toShortString()}")
        val gen = generation
        host.mainHandler.postDelayed({ screenCycle(gen) }, 200)
    }

    /** 拖动/缩放把手后换到新区域：重新比较画面，下一轮直接识别（已显示的译文保留）。 */
    private fun moveRegion(area: Rect) {
        if (mode != Mode.SCREEN) return
        region = Rect(area)
        rememberBand(area)
        processedFingerprint = null
        lastOcrText = null
        Log.i(TAG, "实时框选改到 ${area.toShortString()}")
    }

    private fun rememberBand(area: Rect) {
        val h = host.screenBounds().height().toFloat().coerceAtLeast(1f)
        bandTop = area.top / h
        bandBottom = area.bottom / h
    }

    /** 转屏后：横带按上下比例换算到新屏幕，宽度重新铺满，马上重新识别。主线程。 */
    fun onScreenRotated() {
        if (mode != Mode.SCREEN) return
        val s = host.screenBounds()
        val area = Rect(0, (bandTop * s.height()).toInt(), s.width(), (bandBottom * s.height()).toInt())
        region = area
        processedFingerprint = null
        lastOcrText = null
        host.panel.show(area, band = true) { moved -> moveRegion(moved) }
        Log.i(TAG, "转屏，实时框选换算到 ${area.toShortString()}")
    }

    private fun resetScreenState() {
        seen.clear()
        otherChangeAt = 0L
        lastDynamicKey = null
        processedFingerprint = null
        lastOcrText = null
        translateSeq = 0
        shownSeq = 0
        synchronized(history) { history.clear() }
    }

    fun stop(notify: Boolean = true) {
        if (mode == Mode.OFF) return
        Log.i(TAG, "实时翻译停止")
        mode = Mode.OFF
        generation++
        host.mainHandler.removeCallbacks(nodeRefresh)
        host.overlay.dismiss()
        host.panel.dismiss()
        host.setButtonVisible(true)
        shownSignature = null
        nodeItems = emptyList()
        mediaItems = emptyList()
        mediaRegions = emptyList()
        mediaKey = null
        mediaFingerprint = null
        if (notify) host.onStopped()
    }

    /** 服务销毁时调用：关掉翻译线程。 */
    fun release() {
        stop(notify = false)
        translateExecutor.shutdownNow()
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
            Log.i(TAG, "实时翻译：新翻 ${missing.size} 段，${SystemClock.elapsedRealtime() - start} ms")
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
        if (mode == Mode.FULL) {
            onFullEvent(event)
            return
        }
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
                    nodeItems = emptyList()
                    mediaItems = emptyList()
                    mediaKey = null
                    mediaFingerprint = null
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
            val scan = runCatching { host.scanNodes() }.getOrNull()
            val blocks = scan?.blocks.orEmpty()
            currentScan = scan
            val signature = blocks.joinToString("\n") { it.text + "@" + it.bounds.flattenToString() }
            val translations = if (signature == shownSignature || blocks.isEmpty()) null
            else translateCached(blocks.map { it.text })
            host.mainHandler.post {
                refreshing = false
                if (gen != generation || mode != Mode.NODES) return@post
                val beforeNodes = nodeItems
                val beforeMedia = mediaItems
                when {
                    blocks.isEmpty() -> {
                        nodeItems = emptyList()
                        shownSignature = null
                    }
                    signature == shownSignature && nodeItems.isNotEmpty() -> Unit
                    translations != null -> {
                        nodeItems = blocks.mapIndexed { i, b -> ResultOverlay.Shown(b.bounds, translations[i]) }
                        shownSignature = signature
                    }
                }
                // 只有内容变了才重画，免得视频播放时每次刷新都闪一下
                if (nodeItems !== beforeNodes || mediaItems !== beforeMedia || !host.overlay.isShowing) render()
                if (refreshPending) {
                    refreshPending = false
                    scheduleNodeRefresh(SETTLE_MS)
                }
            }
        }
    }

    // ---------- 界面文字模式里的图片/视频区域 ----------
    //
    // 重读界面时顺便找出图片/视频区域（和"翻译"同一套判断）。有的话每 [INTERVAL_MS] 只截这个应用窗口
    // （安卓 14+ takeScreenshotOfWindow，不包括我们的译文层），只看这些区域：画面没变就跳过，
    // 变了就识别，有新文字才翻译；译文和界面文字的译文一起盖在原位置上。

    @Volatile private var currentScan: NodeTextReader.Scan? = null
    private var nodeItems: List<ResultOverlay.Shown> = emptyList()
    private var mediaItems: List<ResultOverlay.Shown> = emptyList()
    @Volatile private var mediaRegions: List<Rect> = emptyList()
    @Volatile private var mediaWindowId = -1
    @Volatile private var mediaWindowBounds = Rect()
    @Volatile private var mediaFingerprint: IntArray? = null
    @Volatile private var mediaKey: String? = null
    private var mediaLoopRunning = false
    private var mediaUnsupported = false

    /** 两部分译文合在一起显示（不挡手指）。主线程。 */
    private fun render() {
        val items = nodeItems + mediaItems
        if (items.isEmpty()) host.overlay.dismiss() else host.overlay.show(items, touchable = false)
    }

    /** 主线程。 */
    private fun updateMedia(scan: NodeTextReader.Scan?) {
        val regions = scan?.media.orEmpty()
        val changed = regions != mediaRegions || scan?.mediaWindowId != mediaWindowId
        mediaRegions = regions
        mediaWindowId = scan?.mediaWindowId ?: -1
        mediaWindowBounds = scan?.mediaWindowBounds ?: Rect()
        if (changed) {
            mediaFingerprint = null
            mediaKey = null
            if (regions.isEmpty()) mediaItems = emptyList()
            Log.i(TAG, "实时 图片/视频区域 ${regions.size} 个：${regions.joinToString { it.toShortString() }}")
        }
        if (regions.isNotEmpty() && !mediaLoopRunning && !mediaUnsupported) {
            mediaLoopRunning = true
            val gen = generation
            host.mainHandler.post { mediaCycle(gen) }
        }
    }

    // ---------- 实时截屏 ----------
    //
    // 不读界面文字。每 [INTERVAL_MS] 只截最上层应用窗口（takeScreenshotOfWindow，不包括我们的译文层），
    // 整个窗口（去掉状态栏）识别；画面没变跳过，有新文字才翻译；本来就是目标语言的文字不翻、不盖。
    // 译文盖在原位置，不挡手指。下面的 mediaCycle / processMediaShot 两种用法共用。

    fun startFull() {
        stop(notify = false)
        mode = Mode.FULL
        generation++
        currentScan = null
        mediaKey = null
        mediaFingerprint = null
        Log.i(TAG, "实时翻译开始：截屏模式")
        refreshFullWindow()
        mediaLoopRunning = true
        val gen = generation
        host.mainHandler.postDelayed({ mediaCycle(gen) }, 150)
    }

    /** 找最上层应用窗口，识别范围 = 窗口去掉状态栏。 */
    private fun refreshFullWindow() {
        val w = host.topAppWindow()
        if (w == null) {
            mediaWindowId = -1
            mediaRegions = emptyList()
            return
        }
        val (id, bounds) = w
        if (id != mediaWindowId || bounds != mediaWindowBounds) {
            mediaFingerprint = null
            mediaKey = null
        }
        mediaWindowId = id
        mediaWindowBounds = Rect(bounds)
        val top = maxOf(bounds.top, host.statusBarHeight())
        mediaRegions = if (bounds.bottom > top) listOf(Rect(bounds.left, top, bounds.right, bounds.bottom)) else emptyList()
    }

    private fun onFullEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg == host.packageName || pkg == "com.android.systemui") return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // 位置变了，旧译文先收起；下一轮重新识别
                if (mediaItems.isNotEmpty()) {
                    mediaItems = emptyList()
                    render()
                }
                mediaKey = null
                mediaFingerprint = null
            }
        }
    }

    private fun mediaCycle(gen: Int) {
        if (mode == Mode.FULL) refreshFullWindow()
        val alive = mode == Mode.NODES || mode == Mode.FULL
        if (gen != generation || !alive || mediaRegions.isEmpty() || mediaWindowId == -1) {
            if (gen == generation && mode == Mode.FULL) {
                // 暂时找不到应用窗口（比如在切换中），稍后再试
                host.mainHandler.postDelayed({ mediaCycle(gen) }, INTERVAL_MS * 2)
                return
            }
            mediaLoopRunning = false
            return
        }
        val next = { delay: Long ->
            host.mainHandler.post {
                if (gen == generation && (mode == Mode.NODES || mode == Mode.FULL)) host.mainHandler.postDelayed({ mediaCycle(gen) }, delay)
                else mediaLoopRunning = false
            }
            Unit
        }
        val start = SystemClock.elapsedRealtime()
        host.capture.captureWindow(
            mediaWindowId,
            onSuccess = { shot ->
                val result = processMediaShot(shot, gen)
                Log.i(TAG, "实时 区域一轮 ${SystemClock.elapsedRealtime() - start} ms，$result")
                next(INTERVAL_MS)
            },
            onFailure = { message ->
                Log.w(TAG, "实时 窗口截图失败：$message")
                if (message.contains("安卓 14")) {
                    mediaUnsupported = true
                    host.mainHandler.post { mediaLoopRunning = false }
                } else {
                    next(INTERVAL_MS * 4)
                }
            },
        )
    }

    /** worker 线程。返回这一轮做了什么（写日志用）。 */
    private fun processMediaShot(shot: ScreenCapture.Shot, gen: Int): String {
        val full = shot.bitmap
        try {
            val origin = mediaWindowBounds
            val crops = ArrayList<Pair<Rect, Bitmap>>()
            for (region in mediaRegions) {
                // 屏幕坐标 → 窗口截图坐标
                val local = Rect(region).apply { offset(-origin.left, -origin.top) }
                if (!local.intersect(0, 0, full.width, full.height) || local.isEmpty) continue
                crops.add(Rect(local).apply { offset(origin.left, origin.top) } to
                    Bitmap.createBitmap(full, local.left, local.top, local.width(), local.height()))
            }
            try {
                val fp = crops.flatMap { fingerprint(it.second).asList() }.toIntArray()
                val previous = mediaFingerprint
                if (previous != null && !differs(previous, fp)) return "跳过（画面没变）"
                mediaFingerprint = fp

                val scan = currentScan
                val nodeTexts = scan?.blocks.orEmpty().map { it.text }.toSet()
                val lines = ArrayList<io.github.willardzsw.texttack.ocr.OcrLine>()
                for ((screenRect, image) in crops) {
                    val ignoreTop = (host.statusBarHeight() - screenRect.top).coerceAtLeast(0)
                    val r = host.ocr.recognize(image, host.ocrLanguage(), ignoreTop)
                    r.lines.forEach { line ->
                        val moved = line.offset(screenRect.left, screenRect.top)
                        // 落在界面文字或按钮（点赞、评论、转发等）上的不算视频里的字
                        if (scan?.coversUi(moved.bounds) != true && moved.text !in nodeTexts) lines.add(moved)
                    }
                }
                val target = host.targetLanguage()
                val paragraphs = OcrParagraphs.group(lines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })))
                    .filterNot { mode == Mode.FULL && TextFilters.isInTargetLanguage(it.text, target) }
                // 只比文字：位置稍有抖动、文字没变就不重画，免得闪
                val key = paragraphs.joinToString("\n") { it.text }
                if (key == mediaKey) return "识别 ${lines.size} 行，文字没变"
                mediaKey = key
                if (paragraphs.isEmpty()) {
                    host.mainHandler.post {
                        if (gen == generation && (mode == Mode.NODES || mode == Mode.FULL)) { mediaItems = emptyList(); render() }
                    }
                    return "没有文字"
                }
                translateExecutor.execute {
                    val translated = translateCached(paragraphs.map { it.text }) ?: return@execute
                    host.mainHandler.post {
                        if (gen != generation || (mode != Mode.NODES && mode != Mode.FULL) || key != mediaKey) return@post
                        mediaItems = paragraphs.mapIndexed { i, p -> ResultOverlay.Shown(p.bounds, translated[i]) }
                        render()
                    }
                }
                return "识别 ${lines.size} 行，新文字送去翻译"
            } finally {
                crops.forEach { it.second.recycle() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "实时 区域识别失败", e)
            return "识别失败"
        } finally {
            full.recycle()
        }
    }

    // ---------- 截图模式 ----------
    //
    // 每轮：截图 → 取区域 → 和上次识别过的画面比，没变化就跳过；变了就识别（框选区域识别只要约 0.15 秒）。
    // 识别出的文字和上次一样就不翻译；有新字幕才交给后台翻译，识别不等翻译，继续下一轮。
    // 翻译回来时如果已经有更新的字幕显示了，就丢掉这条过时的。

    private var processedFingerprint: IntArray? = null
    @Volatile private var lastOcrText: String? = null
    private val translateExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var translateSeq = 0
    @Volatile private var shownSeq = 0
    private val history = ArrayList<String>()

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
                        val result = processShot(shot, area, gen)
                        Log.i(TAG, "实时截图一轮 ${SystemClock.elapsedRealtime() - start} ms，$result")
                        next(INTERVAL_MS)
                    },
                    onFailure = { message ->
                        Log.w(TAG, "实时截图失败：$message")
                        // 安全页面、间隔太短等：提示（最多 10 秒一次），放慢重试
                        reportError(message)
                        next(INTERVAL_MS * 4)
                    },
                )
            } catch (e: RuntimeException) {
                reportError("截屏失败：${e.message}")
                next(INTERVAL_MS * 4)
            }
        }
        if (hideButton) host.mainHandler.postDelayed({ doCapture() }, 120) else doCapture()
    }

    /** worker 线程。返回这一轮做了什么（写日志用）。 */
    private fun processShot(shot: ScreenCapture.Shot, area: Rect, gen: Int): String {
        val full = shot.bitmap
        try {
            val crop = Rect(area)
            if (!crop.intersect(0, 0, full.width, full.height)) return "区域在屏幕外"
            val image = Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height())
            try {
                val fp = fingerprint(image)
                val processed = processedFingerprint
                if (processed != null && !differs(processed, fp)) return "跳过（画面没变）"
                processedFingerprint = fp

                val ignoreTop = (host.statusBarHeight() - crop.top).coerceAtLeast(0)
                val r = host.ocr.recognize(image, host.ocrLanguage(), ignoreTop)
                r.lines.forEach { Log.i(TAG, "实时 OCR 行 %.2f %s".format(it.score, it.text)) }
                // 注：0.6.7 试过"新文字要读到两次一样才翻译"，但视频字幕约 0.7 秒就换、泰文上下标记识别会抖，
                // 同样的结果很少连续出现，45 轮里只翻了 3 句，已去掉
                val paragraphs = dropWatermarks(OcrParagraphs.group(r.lines), SystemClock.elapsedRealtime()).map { it.text }
                val key = paragraphs.joinToString("\n")
                val ocrInfo = "识别 ${r.lines.size} 行（检测 ${r.detectMs} 识别 ${r.recognizeMs} ms）"
                if (paragraphs.isEmpty()) return "$ocrInfo，没有文字"
                if (key == lastOcrText) return "$ocrInfo，文字没变"
                lastOcrText = key
                val seq = ++translateSeq
                translateExecutor.execute { translateForPanel(paragraphs, seq, gen) }
                return "$ocrInfo，新文字 #$seq 送去翻译"
            } finally {
                if (image !== full) image.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "实时 OCR 失败", e)
            reportError("识别失败：${e.message}")
            return "识别失败"
        } finally {
            full.recycle()
        }
    }

    // ---------- 水印、台标：同一段文字在同一位置一直不动 ----------

    private class Seen(val firstAt: Long, var lastAt: Long, val cx: Int, val cy: Int)

    private val seen = HashMap<String, Seen>()
    private var otherChangeAt = 0L
    private var lastDynamicKey: String? = null

    /**
     * 去掉水印：同一段文字在差不多的位置（中心相差 < [WATERMARK_MOVE_PX]）连续出现超过 [WATERMARK_MS]，
     * 并且这期间框里别的文字变过（说明画面在播放，不是整屏停着），就当成水印、台标，不再翻译。
     * 消失超过 [WATERMARK_GAP_MS] 就忘掉，重新出现时从头计时。worker 线程。
     */
    private fun dropWatermarks(paragraphs: List<OcrParagraphs.Paragraph>, now: Long): List<OcrParagraphs.Paragraph> {
        for (p in paragraphs) {
            val cx = p.bounds.centerX()
            val cy = p.bounds.centerY()
            val s = seen[p.text]
            if (s == null || now - s.lastAt > WATERMARK_GAP_MS ||
                abs(s.cx - cx) > WATERMARK_MOVE_PX || abs(s.cy - cy) > WATERMARK_MOVE_PX
            ) {
                seen[p.text] = Seen(now, now, cx, cy)
            } else {
                s.lastAt = now
            }
        }
        seen.entries.removeAll { now - it.value.lastAt > WATERMARK_GAP_MS }
        val dynamic = paragraphs.filterNot { p ->
            val s = seen[p.text]!!
            (now - s.firstAt > WATERMARK_MS && otherChangeAt > s.firstAt).also {
                if (it) Log.i(TAG, "实时 当作水印跳过：${p.text}")
            }
        }
        val key = dynamic.joinToString("\n") { it.text }
        if (key != lastDynamicKey) {
            otherChangeAt = now
            lastDynamicKey = key
        }
        return dynamic
    }

    /** 翻译线程。过时的结果（已经显示了更新的字幕）直接丢掉。 */
    private fun translateForPanel(paragraphs: List<String>, seq: Int, gen: Int) {
        if (seq < translateSeq - 1) return // 后面已经排了更新的字幕，这条不用翻了
        val start = SystemClock.elapsedRealtime()
        val translated = translateCached(paragraphs) ?: return
        val line = translated.joinToString(" ")
        Log.i(TAG, "实时译文 #$seq ${SystemClock.elapsedRealtime() - start} ms：$line")
        host.mainHandler.post {
            if (gen != generation || mode != Mode.SCREEN || seq <= shownSeq) return@post
            shownSeq = seq
            val lines = synchronized(history) {
                if (history.lastOrNull() != line) history.add(line)
                while (history.size > HISTORY_SIZE) history.removeAt(0)
                history.toList()
            }
            host.panel.setLines(lines)
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
        const val INTERVAL_MS = 400L
        const val HISTORY_SIZE = 3
        const val WATERMARK_MS = 8_000L
        const val WATERMARK_GAP_MS = 2_000L
        const val WATERMARK_MOVE_PX = 40
        const val CACHE_SIZE = 500
    }
}
