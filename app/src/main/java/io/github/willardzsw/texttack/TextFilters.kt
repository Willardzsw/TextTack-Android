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

    private val countUnits = Regex(
        "(小时前|分钟前|秒前|天前|周前|个月前|年前|ago|views?|likes?|comments?|[万千亿次赞条KkMmBbHhDdWwSs])",
    )
    private val numericSymbols = Regex("""[\d\s.,:：/+\-%×xX·•|()（）'’]""")

    /**
     * 只有数字、符号和计数单位：点赞数、评论数、时长、时间，如"1.2K""453""12:34""99+""3.5万""2天前"。
     * 必须含数字；"第 3 集""Episode 12"这种有正常文字的不算。
     */
    fun isNumericNoise(text: String): Boolean {
        val t = text.trim()
        if (t.none { it.isDigit() }) return false
        return numericSymbols.replace(countUnits.replace(t, ""), "").isEmpty()
    }

    /** 读界面节点时，这段文字是否值得显示/翻译。 */
    fun keepNodeText(text: String): Boolean = hasLetterOrDigit(text) && !isUrlLike(text) && !isNumericNoise(text)

    /** OCR 识别出的一行是否保留：去掉网址、纯符号、低置信度的单个字符。 */
    fun keepOcrLine(text: String, score: Float): Boolean {
        val t = text.trim()
        if (!hasLetterOrDigit(t) || isUrlLike(t) || isNumericNoise(t)) return false
        val glyphs = t.codePointCount(0, t.length)
        return !(glyphs <= 1 && score < SINGLE_GLYPH_MIN_SCORE)
    }

    private const val SINGLE_GLYPH_MIN_SCORE = 0.8f
}
