/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiSettingsActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var configStore: AiConfigStore
    private lateinit var enabledSwitch: Switch
    private lateinit var modeSpinner: Spinner
    private lateinit var providerEdit: EditText
    private lateinit var baseUrlEdit: EditText
    private lateinit var modelEdit: EditText
    private lateinit var apiKeyEdit: EditText
    private lateinit var promptEdit: EditText
    private lateinit var descriptionEdit: EditText
    private lateinit var skillEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "AI 数据辅助"
        configStore = AiConfigStore(this)
        setContentView(createContentView())
        loadConfig()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(TextView(this).apply {
            text = "AI 数据辅助"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = AiConfigStore.DefaultFeatureDescription
            textSize = 14f
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, dp(16))
        })

        enabledSwitch = Switch(this).apply {
            text = "启用键盘侧 AI 模式"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(enabledSwitch)
        root.addView(TextView(this).apply {
            text = "触发模式"
            textSize = 13f
            gravity = Gravity.START
            setPadding(0, dp(12), 0, dp(4))
        })
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AiSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("截图模式（OCR 当前屏幕）", "手动模式（读取当前输入框）")
            )
        }
        root.addView(modeSpinner)

        providerEdit = root.addField("供应商", singleLine = true)
        baseUrlEdit = root.addField("请求地址", singleLine = true)
        modelEdit = root.addField("模型名称", singleLine = true)
        apiKeyEdit = root.addField("API Key", singleLine = true).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        promptEdit = root.addField("前置提示词（支持 [OCR_RESULT] 占位符）", minLines = 5)
        descriptionEdit = root.addField("功能介绍", minLines = 4)
        skillEdit = root.addField(
            "本地 Skill 知识库导入（JSON 数组，或每行 keyword: answer / keyword=>answer）",
            minLines = 7
        )

        root.addView(Button(this).apply {
            text = "保存 AI 配置"
            setOnClickListener { saveConfig() }
        })
        root.addView(Button(this).apply {
            text = "测试 DeepSeek API"
            setOnClickListener { testApi() }
        })
        root.addView(Button(this).apply {
            text = "追加导入 Skill"
            setOnClickListener { importSkills(replaceExisting = false) }
        })
        root.addView(Button(this).apply {
            text = "替换本地 Skill 库"
            setOnClickListener { importSkills(replaceExisting = true) }
        })
        root.addView(Button(this).apply {
            text = "清空本地 Skill 库"
            setOnClickListener { clearSkills() }
        })

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun LinearLayout.addField(
        label: String,
        singleLine: Boolean = false,
        minLines: Int = 1
    ): EditText {
        addView(TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.START
            setPadding(0, dp(12), 0, dp(4))
        })
        return EditText(context).apply {
            this.setSingleLine(singleLine)
            this.minLines = minLines
            this.inputType = if (singleLine) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }.also(::addView)
    }

    private fun loadConfig() {
        enabledSwitch.isChecked = configStore.enabled
        modeSpinner.setSelection(
            when (configStore.triggerMode) {
                AiTriggerMode.Screenshot -> 0
                AiTriggerMode.ManualInput -> 1
            }
        )
        providerEdit.setText(configStore.provider)
        baseUrlEdit.setText(configStore.baseUrl)
        modelEdit.setText(configStore.model)
        apiKeyEdit.setText(configStore.apiKey)
        promptEdit.setText(configStore.systemPrompt)
        descriptionEdit.setText(configStore.featureDescription)
    }

    private fun saveConfig() {
        configStore.save(
            enabled = enabledSwitch.isChecked,
            provider = providerEdit.text.toString(),
            baseUrl = baseUrlEdit.text.toString(),
            model = modelEdit.text.toString(),
            apiKey = apiKeyEdit.text.toString(),
            systemPrompt = promptEdit.text.toString(),
            featureDescription = descriptionEdit.text.toString(),
            triggerMode = selectedMode()
        )
        Toast.makeText(this, "AI 配置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun selectedMode(): AiTriggerMode {
        return if (modeSpinner.selectedItemPosition == 1) {
            AiTriggerMode.ManualInput
        } else {
            AiTriggerMode.Screenshot
        }
    }

    private fun testApi() {
        saveConfig()
        if (configStore.apiKey.isBlank()) {
            Toast.makeText(this, "请先填写 API Key", Toast.LENGTH_SHORT).show()
            return
        }
        activityScope.launch {
            val result = runCatching {
                AsyncLlmClient(
                    apiKey = configStore.apiKey,
                    baseUrl = configStore.baseUrl,
                    model = configStore.model,
                    maxRetries = 0
                ).fetchAiResponse(
                    systemPrompt = "Reply with OK only.",
                    userText = "Connectivity test."
                )
            }.getOrElse {
                Toast.makeText(this@AiSettingsActivity, "API 测试失败：${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (result.isNullOrBlank()) {
                Toast.makeText(this@AiSettingsActivity, "API 测试失败：返回为空", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@AiSettingsActivity, "API 测试通过：${result.take(40)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importSkills(replaceExisting: Boolean) {
        saveConfig()
        val raw = skillEdit.text.toString().trim()
        if (raw.isBlank()) {
            Toast.makeText(this, "请先粘贴 Skill 数据", Toast.LENGTH_SHORT).show()
            return
        }
        activityScope.launch {
            val count = runCatching {
                val importer = SkillImportManager(this@AiSettingsActivity)
                if (raw.startsWith("[") || raw.startsWith("{")) {
                    importer.importJson(raw, replaceExisting)
                } else {
                    importer.importPlainText(raw, replaceExisting)
                }
            }.getOrElse {
                Toast.makeText(this@AiSettingsActivity, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(this@AiSettingsActivity, "已导入 $count 条 Skill", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearSkills() {
        activityScope.launch {
            withContext(Dispatchers.IO) {
                LocalSkillDatabase.getInstance(this@AiSettingsActivity).skillDao().clear()
            }
            Toast.makeText(this@AiSettingsActivity, "本地 Skill 库已清空", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
