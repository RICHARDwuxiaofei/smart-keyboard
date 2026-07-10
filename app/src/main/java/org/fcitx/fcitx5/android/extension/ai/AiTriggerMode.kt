/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

enum class AiTriggerMode(val storageValue: String) {
    Screenshot("screenshot"),
    ManualInput("manual_input");

    companion object {
        fun fromStorageValue(value: String?): AiTriggerMode {
            return entries.firstOrNull { it.storageValue == value } ?: Screenshot
        }
    }
}
