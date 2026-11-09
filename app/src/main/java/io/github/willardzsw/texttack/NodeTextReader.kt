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
        /** 节点的 contentDescription（多为图标、图片的说明） */
        NODE_DESCRIPTION,
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

    /**
     * 过滤规则：
     * - 节点下面已经有带文字的子节点时，不用它自己的 contentDescription（多半是把子节点文字拼成一句的重复内容）
     * - 跳过只有网址/域名、纯符号的文字（[TextFilters.keepNodeText]）
     * - 框裁到屏幕范围内，完全在屏幕外的跳过
     */
    fun read(service: AccessibilityService): List<TextBlock> {
        val screen = (service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager)
            .maximumWindowMetrics.bounds
        val out = LinkedHashMap<String, TextBlock>()

        val roots = service.windows
            .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .mapNotNull { it.root }
            .ifEmpty { listOfNotNull(service.rootInActiveWindow) }

        for (root in roots) {
            if (root.packageName == service.packageName) continue
            visit(root, out, 0, screen)
            if (out.size >= MAX_BLOCKS) break
        }
        return out.values.toList()
    }

    /** 返回这个节点或它的子孙是否带有文字（过滤前），用来判断说明文字是不是重复的。 */
    private fun visit(node: AccessibilityNodeInfo, out: MutableMap<String, TextBlock>, depth: Int, screen: Rect): Boolean {
        if (depth > MAX_DEPTH || out.size >= MAX_BLOCKS) return false
        if (!node.isVisibleToUser) return false

        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()

        // 先记下自己的文字，再遍历子节点，保持从上到下的阅读顺序
        if (text.isNotEmpty()) add(node, text, TextBlock.Source.NODE_TEXT, out, screen)

        var childHasText = false
        val descriptionSlot = if (text.isEmpty() && description.isNotEmpty()) out.size else -1
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (visit(child, out, depth + 1, screen)) childHasText = true
        }

        if (descriptionSlot >= 0 && !childHasText) {
            add(node, description, TextBlock.Source.NODE_DESCRIPTION, out, screen)
        }
        return text.isNotEmpty() || description.isNotEmpty() || childHasText
    }

    private fun add(node: AccessibilityNodeInfo, value: String, source: TextBlock.Source, out: MutableMap<String, TextBlock>, screen: Rect) {
        if (!TextFilters.keepNodeText(value)) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.intersect(screen) || rect.width() <= 0 || rect.height() <= 0) return
        val key = value + "@" + rect.flattenToString()
        if (key !in out) out[key] = TextBlock(value, rect, source)
    }
}
