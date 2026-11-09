package io.github.willardzsw.texttack.translate

/**
 * 发给模型的提示词。照 Windows 版 OpenAiPrompt + OpenAiCompatibleProvider.BuildPrompt（简体中文界面的措辞）。
 *
 * 可用参数：{text} {context} {source_name} {source_code} {target_name} {target_code}，
 * 以及旧写法 {source} {target}。源语言固定为"自动"。
 */
class OpenAiPrompt private constructor(
    val template: String,
    private val targetLabel: String,
    val isDefault: Boolean,
) {
    data class Pair(val source: String, val translation: String)

    val hasContext: Boolean get() = template.contains(CONTEXT, ignoreCase = true)

    fun build(text: String, earlier: List<Pair>? = null): String =
        fill(template, context(earlier), bare(text))

    fun buildBatch(texts: List<String>, earlier: List<Pair>? = null): String {
        val body = texts.withIndex().joinToString("\n") { (i, t) -> "<${i + 1}>${oneLine(t)}</${i + 1}>" }
        return fill(template, context(earlier), body)
    }

    private fun context(earlier: List<Pair>?): String {
        if (earlier.isNullOrEmpty()) return ""
        return HISTORY + "\n" + earlier.joinToString("\n\n") {
            AUTOMATIC_SOURCE + COLON + oneLine(it.source) + "\n" + targetLabel + COLON + oneLine(it.translation)
        }
    }

    companion object {
        const val CONTEXT = "{context}"
        const val TEXT = "{text}"
        const val SOURCE_NAME = "{source_name}"
        const val TARGET_NAME = "{target_name}"
        const val SOURCE_CODE = "{source_code}"
        const val TARGET_CODE = "{target_code}"
        const val LEGACY_SOURCE = "{source}"
        const val LEGACY_TARGET = "{target}"

        private const val INSTRUCTION = "将以下文本翻译为$TARGET_NAME，只输出译文，不加标签，不要解释："
        private const val HISTORY = "以下是之前的翻译，翻译时可参考："
        private const val BATCH_INSTRUCTION = "将以下文本翻译为$TARGET_NAME，英文单词与短语也要翻译，只输出译文，不加标签，不要解释："
        private const val BATCH_NOTE = "(按编号输出译文，例如 <1>译文</1>，一段一行)"
        private const val AUTOMATIC_SOURCE = "原文"
        private const val COLON = "："

        fun defaultTemplate(batch: Boolean): String {
            val template = CONTEXT + "\n\n" + (if (batch) BATCH_INSTRUCTION else INSTRUCTION) + "\n" + TEXT
            return if (batch) template + "\n\n" + BATCH_NOTE else template
        }

        fun normaliseLineBreaks(text: String) = text.replace("\r\n", "\n").replace('\r', '\n')

        fun isDefaultTemplate(text: String, batch: Boolean): Boolean {
            val t = normaliseLineBreaks(text).trim()
            return t.isEmpty() || t == defaultTemplate(batch)
        }

        /** 保存时：和默认一样就存空。 */
        fun templateToStore(text: String, batch: Boolean): String =
            if (isDefaultTemplate(text, batch)) "" else normaliseLineBreaks(text)

        fun create(template: String, target: TargetLanguage, batch: Boolean): OpenAiPrompt {
            val own = normaliseLineBreaks(template)
            val isDefault = own.trim().isEmpty()
            val raw = if (isDefault) defaultTemplate(batch) else own
            val filled = raw
                .replace(SOURCE_CODE, "", ignoreCase = true)
                .replace(TARGET_CODE, target.modelTag, ignoreCase = true)
                .replace(SOURCE_NAME, "any language", ignoreCase = true)
                .replace(TARGET_NAME, target.label, ignoreCase = true)
                .replace(LEGACY_SOURCE, "any language", ignoreCase = true)
                .replace(LEGACY_TARGET, target.label, ignoreCase = true)
            return OpenAiPrompt(filled, target.label, isDefault)
        }

        private val PLACEHOLDERS = Regex("""(?<before>\n*)\{context\}(?<after>\n*)|\{text\}""", RegexOption.IGNORE_CASE)

        private fun fill(template: String, context: String, text: String): String {
            var hasText = false
            val message = PLACEHOLDERS.replace(template) { m ->
                val before = m.groups["before"]
                if (before == null) {
                    hasText = true
                    return@replace text
                }
                val after = m.groups["after"]!!.value
                if (context.isNotEmpty()) return@replace before.value + context + after
                val atStart = m.range.first == 0
                val atEnd = m.range.last + 1 == template.length
                val gap = minOf(2, maxOf(before.value.length, after.length))
                if (atStart || atEnd) "" else "\n".repeat(gap)
            }
            return if (hasText) message else message + text
        }

        fun oneLine(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        private fun bare(text: String) = normaliseLineBreaks(text)
    }
}
