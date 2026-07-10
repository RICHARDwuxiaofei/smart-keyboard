/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import java.util.concurrent.CopyOnWriteArraySet

object AiInlinePanel {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    private var panelText: String = ""

    fun setText(text: String) {
        val next = text.trim()
        if (panelText == next) return
        panelText = next
        notifyChanged()
    }

    fun clear() {
        if (panelText.isEmpty()) return
        panelText = ""
        notifyChanged()
    }

    fun currentText(): String = panelText

    fun shouldShow(text: String = panelText): Boolean {
        val value = text.trim()
        return value.contains('\n') || value.length > 48
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
