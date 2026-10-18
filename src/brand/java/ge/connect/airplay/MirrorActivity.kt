/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.airplay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ge.connect.ui.theme.AppTheme
import org.kde.kdeconnect.helpers.DeviceHelper
import org.kde.kdeconnect_tp.R

/**
 * On a TV: a mirrored screen (AirPlay, [AirPlayReceiver]), fitted to the TV with its own shape. Opens by itself when a
 * Mac starts mirroring and closes when it stops; opened from the TV's app row, it says how to mirror to the TV.
 */
class MirrorActivity : ComponentActivity() {
    /** Opened for a mirrored screen (not by hand): closes when it ends. */
    private var forMirroring = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forMirroring = intent.getBooleanExtra(EXTRA_MIRRORING, false)
        org.kde.kdeconnect.BackgroundService.Start(this) // Janus (and so AirPlay) running while this is in front
        AirPlayReceiver.start(this)
        setContent {
            AppTheme {
                val state by AirPlayReceiver.state.collectAsState()
                val aspect = state.mirroring
                LaunchedEffect(aspect != null) {
                    if (aspect != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (forMirroring) finish()
                    }
                }
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    // Always there, so the decoder has somewhere to draw as soon as the first frame comes.
                    AndroidView(
                        factory = { context ->
                            SurfaceView(context).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) = AirPlayReceiver.setSurface(holder.surface)
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = AirPlayReceiver.setSurface(holder.surface)
                                    override fun surfaceDestroyed(holder: SurfaceHolder) = AirPlayReceiver.clearSurface(holder.surface)
                                })
                            }
                        },
                        modifier = Modifier.aspectRatio(aspect ?: (16f / 9f)),
                    )
                    if (aspect == null) Waiting(state.name.ifEmpty { DeviceHelper.getDeviceName(this@MirrorActivity) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_MIRRORING, false)) forMirroring = true
    }

    @Composable
    private fun Waiting(name: String) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(112.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ms_cast), null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(R.string.airplay_ready), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.airplay_ready_text, name),
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            // Android only lets Janus bring a mirrored screen to the front by itself with "Display over other apps".
            var allowed by remember { mutableStateOf(canOpenByItself(this@MirrorActivity)) }
            androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { allowed = canOpenByItself(this@MirrorActivity); onPauseOrDispose { } }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!allowed) androidx.compose.material3.Button(onClick = {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                    }
                }) { Text(stringResource(R.string.airplay_allow_open)) }
                FilledTonalButton(onClick = {
                    startActivity(Intent(this@MirrorActivity, org.kde.kdeconnect.ui.MainActivity::class.java))
                }) { Text(stringResource(R.string.screen_settings)) }
            }
            if (!allowed) Text(
                stringResource(R.string.airplay_allow_open_text),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }

    companion object {
        private const val EXTRA_MIRRORING = "mirroring"

        /** Whether Android lets Janus open the mirrored screen while it's in the background. */
        fun canOpenByItself(context: Context) = Settings.canDrawOverlays(context)

        /**
         * Brings the mirrored screen to the front (from the receiver, while Janus is in the background). Without
         * "Display over other apps" Android doesn't allow it: the TV says to open Janus instead.
         */
        fun open(context: Context) {
            if (!canOpenByItself(context)) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(context, R.string.airplay_open_janus, android.widget.Toast.LENGTH_LONG).show()
                }
            }
            val intent = Intent(context, MirrorActivity::class.java)
                .putExtra(EXTRA_MIRRORING, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            runCatching { context.startActivity(intent) }.onFailure { Log.w("AirPlay", "Couldn't show the screen: ${it.message}") }
        }
    }
}
