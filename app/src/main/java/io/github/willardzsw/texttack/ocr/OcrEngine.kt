package io.github.willardzsw.texttack.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import io.github.willardzsw.texttack.TextFilters
import java.nio.FloatBuffer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 本地 OCR：PP-OCRv6 tiny 检测 + 各语言 PP-OCR 识别，ONNX Runtime 在本进程 CPU 上运行。
 *
 * 前后处理参数照 Windows 版 TextTack（OnnxOcrEngine.cs）和它用的 RapidOcrNet（Apache-2.0）：
 * - 检测：长边超过 [maxDetectSide] 先缩小（Windows 版 2048，这里默认 1600）；右/下补黑边让 宽高+2×8 成为 32 的倍数，再四周加 8 像素白边；
 *   mean = std = 127.5，BGR，阈值 0.2 / 0.4 / 1.4
 * - 识别：按框透视裁出每一行，高 > 宽×1.5 时逆时针转 90°；缩到高 48、宽等比（不补边）；
 *   mean = std = 127.5，BGR；CTC 贪心解码；平均置信度 < 0.6 的行丢弃
 *
 * 不是线程安全的：所有调用都要在同一个后台线程上。
 */
class OcrEngine(private val context: Context) : AutoCloseable {

    /** 检测前长边上限。Windows 版是 2048；手机上 1600 更快（见 docs/第一版范围.md 的实测）。 */
    var maxDetectSide = DEFAULT_MAX_DETECT_SIDE
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var detector: OrtSession? = null
    private var recognizer: OrtSession? = null
    private var recognizerLanguage: OcrLanguage? = null
    private var keys: Array<String> = emptyArray()
    private val post = DbPostProcess()

    /**
     * 识别整张图。图是软件位图（ARGB_8888）。
     * [ignoreTop]：中心落在这条线以上的框不识别（用来跳过状态栏）。
     */
    fun recognize(bitmap: Bitmap, language: OcrLanguage, ignoreTop: Int = 0): OcrResult {
        val loadStart = SystemClock.elapsedRealtime()
        ensureLoaded(language)
        val loadMs = SystemClock.elapsedRealtime() - loadStart

        val detStart = SystemClock.elapsedRealtime()
        val detected = detect(bitmap)
        val detectMs = SystemClock.elapsedRealtime() - detStart

        val recStart = SystemClock.elapsedRealtime()
        val lines = ArrayList<OcrLine>()
        for (quad in detected.quads) {
            if ((quad[0].y + quad[2].y) / 2 < ignoreTop) continue
            val crop = cropLine(bitmap, quad) ?: continue
            val (text, score) = try {
                recognizeLine(crop)
            } finally {
                crop.recycle()
            }
            if (text.isBlank() || score < MIN_CONFIDENCE || !TextFilters.keepOcrLine(text, score)) continue
            lines.add(OcrLine(text.trim(), score, quad, boundsOf(quad, bitmap.width, bitmap.height)))
        }
        val recognizeMs = SystemClock.elapsedRealtime() - recStart

        return OcrResult(lines, detected.quads.size, loadMs, detectMs, recognizeMs, detected.width, detected.height)
    }

    /** 释放模型，省内存。下次识别时重新加载。 */
    override fun close() {
        detector?.close()
        recognizer?.close()
        detector = null
        recognizer = null
        recognizerLanguage = null
        keys = emptyArray()
    }

    val isLoaded: Boolean get() = detector != null

    private fun sessionOptions() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(4)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }

    private fun ensureLoaded(language: OcrLanguage) {
        if (detector == null) {
            detector = env.createSession(readAsset("ocr/det/det.onnx"), sessionOptions())
        }
        if (recognizerLanguage != language) {
            recognizer?.close()
            recognizer = env.createSession(readAsset("ocr/${language.modelDir}/rec.onnx"), sessionOptions())
            keys = loadKeys("ocr/${language.modelDir}/dict.txt")
            recognizerLanguage = language
        }
    }

    private fun readAsset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    /** 第 0 位是 CTC 空白，然后是字典每一行（只去掉换行，不 trim），最后追加一个空格。 */
    private fun loadKeys(path: String): Array<String> {
        val text = context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        val lines = text.split('\n').map { it.removeSuffix("\r") }.toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return (listOf("#") + lines + listOf(" ")).toTypedArray()
    }

    // ---------- 检测 ----------

    private class Detected(val quads: List<Array<PointF>>, val width: Int, val height: Int)

    private fun detect(source: Bitmap): Detected {
        val longest = max(source.width, source.height)
        val ratio = if (longest > maxDetectSide) maxDetectSide.toDouble() / longest else 1.0
        val body = if (ratio < 1.0) {
            Bitmap.createScaledBitmap(
                source,
                max(1, (source.width * ratio).roundToInt()),
                max(1, (source.height * ratio).roundToInt()),
                true,
            )
        } else {
            source
        }
        val bodyW = body.width
        val bodyH = body.height
        val ratioX = bodyW.toDouble() / source.width
        val ratioY = bodyH.toDouble() / source.height

        val alignedW = alignedLength(bodyW)
        val alignedH = alignedLength(bodyH)
        val inW = alignedW + 2 * PADDING
        val inH = alignedH + 2 * PADDING

        val pixels = IntArray(bodyW * bodyH)
        body.getPixels(pixels, 0, bodyW, 0, 0, bodyW, bodyH)
        if (body !== source) body.recycle()

        // 白边 = (255-127.5)/127.5 = 1；对齐补的透明黑边 = -1
        val plane = inW * inH
        val input = FloatArray(3 * plane) { 1f }
        for (y in 0 until alignedH) {
            val rowBase = (y + PADDING) * inW + PADDING
            for (x in 0 until alignedW) {
                val i = rowBase + x
                if (x < bodyW && y < bodyH) {
                    val c = pixels[y * bodyW + x]
                    input[i] = ((c and 0xFF) - 127.5f) / 127.5f                  // B
                    input[plane + i] = ((c shr 8 and 0xFF) - 127.5f) / 127.5f    // G
                    input[2 * plane + i] = ((c shr 16 and 0xFF) - 127.5f) / 127.5f // R
                } else {
                    input[i] = -1f
                    input[plane + i] = -1f
                    input[2 * plane + i] = -1f
                }
            }
        }

        val det = detector!!
        val pred = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, inH.toLong(), inW.toLong())).use { tensor ->
            det.run(mapOf(det.inputNames.first() to tensor)).use { out ->
                val t = out[0] as OnnxTensor
                val shape = t.info.shape
                val buf = t.floatBuffer
                val arr = FloatArray(buf.remaining())
                buf.get(arr)
                check(shape[2].toInt() == inH && shape[3].toInt() == inW) { "检测输出尺寸不符：${shape.contentToString()}" }
                arr
            }
        }

        val boxes = post.boxes(pred, inW, inH)
        val quads = boxes.map { box ->
            Array(4) { j ->
                val p = box.points[j]
                val x = ((p.x - PADDING).coerceIn(0f, bodyW.toFloat()) / ratioX).toFloat()
                val y = ((p.y - PADDING).coerceIn(0f, bodyH.toFloat()) / ratioY).toFloat()
                PointF(x.coerceIn(0f, source.width.toFloat()), y.coerceIn(0f, source.height.toFloat()))
            }
        }
        return Detected(quads, inW, inH)
    }

    private fun alignedLength(length: Int): Int {
        val overshoot = (length + 2 * PADDING) % ALIGNMENT
        return if (overshoot == 0) length else length + (ALIGNMENT - overshoot)
    }

    // ---------- 识别 ----------

    /** 同 PaddleOCR get_rotate_crop_image：透视裁成水平条，竖条逆时针转 90°。 */
    private fun cropLine(source: Bitmap, quad: Array<PointF>): Bitmap? {
        val w = max(dist(quad[0], quad[1]), dist(quad[3], quad[2])).roundToInt()
        val h = max(dist(quad[0], quad[3]), dist(quad[1], quad[2])).roundToInt()
        if (w < 2 || h < 2) return null

        val src = floatArrayOf(quad[0].x, quad[0].y, quad[1].x, quad[1].y, quad[2].x, quad[2].y, quad[3].x, quad[3].y)
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return null

        val crop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(crop).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        if (h < w * 1.5f) return crop

        val rotate = Matrix().apply { postRotate(-90f) }
        return Bitmap.createBitmap(crop, 0, 0, w, h, rotate, true).also { if (it !== crop) crop.recycle() }
    }

    private fun recognizeLine(crop: Bitmap): Pair<String, Float> {
        val dstW = max(MIN_REC_WIDTH, (crop.width * REC_HEIGHT.toFloat() / crop.height).toInt())
        val resized = Bitmap.createScaledBitmap(crop, dstW, REC_HEIGHT, true)
        val pixels = IntArray(dstW * REC_HEIGHT)
        resized.getPixels(pixels, 0, dstW, 0, 0, dstW, REC_HEIGHT)
        if (resized !== crop) resized.recycle()

        val plane = dstW * REC_HEIGHT
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = pixels[i]
            input[i] = ((c and 0xFF) - 127.5f) / 127.5f
            input[plane + i] = ((c shr 8 and 0xFF) - 127.5f) / 127.5f
            input[2 * plane + i] = ((c shr 16 and 0xFF) - 127.5f) / 127.5f
        }

        val rec = recognizer!!
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, REC_HEIGHT.toLong(), dstW.toLong())).use { tensor ->
            rec.run(mapOf(rec.inputNames.first() to tensor)).use { out ->
                val t = out[0] as OnnxTensor
                val shape = t.info.shape // [1, T, C]
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                val buf = t.floatBuffer
                decode(buf, steps, classes)
            }
        }
    }

    /** CTC 贪心解码：每步取最大，跳过空白和与上一步相同的类别。 */
    private fun decode(buf: FloatBuffer, steps: Int, classes: Int): Pair<String, Float> {
        val sb = StringBuilder()
        var scoreSum = 0f
        var count = 0
        var last = 0
        for (t in 0 until steps) {
            var maxIndex = 0
            var maxValue = -1000f
            val base = t * classes
            for (c in 0 until classes) {
                val v = buf.get(base + c)
                if (v > maxValue) {
                    maxValue = v
                    maxIndex = c
                }
            }
            if (maxIndex > 0 && maxIndex < keys.size && !(t > 0 && maxIndex == last)) {
                sb.append(keys[maxIndex])
                scoreSum += maxValue
                count++
            }
            last = maxIndex
        }
        return sb.toString() to if (count == 0) 0f else scoreSum / count
    }

    private fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)

    private fun boundsOf(quad: Array<PointF>, maxW: Int, maxH: Int): Rect {
        val left = quad.minOf { it.x }.toInt().coerceIn(0, maxW)
        val top = quad.minOf { it.y }.toInt().coerceIn(0, maxH)
        val right = quad.maxOf { it.x }.roundToInt().coerceIn(0, maxW)
        val bottom = quad.maxOf { it.y }.roundToInt().coerceIn(0, maxH)
        return Rect(left, top, right, bottom)
    }

    companion object {
        const val DEFAULT_MAX_DETECT_SIDE = 1600
        private const val PADDING = 8
        private const val ALIGNMENT = 32
        private const val REC_HEIGHT = 48
        private const val MIN_REC_WIDTH = 16
        private const val MIN_CONFIDENCE = 0.6f
    }
}
