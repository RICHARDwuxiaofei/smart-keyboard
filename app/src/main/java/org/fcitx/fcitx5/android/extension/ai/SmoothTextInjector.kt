/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import android.view.inputmethod.InputConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

class SmoothTextInjector(
    private val service: FcitxInputMethodService,
    private val scope: CoroutineScope
) {
    private var activeJob: Job? = null

    fun injectTextSmoothly(connection: InputConnection, text: String): Job {
        activeJob?.cancel()
        activeJob = scope.launch(Dispatchers.Main.immediate) {
            val content = text.takeIf { it.isNotBlank() } ?: return@launch
            try {
                connection.finishComposingText()
                content.toCharArray().forEach { char ->
                    coroutineContext.ensureActive()
                    val committed = connection.commitText(char.toString(), 1)
                    if (!committed) {
                        throw CancellationException("InputConnection refused commitText")
                    }
                    delay(100L + Random.nextLong(20, 50))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.w(e, "Smooth text injection stopped")
            }
        }
        return activeJob!!
    }

    fun inject(text: String): Job? {
        val connection = service.currentInputConnection ?: return null
        return injectTextSmoothly(connection, text)
    }

    suspend fun injectTextSmoothlyBlocking(connection: InputConnection, text: String) {
        withContext(Dispatchers.Main.immediate) {
            injectTextSmoothly(connection, text).join()
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }
}
