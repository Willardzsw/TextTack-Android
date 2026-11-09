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
    fun read(service: AccessibilityService): List<TextBlock> {
        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        val screen = wm.maximumWindowMetrics.bounds
        val metrics = service.resources.displayMetrics
        val ctx = Ctx(
            screen = screen,
            minSize = (MIN_SIZE_DP * metrics.density).toInt(),
            minFontPx = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, MIN_FONT_SP, metrics),
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
                    visit(root, ctx, 0)
                    if (ctx.out.size >= MAX_BLOCKS) break
                }
            }
            // 占大半个屏幕的系统窗口（如全屏的系统装饰层）不算遮挡，免得把整屏都当成被盖住
            val large = bounds.width().toLong() * bounds.height() > screen.width().toLong() * screen.height() / 2
            if (!(window.type == AccessibilityWindowInfo.TYPE_SYSTEM && large)) ctx.covered.add(Rect(bounds))
        }
        if (windows.isEmpty()) service.rootInActiveWindow?.let { visit(it, ctx, 0) }
        return ctx.out.values.toList()
    }

    /** 调试用：当前窗口列表（层级、类型、范围、包名）。 */
    fun describeWindows(service: AccessibilityService): List<String> {
        val r = Rect()
        return service.windows.sortedByDescending { it.layer }.map { w ->
            w.getBoundsInScreen(r)
            "layer=${w.layer} type=${w.type} ${r.toShortString()} ${w.root?.packageName ?: "-"} ${w.title ?: ""}"
        }
    }

    private class Ctx(val screen: Rect, val minSize: Int, val minFontPx: Float) {
        val out = LinkedHashMap<String, TextBlock>()
        val covered = ArrayList<Rect>()
    }

    private fun visit(node: AccessibilityNodeInfo, ctx: Ctx, depth: Int) {
        if (depth > MAX_DEPTH || ctx.out.size >= MAX_BLOCKS) return
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) add(node, text, ctx)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            visit(child, ctx, depth + 1)
        }
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
