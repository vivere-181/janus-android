/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.pairing

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ge.connect.setup.SetupActivity
import ge.connect.setup.SetupGuide
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R

/**
 * Pairing with a Mac from its QR code (R41): from Devices ("Scan the code on your Mac"), or from a `janus://pair` link
 * opened by the camera app (confirmed first). Then the setup guide if it hasn't been done, else the Mac's Home.
 */
class PairActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val link = intent?.dataString?.let(QrPairing::parse)
        setContent {
            AppTheme {
                Surface(color = LocalAppSurfaces.current.background, modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        IconButton(onClick = ::finish, modifier = Modifier.align(Alignment.Start).padding(top = 8.dp)) {
                            Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back))
                        }
                        Text(stringResource(R.string.qr_pair_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.qr_pair_text), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        PairFlow(link, onPaired = { deviceId, _ -> done(deviceId) }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = {
                            startActivity(Intent(this@PairActivity, ge.connect.devices.DevicesActivity::class.java))
                            finish()
                        }) { Text(stringResource(R.string.qr_pair_wifi)) }
                    }
                }
            }
        }
    }

    private fun done(deviceId: String) {
        startActivity(
            if (SetupGuide.shouldShow(this)) Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_PAIRED, true)
            else Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)
        )
        finish()
    }

    companion object {
        fun intent(context: Context) = Intent(context, PairActivity::class.java)
    }
}
