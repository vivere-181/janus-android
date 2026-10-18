/*
 * SPDX-FileCopyrightText: 2019 Erik Duisters <e.duisters1@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.os.Handler
import android.os.Looper
import androidx.annotation.GuardedBy
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.async.BackgroundJob
import org.kde.kdeconnect_tp.R

/**
 * A type of [BackgroundJob] that sends Files to another device.
 *
 * We represent the individual upload requests as [NetworkPacket]s.
 *
 * Each packet should have a 'filename' property and a payload. If the payload is
 * missing, we'll just send an empty file. You can add new packets anytime via
 * [addNetworkPacket].
 *
 * The I/O-part of this file sending is handled by
 * [Device.sendPacketBlocking].
 *
 * @see CompositeReceiveFileJob
 * @see SendPacketStatusCallback
 */
class CompositeUploadFileJob internal constructor(
    device: Device,
    callback: Callback<Void?>
) : BackgroundJob<Device, Void?>(device, callback) {
    private var isRunning: Boolean = false
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var currentFileName: String = ""
    private var currentFileNum: Int = 0
    private var updatePacketPending: Boolean = false
    private var totalSend: Long = 0
    private var prevProgressPercentage: Int = 0
    private val uploadNotification: UploadNotification = UploadNotification(device, id)

    private val lock = Any()                                //Use to protect concurrent access to the variables below
    @GuardedBy("lock")
    private val networkPacketList: MutableList<NetworkPacket> = ArrayList()
    private var currentNetworkPacket: NetworkPacket? = null
    private val sendPacketStatusCallback: Device.SendPacketStatusCallback = SendPacketStatusCallback()
    @GuardedBy("lock")
    private var totalNumFiles: Int = 0
    @GuardedBy("lock")
    private var totalPayloadSize: Long = 0

    private val device: Device
        get() = requestInfo

    override fun run() {
        isRunning = true

        var done = synchronized(lock) { networkPacketList.isEmpty() }

        try {
            announceTransferToCompanion() // ge.connect: lets the Mac app show a percentage (R9)
            while (!done && !isCancelled) {
                val np = synchronized(lock) { networkPacketList.removeAt(0) }
                currentNetworkPacket = np

                currentFileName = np.getString("filename")
                currentFileNum++
                ge.connect.transfers.Transfers.outgoingStarted(np) // ge.connect

                setProgress(prevProgressPercentage)

                addTotalsToNetworkPacket(np)

                // We set sendPayloadFromSameThread to true so this call blocks until the payload
                // has been received by the other end,  so payloads are sent one by one.
                if (!device.sendPacketBlocking(np, sendPacketStatusCallback, true)) {
                    throw RuntimeException("Sending packet failed")
                }

                done = synchronized(lock) { networkPacketList.isEmpty() }
            }

            if (isCancelled) {
                failRemainingTransfers() // ge.connect
                uploadNotification.cancel()
            } else {
                uploadNotification.setFinished(device.context.resources.getQuantityString(R.plurals.sent_files_title, currentFileNum, device.name, currentFileNum))
                uploadNotification.show()

                reportResult(null)
            }
        } catch (e: RuntimeException) {
            failRemainingTransfers() // ge.connect
            synchronized(lock) {
                val failedFiles = totalNumFiles - currentFileNum + 1
                uploadNotification.setFailed(device.context.resources
                    .getQuantityString(R.plurals.send_files_fail_title, failedFiles, device.name,
                        failedFiles, totalNumFiles))
            }

            uploadNotification.show()
            reportError(e)
        } finally {
            isRunning = false

            for (networkPacket in networkPacketList) {
                networkPacket.payload!!.close()
            }
            networkPacketList.clear()
        }
    }

    /** ge.connect: the batch's size, sent to the Mac app ahead of the files (PROTOCOL.md (janus-mac docs), R9). */
    private fun announceTransferToCompanion() {
        val total: Long
        val files: Int
        synchronized(lock) {
            total = totalPayloadSize
            files = totalNumFiles
        }
        ge.connect.sync.CompanionMessages.announceTransfer(device.context, device, total, files)
    }

    /** ge.connect: mark the current and still-queued files as failed in the transfers feed. */
    private fun failRemainingTransfers() {
        val failed: MutableList<NetworkPacket> = ArrayList()
        synchronized(lock) {
            currentNetworkPacket?.let { failed.add(it) }
            failed.addAll(networkPacketList)
        }
        ge.connect.transfers.Transfers.outgoingFailed(failed)
    }

    private fun addTotalsToNetworkPacket(networkPacket: NetworkPacket) {
        synchronized(lock) {
            networkPacket[SharePlugin.KEY_NUMBER_OF_FILES] = totalNumFiles
            networkPacket[SharePlugin.KEY_TOTAL_PAYLOAD_SIZE] = totalPayloadSize
        }
    }

    private fun setProgress(progress: Int) {
        synchronized(lock) {
            uploadNotification.setProgress(progress, device.context.resources
                .getQuantityString(R.plurals.outgoing_files_text, totalNumFiles, currentFileName, currentFileNum, totalNumFiles))
        }
        uploadNotification.show()
    }

    internal fun addNetworkPacket(networkPacket: NetworkPacket) {
        synchronized(lock) {
            networkPacketList.add(networkPacket)

            totalNumFiles++

            if (networkPacket.payloadSize >= 0) {
                totalPayloadSize += networkPacket.payloadSize
            }

            uploadNotification.setTitle(device.context.resources
                .getQuantityString(R.plurals.outgoing_file_title, totalNumFiles, totalNumFiles, device.name))

            //Give SharePlugin some time to add more NetworkPackets
            if (isRunning && !updatePacketPending) {
                updatePacketPending = true
                handler.post { sendUpdatePacket() }
            }
        }
    }

    /**
     * Use this to send metadata ahead of all the other [packets][networkPacketList].
     */
    private fun sendUpdatePacket() {
        val np = NetworkPacket(SharePlugin.PACKET_TYPE_SHARE_REQUEST_UPDATE)

        synchronized(lock) {
            np["numberOfFiles"] = totalNumFiles
            np["totalPayloadSize"] = totalPayloadSize
            updatePacketPending = false
        }

        device.sendPacket(np)
        Thread { announceTransferToCompanion() }.start() // ge.connect: new totals for the Mac app
    }

    override fun cancel() {
        super.cancel()

        currentNetworkPacket!!.cancel()
    }

    private inner class SendPacketStatusCallback : Device.SendPacketStatusCallback() {
        override fun onPayloadProgressChanged(percent: Int) {
            val currentNetworkPacket = currentNetworkPacket!!
            val send = totalSend + (currentNetworkPacket.payloadSize * (percent.toFloat() / 100))
            val progress = ((send * 100) / totalPayloadSize).toInt()
            ge.connect.transfers.Transfers.outgoingProgress(currentNetworkPacket, percent) // ge.connect

            if (progress != prevProgressPercentage) {
                setProgress(progress)
                prevProgressPercentage = progress
            }
        }

        override fun onSuccess() {
            val currentNetworkPacket = currentNetworkPacket!!
            if (currentNetworkPacket.payloadSize == 0L) {
                synchronized(lock) {
                    if (networkPacketList.isEmpty()) {
                        setProgress(100)
                    }
                }
            }

            totalSend += currentNetworkPacket.payloadSize
            ge.connect.transfers.Transfers.outgoingDone(currentNetworkPacket) // ge.connect
        }

        override fun onFailure(e: Throwable) {
            // Handled in the run() function when sendPacketBlocking returns false
        }
    }
}
