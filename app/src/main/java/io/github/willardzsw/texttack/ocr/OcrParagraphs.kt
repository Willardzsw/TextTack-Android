package io.github.willardzsw.texttack.ocr

import android.graphics.Rect
import kotlin.math.abs

/**
 * 把 OCR 识别出的一行行文字合并成段落，再拿去翻译（一句话被换行拆开时，单独翻每行会不通顺）。
 *
 * 合并条件（下一行接在当前段落最后一行后面）：
 * - 左边对齐：左边相差不超过行高的 0.6 倍
 * - 行高相近：0.7～1.4 倍（泰文上下有标记，行框高度差别大，不能卡太严；代价是字号相近的标题会并进正文）
 * - 行距小：上下间隔在 -0.3～0.7 倍行高之间
 * 拼接：两边都是拉丁字母/数字时加空格，其他（泰文、中文）直接连上。
 */
object OcrParagraphs {

    data class Paragraph(val text: String, val bounds: Rect)

    fun group(lines: List<OcrLine>): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        var text = StringBuilder()
        var bounds: Rect? = null
        var last: Rect? = null

        for (line in lines) {
            val b = line.bounds
            val prev = last
            if (prev != null && continues(prev, b)) {
                text = join(text, line.text)
                bounds!!.union(b)
            } else {
                if (bounds != null) out.add(Paragraph(text.toString(), bounds))
                text = StringBuilder(line.text)
                bounds = Rect(b)
            }
            last = b
        }
        if (bounds != null) out.add(Paragraph(text.toString(), bounds))
        return out
    }

    private fun continues(prev: Rect, next: Rect): Boolean {
        val h = (prev.height() + next.height()) / 2f
        if (h <= 0f) return false
        val ratio = next.height().toFloat() / prev.height().coerceAtLeast(1)
        if (ratio < 0.7f || ratio > 1.4f) return false
        if (abs(next.left - prev.left) > 0.6f * h) return false
        val gap = next.top - prev.bottom
        return gap >= -0.3f * h && gap <= 0.7f * h
    }

    private fun join(sb: StringBuilder, next: String): StringBuilder {
        val a = sb.lastOrNull()
        val b = next.firstOrNull()
        if (a != null && b != null && isLatin(a) && isLatin(b)) sb.append(' ')
        else if (a != null && b != null && a in ".,;:!?" && isLatin(b)) sb.append(' ')
        return sb.append(next)
    }

    private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in 'À'..'ɏ'
}
