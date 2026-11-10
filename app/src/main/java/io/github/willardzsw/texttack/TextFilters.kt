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

    private val counterLabel = Regex(
        """(?i)^(\S+\s+)?(number\s+is|count\s+is)\s+[\d.,]+[kmb]?$|^\S+\s+[\d.,]+[kmb]?\s+times?$|""" +
            """^[\d.,]+[kmb]?\s+(likes?|comments?|views?|shares?|reposts?|reshares?|saves?|plays?|replies|reply)$""",
    )

    /** 中文写法：已转发726次、转发次数为26984、收藏次数为1845、点赞数为1.2万、3.5万次播放、128条评论。 */
    private val counterLabelZh = Regex(
        """^\S{0,6}?(次数|数量|数)[为是：:]\s*[\d.,]+[万千亿Kk]?$|^已?\S{1,4}[\d.,]+[万千亿]?次$|""" +
            """^[\d.,]+[万千亿]?\s*(次播放|次观看|次转发|次分享|条评论|个赞|人点赞|次收藏)$""",
    )

    /**
     * 按钮上藏着的计数说明：屏幕上只显示数字，节点文字却是一句话，
     * 如 Instagram 英文界面的"Reposted 22 times""Save number is 76""1,234 likes"，
     * 中文界面的"已转发726次""转发次数为26984""收藏次数为1845"。
     * 只匹配这类很短的固定句式，正常句子不受影响。
     */
    fun isCounterLabel(text: String): Boolean {
        val t = text.trim()
        return counterLabel.matches(t) || counterLabelZh.matches(t)
    }

    /**
     * 这段文字本来就是目标语言（按文字种类粗判）：实时截屏里这种不翻、不盖，画面更干净。
     * - 简体/繁体中文：有汉字，没有拉丁字母、泰文、日文假名、韩文
     * - 泰语：有泰文，没有拉丁字母、汉字
     * - 英语：只有拉丁字母
     * - 日语：有假名；韩语：有韩文
     */
    fun isInTargetLanguage(text: String, target: io.github.willardzsw.texttack.translate.TargetLanguage): Boolean {
        var han = false; var latin = false; var thai = false; var kana = false; var hangul = false
        for (c in text) {
            when {
                c in '\u4E00'..'\u9FFF' || c in '\u3400'..'\u4DBF' -> han = true
                c in 'A'..'Z' || c in 'a'..'z' -> latin = true
                c in '\u0E00'..'\u0E7F' -> thai = true
                c in '\u3040'..'\u30FF' -> kana = true
                c in '\uAC00'..'\uD7AF' -> hangul = true
            }
        }
        return when (target) {
            io.github.willardzsw.texttack.translate.TargetLanguage.SIMPLIFIED_CHINESE,
            io.github.willardzsw.texttack.translate.TargetLanguage.TRADITIONAL_CHINESE -> han && !latin && !thai && !kana && !hangul
            io.github.willardzsw.texttack.translate.TargetLanguage.THAI -> thai && !latin && !han
            io.github.willardzsw.texttack.translate.TargetLanguage.ENGLISH -> latin && !han && !thai && !kana && !hangul
            io.github.willardzsw.texttack.translate.TargetLanguage.JAPANESE -> kana
            io.github.willardzsw.texttack.translate.TargetLanguage.KOREAN -> hangul
        }
    }

    /** 读界面节点时，这段文字是否值得显示/翻译。 */
    fun keepNodeText(text: String): Boolean =
        hasLetterOrDigit(text) && !isUrlLike(text) && !isNumericNoise(text) && !isCounterLabel(text)

    private val codeToken = Regex("""^[A-Za-z0-9.\-/*_+:#]+$""")
    private val edgePunct = Regex("""^[\p{Punct}「」『』“”‘’（）【】]+|[\p{Punct}「」『』“”‘’（）【】]+$""")

    /**
     * 识别结果里去掉像编号、型号、车牌、水印的词：只由拉丁字母、数字和 - / . * _ + : # 组成，
     * 并且字母、数字都有（如"JT-45""Z15""z18EAE2T/k""J7-43"）。泰文、中文、普通英文单词和句子里单独的数字不受影响。
     * 返回去掉后的文字；没剩下任何字母（泰文、中文、英文）就返回空字符串。
     */
    fun stripCodeTokens(text: String): String {
        val kept = text.split(Regex("""\s+""")).filter { raw ->
            val token = edgePunct.replace(raw, "")
            // 必须字母和数字都有：句子里单独的数字（"3 美元"）不动
            !(token.any { it.isDigit() } && token.any { it in 'A'..'Z' || it in 'a'..'z' } && codeToken.matches(token))
        }
        val joined = kept.joinToString(" ").trim()
        return if (joined.any { Character.isLetter(it) }) joined else ""
    }

    /** OCR 识别出的一行是否保留：去掉网址、纯符号、低置信度的单个字符。 */
    fun keepOcrLine(text: String, score: Float): Boolean {
        val t = text.trim()
        if (!hasLetterOrDigit(t) || isUrlLike(t) || isNumericNoise(t)) return false
        val glyphs = t.codePointCount(0, t.length)
        return !(glyphs <= 1 && score < SINGLE_GLYPH_MIN_SCORE)
    }

    private const val SINGLE_GLYPH_MIN_SCORE = 0.8f
    private const val LATIN_ONLY_MIN_SCORE = 0.9f
    private const val SHORT_LATIN_MIN_SCORE = 0.97f
    private const val SHORT_LATIN_MAX_LETTERS = 3

    /**
     * 泰语识别时，没有泰文（也没有中日韩文字）、只有拉丁字母的行，可信度门槛提高：
     * 至少 [LATIN_ONLY_MIN_SCORE]；3 个字母以内的短行至少 [SHORT_LATIN_MIN_SCORE]。
     * 依据（0.6.6 在 IG 视频上收集的 206 行）：真英文字幕可信度中位数 0.98，乱码（"ove""Sis""se"、
     * 拼乱的长串）都在 0.9 以下；"S""is"这类短误识别在 0.93～0.95。
     */
    fun keepLatinLineInThaiMode(text: String, score: Float): Boolean {
        if (text.any { it in '\u0E00'..'\u0E7F' || it in '\u2E80'..'\u9FFF' || it in '\uAC00'..'\uD7AF' }) return true
        val letters = text.count { Character.isLetter(it) }
        if (letters == 0) return true
        return if (letters <= SHORT_LATIN_MAX_LETTERS) score >= SHORT_LATIN_MIN_SCORE else score >= LATIN_ONLY_MIN_SCORE
    }
}
