/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import java.util.concurrent.CopyOnWriteArraySet

object AiManualInputPanel {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val builder = StringBuilder()

    @Volatile
    private var visible = false

    fun show(initialText: String = "", replace: Boolean = true) {
        if (replace) {
            builder.clear()
            builder.append(initialText)
        }
        visible = true
        notifyChanged()
    }

    fun hide(clearText: Boolean = false) {
        visible = false
        if (clearText) builder.clear()
        notifyChanged()
    }

    fun isVisible(): Boolean = visible

    fun currentText(): String = builder.toString()

    fun setText(text: String) {
        builder.clear()
        builder.append(text)
        visible = true
        notifyChanged()
    }

    fun append(text: String) {
        if (text.isEmpty()) return
        builder.append(text)
        visible = true
        notifyChanged()
    }

    fun backspace() {
        if (builder.isNotEmpty()) {
            builder.deleteCharAt(builder.length - 1)
            notifyChanged()
        }
    }

    fun clear() {
        if (builder.isEmpty() && !visible) return
        builder.clear()
        visible = false
        notifyChanged()
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
