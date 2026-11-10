package io.github.willardzsw.texttack

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** 屏幕上的一段文字和它的位置（屏幕坐标）。 */
data class TextBlock(
    val text: String,
    val bounds: Rect,
    val source: Source,
) {
    enum class Source {
        /** 界面节点上直接显示的文字 */
        NODE_TEXT,
        /** 截屏后本地 OCR 识别 */
        OCR,
    }
}

/**
 * 通过无障碍节点读取当前屏幕上所有可见文字。
 * 节点调用是跨进程的，放在后台线程跑。
 */
object NodeTextReader {

    private const val MAX_DEPTH = 80
    private const val MAX_BLOCKS = 400
    private const val MIN_SIZE_DP = 4f
    private const val MIN_FONT_SP = 9f
    private const val MIN_MEDIA_DP = 80f
    private const val MIN_MEDIA_SCREEN_FRACTION = 0.04

    /** 不读：自己的悬浮层、状态栏/导航栏等系统窗口、输入法键盘。 */
    private val SKIPPED_WINDOW_TYPES = setOf(
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
        AccessibilityWindowInfo.TYPE_SYSTEM,
        AccessibilityWindowInfo.TYPE_INPUT_METHOD,
    )

    /**
     * 只读屏幕上真正看得见的文字（节点的 text），过滤规则：
     * - 不读 contentDescription：那是给读屏软件念的说明（如图标按钮"关于这条结果的详细信息"），屏幕上看不到
     * - 跳过只有网址/域名、纯符号的文字（[TextFilters.keepNodeText]）
     * - 不读状态栏、导航栏等系统窗口和输入法键盘
     * - 被更上层窗口（弹窗、菜单、键盘、状态栏、导航栏）盖住的文字不读：按窗口层级从上往下，文字中心落在上层窗口里就跳过
     * - 框裁到屏幕范围内；裁完宽或高小于 [MIN_SIZE_DP] 的跳过（网页给读屏软件准备的隐藏标题常是几个像素）
     * - 只露出一部分的段落先不读：按"框面积 ÷ 字数"估算字号，小于 [MIN_FONT_SP] 说明整段文字只露出一小条
     *   （滑动时被视口裁掉），硬塞译文会缩得很小；等它完全滑进来再读
     */
    fun read(service: AccessibilityService): List<TextBlock> = scan(service).blocks

    /** 一次读取的结果：界面文字 + 界面文字读不到的图片/视频/画布区域（要截图识别的地方）。 */
    class Scan(
        val blocks: List<TextBlock>,
        val media: List<Rect>,
        val mediaKinds: List<String>,
        /** 图片/视频区域所在的应用窗口（用来只截这个窗口，不截到自己的译文层）；没有为 -1。 */
        val mediaWindowId: Int = -1,
        /** 这个窗口在屏幕上的位置（窗口截图坐标 + 左上角 = 屏幕坐标）。 */
        val mediaWindowBounds: Rect = Rect(),
    )

    /**
     * 读界面文字，同时找出图片、视频、游戏画布这类区域：这些控件里的字读不到，只能截图识别。
     * 太小的（图标、头像）不算：宽、高都要至少 [MIN_MEDIA_DP]，面积至少屏幕的 [MIN_MEDIA_SCREEN_FRACTION]。
     */
    fun scan(service: AccessibilityService): Scan {
        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        val screen = wm.maximumWindowMetrics.bounds
        val metrics = service.resources.displayMetrics
        val ctx = Ctx(
            screen = screen,
            minSize = (MIN_SIZE_DP * metrics.density).toInt(),
            minFontPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, MIN_FONT_SP, metrics),
            minMediaPx = (MIN_MEDIA_DP * metrics.density).toInt(),
        )

        // 从最上层往下：每处理完一个窗口，就把它的范围算进"已被盖住"
        val windows = service.windows.sortedByDescending { it.layer }
        val bounds = Rect()
        for (window in windows) {
            window.getBoundsInScreen(bounds)
            if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            if (window.type !in SKIPPED_WINDOW_TYPES) {
                val root = window.root
                if (root != null && root.packageName != service.packageName) {
                    ctx.windowId = window.id
                    ctx.windowBounds = Rect(bounds)
                    visit(root, ctx, 0)
                    if (ctx.out.size >= MAX_BLOCKS) break
                }
            }
            // 占大半个屏幕的系统窗口（如全屏的系统装饰层）不算遮挡，免得把整屏都当成被盖住
            val large = bounds.width().toLong() * bounds.height() > screen.width().toLong() * screen.height() / 2
            if (!(window.type == AccessibilityWindowInfo.TYPE_SYSTEM && large)) ctx.covered.add(Rect(bounds))
        }
        if (windows.isEmpty()) service.rootInActiveWindow?.let { visit(it, ctx, 0) }
        val merged = mergeOverlapping(ctx.media)
        return Scan(ctx.out.values.toList(), merged, ctx.mediaKinds, ctx.mediaWindowId, ctx.mediaWindowBounds)
    }

    /** 相交的区域合并成一个外接矩形，减少截图识别的次数。 */
    private fun mergeOverlapping(rects: List<Rect>): List<Rect> {
        val out = rects.map { Rect(it) }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in out.indices) {
                for (j in i + 1 until out.size) {
                    if (Rect.intersects(out[i], out[j])) {
                        out[i].union(out[j])
                        out.removeAt(j)
                        changed = true
                        break@loop
                    }
                }
            }
        }
        return out
    }

    /** 调试用：当前窗口列表（层级、类型、范围、包名）。 */
    fun describeWindows(service: AccessibilityService): List<String> {
        val r = Rect()
        return service.windows.sortedByDescending { it.layer }.map { w ->
            w.getBoundsInScreen(r)
            "layer=${w.layer} type=${w.type} ${r.toShortString()} ${w.root?.packageName ?: "-"} ${w.title ?: ""}"
        }
    }

    private class Ctx(val screen: Rect, val minSize: Int, val minFontPx: Float, val minMediaPx: Int) {
        val out = LinkedHashMap<String, TextBlock>()
        val covered = ArrayList<Rect>()
        val media = ArrayList<Rect>()
        val mediaKinds = ArrayList<String>()
        var windowId = -1
        var windowBounds = Rect()
        var mediaWindowId = -1
        var mediaWindowBounds = Rect()
    }

    private fun visit(node: AccessibilityNodeInfo, ctx: Ctx, depth: Int) {
        if (depth > MAX_DEPTH || ctx.out.size >= MAX_BLOCKS) return
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) add(node, text, ctx)
        if (isMediaClass(node.className?.toString())) addMedia(node, ctx)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            visit(child, ctx, depth + 1)
        }
    }

    /**
     * 图片、视频、画布类控件：android.widget.ImageView 及其子类（类名以 ImageView 结尾）、
     * SurfaceView / TextureView / VideoView（视频、游戏），网页里的图片（WebView 报告为 android.widget.Image）。
     */
    private fun isMediaClass(cls: String?): Boolean {
        if (cls == null) return false
        val simple = cls.substringAfterLast('.')
        return simple.endsWith("ImageView") || simple == "Image" ||
            simple.endsWith("SurfaceView") || simple.endsWith("TextureView") || simple.endsWith("VideoView")
    }

    private fun addMedia(node: AccessibilityNodeInfo, ctx: Ctx) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.intersect(ctx.screen)) return
        if (rect.width() < ctx.minMediaPx || rect.height() < ctx.minMediaPx) return
        val screenArea = ctx.screen.width().toLong() * ctx.screen.height()
        if (rect.width().toLong() * rect.height() < screenArea * MIN_MEDIA_SCREEN_FRACTION) return
        if (ctx.covered.any { it.contains(rect.centerX(), rect.centerY()) }) return
        // 只取第一个（最上层）窗口里的区域：实时模式只截这一个窗口
        if (ctx.mediaWindowId == -1) {
            ctx.mediaWindowId = ctx.windowId
            ctx.mediaWindowBounds = Rect(ctx.windowBounds)
        } else if (ctx.mediaWindowId != ctx.windowId) {
            return
        }
        ctx.media.add(rect)
        ctx.mediaKinds.add("${node.className?.toString()?.substringAfterLast('.')} ${rect.toShortString()}")
    }

    private fun add(node: AccessibilityNodeInfo, value: String, ctx: Ctx) {
        if (!TextFilters.keepNodeText(value)) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.intersect(ctx.screen) || rect.width() < ctx.minSize || rect.height() < ctx.minSize) return
        if (ctx.covered.any { it.contains(rect.centerX(), rect.centerY()) }) return
        if (impliedFontPx(value, rect) < ctx.minFontPx) return
        val key = value + "@" + rect.flattenToString()
        if (key !in ctx.out) ctx.out[key] = TextBlock(value, rect, TextBlock.Source.NODE_TEXT)
    }

    /**
     * 这段文字要完整放进这个框，字号大约是多少（像素）。
     * 宽度单位：中日韩等全角字 1，泰文上下标记 0，空格 0.3，其他 0.55；行高按 1.25 倍字号。
     */
    fun impliedFontPx(text: String, rect: Rect): Float {
        var units = 0f
        for (c in text) {
            units += when {
                c in '\u0E31'..'\u0E31' || c in '\u0E34'..'\u0E3A' || c in '\u0E47'..'\u0E4E' -> 0f
                Character.isWhitespace(c) -> 0.3f
                c in '\u2E80'..'\u9FFF' || c in '\uAC00'..'\uD7AF' || c in '\uF900'..'\uFAFF' || c in '\uFF00'..'\uFFEF' -> 1f
                else -> 0.55f
            }
        }
        if (units <= 0f) return Float.MAX_VALUE
        return kotlin.math.sqrt(rect.width().toFloat() * rect.height() / (units * 1.25f))
    }
}
