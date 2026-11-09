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

    /** 不读：自己的悬浮层、状态栏/导航栏等系统窗口、输入法键盘。 */
    private val SKIPPED_WINDOW_TYPES = setOf(
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
        AccessibilityWindowInfo.TYPE_SYSTEM,
        AccessibilityWindowInfo.TYPE_INPUT_METHOD,
    )

    /**
     * 只读屏幕上真正显示出来的文字（节点的 text），过滤规则：
     * - 不读 contentDescription：那是给读屏软件念的说明（如图标按钮"关于这条结果的详细信息"），屏幕上看不到
     * - 跳过只有网址/域名、纯符号的文字（[TextFilters.keepNodeText]）
     * - 不读状态栏、导航栏等系统窗口和输入法键盘
     * - 框裁到屏幕范围内；裁完宽或高小于 [MIN_SIZE_DP] 的跳过（网页给读屏软件准备的隐藏标题常是几个像素）
     */
    fun read(service: AccessibilityService): List<TextBlock> {
        val screen = (service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager)
            .maximumWindowMetrics.bounds
        val out = LinkedHashMap<String, TextBlock>()
        val minSize = (MIN_SIZE_DP * service.resources.displayMetrics.density).toInt()

        val roots = service.windows
            .filter { it.type !in SKIPPED_WINDOW_TYPES }
            .mapNotNull { it.root }
            .ifEmpty { listOfNotNull(service.rootInActiveWindow) }

        for (root in roots) {
            if (root.packageName == service.packageName) continue
            visit(root, out, 0, screen, minSize)
            if (out.size >= MAX_BLOCKS) break
        }
        return out.values.toList()
    }

    private fun visit(node: AccessibilityNodeInfo, out: MutableMap<String, TextBlock>, depth: Int, screen: Rect, minSize: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_BLOCKS) return
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) add(node, text, out, screen, minSize)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            visit(child, out, depth + 1, screen, minSize)
        }
    }

    private fun add(node: AccessibilityNodeInfo, value: String, out: MutableMap<String, TextBlock>, screen: Rect, minSize: Int) {
        if (!TextFilters.keepNodeText(value)) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.intersect(screen) || rect.width() < minSize || rect.height() < minSize) return
        val key = value + "@" + rect.flattenToString()
        if (key !in out) out[key] = TextBlock(value, rect, TextBlock.Source.NODE_TEXT)
    }
}
