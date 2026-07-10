/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import android.content.Context
import android.content.SharedPreferences

class AiConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KeyEnabled, false)
        set(value) = prefs.edit().putBoolean(KeyEnabled, value).apply()

    var provider: String
        get() = prefs.getString(KeyProvider, DefaultProvider).orEmpty().ifBlank { DefaultProvider }
        set(value) = prefs.edit().putString(KeyProvider, value.trim()).apply()

    var triggerMode: AiTriggerMode
        get() = AiTriggerMode.fromStorageValue(prefs.getString(KeyTriggerMode, AiTriggerMode.Screenshot.storageValue))
        set(value) = prefs.edit().putString(KeyTriggerMode, value.storageValue).apply()

    var baseUrl: String
        get() = prefs.getString(KeyBaseUrl, DefaultBaseUrl).orEmpty().ifBlank { DefaultBaseUrl }
        set(value) = prefs.edit().putString(KeyBaseUrl, value.trim()).apply()

    var model: String
        get() = prefs.getString(KeyModel, DefaultModel).orEmpty().ifBlank { DefaultModel }
        set(value) = prefs.edit().putString(KeyModel, value.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KeyApiKey, "").orEmpty()
        set(value) = prefs.edit().putString(KeyApiKey, value.trim()).apply()

    var systemPrompt: String
        get() = prefs.getString(KeySystemPrompt, AsyncLlmClient.DefaultSystemPrompt)
            .orEmpty()
            .ifBlank { AsyncLlmClient.DefaultSystemPrompt }
        set(value) = prefs.edit().putString(KeySystemPrompt, value.trim()).apply()

    var featureDescription: String
        get() = prefs.getString(KeyFeatureDescription, DefaultFeatureDescription)
            .orEmpty()
            .ifBlank { DefaultFeatureDescription }
        set(value) = prefs.edit().putString(KeyFeatureDescription, value.trim()).apply()

    fun save(
        enabled: Boolean,
        provider: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        featureDescription: String,
        triggerMode: AiTriggerMode = this.triggerMode
    ) {
        prefs.edit()
            .putBoolean(KeyEnabled, enabled)
            .putString(KeyTriggerMode, triggerMode.storageValue)
            .putString(KeyProvider, provider.trim().ifBlank { DefaultProvider })
            .putString(KeyBaseUrl, baseUrl.trim().ifBlank { DefaultBaseUrl })
            .putString(KeyModel, model.trim().ifBlank { DefaultModel })
            .putString(KeyApiKey, apiKey.trim())
            .putString(KeySystemPrompt, systemPrompt.trim().ifBlank { AsyncLlmClient.DefaultSystemPrompt })
            .putString(KeyFeatureDescription, featureDescription.trim().ifBlank { DefaultFeatureDescription })
            .apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        private const val PrefsName = "ai_data_assistance"
        private const val KeyEnabled = "enabled"
        private const val KeyTriggerMode = "trigger_mode"
        private const val KeyProvider = "provider"
        private const val KeyBaseUrl = "base_url"
        private const val KeyModel = "model"
        private const val KeyApiKey = "api_key"
        private const val KeySystemPrompt = "system_prompt"
        private const val KeyFeatureDescription = "feature_description"

        const val DefaultProvider = "DeepSeek"
        const val DefaultBaseUrl = "https://api.deepseek.com/anthropic"
        const val DefaultModel = "deepseek-v4-flash"

        const val DefaultFeatureDescription =
            "AI 数据辅助启用后可选择截图模式或手动模式。截图模式通过空格长按触发本地单帧 OCR；手动模式直接读取当前输入框内容。两种模式都会优先检索本地 Skill 知识库，本地未命中时才调用云端模型，并把结果放入候选栏第一项，点击后按逐字限流方式上屏。截图只在本机 OCR，不上传图像。"
    }
}
