package io.github.willardzsw.texttack.translate

import android.util.Log
import io.github.willardzsw.texttack.TAG
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** OpenAI 兼容服务一次翻译要用的全部设置。 */
data class OpenAiOptions(
    val baseUrl: String,
    val apiKey: String,
    val timeoutSeconds: Int,
    val profile: OpenAiModelProfile,
)

/**
 * OpenAI 兼容翻译。照 Windows 版 OpenAiCompatibleProvider + OpenAiChatTranslator + OpenAiAnswer：
 * - 标准模式：提示词带 {context} 时逐段依次翻译（前面的译文作为上下文），否则最多 8 段并发
 * - 整批模式：多段编号 <1>…</1> 一次发出（每批最多 40 段，受 4096 上下文限制），缺号的再一段一批补发
 * - 请求只带用户打开的参数（temperature、top_p、seed、reasoning_effort）
 * - 回复去掉 <think> 思考段、代码块、包裹的引号/标签
 */
class OpenAiTranslator(private val options: OpenAiOptions) : Translator {

    private class ChatException(
        val kind: Kind,
        val status: Int? = null,
        val serverMessage: String? = null,
        cause: Throwable? = null,
    ) : Exception(kind.name, cause) {
        enum class Kind { REJECTED, TIMED_OUT, UNPARSABLE, NO_TRANSLATION }
    }

    private val endpoint: String = buildEndpoint(options.baseUrl)
    private val model = options.profile.model.trim()

    override fun translate(texts: List<String>, target: TargetLanguage): List<String?> {
        if (texts.isEmpty()) return emptyList()
        if (model.isEmpty()) throw TranslationException("尚未指定模型名称。请在「翻译服务设置」中填入要使用的模型。")
        val prompt = OpenAiPrompt.create(options.profile.template, target, options.profile.batch)
        val job = Job(texts, prompt)
        when {
            options.profile.batch -> batch(job)
            prompt.hasContext -> segments(job)
            else -> alones(job)
        }
        Log.i(TAG, "OpenAI 翻译：${texts.size} 段，模型 $model，${if (options.profile.batch) "整批" else "标准"}，" +
            "${if (prompt.isDefault) "默认提示词" else "自定义提示词"}，失败 ${job.failed} 段")
        if (job.translated == 0 && job.outcomes.any { it == null }) {
            throw TranslationException(localized(job.firstError))
        }
        return job.outcomes.toList()
    }

    private inner class Job(val texts: List<String>, val prompt: OpenAiPrompt) {
        val outcomes = arrayOfNulls<String>(texts.size)
        @Volatile var translated = 0
        @Volatile var failed = 0
        @Volatile var firstError: Exception? = null

        @Synchronized fun succeed(i: Int, t: String, sent: Boolean = true) {
            outcomes[i] = t
            if (sent) translated++
        }

        @Synchronized fun fail(e: Exception) {
            failed++
            if (firstError == null) firstError = e
        }

        val stopped get() = translated == 0 && failed >= MAX_CONCURRENT
    }

    // ---------- 标准模式 ----------

    private fun segments(job: Job) {
        val sent = sendable(job)
        val soFar = ArrayList<OpenAiPrompt.Pair>()
        for (index in sent) {
            if (job.stopped) break
            val text = job.texts[index]
            try {
                val translation = onceMore(job, message(job.prompt, text, soFar), text, ofOne = false)
                job.succeed(index, translation)
                soFar.add(OpenAiPrompt.Pair(text, translation))
            } catch (e: Exception) {
                job.fail(e)
                if (!mightBeTheTexts(e)) break
            }
        }
    }

    private fun alones(job: Job) {
        val pool = Executors.newFixedThreadPool(MAX_CONCURRENT)
        try {
            val tasks = job.texts.indices.map { index ->
                Callable {
                    if (job.stopped) return@Callable
                    val text = job.texts[index]
                    if (!hasLetters(text)) {
                        job.succeed(index, text, sent = false)
                        return@Callable
                    }
                    try {
                        job.succeed(index, segment(job.prompt.build(text), text, job.prompt, ofOne = false))
                    } catch (e: Exception) {
                        job.fail(e)
                    }
                }
            }
            pool.invokeAll(tasks)
        } finally {
            pool.shutdown()
        }
    }

    // ---------- 整批模式 ----------

    private fun batch(job: Job) {
        val sent = sendable(job)
        val prompt = job.prompt
        val done = ArrayList<OpenAiPrompt.Pair>()
        fun carried(): List<OpenAiPrompt.Pair>? = if (prompt.hasContext) done.toList() else null
        val again = ArrayList<Int>()
        var start = 0
        var stop: Exception? = null
        while (start < sent.size && stop == null) {
            val rest = sent.drop(start).map { job.texts[it] }
            val (count, earlier) = batchSize(prompt, rest, carried())
            val group = sent.subList(start, start + count)
            start += count
            if (count == 1) {
                again.add(group[0])
                continue
            }
            try {
                val texts = group.map { job.texts[it] }
                val answer = send(prompt.buildBatch(texts, earlier))
                val read = readNumbered(answer, count)
                for (k in 0 until count) {
                    val translation = read[k]
                    if (translation == null) {
                        again.add(group[k])
                        continue
                    }
                    job.succeed(group[k], translation)
                    done.add(OpenAiPrompt.Pair(texts[k], translation))
                }
            } catch (e: Exception) {
                if (mightBeTheTexts(e)) {
                    again.addAll(group)
                    continue
                }
                stop = e
                job.fail(e)
            }
        }
        if (stop == null) oneByOne(job, again, carried())
    }

    private fun oneByOne(job: Job, again: List<Int>, earlier: List<OpenAiPrompt.Pair>?) {
        if (again.isEmpty()) return
        val unreachable = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(MAX_CONCURRENT)
        try {
            pool.invokeAll(again.map { index ->
                Callable {
                    if (unreachable.get() || job.stopped) return@Callable
                    val text = job.texts[index]
                    try {
                        val message = cutToFit(text, earlier.orEmpty()) { job.prompt.buildBatch(listOf(text), it) }
                        job.succeed(index, onceMore(job, message, text, ofOne = true))
                    } catch (e: Exception) {
                        if (!mightBeTheTexts(e)) unreachable.set(true)
                        job.fail(e)
                    }
                }
            })
        } finally {
            pool.shutdown()
        }
    }

    private fun batchSize(prompt: OpenAiPrompt, texts: List<String>, earlier: List<OpenAiPrompt.Pair>?): kotlin.Pair<Int, List<OpenAiPrompt.Pair>?> {
        fun fits(count: Int, carried: List<OpenAiPrompt.Pair>?): Boolean {
            val some = texts.take(count)
            return prompt.buildBatch(some, carried).length + 32 + 2 * some.sumOf { it.length } <= CONTEXT_TOKENS
        }
        if (texts.size < 2) return texts.size to earlier
        var carried = earlier
        while (!fits(2, carried)) {
            if (carried.isNullOrEmpty()) return 1 to earlier
            carried = if (carried.size > 1) carried.drop(carried.size / 2) else null
        }
        var count = 2
        while (count < texts.size && count < MAX_BATCH_TEXTS && fits(count + 1, carried)) count++
        return count to carried
    }

    // ---------- 公共 ----------

    private fun sendable(job: Job): List<Int> {
        val sent = ArrayList<Int>()
        job.texts.forEachIndexed { i, t -> if (hasLetters(t)) sent.add(i) else job.succeed(i, t, sent = false) }
        return sent
    }

    private fun message(prompt: OpenAiPrompt, text: String, earlier: List<OpenAiPrompt.Pair>) =
        cutToFit(text, earlier) { prompt.build(text, it) }

    private fun cutToFit(text: String, earlier: List<OpenAiPrompt.Pair>, build: (List<OpenAiPrompt.Pair>) -> String): String {
        val kept = earlier.toMutableList()
        var message = build(kept)
        while (message.length + 32 + 2 * text.length > CONTEXT_TOKENS && kept.isNotEmpty()) {
            kept.removeAt(0)
            message = build(kept)
        }
        return message
    }

    /** 失败且可能是内容的问题时，去掉上下文单独再试一次。 */
    private fun onceMore(job: Job, message: String, text: String, ofOne: Boolean): String {
        try {
            return segment(message, text, job.prompt, ofOne)
        } catch (e: Exception) {
            if (!mightBeTheTexts(e)) throw e
            val alone = if (ofOne) job.prompt.buildBatch(listOf(text)) else job.prompt.build(text)
            if (alone == message) throw e
            try {
                return segment(alone, text, job.prompt, ofOne)
            } catch (again: Exception) {
                if (mightBeTheTexts(again)) throw e
                throw again
            }
        }
    }

    private fun segment(message: String, text: String, prompt: OpenAiPrompt, ofOne: Boolean): String {
        val answer = send(message)
        val translation = if (ofOne) readOfOne(answer, text, prompt.isDefault)
        else if (prompt.isDefault) read(answer, text) else stripThinking(answer)
        return translation?.takeIf { it.isNotEmpty() } ?: throw ChatException(ChatException.Kind.NO_TRANSLATION)
    }

    private fun send(message: String): String {
        val p = options.profile
        val payload = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", message)))
        if (p.temperatureEnabled) payload.put("temperature", p.temperature)
        if (p.topPEnabled) payload.put("top_p", p.topP)
        if (p.seedEnabled) payload.put("seed", p.seed)
        if (p.thinkingOff) payload.put("reasoning_effort", OpenAiModelProfile.THINKING_OFF_EFFORT)
        payload.put("stream", false)

        val key = options.apiKey.trim()
        val headers = if (key.isNotEmpty()) mapOf("Authorization" to "Bearer $key") else emptyMap()
        val response = try {
            Http.post(endpoint, payload.toString(), "application/json; charset=utf-8", options.timeoutSeconds * 1000, headers)
        } catch (e: SocketTimeoutException) {
            throw ChatException(ChatException.Kind.TIMED_OUT, cause = e)
        }
        if (response.code !in 200..299) {
            throw ChatException(ChatException.Kind.REJECTED, response.code, readError(response.body))
        }
        val content = try {
            readContent(JSONObject(response.body).getJSONArray("choices").getJSONObject(0).getJSONObject("message"))
        } catch (e: Exception) {
            throw ChatException(ChatException.Kind.UNPARSABLE, cause = e)
        }
        if (stripThinking(content).isEmpty()) throw ChatException(ChatException.Kind.NO_TRANSLATION)
        return content
    }

    private fun readContent(message: JSONObject): String = when (val content = message.opt("content")) {
        is String -> content
        is JSONArray -> (0 until content.length()).joinToString("") { content.optJSONObject(it)?.optString("text").orEmpty() }
        else -> ""
    }

    private fun readError(json: String): String? {
        val text = runCatching {
            JSONObject(json).optJSONObject("error")?.optString("message")
        }.getOrNull() ?: json.trim()
        val redacted = options.apiKey.trim().takeIf { it.isNotEmpty() }?.let { text.replace(it, "<redacted>") } ?: text
        return if (redacted.length > 300) redacted.take(300) + "…" else redacted
    }

    private fun mightBeTheTexts(e: Exception): Boolean = when {
        e is IOException -> false
        e is ChatException && e.kind == ChatException.Kind.REJECTED && e.status in setOf(401, 403, 404, 429) -> false
        else -> true
    }

    private fun localized(e: Exception?): String = when (e) {
        is ChatException -> when (e.kind) {
            ChatException.Kind.TIMED_OUT -> "OpenAI Compatible API 在 ${options.timeoutSeconds} 秒内没有响应。可在「翻译服务设置」中调整超时秒数。"
            ChatException.Kind.REJECTED -> "OpenAI Compatible API 返回 ${e.status}：${e.serverMessage?.ifEmpty { "（无错误内容）" } ?: "未知错误"}"
            ChatException.Kind.UNPARSABLE -> "OpenAI Compatible API 响应格式无法解析。"
            ChatException.Kind.NO_TRANSLATION -> "OpenAI Compatible API 未返回译文。"
        }
        is TranslationException -> e.message ?: "翻译失败"
        is IOException -> "连不上 OpenAI Compatible API：${e.message}"
        null -> "OpenAI Compatible API 未返回译文。"
        else -> "翻译失败：${e.message}"
    }

    companion object {
        const val MAX_CONCURRENT = 8
        const val MAX_BATCH_TEXTS = 40
        const val CONTEXT_TOKENS = 4096

        /** 照 Windows 版 OpenAiChatEndpoint.Resolve：补上 /v1/chat/completions。 */
        fun buildEndpoint(baseUrl: String): String {
            val raw = baseUrl.trim().ifEmpty { OpenAiSettings.DEFAULT_BASE_URL }
            val uri = runCatching { URI(raw) }.getOrNull()
            if (uri == null || (uri.scheme != "http" && uri.scheme != "https") || uri.host.isNullOrEmpty()) {
                throw TranslationException("OpenAI Compatible API 地址必须是有效的 HTTP 或 HTTPS 网址。")
            }
            var path = (uri.rawPath ?: "").trimEnd('/')
            path = if (path.endsWith("/chat/completions", ignoreCase = true)) path
            else (if (path.isEmpty()) "/v1" else path) + "/chat/completions"
            return URI(uri.scheme, uri.rawAuthority, null, null, null).toString() + path +
                (uri.rawQuery?.let { "?$it" } ?: "")
        }

        fun hasLetters(text: String): Boolean {
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (Character.isLetter(cp)) return true
                i += Character.charCount(cp)
            }
            return false
        }

        // ----- 读回复（OpenAiAnswer） -----

        private val THINKING_BLOCK = Regex("""<think(?:\s[^>]*)?>.*?</think\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val UP_TO_CLOSING = Regex("""^.*?</think\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val UNCLOSED_LEADING = Regex("""^\s*<think(?:\s[^>]*)?>.*\z""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val OPENING_TAG = Regex("""<t\s+id\s*=\s*["']?(\d+)["']?\s*>""", RegexOption.IGNORE_CASE)
        private val CLOSING_TAG = Regex("""</t\s*>""", RegexOption.IGNORE_CASE)
        private val WRAPPED = Regex("""^<t>(?<body>.*)</t>$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val CONTEXT_TAG = Regex("""</?(?:s|t|history)>""", RegexOption.IGNORE_CASE)
        private val FENCE = Regex("""^```[a-zA-Z]*\n(.*?)\n?```$""", RegexOption.DOT_MATCHES_ALL)
        private val QUOTES = listOf('"' to '"', '“' to '”', '「' to '」', '『' to '』')
        private val NUMBERED = Regex("""<(\d+)>(.*?)(?=</\1>|<\d+>|\z)""", RegexOption.DOT_MATCHES_ALL)
        private val NUMBERED_CLOSING = Regex("""</\d+>""")
        private val ANY_NUMBERED = Regex("""</?\d+>""")
        private val WRAPPED_IN_NUMBER = Regex("""^<(\d+)>(?<body>.*)</\1>$""", RegexOption.DOT_MATCHES_ALL)

        fun stripThinking(value: String): String {
            var v = THINKING_BLOCK.replace(value, "")
            v = UP_TO_CLOSING.replaceFirst(v, "")
            v = UNCLOSED_LEADING.replaceFirst(v, "")
            return v.trim()
        }

        fun read(answer: String, source: String): String? {
            var text = stripThinking(answer)
            FENCE.find(text)?.let { text = it.groupValues[1].trim() }
            WRAPPED.find(text)?.let { m ->
                val body = m.groups["body"]!!.value
                if (!CONTEXT_TAG.containsMatchIn(body)) text = body.trim()
            }
            if (OPENING_TAG.containsMatchIn(text)) {
                text = lastFirstTag(text) ?: ""
                if (text.isEmpty()) return null
            }
            val trimmed = source.trim()
            for ((open, close) in QUOTES) {
                if (text.length >= 2 && text.first() == open && text.last() == close &&
                    text.indexOf(close, 1) == text.length - 1 &&
                    !trimmed.contains(open) && !trimmed.contains(close)
                ) {
                    text = text.substring(1, text.length - 1).trim()
                    break
                }
            }
            return text.ifEmpty { null }
        }

        fun readNumbered(answer: String, count: Int): Array<String?> {
            var text = stripThinking(answer)
            FENCE.find(text)?.let { text = it.groupValues[1].trim() }
            val out = arrayOfNulls<String>(count)
            for (m in NUMBERED.findAll(text)) {
                val n = m.groupValues[1].toIntOrNull() ?: continue
                if (n < 1 || n > count) continue
                val t = NUMBERED_CLOSING.replace(m.groupValues[2], "").trim()
                if (out[n - 1] == null && t.isNotEmpty()) out[n - 1] = t
            }
            return out
        }

        fun readOfOne(answer: String, source: String, isDefault: Boolean): String? {
            readNumbered(answer, 1)[0]?.let { return it }
            var text = stripThinking(answer)
            WRAPPED_IN_NUMBER.find(text)?.let { text = it.groups["body"]!!.value.trim() }
            if (ANY_NUMBERED.containsMatchIn(text)) return null
            return if (isDefault) read(text, source) else text
        }

        private fun lastFirstTag(text: String): String? {
            val openings = OPENING_TAG.findAll(text).toList()
            for (i in openings.indices.reversed()) {
                if (openings[i].groupValues[1] != "1") continue
                val start = openings[i].range.last + 1
                val next = if (i + 1 < openings.size) openings[i + 1].range.first else text.length
                val closing = CLOSING_TAG.find(text, start)
                val end = if (closing != null && closing.range.first <= next) closing.range.first
                else {
                    val lineEnd = text.indexOf('\n', start)
                    if (lineEnd in 0 until next) lineEnd else next
                }
                return text.substring(start, end).trim()
            }
            return null
        }
    }
}
