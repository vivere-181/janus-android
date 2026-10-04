/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.share

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Right after a file appears (just saved, recorded or copied), Android can report a placeholder display name made of
 * the file's media id and extension ("1000013994.png"), and that's the name the other device would save it under.
 * When a name looks like that, look for the real one: the media index entry, then the file's path.
 */
object RealFileName {
    private const val TAG = "RealFileName"

    fun fix(context: Context, uri: Uri, name: String): String = runCatching { resolve(context, uri, name) }.getOrDefault(name)

    private fun resolve(context: Context, uri: Uri, name: String): String {
        val id = mediaId(context, uri) ?: return name
        if (name.substringBeforeLast('.') != id.toString()) return name
        val real = runCatching {
            context.contentResolver.query(
                ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATA), null, null, null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val display = cursor.getString(0)?.takeIf { it.substringBeforeLast('.') != id.toString() }
                @Suppress("DEPRECATION")
                display ?: cursor.getString(1)?.let { File(it).name }?.takeIf { it.substringBeforeLast('.') != id.toString() }
            }
        }.onFailure { Log.w(TAG, "No real name for $uri: ${it.message}") }.getOrNull()
        if (real != null) Log.i(TAG, "Placeholder name $name → $real")
        return real ?: name
    }

    /** The media id behind a MediaStore or Documents URI ("…/media/123", "…/document/msf:123", "image:123"). */
    private fun mediaId(context: Context, uri: Uri): Long? {
        val last = if (runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)) {
            runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()?.substringAfterLast(':')
        } else uri.lastPathSegment
        return last?.toLongOrNull()
    }
}
