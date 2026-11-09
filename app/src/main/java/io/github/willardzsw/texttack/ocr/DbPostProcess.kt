package io.github.willardzsw.texttack.ocr

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * DB 文字检测的后处理：概率图 → 文字框。
 *
 * 参考 RapidOcrNet（Apache-2.0，源自 RapidAI/RapidOCR）的 TextDetector.GetTextBoxes，
 * 用纯 Kotlin 重写，不依赖 OpenCV：
 * 1. 概率 > [boxThresh] 二值化，3×3 膨胀
 * 2. 8 连通区域（代替找轮廓）
 * 3. 最小外接矩形，短边 < 3 丢弃
 * 4. 区域内平均概率 < [boxScoreThresh] 丢弃
 * 5. 外扩：距离 = 面积 × [unclipRatio] / 周长（矩形四边各外扩这么多，等价于圆角偏移后再取最小外接矩形）
 * 6. 再检查短边 < 5 丢弃
 * 返回检测图坐标下的四边形（左上、右上、右下、左下），按阅读顺序排序。
 */
class DbPostProcess(
    private val boxThresh: Float = 0.2f,
    private val boxScoreThresh: Float = 0.4f,
    private val unclipRatio: Float = 1.4f,
) {
    class Box(val points: Array<PointF>, val score: Float)

    fun boxes(pred: FloatArray, width: Int, height: Int): List<Box> {
        val binary = dilate(threshold(pred), width, height)
        val labels = IntArray(width * height)
        val stack = IntArray(width * height)
        val result = ArrayList<Box>()
        var nextLabel = 0

        for (start in binary.indices) {
            if (binary[start] == 0.toByte() || labels[start] != 0) continue
            nextLabel++
            // 洪水填充，记录每行最左、最右像素
            var top = start / width
            var bottom = top
            var left = start % width
            var right = left
            val pixels = ArrayList<Int>()
            var sp = 0
            stack[sp++] = start
            labels[start] = nextLabel
            while (sp > 0) {
                val p = stack[--sp]
                pixels.add(p)
                val x = p % width
                val y = p / width
                if (y < top) top = y
                if (y > bottom) bottom = y
                if (x < left) left = x
                if (x > right) right = x
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val q = ny * width + nx
                        if (binary[q] != 0.toByte() && labels[q] == 0) {
                            labels[q] = nextLabel
                            stack[sp++] = q
                        }
                    }
                }
            }
            if (pixels.size < 3) continue

            val rows = bottom - top + 1
            val rowMin = IntArray(rows) { Int.MAX_VALUE }
            val rowMax = IntArray(rows) { -1 }
            for (p in pixels) {
                val r = p / width - top
                val x = p % width
                if (x < rowMin[r]) rowMin[r] = x
                if (x > rowMax[r]) rowMax[r] = x
            }

            val extremes = ArrayList<PointF>(rows * 2)
            for (r in 0 until rows) {
                if (rowMax[r] < 0) continue
                extremes.add(PointF(rowMin[r].toFloat(), (top + r).toFloat()))
                if (rowMax[r] != rowMin[r]) extremes.add(PointF(rowMax[r].toFloat(), (top + r).toFloat()))
            }
            val rect = minAreaRect(convexHull(extremes)) ?: continue
            if (min(rect.w, rect.h) < 3f) continue

            val score = score(pred, width, top, rowMin, rowMax)
            if (score < boxScoreThresh) continue

            val area = rect.w * rect.h
            val perimeter = 2f * (rect.w + rect.h)
            if (rect.w < 1.001f && rect.h < 1.001f) continue
            val distance = area * unclipRatio / perimeter
            val expanded = rect.copy(w = rect.w + 2 * distance, h = rect.h + 2 * distance)
            if (min(expanded.w, expanded.h) < 5f) continue

            result.add(Box(orderPoints(expanded.corners()), score))
        }
        return sortReadingOrder(result)
    }

    private fun threshold(pred: FloatArray) = ByteArray(pred.size) { if (pred[it] > boxThresh) 1 else 0 }

    private fun dilate(src: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (src[y * width + x] == 0.toByte()) continue
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx in 0 until width) out[ny * width + nx] = 1
                    }
                }
            }
        }
        return out
    }

    /** 区域按行填满（含内部空洞）后的平均概率。 */
    private fun score(pred: FloatArray, width: Int, top: Int, rowMin: IntArray, rowMax: IntArray): Float {
        var sum = 0.0
        var count = 0
        for (r in rowMin.indices) {
            if (rowMax[r] < 0) continue
            val base = (top + r) * width
            for (x in rowMin[r]..rowMax[r]) {
                sum += pred[base + x]
                count++
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    data class RotatedRect(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float) {
        fun corners(): Array<PointF> {
            val c = cos(angle)
            val s = sin(angle)
            val hw = w / 2
            val hh = h / 2
            return arrayOf(
                PointF(cx - hw * c + hh * s, cy - hw * s - hh * c),
                PointF(cx + hw * c + hh * s, cy + hw * s - hh * c),
                PointF(cx + hw * c - hh * s, cy + hw * s + hh * c),
                PointF(cx - hw * c - hh * s, cy - hw * s + hh * c),
            )
        }
    }

    companion object {
        /** Andrew 单调链凸包。 */
        fun convexHull(points: List<PointF>): List<PointF> {
            if (points.size < 3) return points
            val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
            fun cross(o: PointF, a: PointF, b: PointF) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
            val lower = ArrayList<PointF>()
            for (p in sorted) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
                lower.add(p)
            }
            val upper = ArrayList<PointF>()
            for (p in sorted.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
                upper.add(p)
            }
            lower.removeAt(lower.size - 1)
            upper.removeAt(upper.size - 1)
            return lower + upper
        }

        /** 旋转卡壳求最小面积外接矩形。 */
        fun minAreaRect(hull: List<PointF>): RotatedRect? {
            if (hull.isEmpty()) return null
            if (hull.size == 1) return RotatedRect(hull[0].x, hull[0].y, 0f, 0f, 0f)
            var best: RotatedRect? = null
            var bestArea = Float.MAX_VALUE
            for (i in hull.indices) {
                val a = hull[i]
                val b = hull[(i + 1) % hull.size]
                val len = hypot(b.x - a.x, b.y - a.y)
                if (len == 0f) continue
                val ux = (b.x - a.x) / len
                val uy = (b.y - a.y) / len
                var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
                var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
                for (p in hull) {
                    val u = p.x * ux + p.y * uy
                    val v = -p.x * uy + p.y * ux
                    if (u < minU) minU = u
                    if (u > maxU) maxU = u
                    if (v < minV) minV = v
                    if (v > maxV) maxV = v
                }
                val w = maxU - minU
                val h = maxV - minV
                val area = w * h
                if (area < bestArea) {
                    bestArea = area
                    val cu = (minU + maxU) / 2
                    val cv = (minV + maxV) / 2
                    best = RotatedRect(cu * ux - cv * uy, cu * uy + cv * ux, w, h, kotlin.math.atan2(uy, ux))
                }
            }
            return best
        }

        /** 排成左上、右上、右下、左下（同 RapidOcrNet GetMiniBox）。 */
        fun orderPoints(pts: Array<PointF>): Array<PointF> {
            val p = pts.sortedBy { it.x }
            val (tl, bl) = if (p[1].y > p[0].y) p[0] to p[1] else p[1] to p[0]
            val (tr, br) = if (p[3].y > p[2].y) p[2] to p[3] else p[3] to p[2]
            return arrayOf(tl, tr, br, bl)
        }

        /** 从上到下、同一行（左上角 y 相差 < 10）从左到右。 */
        fun sortReadingOrder(boxes: List<Box>): List<Box> {
            if (boxes.size < 2) return boxes
            val byY = boxes.sortedBy { it.points[0].y }
            val lineIds = IntArray(byY.size)
            for (i in 1 until byY.size) {
                val dy = byY[i].points[0].y - byY[i - 1].points[0].y
                lineIds[i] = if (abs(dy) >= 10f) lineIds[i - 1] + 1 else lineIds[i - 1]
            }
            return byY.indices.sortedWith(compareBy({ lineIds[it] }, { byY[it].points[0].x })).map { byY[it] }
        }
    }
}
