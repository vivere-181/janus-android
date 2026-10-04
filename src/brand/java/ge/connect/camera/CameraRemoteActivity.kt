/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.camera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ge.connect.ui.theme.AppTheme
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R

/** The other phone as a viewfinder and shutter: live preview, timer, switch camera; photos arrive as transfers. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
class CameraRemoteActivity : AppCompatActivity() {
    private lateinit var device: Device

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        device = intent.getStringExtra(EXTRA_DEVICE)?.let { KdeConnect.getInstance().getDevice(it) }?.takeIf { it.isReachable }
            ?: run { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AppTheme { Screen() } }
    }

    override fun onStart() {
        super.onStart()
        CameraViewer.reset(device.deviceId)
        CameraViewer.request(device, "start")
    }

    override fun onStop() {
        CameraViewer.request(device, "stop")
        super.onStop()
    }

    @Composable
    private fun Screen() {
        val feeds by CameraViewer.feeds.collectAsStateWithLifecycle()
        val feed = feeds[device.deviceId] ?: CameraViewer.Feed()
        var timer by remember { mutableIntStateOf(0) }
        LaunchedEffect(feed.status) {
            if (feed.status == "captured") android.widget.Toast.makeText(
                this@CameraRemoteActivity, getString(R.string.camera_captured, device.name), android.widget.Toast.LENGTH_SHORT,
            ).show()
            if (feed.status == "stopped" || feed.status == "denied") finish()
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val frame = feed.frame
            if (frame != null) {
                Image(frame.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator(Modifier.size(56.dp), color = Color.White)
                    Text(
                        stringResource(
                            if (feed.status == "waiting") R.string.camera_waiting else R.string.camera_connecting, device.name,
                        ),
                        color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            if (feed.status == "countdown" && feed.countdown > 0) {
                Text("${feed.countdown}", color = Color.White, fontSize = 120.sp, modifier = Modifier.align(Alignment.Center))
            }
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::finish) { Icon(painterResource(R.drawable.ms_close), stringResource(R.string.camera_stop), tint = Color.White) }
                Text(
                    stringResource(R.string.camera_viewer_title, device.name), color = Color.White,
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp),
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Timer: off → 3 s → 10 s
                Box(
                    Modifier.size(56.dp).background(if (timer > 0) Color.White else Color.White.copy(alpha = 0.18f), CircleShape)
                        .clickable { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 } },
                    contentAlignment = Alignment.Center,
                ) {
                    if (timer == 0) Icon(painterResource(R.drawable.ms_timer), stringResource(R.string.camera_timer), tint = Color.White)
                    else Text("${timer}s", color = Color.Black, style = MaterialTheme.typography.labelLarge)
                }
                Box(
                    Modifier.size(80.dp).background(Color.White, CircleShape).padding(5.dp).background(Color.Black, CircleShape)
                        .padding(3.dp).background(if (frame != null) Color.White else Color.Gray, CircleShape)
                        .clickable(enabled = frame != null) { CameraViewer.request(device, "shutter", timer) },
                )
                Box(
                    Modifier.size(56.dp).background(Color.White.copy(alpha = 0.18f), CircleShape).clickable { CameraViewer.request(device, "switch") },
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ms_cameraswitch), stringResource(R.string.camera_switch), tint = Color.White) }
            }
        }
    }

    companion object {
        private const val EXTRA_DEVICE = "device"
        fun intent(context: Context, deviceId: String): Intent =
            Intent(context, CameraRemoteActivity::class.java).putExtra(EXTRA_DEVICE, deviceId)
    }
}
