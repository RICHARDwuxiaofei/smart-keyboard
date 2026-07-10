/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume

class ScreenCaptureOcrPipeline(
    private val context: Context
) {
    private val recognizer by lazy {
        TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
    }

    fun startCaptureSession(
        mediaProjection: MediaProjection,
        onBitmapCaptured: (Bitmap?) -> Unit
    ) {
        val metrics = context.resources.displayMetrics
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = context.getSystemService(WindowManager::class.java)
                .currentWindowMetrics
                .bounds
            width = bounds.width().coerceAtLeast(metrics.widthPixels)
            height = bounds.height().coerceAtLeast(metrics.heightPixels)
        } else {
            width = metrics.widthPixels
            height = metrics.heightPixels
        }

        val density = metrics.densityDpi
        val handlerThread = HandlerThread("ai-screen-capture").apply { start() }
        val handler = Handler(handlerThread.looper)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null
        var completed = false

        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!completed) {
                    completed = true
                    onBitmapCaptured(null)
                }
                imageReader.setOnImageAvailableListener(null, null)
                virtualDisplay?.release()
                imageReader.close()
                handlerThread.quitSafely()
            }
        }

        fun finish(bitmap: Bitmap?) {
            if (completed) return
            completed = true
            imageReader.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            mediaProjection.unregisterCallback(projectionCallback)
            imageReader.close()
            handlerThread.quitSafely()
            onBitmapCaptured(bitmap)
        }

        mediaProjection.registerCallback(projectionCallback, handler)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image == null) {
                finish(null)
                return@setOnImageAvailableListener
            }
            val bitmap = runCatching {
                image.use { it.toBitmap(width, height) }
            }.onFailure {
                Timber.w(it, "Failed to convert screen capture frame to Bitmap")
            }.getOrNull()
            finish(bitmap)
        }, handler)

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ai-data-assistance-single-frame",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler
        )
    }

    suspend fun captureSingleFrame(mediaProjection: MediaProjection?): Bitmap? {
        val projection = mediaProjection ?: return null
        return suspendCancellableCoroutine { continuation ->
            startCaptureSession(projection) { bitmap ->
                if (continuation.isActive) {
                    continuation.resume(bitmap)
                } else {
                    bitmap?.recycle()
                }
            }
        }
    }

    fun processOcr(bitmap: Bitmap, onTextExtracted: (String) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                onTextExtracted(result.text.trim())
            }
            .addOnFailureListener { throwable ->
                Timber.w(throwable, "Offline OCR failed")
                onTextExtracted("")
            }
    }

    suspend fun recognizeOffline(bitmap: Bitmap): String {
        return suspendCancellableCoroutine { continuation ->
            processOcr(bitmap) { text ->
                if (continuation.isActive) {
                    continuation.resume(text)
                }
            }
        }
    }

    suspend fun extractTextOnce(mediaProjection: MediaProjection?): String = withContext(Dispatchers.IO) {
        val bitmap = captureSingleFrame(mediaProjection) ?: return@withContext ""
        try {
            recognizeOffline(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun Image.toBitmap(expectedWidth: Int, expectedHeight: Int): Bitmap {
        val plane = planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * expectedWidth
        val paddedWidth = expectedWidth + rowPadding / pixelStride
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, expectedHeight, Bitmap.Config.ARGB_8888)
        paddedBitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, expectedWidth, expectedHeight)
        paddedBitmap.recycle()
        return cropped
    }

    private inline fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R {
        var closed = false
        try {
            return block(this)
        } finally {
            if (!closed) {
                closed = true
                this?.close()
            }
        }
    }
}
