/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.share

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ge.connect.ui.theme.AppTheme
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.share.SharePlugin
import org.kde.kdeconnect_tp.R

/** Send a piece of text or a link to a device on purpose (for someone else's device, instead of clipboard sync). */
class SendTextActivity : AppCompatActivity() {
    private var text by mutableStateOf("")
    private var prefilled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = intent.getStringExtra(EXTRA_DEVICE)?.let { KdeConnect.getInstance().getDevice(it) }
        val plugin = device?.getPlugin(SharePlugin::class.java) ?: run { finish(); return }
        setContent {
            AppTheme {
                AlertDialog(
                    onDismissRequest = ::finish,
                    title = { Text(stringResource(R.string.send_text_title, device.name)) },
                    text = {
                        OutlinedTextField(
                            value = text, onValueChange = { text = it },
                            placeholder = { Text(stringResource(R.string.send_text_hint)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        )
                    },
                    confirmButton = {
                        TextButton(enabled = text.isNotBlank(), onClick = {
                            val value = text.trim()
                            if (Patterns.WEB_URL.matcher(value).matches() && !value.contains(' ')) plugin.sendUrls(listOf(value))
                            else plugin.sendText(value)
                            Toast.makeText(this, getString(R.string.send_text_sent, device.name), Toast.LENGTH_SHORT).show()
                            finish()
                        }) { Text(stringResource(R.string.send_text_send)) }
                    },
                    dismissButton = { TextButton(onClick = ::finish) { Text(stringResource(R.string.settings_cancel)) } },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The clipboard can only be read once we have focus; offer it as a starting point.
        if (hasFocus && !prefilled) {
            prefilled = true
            getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                ?.takeIf { it.isNotBlank() && text.isEmpty() }?.let { text = it }
        }
    }

    companion object {
        private const val EXTRA_DEVICE = "device"
        fun intent(context: Context, deviceId: String): Intent = Intent(context, SendTextActivity::class.java).putExtra(EXTRA_DEVICE, deviceId)
    }
}
