/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package ge.connect.transfers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import ge.connect.home.symbol
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R
import java.io.File

/**
 * Blip-style transfer card: big preview with the device badge, name, "From/To …", and a wavy
 * progress bar while it's moving. [compact] is the smaller version for the Home carousel.
 */
@Composable
fun TransferCard(item: Transfers.Item, modifier: Modifier = Modifier, compact: Boolean = false) {
    val badge: Dp = if (compact) 36.dp else 48.dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomCenter) {
            Preview(item, Modifier.fillMaxWidth().aspectRatio(if (compact) 1f else 4f / 3f).padding(bottom = badge / 2))
            DeviceBadge(item, badge)
        }
        Text(
            item.name,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMediumEmphasized,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
        )
        Status(item, compact)
    }
}

/**
 * A placeholder in the shape of a compact [TransferCard] (preview, badge spot, two text lines), shown on Home until real
 * transfers fill the row, so the section reads as "your files go here". Static (no shimmer): it costs nothing.
 */
@Composable
fun TransferPlaceholder(@DrawableRes icon: Int, modifier: Modifier = Modifier) {
    val surface = LocalAppSurfaces.current.card
    val bar = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).padding(bottom = 18.dp).clip(RoundedCornerShape(28.dp)).background(surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(icon), null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxSize(0.3f),
                )
            }
            Box(
                Modifier.size(36.dp).border(4.dp, MaterialTheme.colorScheme.background, CircleShape).padding(2.dp).background(bar, CircleShape),
            )
        }
        Box(Modifier.padding(top = 12.dp).fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(bar))
        Box(Modifier.padding(top = 8.dp).fillMaxWidth(0.45f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(bar.copy(alpha = 0.6f)))
    }
}

@Composable
private fun Preview(item: Transfers.Item, modifier: Modifier) {
    val shape = RoundedCornerShape(28.dp)
    val bitmap = remember(item.thumb) { item.thumb?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() } }
    Box(
        modifier.clip(shape).background(LocalAppSurfaces.current.card),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                painterResource(kindIcon(item)), null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.35f),
            )
        }
        // Finished: a check pops in on the preview.
        AnimatedVisibility(
            visible = item.state == Transfers.State.DONE && !item.awaitingAccept && !item.awaitingDelivery && item.kind == Transfers.Kind.FILE,
            enter = scaleIn() + fadeIn(),
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            Icon(
                painterResource(R.drawable.ms_check_circle_filled), stringResource(R.string.transfers_done),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
            )
        }
    }
}

@Composable
private fun DeviceBadge(item: Transfers.Item, size: Dp) {
    val device = remember(item.deviceId) { KdeConnect.getInstance().getDevice(item.deviceId) }
    Box(
        Modifier
            .size(size)
            .border(4.dp, MaterialTheme.colorScheme.background, CircleShape)
            .padding(2.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val art = ge.connect.home.DeviceArtOrNull(device, size * 0.78f)
        if (art != null) art() else Icon(
            painterResource(device?.symbol ?: R.drawable.ms_laptop_mac), null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun Status(item: Transfers.Item, compact: Boolean) {
    val context = LocalContext.current
    val who = stringResource(if (item.direction == Transfers.Direction.IN) R.string.transfers_from else R.string.transfers_to, item.deviceName)
    val style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.awaitingAccept) {
            Text(stringResource(R.string.transfers_awaiting_accept), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val viaCloud = stringResource(R.string.transfers_via_cloud).takeIf { item.cloud }
        if (item.awaitingDelivery) {
            // Uploaded to Janus Cloud; the computer hasn't picked it up yet.
            Text(
                stringResource(R.string.transfers_awaiting_delivery, item.deviceName), style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp),
            )
            return@Column
        }
        when (item.state) {
            Transfers.State.WAITING -> Text(stringResource(R.string.transfers_waiting), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Transfers.State.RUNNING -> {
                LinearWavyProgressIndicator(progress = { item.progress }, modifier = Modifier.padding(top = 4.dp).fillMaxWidth(if (compact) 0.8f else 0.6f))
                Text(
                    if (compact) "${(item.progress * 100).toInt()}%"
                    else listOfNotNull(
                        stringResource(
                            R.string.transfers_progress,
                            (item.progress * 100).toInt(),
                            Formatter.formatShortFileSize(context, item.bytesPerSecond),
                        ),
                        viaCloud,
                    ).joinToString(" · "),
                    style = style, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Transfers.State.FAILED -> Text(stringResource(R.string.transfers_failed), style = style, color = MaterialTheme.colorScheme.error)
            Transfers.State.DECLINED -> Text(stringResource(R.string.transfers_declined), style = style, color = MaterialTheme.colorScheme.error)
            Transfers.State.DONE -> Text(
                if (compact) who else listOfNotNull(
                    who,
                    viaCloud,
                    Formatter.formatShortFileSize(context, item.size).takeIf { item.kind == Transfers.Kind.FILE },
                    if (System.currentTimeMillis() - item.time < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.transfers_just_now)
                    else DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                ).joinToString(" · "),
                style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@DrawableRes
private fun kindIcon(item: Transfers.Item): Int = when {
    item.kind == Transfers.Kind.LINK -> R.drawable.ms_link
    item.kind == Transfers.Kind.TEXT -> R.drawable.ms_notes
    item.mime?.startsWith("image/") == true -> R.drawable.ms_image
    item.mime?.startsWith("video/") == true -> R.drawable.ms_movie
    item.mime?.startsWith("audio/") == true -> R.drawable.ms_music_note
    else -> R.drawable.ms_description
}

/** Open what the card shows: the file, the link, or copy the text. */
fun openTransfer(context: Context, item: Transfers.Item) {
    when (item.kind) {
        Transfers.Kind.LINK -> context.startSafely(Intent(Intent.ACTION_VIEW, item.name.toUri()))
        Transfers.Kind.TEXT -> {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, item.name))
            Toast.makeText(context, R.string.transfers_text_copied, Toast.LENGTH_SHORT).show()
        }
        Transfers.Kind.FILE -> contentUri(context, item)?.let { uri ->
            context.startSafely(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } ?: Toast.makeText(context, R.string.transfers_cant_open, Toast.LENGTH_SHORT).show()
    }
}

fun shareTransfer(context: Context, item: Transfers.Item) {
    val intent = when (item.kind) {
        Transfers.Kind.FILE -> contentUri(context, item)?.let {
            Intent(Intent.ACTION_SEND).setType(item.mime ?: "*/*").putExtra(Intent.EXTRA_STREAM, it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        else -> Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, item.name)
    } ?: return Toast.makeText(context, R.string.transfers_cant_open, Toast.LENGTH_SHORT).show()
    context.startSafely(Intent.createChooser(intent, null))
}

private fun contentUri(context: Context, item: Transfers.Item): Uri? {
    val uri = item.uri?.toUri() ?: return null
    if (uri.scheme != "file") return uri
    val file = File(uri.path ?: return null).takeIf { it.exists() } ?: return null
    return runCatching { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file) }.getOrNull()
}

private fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(this, R.string.transfers_cant_open, Toast.LENGTH_SHORT).show() }
}
