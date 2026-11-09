package io.github.willardzsw.texttack

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import io.github.willardzsw.texttack.translate.OpenAiModelProfile
import io.github.willardzsw.texttack.translate.OpenAiPrompt
import io.github.willardzsw.texttack.translate.OpenAiSettings
import kotlin.math.roundToInt

/** 界面小工具：纵向表单。 */
internal class Form(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).roundToInt()

    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(24))
    }

    fun scroll() = ScrollView(context).apply { addView(root) }

    fun title(text: String) = TextView(context).apply {
        this.text = text
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }.also { root.addView(it) }

    fun label(text: String) = TextView(context).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(10), 0, dp(2))
    }.also { root.addView(it) }

    fun hint(text: String, into: ViewGroup = root) = TextView(context).apply {
        this.text = text
        textSize = 12f
        alpha = 0.7f
        setPadding(0, dp(2), 0, dp(2))
    }.also { into.addView(it) }

    fun edit(value: String, hint: String = "", type: Int = InputType.TYPE_CLASS_TEXT, into: ViewGroup = root) =
        EditText(context).apply {
            setText(value)
            this.hint = hint
            inputType = type
            textSize = 15f
        }.also { into.addView(it) }

    fun button(text: String, into: ViewGroup = root, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }.also { into.addView(it) }

    fun row(into: ViewGroup = root) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }.also { into.addView(it) }
}

/** 「OpenAI设置」：照 Windows 版的 API 地址、API Key、超时、模型设置。 */
class OpenAiSettingsActivity : Activity() {

    private lateinit var baseUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var timeout: EditText
    private lateinit var profileBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "OpenAI设置"
        val f = Form(this)

        f.label("API 地址")
        baseUrl = f.edit(OpenAiSettings.baseUrl(this), OpenAiSettings.DEFAULT_BASE_URL, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        f.label("API Key")
        apiKey = f.edit(OpenAiSettings.apiKey(this), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        f.label("超时")
        val tr = f.row()
        timeout = f.edit(
            OpenAiSettings.savedTimeout(this)?.toString() ?: "",
            OpenAiSettings.DEFAULT_TIMEOUT_SECONDS.toString(),
            InputType.TYPE_CLASS_NUMBER,
            tr,
        ).apply { minEms = 4 }
        TextView(this).apply { text = " 秒（最少 ${OpenAiSettings.MIN_TIMEOUT_SECONDS} 秒）" }.also { tr.addView(it) }

        f.title("模型设置")
        f.hint("点击选择要使用的设置")
        profileBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        f.root.addView(profileBox)
        f.hint("系统默认会随版本更新；想保留当前的内容，点击系统默认右侧的复制按钮另存一份。")

        setContentView(f.scroll())
    }

    override fun onResume() {
        super.onResume()
        renderProfiles()
    }

    override fun onPause() {
        super.onPause()
        OpenAiSettings.setBaseUrl(this, baseUrl.text.toString().trim())
        OpenAiSettings.setApiKey(this, apiKey.text.toString().trim())
        OpenAiSettings.setTimeout(this, timeout.text.toString().trim().toIntOrNull()?.let { maxOf(OpenAiSettings.MIN_TIMEOUT_SECONDS, it) })
    }

    private fun renderProfiles() {
        val f = Form(this)
        profileBox.removeAllViews()
        val profiles = OpenAiSettings.profiles(this)
        val selected = OpenAiSettings.selectedId(this).takeIf { id -> profiles.any { it.id == id } } ?: ""
        val group = RadioGroup(this)
        profileBox.addView(group)

        fun addRow(p: OpenAiModelProfile, builtIn: Boolean) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val radio = RadioButton(this).apply {
                val tags = buildList {
                    if (builtIn) add("只读")
                    if (p.template.isNotEmpty()) add("自定义提示词")
                    add(if (p.batch) "整批" else "标准")
                }
                text = "${p.name}\n${p.model.ifEmpty { "（未填模型）" }} · ${tags.joinToString(" · ")}"
                isChecked = p.id == selected
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    OpenAiSettings.setSelectedId(this@OpenAiSettingsActivity, p.id)
                    renderProfiles()
                }
            }
            row.addView(radio)
            if (builtIn) {
                f.button("复制为新设置", row) { copyBuiltIn() }
            } else {
                f.button("编辑", row) { startActivity(ProfileEditActivity.intent(this, p.id)) }
            }
            profileBox.addView(row)
        }

        addRow(OpenAiModelProfile.builtIn(), builtIn = true)
        profiles.forEach { addRow(it, builtIn = false) }

        val add = f.button("新增设置（${profiles.size}/${OpenAiSettings.MAX_PROFILES}）", profileBox) {
            startActivity(ProfileEditActivity.intent(this, null))
        }
        add.isEnabled = profiles.size < OpenAiSettings.MAX_PROFILES
    }

    private fun copyBuiltIn() {
        val profiles = OpenAiSettings.profiles(this)
        if (profiles.size >= OpenAiSettings.MAX_PROFILES) return
        val copy = OpenAiModelProfile.builtIn().copy(id = OpenAiModelProfile.newId(), name = "系统默认 v${BuildConfig.VERSION_NAME}")
        OpenAiSettings.saveProfiles(this, profiles + copy)
        renderProfiles()
    }
}

/** 新增/编辑一组模型设置。照 Windows 版的字段和说明文字。 */
class ProfileEditActivity : Activity() {

    private var original: OpenAiModelProfile? = null
    private lateinit var name: EditText
    private lateinit var model: EditText
    private lateinit var modeGroup: RadioGroup
    private lateinit var template: EditText
    private lateinit var thinkingOff: CheckBox
    private lateinit var temperatureOn: CheckBox
    private lateinit var temperature: EditText
    private lateinit var topPOn: CheckBox
    private lateinit var topP: EditText
    private lateinit var seedOn: CheckBox
    private lateinit var seed: EditText
    private var batch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val profiles = OpenAiSettings.profiles(this)
        original = profiles.firstOrNull { it.id == id }
        val p = original ?: OpenAiModelProfile(
            id = OpenAiModelProfile.newId(),
            name = "我的设置 ${profiles.size + 1}",
        )
        batch = p.batch
        title = if (original == null) "新增设置" else "编辑设置"

        val f = Form(this)
        f.label("名称")
        name = f.edit(p.name, "为这个设置命名")
        f.label("模型名称（必填）")
        model = f.edit(p.model)
        f.button("带入推荐模型") { model.setText(OpenAiModelProfile.RECOMMENDED_MODEL) }

        f.title("翻译模式")
        modeGroup = RadioGroup(this)
        val segment = RadioButton(this).apply { text = "标准\n依次逐一翻译画面上的文字区块。适合：影片字幕、剧情对话"; this.id = View.generateViewId() }
        val whole = RadioButton(this).apply { text = "整批\n将画面上的文字区块组合后一起翻译。适合：漫画、聊天室"; this.id = View.generateViewId() }
        modeGroup.addView(segment)
        modeGroup.addView(whole)
        f.root.addView(modeGroup)
        modeGroup.check(if (batch) whole.id else segment.id)
        modeGroup.setOnCheckedChangeListener { _, checked -> onModeChanged(checked == whole.id) }

        f.title("提示词")
        f.hint("翻译时发送给模型的内容。花括号标示的参数会替换成实际的文字。")
        template = f.edit(
            p.template.ifEmpty { OpenAiPrompt.defaultTemplate(batch) },
            type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        ).apply {
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
            textSize = 13f
        }
        f.button("恢复默认") { template.setText(OpenAiPrompt.defaultTemplate(batch)) }
        f.label("可用参数")
        f.hint(
            "{source_name} 源语言名称（例如：英语）\n" +
                "{source_code} 源语言代码\n" +
                "{target_name} 目标语言名称（例如：日语）\n" +
                "{target_code} 目标语言代码\n" +
                "源语言为“自动”时，源语言名称为 any language，代码为空。\n" +
                "{text} 要翻译的原文。标准模式一次只发送一个文字区块；整批模式会把画面上的文字区块组合后一起发送，每块一行并加上编号，例如 <1>text...</1>。未使用此参数时，{text} 会自动附加在提示词末尾。\n" +
                "{context} 先前的翻译内容，可供模型参考上下文及用词，让语意更连贯。没有可参考的内容时，会自动移除此部分及多余的空行。未使用此参数时，模型将无法参考先前的翻译内容。"
        )

        f.title("高级")
        f.hint("未勾选的参数不会发送。各模型支持的参数与范围不同，请以模型文档为准。")
        thinkingOff = CheckBox(this).apply { text = "关闭思考（发送 reasoning_effort: none）"; isChecked = p.thinkingOff }
        f.root.addView(thinkingOff)
        f.hint("要求模型直接回答、不先思考。部分通用模型默认会先思考再回答，翻译会明显变慢，甚至超时；翻译专用模型通常不需要。有些服务不认识这个参数会返回错误，这时请取消勾选。")

        fun param(label: String, on: Boolean, value: String, range: String, tip: String, type: Int): kotlin.Pair<CheckBox, EditText> {
            val r = f.row()
            val cb = CheckBox(this).apply { text = label; isChecked = on }
            r.addView(cb)
            val et = f.edit(value, type = type, into = r).apply { minEms = 4 }
            TextView(this).apply { text = "  $range" }.also { r.addView(it) }
            f.hint(tip)
            return cb to et
        }
        val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        param("Temperature", p.temperatureEnabled, p.temperature.toString(), "范围：0.0 ~ 2.0",
            "调整模型对高概率结果的偏好程度。数值越低，越倾向选择最可能的表达；数值越高，其他可能的表达也更有机会被选中。", decimal)
            .let { temperatureOn = it.first; temperature = it.second }
        param("Top P", p.topPEnabled, p.topP.toString(), "范围：0.0 ~ 1.0",
            "限制模型可选择的候选范围。数值越低，只保留概率较高的少数候选；数值越高，可选择的候选越多。", decimal)
            .let { topPOn = it.first; topP = it.second }
        param("Seed", p.seedEnabled, p.seed.toString(), "整数",
            "用于让生成结果更容易重现。在模型、提示词与其他设置相同时，使用相同 Seed 通常可得到相同结果。",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)
            .let { seedOn = it.first; seed = it.second }

        val buttons = f.row()
        buttons.setPadding(0, f.dp(16), 0, 0)
        f.button("保存", buttons) { save(p) }
        f.button("取消", buttons) { finish() }
        if (original != null) f.button("删除", buttons) { confirmDelete(p) }

        setContentView(f.scroll())
    }

    /** 切换翻译模式：默认提示词跟着换；自定义提示词时询问。照 Windows 版。 */
    private fun onModeChanged(toBatch: Boolean) {
        val current = template.text.toString()
        val wasDefault = OpenAiPrompt.isDefaultTemplate(current, !toBatch)
        batch = toBatch
        if (wasDefault) {
            template.setText(OpenAiPrompt.defaultTemplate(toBatch))
            return
        }
        AlertDialog.Builder(this)
            .setTitle("切换翻译模式")
            .setMessage(if (toBatch) "当前使用自定义提示词，要切换成“整批”的默认提示词吗？" else "当前使用自定义提示词，要切换成“标准”的默认提示词吗？")
            .setPositiveButton("切换默认") { _, _ -> template.setText(OpenAiPrompt.defaultTemplate(toBatch)) }
            .setNegativeButton("保留", null)
            .show()
    }

    private fun save(base: OpenAiModelProfile) {
        val modelName = model.text.toString().trim()
        if (modelName.isEmpty()) {
            model.error = "必填"
            model.requestFocus()
            return
        }
        val updated = base.copy(
            name = name.text.toString().trim().take(OpenAiSettings.MAX_NAME_LENGTH).ifEmpty { base.name },
            model = modelName,
            batch = batch,
            template = OpenAiPrompt.templateToStore(template.text.toString(), batch),
            thinkingOff = thinkingOff.isChecked,
            temperatureEnabled = temperatureOn.isChecked,
            temperature = temperature.text.toString().toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: base.temperature,
            topPEnabled = topPOn.isChecked,
            topP = topP.text.toString().toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: base.topP,
            seedEnabled = seedOn.isChecked,
            seed = seed.text.toString().toIntOrNull() ?: base.seed,
        )
        val profiles = OpenAiSettings.profiles(this).toMutableList()
        val index = profiles.indexOfFirst { it.id == updated.id }
        if (index >= 0) profiles[index] = updated
        else {
            if (profiles.size >= OpenAiSettings.MAX_PROFILES) return finish()
            profiles.add(updated)
            OpenAiSettings.setSelectedId(this, updated.id)
        }
        OpenAiSettings.saveProfiles(this, profiles)
        finish()
    }

    private fun confirmDelete(p: OpenAiModelProfile) {
        AlertDialog.Builder(this)
            .setTitle("删除设置")
            .setMessage("确定要删除“${p.name}”吗？删除后无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                OpenAiSettings.saveProfiles(this, OpenAiSettings.profiles(this).filterNot { it.id == p.id })
                if (OpenAiSettings.selectedId(this) == p.id) OpenAiSettings.setSelectedId(this, "")
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val EXTRA_ID = "id"
        fun intent(context: Context, id: String?) =
            Intent(context, ProfileEditActivity::class.java).apply { if (id != null) putExtra(EXTRA_ID, id) }
    }
}
