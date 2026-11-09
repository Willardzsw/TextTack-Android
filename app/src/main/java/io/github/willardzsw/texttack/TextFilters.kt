package io.github.willardzsw.texttack

/** 判断一段文字要不要拿去翻译。读节点和 OCR 共用。 */
object TextFilters {

    private val domainOnly = Regex("""^[\p{L}\p{N}-]+(\.[\p{L}\p{N}-]+)*\.[a-zA-Z]{2,}(:\d+)?([/?#]\S*)?$""")

    /**
     * 整段只是网址或域名：https://…、www.…、pantip.com、google.com/search?q，
     * 以及 Google 结果里的面包屑"https://pantip.com › topic"。
     */
    fun isUrlLike(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val head = t.substringBefore(" › ").trim()
        if (head.startsWith("http://", ignoreCase = true) ||
            head.startsWith("https://", ignoreCase = true) ||
            head.startsWith("www.", ignoreCase = true)
        ) {
            return !head.contains(' ')
        }
        return !t.contains(' ') && domainOnly.matches(t)
    }

    /** 至少有一个字母、文字或数字（"•••"、"…"、"+" 这种纯符号不算）。 */
    fun hasLetterOrDigit(text: String): Boolean = text.any { Character.isLetterOrDigit(it) }

    /** 读界面节点时，这段文字是否值得显示/翻译。 */
    fun keepNodeText(text: String): Boolean = hasLetterOrDigit(text) && !isUrlLike(text)

    /** OCR 识别出的一行是否保留：去掉网址、纯符号、低置信度的单个字符。 */
    fun keepOcrLine(text: String, score: Float): Boolean {
        val t = text.trim()
        if (!hasLetterOrDigit(t) || isUrlLike(t)) return false
        val glyphs = t.codePointCount(0, t.length)
        return !(glyphs <= 1 && score < SINGLE_GLYPH_MIN_SCORE)
    }

    private const val SINGLE_GLYPH_MIN_SCORE = 0.8f
}
