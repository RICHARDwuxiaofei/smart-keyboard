/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import org.fcitx.fcitx5.android.core.CandidateWord
import java.util.concurrent.CopyOnWriteArraySet

object AiCandidateOverlay {
    @Volatile
    private var candidateText: String? = null

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun setSuggestion(text: String) {
        candidateText = text.takeIf { it.isNotBlank() }
        notifyChanged()
    }

    fun clear() {
        candidateText = null
        notifyChanged()
    }

    fun currentText(): String? = candidateText

    fun hasSuggestion(): Boolean = candidateText != null

    fun applyTo(candidates: Array<CandidateWord>): Array<CandidateWord> {
        val text = candidateText ?: return candidates
        return arrayOf(CandidateWord("", text, "", false)) + candidates
    }

    fun mapDisplayedIndexToFcitxIndex(displayedIndex: Int): Int? {
        return if (candidateText == null) {
            displayedIndex
        } else if (displayedIndex == 0) {
            null
        } else {
            displayedIndex - 1
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyChanged() {
        listeners.forEach { it.invoke() }
    }
}
