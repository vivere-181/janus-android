/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.toShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import ge.connect.home.symbol
import ge.connect.ui.components.IconBadge
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.IntentHelper
import org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin
import org.kde.kdeconnect.plugins.share.SharePlugin
import org.kde.kdeconnect_tp.R

/** What another app shared with us. */
private sealed interface Shared {
    class Files(val uris: List<Uri>) : Shared
    class Links(val urls: List<String>) : Shared
    class Text(val text: String) : Shared

    companion object {
        fun from(intent: Intent): Shared? {
            IntentHelper.streamsFromIntent(intent).takeIf { it.isNotEmpty() }?.let { return Files(it) }
            IntentHelper.parseIntentUrls(intent).takeIf { it.isNotEmpty() }?.let { return Links(it) }
            return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let(::Text)
        }
    }
}

/**
 * Quick Share-style sheet over the sharing app: preview, choose a computer, done.
 * Links and text can go to the computer's clipboard instead. Replaces upstream's ShareActivity.
 */
class ShareSheetActivity : AppCompatActivity() {
    private var devices by mutableStateOf(emptyList<Device>())
    /** Files being sent from this sheet: to whom, and since when (to pick them out of the transfers log). */
    private var sending by mutableStateOf<Pair<Device, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val shared = Shared.from(intent) ?: run { finish(); return }

        // Direct share (the computer picked in Android's share sheet): send right away.
        val directId = intent.getStringExtra("deviceId")
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID) else null
        directId?.let { id ->
            KdeConnect.getInstance().getDevice(id)?.let { send(it, shared, copy = false) }
            finish()
            return
        }

        setContent {
            AppTheme {
                ShareSheet(shared, devices, sending, result, onDismiss = ::finish) { device, copy ->
                    if (shared is Shared.Files && (device.isReachable || ge.connect.cloud.JanusCloud.canSend(device))) {
                        // Files: the sheet turns into a progress view, then shows the result until Done.
                        sending = device to System.currentTimeMillis()
                        send(device, shared, copy, quiet = true)
                    } else {
                        // Links and text: the sheet shows what happened, until Done.
                        result = send(device, shared, copy, quiet = true)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        KdeConnect.getInstance().addDeviceListChangedCallback(TAG) { runOnUiThread { updateDevices() } }
        BackgroundService.ForceRefreshConnections(this)
        updateDevices()
    }

    override fun onStop() {
        KdeConnect.getInstance().removeDeviceListChangedCallback(TAG)
        super.onStop()
    }

    private fun updateDevices() {
        devices = KdeConnect.getInstance().devices.values.filter { it.isPaired }.sortedByDescending { it.isReachable }
    }

    private var result by mutableStateOf<String?>(null)

    private fun send(device: Device, shared: Shared, copy: Boolean, quiet: Boolean = false): String {
        val toast: String = when {
            // Links and text for a Mac that's away go through Janus Cloud (R37); copied text lands on its clipboard too.
            !device.isReachable && shared !is Shared.Files && ge.connect.cloud.CloudCompanion.away(device) -> {
                when (shared) {
                    is Shared.Links -> shared.urls.forEach { ge.connect.cloud.CloudCompanion.sendText(device, it, link = !copy) }
                    is Shared.Text -> ge.connect.cloud.CloudCompanion.sendText(device, shared.text, link = false)
                    is Shared.Files -> Unit
                }
                getString(R.string.share_sent, device.name)
            }
            // Links for a computer that's away are delivered when it reconnects (same store as upstream).
            !device.isReachable && shared is Shared.Links -> {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                val key = SharePlugin.KEY_UNREACHABLE_URL_LIST + device.deviceId
                prefs.edit { putStringSet(key, shared.urls.toSet() + prefs.getStringSet(key, emptySet()).orEmpty()) }
                getString(R.string.share_later, device.name)
            }
            // Files for a Mac that's away go through Janus Cloud (R36).
            shared is Shared.Files && !copy && ge.connect.cloud.JanusCloud.useCloud(device) -> {
                val uris = shared.uris
                // Direct share finishes right away: open the files first, while we hold the permission.
                if (quiet) Thread { ge.connect.cloud.CloudTransfers.send(device, uris) }.start()
                else ge.connect.cloud.CloudTransfers.send(device, uris)
                getString(R.string.share_sent, device.name)
            }
            !device.isReachable -> getString(R.string.share_not_connected, device.name)
            copy -> {
                val text = when (shared) {
                    is Shared.Links -> shared.urls.joinToString("\n")
                    is Shared.Text -> shared.text
                    is Shared.Files -> ""
                }
                device.getPlugin(ClipboardPlugin::class.java)?.propagateClipboard(text)
                getString(R.string.share_copied, device.name)
            }
            else -> {
                val plugin = device.getPlugin(SharePlugin::class.java)
                when (shared) {
                    is Shared.Files -> plugin?.share(intent) // progress shows in a notification
                    is Shared.Links -> plugin?.sendUrls(shared.urls)
                    is Shared.Text -> plugin?.sendText(shared.text)
                }
                getString(R.string.share_sent, device.name)
            }
        }
        if (!quiet) Toast.makeText(applicationContext, toast, Toast.LENGTH_SHORT).show()
        return toast
    }

    private companion object {
        const val TAG = "ShareSheetActivity"
    }
}

@Composable
private fun ShareSheet(
    shared: Shared, devices: List<Device>, sending: Pair<Device, Long>?, result: String?, onDismiss: () -> Unit, onSend: (Device, Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var copy by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = LocalAppSurfaces.current.background) {
        androidx.compose.animation.AnimatedContent(sending to result, label = "share sheet") { (target, message) ->
            when {
                target != null -> SendingContent(target.first, target.second, onDismiss)
                message != null -> ResultContent(R.drawable.ms_check, error = false, title = message, subtitle = null, onDone = onDismiss)
                else -> ChooseContent(shared, devices, copy, { copy = it }, onSend)
            }
        }
    }
}

/**
 * After tapping a device: its files' progress (or "waiting for them to accept" for someone else's device), then the
 * result — sent, declined or failed — which stays until the person taps Done. Hide keeps sending in the background.
 */
@Composable
internal fun SendingContent(device: Device, since: Long, onDone: () -> Unit) {
    val all by ge.connect.transfers.Transfers.items.collectAsStateWithLifecycle()
    val mine = all.filter { it.direction == ge.connect.transfers.Transfers.Direction.OUT && it.deviceId == device.deviceId && it.time >= since - 1000 }
    val finished = mine.isNotEmpty() && mine.none { it.active }
    val declined = mine.any { it.state == ge.connect.transfers.Transfers.State.DECLINED }
    val failed = !declined && mine.any { it.state == ge.connect.transfers.Transfers.State.FAILED }
    // Their phone asked its owner to accept (it tells us), until they answer.
    val waiting = !finished && mine.any { it.awaitingAccept }
    if (finished) {
        val count = mine.count { it.state == ge.connect.transfers.Transfers.State.DONE }
        ResultContent(
            icon = when { declined -> R.drawable.ms_block; failed -> R.drawable.ms_warning_filled; else -> R.drawable.ms_check }
            ,
            error = declined || failed,
            title = stringResource(
                when { declined -> R.string.share_declined; failed -> R.string.share_failed; else -> R.string.share_sent_title }, device.name,
            ),
            subtitle = if (!declined && !failed) {
                if (count == 1) mine.first().name else pluralStringResource(R.plurals.share_files, count, count)
            } else null,
            onDone = onDone,
        )
        return
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(if (waiting) R.string.share_waiting else R.string.share_sending, device.name),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(bottom = if (waiting) 4.dp else 16.dp),
        )
        if (waiting) {
            Text(
                stringResource(R.string.share_waiting_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        mine.firstOrNull()?.let { first ->
            // One card for the current file; the count covers the rest.
            val current = mine.firstOrNull { it.active } ?: first
            ge.connect.transfers.TransferCard(current, Modifier.fillMaxWidth(0.7f))
            if (mine.size > 1) {
                Text(
                    stringResource(R.string.share_sending_count, mine.count { !it.active }, mine.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } ?: androidx.compose.material3.LoadingIndicator()
        androidx.compose.material3.OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(48.dp),
        ) { Text(stringResource(R.string.share_hide)) }
    }
}

/** The end of a send: a big icon, what happened, and Done (the sheet doesn't close by itself). */
@Composable
internal fun ResultContent(icon: Int, error: Boolean, title: String, subtitle: String?, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).background(
                if (error) colors.errorContainer else colors.primaryContainer,
                androidx.compose.material3.MaterialShapes.Cookie9Sided.toShape(),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, tint = if (error) colors.onErrorContainer else colors.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Text(
            title, style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 20.dp),
        )
        subtitle?.let {
            Text(
                it, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
        }
        androidx.compose.material3.Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(52.dp),
        ) { Text(stringResource(R.string.share_done)) }
    }
}

@Composable
private fun ChooseContent(shared: Shared, devices: List<Device>, copy: Boolean, setCopy: (Boolean) -> Unit, onSend: (Device, Boolean) -> Unit) {
    run {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.share_title),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier.padding(start = 8.dp, bottom = 16.dp),
            )
            Preview(shared)

            if (shared !is Shared.Files) {
                val options = if (shared is Shared.Links) {
                    listOf(R.string.share_open_link, R.string.share_copy)
                } else {
                    listOf(R.string.share_send_text, R.string.share_copy)
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    options.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = copy == (index == 1),
                            onClick = { setCopy(index == 1) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(stringResource(label)) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            if (devices.isEmpty()) {
                Text(
                    stringResource(R.string.share_no_devices),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                ListGroup(devices.map { device ->
                    { shapes ->
                        val live = device.isReachable
                        val cloud = !live && ge.connect.cloud.JanusCloud.canSend(device)
                        // A link can wait for an away computer, files can go through the cloud; else it must be connected.
                        val usable = live || (shared is Shared.Links && !copy) || (cloud && (shared !is Shared.Files || !copy))
                        NavRow(
                            shapes, device.symbol, device.name,
                            summary = stringResource(
                                when {
                                    live -> R.string.home_connected
                                    cloud -> R.string.home_via_cloud
                                    else -> R.string.home_not_connected
                                }
                            ),
                            enabled = usable,
                            iconContainer = if (live) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                            iconColor = if (live) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        ) { onSend(device, copy) }
                    }
                })
            }
        }
    }
}

/** What's being sent: thumbnail or icon, and a short description. */
@Composable
private fun Preview(shared: Shared) {
    Surface(shape = RoundedCornerShape(24.dp), color = LocalAppSurfaces.current.card, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            when (shared) {
                is Shared.Files -> {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    var names by remember { mutableStateOf(emptyList<String>()) }
                    var thumb by remember { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(shared) {
                        withContext(Dispatchers.IO) {
                            names = shared.uris.map { displayName(context, it) }
                            thumb = thumbnail(context, shared.uris.first())
                        }
                    }
                    thumb?.let {
                        Image(it.asImageBitmap(), null, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    } ?: IconBadge(R.drawable.ms_description, size = 56)
                    PreviewText(
                        pluralStringResource(R.plurals.share_files, shared.uris.size, shared.uris.size),
                        names.joinToString(", "),
                    )
                }
                is Shared.Links -> {
                    IconBadge(R.drawable.ms_link, size = 56)
                    PreviewText(pluralStringResource(R.plurals.share_links, shared.urls.size, shared.urls.size), shared.urls.joinToString("\n"))
                }
                is Shared.Text -> {
                    IconBadge(R.drawable.ms_notes, size = 56)
                    PreviewText(stringResource(R.string.share_text), shared.text)
                }
            }
        }
    }
}

@Composable
private fun PreviewText(title: String, detail: String) {
    Column(Modifier.padding(start = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (detail.isNotEmpty()) {
            Text(
                detail, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

private fun thumbnail(context: Context, uri: Uri): Bitmap? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { context.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
    } else null
