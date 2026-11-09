package io.github.willardzsw.texttack.ocr

import android.graphics.PointF
import android.graphics.Rect

/** 识别语言，对应 assets/ocr/ 下的识别模型目录。 */
enum class OcrLanguage(val key: String, val modelDir: String, val label: String) {
    THAI("th", "thai", "泰语"),
    CJK("cjk", "cjk", "中文和英文");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: THAI
    }
}

/** 一行识别结果。[quad] 是原图上的四个角（左上、右上、右下、左下）。 */
data class OcrLine(
    val text: String,
    val score: Float,
    val quad: Array<PointF>,
    val bounds: Rect,
)

/** 一次识别的结果和各步骤耗时（毫秒）。 */
data class OcrResult(
    val lines: List<OcrLine>,
    val boxCount: Int,
    val loadMs: Long,
    val detectMs: Long,
    val recognizeMs: Long,
    val detectorWidth: Int,
    val detectorHeight: Int,
)
