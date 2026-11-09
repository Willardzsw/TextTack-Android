package io.github.willardzsw.texttack.translate

import android.util.Log
import io.github.willardzsw.texttack.TAG
import org.json.JSONArray
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * 「Google 翻译（标准）」：照 Windows 版 TextTack 的 GoogleWebTranslator（translate_a/t），
 * 失败时换 GoogleRpcTranslator（batchexecute）。每个请求最多 50 段、5000 字。
 */
class GoogleTranslator : Translator {

    override fun translate(texts: List<String>, target: TargetLanguage): List<String?> {
        val result = arrayOfNulls<String>(texts.size)
        val sent = texts.indices.filter { texts[it].isNotBlank() }
        texts.indices.filter { texts[it].isBlank() }.forEach { result[it] = texts[it] }

        for (group in pack(sent.map { texts[it] })) {
            val pieces = group.map { texts[sent[it]] }
            val answers = try {
                web(pieces, target)
            } catch (e: Exception) {
                Log.w(TAG, "Google (Web) 失败，改用 Google (RPC)：$e")
                try {
                    rpc(pieces, target)
                } catch (e2: Exception) {
                    throw TranslationException(describe(e2), e2)
                }
            }
            group.forEachIndexed { k, i -> result[sent[i]] = answers.getOrNull(k) }
        }
        return result.toList()
    }

    private fun pack(pieces: List<String>): List<List<Int>> {
        val groups = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var chars = 0
        pieces.forEachIndexed { i, p ->
            if (current.isNotEmpty() && (current.size + 1 > MAX_ITEMS || chars + p.length > MAX_CHARS)) {
                groups.add(current)
                current = ArrayList()
                chars = 0
            }
            current.add(i)
            chars += p.length
        }
        if (current.isNotEmpty()) groups.add(current)
        return groups
    }

    private fun web(pieces: List<String>, target: TargetLanguage): List<String?> {
        val url = "https://translate.googleapis.com/translate_a/t?client=gtx&sl=auto&tl=${enc(target.googleCode)}&format=text"
        val body = pieces.joinToString("&") { "q=" + enc(it) }
        val response = Http.post(url, body, "application/x-www-form-urlencoded;charset=UTF-8", TIMEOUT_MS)
        if (response.code !in 200..299) throw TranslationException("Google 翻译返回 ${response.code}")
        val root = JSONArray(response.body)
        // 只有一段时，Google 可能直接回 ["译文","th"]
        if (pieces.size == 1 && root.length() > 0 && root.opt(0) is String) {
            return listOf(root.getString(0))
        }
        return List(pieces.size) { i ->
            when (val answer = root.opt(i)) {
                is String -> answer
                is JSONArray -> answer.optString(0).takeIf { answer.length() > 0 }
                else -> null
            }
        }
    }

    private fun rpc(pieces: List<String>, target: TargetLanguage): List<String?> {
        val calls = JSONArray()
        pieces.forEachIndexed { i, piece ->
            val inner = JSONArray().put(JSONArray().put(piece).put("auto").put(target.googleCode).put(1)).put(JSONArray())
            calls.put(JSONArray().put(RPC_ID).put(inner.toString()).put(org.json.JSONObject.NULL).put((i + 1).toString()))
        }
        val body = "f.req=" + enc(JSONArray().put(calls).toString())
        val response = Http.post(RPC_URL, body, "application/x-www-form-urlencoded;charset=UTF-8", TIMEOUT_MS)
        if (response.code !in 200..299) throw TranslationException("Google 翻译返回 ${response.code}")
        val start = response.body.indexOf('[')
        if (start < 0) throw TranslationException("Google 翻译返回格式无法解析")
        val root = JSONArray(response.body.substring(start))
        val answers = arrayOfNulls<String>(pieces.size)
        for (e in 0 until root.length()) {
            val entry = root.optJSONArray(e) ?: continue
            if (entry.length() < 7 || entry.optString(0) != "wrb.fr") continue
            val position = entry.optString(6).toIntOrNull() ?: continue
            if (position < 1 || position > pieces.size) continue
            val data = entry.opt(2) as? String ?: continue
            answers[position - 1] = readRpc(data)
        }
        return answers.toList()
    }

    private fun readRpc(data: String): String {
        val result = JSONArray(data).getJSONArray(1).getJSONArray(0).getJSONArray(0)
        val sentences = if (result.length() > 5) result.optJSONArray(5) else null
        if (sentences == null) return result.getString(0)
        val joined = StringBuilder()
        for (i in 0 until sentences.length()) {
            val part = sentences.getJSONArray(i).getString(0).trim()
            if (part.isEmpty()) continue
            if (joined.isNotEmpty() && !isCloseSet(joined.last()) && !isCloseSet(part[0])) joined.append(' ')
            joined.append(part)
        }
        return joined.toString()
    }

    private fun isCloseSet(c: Char) = c in '⺀'..'鿿' || c in '豈'..'﫿' || c in '＀'..'￯'

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun describe(e: Exception) = when (e) {
        is TranslationException -> e.message ?: "Google 翻译失败"
        is UnknownHostException -> "连不上 Google 翻译，请检查网络"
        is SocketTimeoutException -> "Google 翻译超时"
        else -> "Google 翻译失败：${e.message}"
    }

    private companion object {
        const val MAX_ITEMS = 50
        const val MAX_CHARS = 5000
        const val TIMEOUT_MS = 15_000
        const val RPC_ID = "MkEWBc"
        const val RPC_URL = "https://translate.google.com/_/TranslateWebserverUi/data/batchexecute?rpcids=$RPC_ID"
    }
}
