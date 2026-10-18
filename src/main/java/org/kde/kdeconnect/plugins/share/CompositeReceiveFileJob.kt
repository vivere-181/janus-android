/*
 * SPDX-FileCopyrightText: 2018 Erik Duisters <e.duisters1@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.GuardedBy
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.apache.commons.io.IOUtils
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.async.BackgroundJob
import org.kde.kdeconnect.helpers.FilesHelper
import org.kde.kdeconnect.helpers.MediaStoreHelper
import org.kde.kdeconnect_tp.R
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.FileTime

/**
 * A type of [BackgroundJob] that reads Files from another device.
 *
 * We receive the requests as [NetworkPacket]s.
 *
 * Each packet should have a 'filename' property and a payload. If the payload is missing,
 * we'll just create an empty file. You can add new packets anytime via
 * [addNetworkPacket].
 *
 * The I/O-part of this file reading is handled by [receiveFile].
 *
 * @see CompositeUploadFileJob
 */
class CompositeReceiveFileJob internal constructor(
    device: Device,
    callBack: Callback<Void?>
) : BackgroundJob<Device, Void?>(device, callBack) {
    private val receiveNotification: ReceiveNotification = ReceiveNotification(device, id)
    private var currentNetworkPacket: NetworkPacket? = null
    private var currentFileName: String? = null
    private var currentFileNum: Int = 0
    private var totalReceived: Long = 0
    private var lastProgressTimeMillis: Long = 0
    private var prevProgressPercentage: Long = 0

    private val lock = Any()                                //Use to protect concurrent access to the variables below
    @GuardedBy("lock")
    private val networkPacketList: MutableList<NetworkPacket> = ArrayList()
    @GuardedBy("lock")
    private var totalNumFiles: Int = 0
    @GuardedBy("lock")
    private var totalPayloadSize: Long = 0
    private var geTransferId: Long = -1 // ge.connect: transfers feed item of the current file
    private var geLastProgressMillis: Long = 0
    internal var isRunning: Boolean = false
        private set

    private val device: Device
        get() = requestInfo

    internal fun updateTotals(numberOfFiles: Int, totalPayloadSize: Long) {
        synchronized(lock) {
            this.totalNumFiles = numberOfFiles
            this.totalPayloadSize = totalPayloadSize

            receiveNotification.setTitle(device.context.resources
                .getQuantityString(R.plurals.incoming_file_title, totalNumFiles, totalNumFiles, device.name))
        }
    }

    internal fun addNetworkPacket(networkPacket: NetworkPacket) {
        synchronized(lock) {
            if (!networkPacketList.contains(networkPacket)) {
                networkPacketList.add(networkPacket)

                totalNumFiles = networkPacket.getInt(SharePlugin.KEY_NUMBER_OF_FILES, 1)
                totalPayloadSize = networkPacket.getLong(SharePlugin.KEY_TOTAL_PAYLOAD_SIZE)

                receiveNotification.setTitle(device.context.resources
                    .getQuantityString(R.plurals.incoming_file_title, totalNumFiles, totalNumFiles, device.name))
            }
        }
    }

    override fun run() {
        var outputStream: OutputStream? = null

        var done = synchronized(lock) { networkPacketList.isEmpty() }

        try {
            var fileDocument: DocumentFile? = null

            isRunning = true

            while (!done && !isCancelled) {
                val np = synchronized(lock) { networkPacketList[0] }
                currentNetworkPacket = np
                val fileName = np.getString("filename", System.currentTimeMillis().toString())
                currentFileName = fileName
                currentFileNum++

                setProgress(prevProgressPercentage.toInt())

                val document = getDocumentFileFor(fileName, np.getBoolean("open", false))
                fileDocument = document
                geTransferId = ge.connect.transfers.Transfers.incomingStarted(device, np, document.name ?: fileName) // ge.connect

                if (np.hasPayload()) {
                    val bufferedOutputStream = BufferedOutputStream(device.context.contentResolver.openOutputStream(document.uri))
                    outputStream = bufferedOutputStream
                    val inputStream = np.payload!!.inputStream

                    val received = receiveFile(inputStream, bufferedOutputStream)

                    np.payload!!.close()

                    try {
                        bufferedOutputStream.close()
                    } catch (ignored: IOException) {}
                    outputStream = null

                    if (received != np.payloadSize) {
                        document.delete()

                        if (!isCancelled) {
                            throw RuntimeException("Failed to receive: $fileName received:$received bytes, expected: ${np.payloadSize} bytes")
                        }
                    } else {
                        publishFile(document, received)
                        ge.connect.transfers.Transfers.incomingDone(geTransferId, document.uri, document.type) // ge.connect
                    }
                } else {
                    //TODO: Only set progress to 100 if this is the only file/packet to send
                    setProgress(100)
                    publishFile(document, 0)
                    ge.connect.transfers.Transfers.incomingDone(geTransferId, document.uri, document.type) // ge.connect
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (np.has("lastModified")) {
                        try {
                            val lastModified = np.getLong("lastModified")
                            Files.setLastModifiedTime(Paths.get(document.uri.path), FileTime.fromMillis(lastModified))
                        } catch (e: Exception) {
                            Log.e("SharePlugin", "Can't set date on file")
                            e.printStackTrace()
                        }
                    }
                }

                val listIsEmpty = synchronized(lock) {
                    networkPacketList.removeAt(0)
                    networkPacketList.isEmpty()
                }

                if (listIsEmpty && !isCancelled) {
                    try {
                        Thread.sleep(1000)
                    } catch (ignored: InterruptedException) {}

                    synchronized(lock) {
                        if (currentFileNum < totalNumFiles && networkPacketList.isEmpty()) {
                            throw RuntimeException("Failed to receive ${totalNumFiles - currentFileNum + 1} files")
                        }
                    }
                }

                done = synchronized(lock) { networkPacketList.isEmpty() }
            }

            isRunning = false

            if (isCancelled) {
                if (geTransferId != -1L) ge.connect.transfers.Transfers.incomingFailed(geTransferId) // ge.connect
                receiveNotification.cancel()
                return
            }

            val numFiles = synchronized(lock) { totalNumFiles }

            if (numFiles == 1 && currentNetworkPacket!!.getBoolean("open", false) && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                receiveNotification.cancel()
                openFile(fileDocument!!)
            } else {
                //Update the notification and allow to open the file from it
                receiveNotification.setFinished(device.context.resources.getQuantityString(R.plurals.received_files_title, numFiles, device.name, numFiles))

                if (totalNumFiles == 1 && fileDocument != null) {
                    receiveNotification.setURI(fileDocument.uri, fileDocument.type!!, fileDocument.name)
                }

                receiveNotification.show()
            }
            reportResult(null)
        } catch (e: ActivityNotFoundException) {
            receiveNotification.setFinished(device.context.getString(R.string.no_app_for_opening))
            receiveNotification.show()
        } catch (e: Exception) {
            isRunning = false

            Log.e("Shareplugin", "Error receiving file", e)
            if (geTransferId != -1L) ge.connect.transfers.Transfers.incomingFailed(geTransferId) // ge.connect

            val failedFiles = synchronized(lock) { totalNumFiles - currentFileNum + 1 }

            receiveNotification.setFailed(device.context.resources.getQuantityString(R.plurals.received_files_fail_title, failedFiles, device.name, failedFiles, totalNumFiles))
            receiveNotification.show()
            reportError(e)
        } finally {
            closeAllInputStreams()
            networkPacketList.clear()
            try {
                IOUtils.close(outputStream)
            } catch (ignored: IOException) {
            }
        }
    }

    @Throws(RuntimeException::class)
    private fun getDocumentFileFor(filename: String, open: Boolean): DocumentFile {
        // If the file should be opened immediately store it in the standard location to avoid the FileProvider trouble (See ReceiveNotification::setURI)
        val destinationFolderDocument: DocumentFile = if (open || !ShareSettingsFragment.isCustomDestinationEnabled(device.context)) {
            val defaultPath = ShareSettingsFragment.getDefaultDestinationDirectory().absolutePath
            DocumentFile.fromFile(File(defaultPath))
        } else {
            ShareSettingsFragment.getDestinationDirectory(device.context)
        }

        val filenameToUse = FilesHelper.findValidNonExistingFileName(destinationFolderDocument, filename)

        val fileDocument = destinationFolderDocument.createFile("*/*", filenameToUse)
            ?: throw RuntimeException(device.context.getString(R.string.cannot_create_file, filenameToUse))

        return fileDocument
    }

    @Throws(IOException::class)
    private fun receiveFile(input: InputStream?, output: OutputStream): Long {
        val data = ByteArray(4096)
        var received: Long = 0
        // ge.connect: stop once the announced size is in, rather than waiting for the sender to close the stream (a
        // sender that waits for us to close first would otherwise stall until the read timeout and fail the file).
        val expected = currentNetworkPacket?.payloadSize ?: -1L

        while (true) {
            if (expected > 0 && received >= expected) break
            val count = input!!.read(data, 0, if (expected > 0) minOf(data.size.toLong(), expected - received).toInt() else data.size)
            if (count < 0 || isCancelled) break

            received += count
            totalReceived += count
            val geNow = System.currentTimeMillis() // ge.connect: transfers feed, at most 5 updates/s
            if (geNow - geLastProgressMillis >= 200) {
                geLastProgressMillis = geNow
                ge.connect.transfers.Transfers.incomingProgress(geTransferId, received, currentNetworkPacket!!.payloadSize)
            }

            output.write(data, 0, count)

            val progressPercentage = synchronized(lock) { totalReceived * 100 / totalPayloadSize }
            val curTimeMillis = System.currentTimeMillis()

            if (progressPercentage != prevProgressPercentage &&
                (progressPercentage == 100L || curTimeMillis - lastProgressTimeMillis >= 500)) {
                prevProgressPercentage = progressPercentage
                lastProgressTimeMillis = curTimeMillis
                setProgress(progressPercentage.toInt())
            }
        }

        output.flush()

        return received
    }

    private fun closeAllInputStreams() {
        for (np in networkPacketList) {
            np.payload!!.close()
        }
    }

    private fun setProgress(progress: Int) {
        synchronized(lock) {
            receiveNotification.setProgress(progress, device.context.resources
                .getQuantityString(R.plurals.incoming_files_text, totalNumFiles, currentFileName, currentFileNum, totalNumFiles))
        }
        receiveNotification.show()
    }

    private fun publishFile(fileDocument: DocumentFile, size: Long) {
        if (!ShareSettingsFragment.isCustomDestinationEnabled(device.context)) {
            Log.i("SharePlugin", "Adding to downloads")
            val manager = ContextCompat.getSystemService(device.context, DownloadManager::class.java)
            manager!!.addCompletedDownload(fileDocument.uri.lastPathSegment, device.name, true, fileDocument.type, fileDocument.uri.path, size, false)
        } else {
            //Make sure it is added to the Android Gallery anyway
            Log.i("SharePlugin", "Adding to gallery")
            MediaStoreHelper.indexFile(device.context, fileDocument.uri)
        }
    }

    private fun openFile(fileDocument: DocumentFile) {
        val mimeType = FilesHelper.getMimeTypeFromFile(fileDocument.name)
        val intent = Intent(Intent.ACTION_VIEW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            //Nougat and later require "content://" uris instead of "file://" uris
            val file = File(fileDocument.uri.path)
            val contentUri = FileProvider.getUriForFile(device.context, device.context.packageName + ".fileprovider", file) // ge.connect: was hardcoded to KDE's package
            intent.setDataAndType(contentUri, mimeType)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            intent.setDataAndType(fileDocument.uri, mimeType)
        }

        // Open files for KDE Itinerary explicitly because Android's activity resolution sucks
        if (fileDocument.name!!.endsWith(".itinerary")) {
            intent.setClassName("org.kde.itinerary", "org.kde.itinerary.Activity")
        }

        device.context.startActivity(intent)
    }
}
