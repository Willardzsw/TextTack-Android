package io.github.willardzsw.texttack.translate

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 目标语言。名称、模型语言代码照 Windows 版 LanguageData（简体中文界面）。 */
enum class TargetLanguage(val key: String, val label: String, val googleCode: String, val modelTag: String) {
    SIMPLIFIED_CHINESE("ZH", "简体中文", "zh-CN", "zh-Hans"),
    TRADITIONAL_CHINESE("ZH-HANT", "繁体中文", "zh-TW", "zh-Hant"),
    ENGLISH("EN", "英语", "en", "en"),
    JAPANESE("JA", "日语", "ja", "ja"),
    KOREAN("KO", "韩语", "ko", "ko"),
    THAI("TH", "泰语", "th", "th");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SIMPLIFIED_CHINESE
    }
}

/** 翻译服务。 */
enum class TranslationProvider(val key: String, val label: String) {
    GOOGLE("google", "Google 翻译"),
    OPENAI("openai", "OpenAI");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: GOOGLE
    }
}

/** 给用户看的翻译错误（中文）。 */
class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface Translator {
    /**
     * 按顺序翻译每段文字。返回同样长度的列表，某段失败为 null（显示时用原文代替）。
     * 全部失败时抛 [TranslationException]。在后台线程调用。
     */
    fun translate(texts: List<String>, target: TargetLanguage): List<String?>
}

internal object Http {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    class Response(val code: Int, val body: String)

    fun post(
        url: String,
        body: String,
        contentType: String,
        timeoutMs: Int,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Connection", "keep-alive")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return Response(code, text)
        } catch (e: IOException) {
            // 出错时断开，免得坏连接留在连接池里
            conn.disconnect()
            throw e
        }
        // 正常时不调用 disconnect()：读完响应后连接回到连接池，下次请求复用，省掉重新握手（手机上几百毫秒）
    }
}
