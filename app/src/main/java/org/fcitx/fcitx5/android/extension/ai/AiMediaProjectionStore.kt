/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.extension.ai

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection

object AiMediaProjectionStore {
    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var pendingAfterGrant: (() -> Unit)? = null

    fun currentProjection(): MediaProjection? = projection

    fun requestPermission(context: Context, afterGrant: () -> Unit) {
        pendingAfterGrant = afterGrant
        val intent = Intent(context, ScreenCapturePermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun onPermissionGranted(mediaProjection: MediaProjection) {
        projection = mediaProjection
        val callback = pendingAfterGrant
        pendingAfterGrant = null
        callback?.invoke()
    }

    fun onPermissionDenied() {
        pendingAfterGrant = null
    }

    fun clearSession(stopProjection: Boolean = false) {
        pendingAfterGrant = null
        if (stopProjection) {
            projection?.stop()
            projection = null
        }
    }
}
