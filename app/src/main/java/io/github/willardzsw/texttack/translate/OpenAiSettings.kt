package io.github.willardzsw.texttack.translate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一组模型设置。字段和默认值照 Windows 版 OpenAiModelProfile。
 * [template] 为空表示用默认提示词。
 */
data class OpenAiModelProfile(
    val id: String = "",
    val name: String = "",
    val model: String = "",
    val temperatureEnabled: Boolean = true,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val topPEnabled: Boolean = true,
    val topP: Double = DEFAULT_TOP_P,
    val seedEnabled: Boolean = true,
    val seed: Int = DEFAULT_SEED,
    val thinkingOff: Boolean = false,
    val batch: Boolean = false,
    val template: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("model", model)
        .put("temperatureEnabled", temperatureEnabled).put("temperature", temperature)
        .put("topPEnabled", topPEnabled).put("topP", topP)
        .put("seedEnabled", seedEnabled).put("seed", seed)
        .put("thinkingOff", thinkingOff).put("batch", batch).put("template", template)

    companion object {
        const val DEFAULT_TEMPERATURE = 0.7
        const val DEFAULT_TOP_P = 0.6
        const val DEFAULT_SEED = 42
        const val THINKING_OFF_EFFORT = "none"

        /** Windows 版的推荐模型（系统默认）。 */
        const val RECOMMENDED_MODEL = "hf.co/unsloth/Hy-MT2-7B-GGUF:UD-IQ3_XXS"

        /** 只读的"系统默认"。 */
        fun builtIn() = OpenAiModelProfile(id = "", name = "系统默认", model = RECOMMENDED_MODEL)

        fun fromJson(o: JSONObject) = OpenAiModelProfile(
            id = o.optString("id"),
            name = o.optString("name"),
            model = o.optString("model"),
            temperatureEnabled = o.optBoolean("temperatureEnabled", true),
            temperature = o.optDouble("temperature", DEFAULT_TEMPERATURE),
            topPEnabled = o.optBoolean("topPEnabled", true),
            topP = o.optDouble("topP", DEFAULT_TOP_P),
            seedEnabled = o.optBoolean("seedEnabled", true),
            seed = o.optInt("seed", DEFAULT_SEED),
            thinkingOff = o.optBoolean("thinkingOff", false),
            batch = o.optBoolean("batch", false),
            template = o.optString("template"),
        )

        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
    }
}

/** OpenAI 兼容设置：API 地址、API 密钥、超时、模型设置（最多 5 组）。照 Windows 版。 */
object OpenAiSettings {
    const val MAX_PROFILES = 5
    const val MAX_NAME_LENGTH = 40
    const val DEFAULT_BASE_URL = "http://localhost:11434/v1"
    const val DEFAULT_TIMEOUT_SECONDS = 60
    const val MIN_TIMEOUT_SECONDS = 10

    private const val FILE = "openai"
    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String = prefs(c).getString("baseUrl", "") ?: ""
    fun apiKey(c: Context): String = prefs(c).getString("apiKey", "") ?: ""

    /** 保存的超时；null 表示没设过（用默认 60 秒）。 */
    fun savedTimeout(c: Context): Int? = prefs(c).getInt("timeout", -1).takeIf { it > 0 }
    fun timeoutSeconds(c: Context): Int = savedTimeout(c)?.let { maxOf(MIN_TIMEOUT_SECONDS, it) } ?: DEFAULT_TIMEOUT_SECONDS

    fun setBaseUrl(c: Context, v: String) = prefs(c).edit().putString("baseUrl", v).apply()
    fun setApiKey(c: Context, v: String) = prefs(c).edit().putString("apiKey", v).apply()
    fun setTimeout(c: Context, v: Int?) = prefs(c).edit().putInt("timeout", v ?: -1).apply()

    fun profiles(c: Context): List<OpenAiModelProfile> {
        val raw = prefs(c).getString("profiles", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { OpenAiModelProfile.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun saveProfiles(c: Context, list: List<OpenAiModelProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString("profiles", arr.toString()).apply()
    }

    fun selectedId(c: Context): String = prefs(c).getString("selected", "") ?: ""
    fun setSelectedId(c: Context, id: String) = prefs(c).edit().putString("selected", id).apply()

    /** 当前使用的模型设置；没选或找不到时用系统默认。 */
    fun selectedProfile(c: Context): OpenAiModelProfile {
        val id = selectedId(c)
        if (id.isEmpty()) return OpenAiModelProfile.builtIn()
        return profiles(c).firstOrNull { it.id == id } ?: OpenAiModelProfile.builtIn()
    }
}
