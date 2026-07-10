/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import android.content.ClipData
import android.media.projection.MediaProjection
import android.view.inputmethod.ExtractedTextRequest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.utils.clipboardManager
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong

class DataAssistanceCoordinator private constructor(
    private val service: FcitxInputMethodService
) {
    private val skillDao by lazy { LocalSkillDatabase.getInstance(service).skillDao() }
    private val configStore by lazy { AiConfigStore(service) }
    private val injector by lazy { SmoothTextInjector(service, service.lifecycleScope) }
    private val ocrPipeline by lazy { ScreenCaptureOcrPipeline(service) }
    private val generationCounter = AtomicLong(0L)

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var llmClient: AsyncLlmClient? = null

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var cachedRawText: String = ""

    @Volatile
    private var cachedFinalResult: String = ""

    @Volatile
    var systemPromptTemplate: String = AsyncLlmClient.DefaultSystemPrompt

    fun setMediaProjection(projection: MediaProjection?) {
        mediaProjection = projection
    }

    fun configureDeepSeek(
        apiKey: String,
        baseUrl: String = AiConfigStore.DefaultBaseUrl,
        model: String = AiConfigStore.DefaultModel
    ) {
        llmClient = AsyncLlmClient(
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model
        )
    }

    fun triggerDataAssistance() {
        triggerDataAssistance(configStore.triggerMode)
    }

    fun triggerDataAssistance(mode: AiTriggerMode) {
        if (!configStore.enabled) {
            panicReset()
            return
        }
        val sequence = generationCounter.incrementAndGet()
        activeJob?.cancel()
        activeJob = service.lifecycleScope.launch {
            try {
                val rawText = when (mode) {
                    AiTriggerMode.Screenshot -> extractScreenText()
                    AiTriggerMode.ManualInput -> extractCurrentInputText()
                }.trim()
                if (rawText.isBlank() || sequence != generationCounter.get()) {
                    return@launch
                }
                cachedRawText = rawText

                val localAnswer = findLocalAnswer(rawText)
                val clipboardOnly = mode == AiTriggerMode.ManualInput
                val finalResult = localAnswer ?: fetchCloudAnswer(rawText, clipboardOnly)
                if (finalResult.isNullOrBlank() || sequence != generationCounter.get()) {
                    return@launch
                }

                cachedFinalResult = finalResult
                publishResult(finalResult, clipboardOnly)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "Data assistance workflow failed")
                AiCandidateOverlay.clear()
                AiInlinePanel.clear()
            }
        }
    }

    fun openManualInput(initialText: String = "", replace: Boolean = true) {
        if (!isManualModeEnabled()) return
        AiManualInputPanel.show(initialText, replace)
    }

    fun enableManualModeAndOpenInput(initialText: String = "", replace: Boolean = true) {
        configStore.enabled = true
        configStore.triggerMode = AiTriggerMode.ManualInput
        AiManualInputPanel.show(initialText, replace)
    }

    fun captureCurrentInputForManualMode() {
        if (!isManualModeEnabled()) return
        AiManualInputPanel.setText(extractCurrentInputText())
    }

    fun triggerCurrentInputAssistance() {
        if (!isManualModeEnabled()) return
        triggerDataAssistance(AiTriggerMode.ManualInput)
    }

    fun triggerManualInputAssistance() {
        if (!isManualModeEnabled()) return
        val manualText = AiManualInputPanel.currentText().trim()
        if (manualText.isBlank()) {
            triggerDataAssistance(AiTriggerMode.ManualInput)
        } else {
            triggerDataAssistanceWithText(manualText)
        }
    }

    fun isManualModeEnabled(): Boolean {
        return configStore.enabled && configStore.triggerMode == AiTriggerMode.ManualInput
    }

    private fun triggerDataAssistanceWithText(rawText: String) {
        val sequence = generationCounter.incrementAndGet()
        activeJob?.cancel()
        activeJob = service.lifecycleScope.launch {
            try {
                val text = rawText.trim()
                if (text.isBlank() || sequence != generationCounter.get()) return@launch
                cachedRawText = text
                val localAnswer = findLocalAnswer(text)
                val finalResult = localAnswer ?: fetchCloudAnswer(text, clipboardOnly = true)
                if (finalResult.isNullOrBlank() || sequence != generationCounter.get()) return@launch
                cachedFinalResult = finalResult
                publishResult(finalResult, clipboardOnly = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "Manual data assistance workflow failed")
                AiCandidateOverlay.clear()
                AiInlinePanel.clear()
            }
        }
    }

    private suspend fun extractScreenText(): String {
        val projection = mediaProjection ?: AiMediaProjectionStore.currentProjection()
        if (projection != null) {
            return ocrPipeline.extractTextOnce(projection)
        }
        AiMediaProjectionStore.requestPermission(service) {
            triggerDataAssistance(AiTriggerMode.Screenshot)
        }
        return ""
    }

    private fun extractCurrentInputText(): String {
        val connection = service.currentInputConnection ?: return ""
        val extracted = runCatching {
            connection.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        }.getOrNull()
        if (!extracted.isNullOrBlank()) return extracted

        val before = runCatching {
            connection.getTextBeforeCursor(4096, 0)?.toString()
        }.getOrNull().orEmpty()
        val after = runCatching {
            connection.getTextAfterCursor(4096, 0)?.toString()
        }.getOrNull().orEmpty()
        return (before + after).trim()
    }

    fun injectCandidateOverlayText() {
        val text = AiCandidateOverlay.currentText() ?: cachedFinalResult
        if (text.isBlank()) return
        injector.inject(text)
    }

    fun panicReset() {
        val jobToCancel = activeJob
        generationCounter.incrementAndGet()
        activeJob = null
        cachedRawText = ""
        cachedFinalResult = ""
        AiCandidateOverlay.clear()
        AiInlinePanel.clear()
        AiManualInputPanel.hide(clearText = false)
        injector.cancel()
        AiMediaProjectionStore.clearSession(stopProjection = true)
        service.lifecycleScope.launch {
            jobToCancel?.cancelAndJoin()
        }
    }

    private suspend fun findLocalAnswer(rawText: String): String? = withContext(Dispatchers.IO) {
        val ftsQuery = LocalSkillDatabase.ftsQuery(rawText)
        val candidates = if (ftsQuery.isNotBlank()) {
            runCatching {
                skillDao.searchSkillsFts(ftsQuery)
            }.getOrDefault(emptyList()).ifEmpty {
                skillDao.searchSkills(rawText)
            }
        } else {
            skillDao.searchSkills(rawText)
        }
        selectBestLocalHit(rawText, candidates)?.answer
    }

    private fun selectBestLocalHit(rawText: String, candidates: List<SkillEntity>): SkillEntity? {
        if (candidates.isEmpty()) return null
        val normalizedRaw = rawText.lowercase()
        return candidates
            .map { entity ->
                val keyword = entity.keyword.lowercase()
                val score = when {
                    keyword.isBlank() -> 0
                    normalizedRaw == keyword -> 1000 + keyword.length
                    normalizedRaw.contains(keyword) -> 700 + keyword.length
                    keyword.contains(normalizedRaw) -> 500 + normalizedRaw.length
                    else -> overlapScore(normalizedRaw, keyword)
                }
                entity to score
            }
            .filter { (_, score) -> score > 0 }
            .maxWithOrNull(compareBy<Pair<SkillEntity, Int>> { it.second }.thenBy { it.first.id })
            ?.first
    }

    private fun overlapScore(rawText: String, keyword: String): Int {
        if (rawText.isBlank() || keyword.isBlank()) return 0
        val rawTokens = rawText.split(TokenSeparators).filter { it.length >= 2 }.toSet()
        val keywordTokens = keyword.split(TokenSeparators).filter { it.length >= 2 }.toSet()
        if (rawTokens.isEmpty() || keywordTokens.isEmpty()) return 0
        return rawTokens.intersect(keywordTokens).size * 50
    }

    private suspend fun fetchCloudAnswer(rawText: String, clipboardOnly: Boolean): String? {
        val configuredClient = AsyncLlmClient(
            apiKey = configStore.apiKey,
            baseUrl = configStore.baseUrl,
            model = configStore.model
        )
        llmClient = configuredClient
        val client = configuredClient.takeIf { configStore.apiKey.isNotBlank() } ?: llmClient ?: return null
        val promptTemplate = configStore.systemPrompt.ifBlank { systemPromptTemplate }
        val systemPrompt = promptTemplate.replace("[OCR_RESULT]", rawText)
        val builder = StringBuilder()
        return client.streamAiResponse(
            systemPrompt = systemPrompt,
            userText = rawText,
            onDelta = { delta ->
                builder.append(delta)
                val partial = builder.toString()
                if (partial.isNotBlank() && !clipboardOnly) {
                    cachedFinalResult = partial
                    AiCandidateOverlay.setSuggestion(partial)
                    updateInlinePanel(partial)
                }
            },
            onComplete = { result ->
                if (result.isNotBlank()) {
                    cachedFinalResult = result
                    publishResult(result, clipboardOnly)
                }
            }
        )
    }

    private fun publishResult(text: String, clipboardOnly: Boolean = false) {
        if (clipboardOnly) {
            AiCandidateOverlay.clear()
            AiInlinePanel.clear()
        } else {
            AiCandidateOverlay.setSuggestion(text)
            updateInlinePanel(text)
        }
        copyResultToClipboard(text)
    }

    private fun copyResultToClipboard(text: String) {
        if (text.isBlank()) return
        runCatching {
            service.clipboardManager.setPrimaryClip(
                ClipData.newPlainText("AI 数据辅助", text)
            )
        }.onFailure {
            Timber.w(it, "Failed to copy AI result to clipboard")
        }
    }

    private fun updateInlinePanel(text: String) {
        if (AiInlinePanel.shouldShow(text)) {
            AiInlinePanel.setText(text)
        } else {
            AiInlinePanel.clear()
        }
    }

    companion object {
        private val TokenSeparators =
            Regex("\\s+|[\\uFF0C\\u3002\\uFF01\\uFF1F\\u3001\\uFF1B\\uFF1A,.!?;:\\[\\]\\uFF08\\uFF09(){}<>\"'`]+")

        @Volatile
        private var instance: DataAssistanceCoordinator? = null

        fun getInstance(service: FcitxInputMethodService): DataAssistanceCoordinator {
            return instance?.takeIf { it.service === service } ?: synchronized(this) {
                instance?.takeIf { it.service === service } ?: DataAssistanceCoordinator(service).also {
                    instance = it
                }
            }
        }
    }
}
