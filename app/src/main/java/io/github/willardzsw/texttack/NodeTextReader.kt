package io.github.willardzsw.texttack

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** 屏幕上的一段文字和它的位置（屏幕坐标）。 */
data class TextBlock(
    val text: String,
    val bounds: Rect,
    /** true 表示来自 contentDescription（多为图标、图片的说明），不是界面上直接显示的文字。 */
    val fromDescription: Boolean,
)

/**
 * 通过无障碍节点读取当前屏幕上所有可见文字。
 * 节点调用是跨进程的，放在后台线程跑。
 */
object NodeTextReader {

    private const val MAX_DEPTH = 80
    private const val MAX_BLOCKS = 400

    fun read(service: AccessibilityService): List<TextBlock> {
        val screen = Rect()
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

    private fun visit(node: AccessibilityNodeInfo, out: MutableMap<String, TextBlock>, depth: Int, rect: Rect) {
        if (depth > MAX_DEPTH || out.size >= MAX_BLOCKS) return
        if (!node.isVisibleToUser) return

        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        val fromDescription = text.isEmpty()
        val value = text.ifEmpty { description }
        if (value.isNotEmpty()) {
            node.getBoundsInScreen(rect)
            if (rect.width() > 0 && rect.height() > 0) {
                val key = value + "@" + rect.flattenToString()
                if (key !in out) out[key] = TextBlock(value, Rect(rect), fromDescription)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            visit(child, out, depth + 1, rect)
        }
    }
}
