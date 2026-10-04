/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.share

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ge.connect.home.DeviceArtOrNull
import ge.connect.home.symbol
import ge.connect.ui.components.IconBadge
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.plugins.share.SharePlugin
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R
import java.io.File

/**
 * Blip-style "send to this computer" sheet: photos and videos, files, camera. Each opens
 * Android's own picker; the picked items are sent and the sheet shows their progress.
 */
class SendSheetActivity : AppCompatActivity() {
    private var sending by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val device = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ID)?.let { KdeConnect.getInstance().getDevice(it) }
            ?.takeIf { it.isReachable && it.isPaired }
            ?: run {
                Toast.makeText(this, R.string.shortcut_not_connected, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        setContent {
            AppTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(onDismissRequest = ::finish, sheetState = sheetState, containerColor = LocalAppSurfaces.current.background) {
                    AnimatedContent(sending, label = "send sheet") { since ->
                        if (since != null) SendingContent(device, since, onDone = ::finish)
                        else Options(device) { uris -> send(device, uris) }
                    }
                }
            }
        }
    }

    private fun send(device: Device, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val plugin = device.getPlugin(SharePlugin::class.java) ?: return
        sending = System.currentTimeMillis()
        // Reads the files right away, while we still hold the pickers' read permission.
        Thread { plugin.sendFiles(uris) }.start()
    }

    companion object {
        fun intent(context: Context, deviceId: String): Intent =
            Intent(context, SendSheetActivity::class.java).putExtra(MainActivity.EXTRA_DEVICE_ID, deviceId)
    }
}

private class SendOption(
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @DrawableRes val icon: Int,
    val container: @Composable () -> Color,
    val onContainer: @Composable () -> Color,
)

@Composable
private fun Options(device: Device, onPicked: (List<Uri>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val media = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { onPicked(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onPicked(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        cameraUri?.takeIf { taken }?.let { onPicked(listOf(it)) }
    }
    fun openCamera() {
        val uri = newPhotoUri(context)
        cameraUri = uri
        camera.launch(uri)
    }
    // The app declares CAMERA (for another feature), so Android requires it before the camera opens.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera()
    }

    val options = listOf(
        SendOption(R.string.send_photos, R.string.send_photos_summary, R.drawable.ms_photo_library_filled,
            { MaterialTheme.colorScheme.tertiaryContainer }, { MaterialTheme.colorScheme.onTertiaryContainer }),
        SendOption(R.string.send_files_option, R.string.send_files_summary, R.drawable.ms_draft_filled,
            { MaterialTheme.colorScheme.primaryContainer }, { MaterialTheme.colorScheme.onPrimaryContainer }),
        SendOption(R.string.send_camera, R.string.send_camera_summary, R.drawable.ms_photo_camera_filled,
            { MaterialTheme.colorScheme.errorContainer }, { MaterialTheme.colorScheme.onErrorContainer })
    )

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DeviceArtOrNull(device, 132.dp)?.invoke() ?: IconBadge(device.symbol, size = 88)
        Text(
            stringResource(R.string.send_to_title, device.name),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
        )
        ListGroup(options.mapIndexed { index, option ->
            { shapes ->
                NavRow(
                    shapes, option.icon, stringResource(option.title), stringResource(option.summary),
                    iconContainer = option.container(), iconColor = option.onContainer(),
                ) {
                    when (index) {
                        0 -> media.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        1 -> files.launch(arrayOf("*/*"))
                        2 -> if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            openCamera()
                        } else cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                }
            }
        })
        Spacer(Modifier.height(8.dp))
    }
}

/** Where the camera saves the new photo: app-specific external cache (covered by our FileProvider). */
private fun newPhotoUri(context: Context): Uri {
    val dir = File(context.externalCacheDir ?: context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "Photo ${java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())}.jpg")
    return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
}
