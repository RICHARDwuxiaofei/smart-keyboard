/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SkillImportManager(context: Context) {
    private val skillDao = LocalSkillDatabase.getInstance(context).skillDao()

    suspend fun importJson(json: String, replaceExisting: Boolean = false): Int = withContext(Dispatchers.IO) {
        val skills = parseJson(json)
        if (replaceExisting) {
            skillDao.replaceAll(skills)
        } else {
            skillDao.addOrReplaceAll(skills)
        }
        skills.size
    }

    suspend fun importPlainText(text: String, replaceExisting: Boolean = false): Int = withContext(Dispatchers.IO) {
        val skills = parsePlainText(text)
        if (replaceExisting) {
            skillDao.replaceAll(skills)
        } else {
            skillDao.addOrReplaceAll(skills)
        }
        skills.size
    }

    private fun parseJson(json: String): List<SkillEntity> {
        val trimmed = json.trim()
        if (trimmed.isBlank()) return emptyList()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            JSONObject(trimmed).optJSONArray("skills") ?: JSONArray().put(JSONObject(trimmed))
        }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val keyword = item.optString("keyword", item.optString("question")).trim()
                val answer = item.optString("answer", item.optString("content")).trim()
                if (keyword.isNotBlank() && answer.isNotBlank()) {
                    add(SkillEntity(keyword = keyword, answer = answer))
                }
            }
        }
    }

    private fun parsePlainText(text: String): List<SkillEntity> {
        return text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = when {
                    "\t" in line -> "\t"
                    "=>" in line -> "=>"
                    "|" in line -> "|"
                    ":" in line -> ":"
                    else -> null
                } ?: return@mapNotNull null
                val parts = line.split(separator, limit = 2)
                val keyword = parts.getOrNull(0)?.trim().orEmpty()
                val answer = parts.getOrNull(1)?.trim().orEmpty()
                if (keyword.isBlank() || answer.isBlank()) null else SkillEntity(keyword = keyword, answer = answer)
            }
            .toList()
    }
}
