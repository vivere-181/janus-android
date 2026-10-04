/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import org.kde.kdeconnect_tp.R

/**
 * Invisible screen behind the "Allow declining calls" notification: asks for call control (ANSWER_PHONE_CALLS)
 * and closes. If Android won't show the dialog any more, opens the app's settings instead.
 */
class CallPermissionActivity : ComponentActivity() {
    private val request = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            Toast.makeText(this, R.string.call_control_granted, Toast.LENGTH_SHORT).show()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.ANSWER_PHONE_CALLS)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationManagerCompat.from(this).cancel(CallCommands.CALL_CONTROL_NOTIFICATION_ID)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || CallCommands.hasCallControl(this)) {
            finish()
            return
        }
        if (savedInstanceState == null) request.launch(Manifest.permission.ANSWER_PHONE_CALLS)
    }
}
