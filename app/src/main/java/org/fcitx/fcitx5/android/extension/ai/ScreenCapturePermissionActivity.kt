/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import timber.log.Timber

class ScreenCapturePermissionActivity : Activity() {
    private lateinit var projectionManager: MediaProjectionManager
    private var permissionRequestStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionRequestStarted = savedInstanceState?.getBoolean(KEY_REQUEST_STARTED) == true
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (!permissionRequestStarted) {
            permissionRequestStarted = true
            startActivityForResult(
                projectionManager.createScreenCaptureIntent(),
                REQUEST_CAPTURE_PERMISSION
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_REQUEST_STARTED, permissionRequestStarted)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE_PERMISSION) {
            finish()
            return
        }
        if (resultCode == RESULT_OK && data != null) {
            runCatching {
                projectionManager.getMediaProjection(resultCode, data)
            }.onSuccess { projection ->
                if (projection != null) {
                    AiMediaProjectionStore.onPermissionGranted(projection)
                } else {
                    Timber.w("MediaProjectionManager returned null projection")
                    AiMediaProjectionStore.onPermissionDenied()
                }
            }.onFailure { throwable ->
                Timber.w(throwable, "Failed to create MediaProjection")
                AiMediaProjectionStore.onPermissionDenied()
            }
        } else {
            AiMediaProjectionStore.onPermissionDenied()
        }
        finish()
    }

    companion object {
        private const val REQUEST_CAPTURE_PERMISSION = 0x5149
        private const val KEY_REQUEST_STARTED = "request_started"
    }
}
