/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.backends.lan

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import org.apache.commons.io.IOUtils
import org.json.JSONObject
import org.kde.kdeconnect.backends.BaseLink
import org.kde.kdeconnect.backends.BaseLinkProvider
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceInfo
import org.kde.kdeconnect.helpers.security.SslHelper
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect.helpers.LineTooLongException
import org.kde.kdeconnect.helpers.readLineBounded
import org.kde.kdeconnect.NetworkPacket
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.channels.NotYetConnectedException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

class LanLink @WorkerThread constructor(
    context: Context,
    deviceInfo: DeviceInfo,
    linkProvider: BaseLinkProvider,
    socket: SSLSocket,
) : BaseLink(context, linkProvider) {

    enum class ConnectionStarted {
        Locally, Remotely
    }

    override var deviceInfo: DeviceInfo = deviceInfo
        private set

    @Volatile
    private var socket: SSLSocket? = socket

    override fun disconnect() {
        Log.i("LanLink/Disconnect", "socket:" + socket!!.hashCode())
        try {
            socket!!.close()
        } catch (e: IOException) {
            Log.e("LanLink", "Error", e)
        }
    }

    //Returns the old socket
    @WorkerThread
    @Throws(IOException::class)
    fun reset(newSocket: SSLSocket, deviceInfo: DeviceInfo): SSLSocket? {

        this.deviceInfo = deviceInfo

        val oldSocket = socket
        socket = newSocket

        IOUtils.close(oldSocket) //This should cancel the readThread

        startListening(newSocket)

        return oldSocket
    }

    private fun startListening(socket: SSLSocket) {
        // Create a thread to take care of incoming data for the socket
        ThreadHelper.execute {
            try {
                val stream = BufferedInputStream(socket.inputStream)
                while (true) {
                    val packet = try {
                        readLineBounded(stream, MAX_PACKET_SIZE)
                    } catch (e: LineTooLongException) {
                        continue
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    if (packet.isEmpty()) {
                        continue
                    }
                    val np = NetworkPacket.unserialize(packet)
                    receivedNetworkPacket(np)
                }
            } catch (e: Exception) {
                Log.i("LanLink", "Socket closed: " + socket.hashCode() + ". Reason: " + e.message)
                try { socket.close() } catch (ignored: IOException) { }
                try { Thread.sleep(300) } catch (ignored: InterruptedException) { } // Wait a bit because we might receive a new socket meanwhile
                val thereIsaANewSocket = (socket !== this@LanLink.socket)
                if (!thereIsaANewSocket) {
                    Log.i("LanLink", "Socket closed and there's no new socket, disconnecting device")
                    linkProvider.onConnectionLost(this@LanLink)
                }
            }
        }
    }

    fun startListening() {
        startListening(socket!!)
    }

    /** ge.connect: the other device's address, for the connection screen. */
    val remoteAddress: InetAddress?
        get() = socket?.inetAddress

    override val name: String
        get() = "LanLink"

    @WorkerThread
    override fun sendPacket(np: NetworkPacket, callback: Device.SendPacketStatusCallback, sendPayloadFromSameThread: Boolean): Boolean {
        var payloadTransferStarted = false
        if (socket == null) {
            Log.e("KDE/sendPacket", "Not yet connected")
            callback.onFailure(NotYetConnectedException())
            return false
        }

        try {

            //Prepare socket for the payload
            val server: ServerSocket?
            if (np.hasPayload()) {
                server = LanLinkProvider.openServerSocketOnFreePort(LanLinkProvider.PAYLOAD_TRANSFER_MIN_PORT)
                val payloadTransferInfo = JSONObject()
                payloadTransferInfo.put("port", server.localPort)
                np.payloadTransferInfo = payloadTransferInfo
            } else {
                server = null
            }

            //Log.e("LanLink/sendPacket", np.getType());

            //Send body of the network packet
            try {
                val writer = socket!!.outputStream
                writer.write(np.serialize().toByteArray(Charsets.UTF_8))
                writer.flush()
            } catch (e: Exception) {
                disconnect() //main socket is broken, disconnect
                if (server != null) {
                    try { server.close() } catch (ignored: Exception) { }
                }
                throw e
            }

            //Send payload
            if (server != null) {
                if (sendPayloadFromSameThread) {
                    payloadTransferStarted = true
                    sendPayload(np, callback, server)
                } else {
                    ThreadHelper.execute {
                        try {
                            sendPayload(np, callback, server)
                        } catch (e: IOException) {
                            e.printStackTrace()
                            Log.e("LanLink/sendPacket", "Async sendPayload failed for packet of type " + np.type + ". The Plugin was NOT notified.")
                        }
                    }
                    payloadTransferStarted = true
                }
            }

            if (!np.isCanceled) {
                callback.onSuccess()
            }
            return true
        } catch (e: Exception) {
            callback.onFailure(e)
            return false
        } finally {
            // Close the payload if we never called sendPayload(). Otherwise it will close it.
            val payload = np.payload
            if (payload != null && !payloadTransferStarted) {
                payload.close()
            }
        }
    }

    @Throws(IOException::class)
    private fun sendPayload(np: NetworkPacket, callback: Device.SendPacketStatusCallback, server: ServerSocket) {
        var payloadSocket: Socket? = null
        var outputStream: OutputStream? = null
        try {
            if (!np.isCanceled) {
                //Wait a maximum of 10 seconds for the other end to establish a connection with our socket, close it afterwards
                server.soTimeout = 10 * 1000

                payloadSocket = server.accept()

                //Convert to SSL if needed
                payloadSocket = SslHelper.convertToSslSocket(context, payloadSocket, deviceId, true, false)

                outputStream = payloadSocket.outputStream
                val inputStream = np.payload!!.inputStream

                Log.i("KDE/LanLink", "Beginning to send payload for " + np.type)
                val buffer = ByteArray(4096)
                var bytesRead: Int
                val size = np.payloadSize
                var progress: Long = 0
                var timeSinceLastUpdate: Long = -1
                while (!np.isCanceled) {
                    bytesRead = inputStream!!.read(buffer)
                    if (bytesRead == -1) break
                    //Log.e("ok",""+bytesRead);
                    progress += bytesRead
                    outputStream.write(buffer, 0, bytesRead)
                    if (size > 0) {
                        if (timeSinceLastUpdate + 500 < System.currentTimeMillis()) { //Report progress every half a second
                            val percent = ((100 * progress) / size)
                            callback.onPayloadProgressChanged(percent.toInt())
                            timeSinceLastUpdate = System.currentTimeMillis()
                        }
                    }
                }
                outputStream.flush()
                Log.i("KDE/LanLink", "Finished sending payload ($progress bytes written)")
            }
        } catch (e: SocketTimeoutException) {
            Log.e("LanLink", "Socket for payload in packet " + np.type + " timed out. The other end didn't fetch the payload.")
            throw e
        } catch (e: CertificateException) {
            // The exception can be due to several causes. "Connection closed by peer" seems to be a common one.
            // If we could distinguish different cases we could react differently for some of them, but I haven't found how.
            Log.e("LanLink/sendPacket", "Payload SSLSocket failed", e)
            throw IOException("Payload SSL socket failed", e)
        } catch (e: SSLHandshakeException) {
            // Same handling as CertificateException above.
            Log.e("LanLink/sendPacket", "Payload SSLSocket failed", e)
            throw IOException("Payload SSL socket failed", e)
        } finally {
            try { server.close() } catch (ignored: Exception) { }
            try { IOUtils.close(payloadSocket) } catch (ignored: Exception) { }
            np.payload!!.close()
            try { IOUtils.close(outputStream) } catch (ignored: Exception) { }
        }
    }

    private fun receivedNetworkPacket(np: NetworkPacket) {

        if (np.hasPayloadTransferInfo()) {
            var payloadSocket = Socket()
            try {
                val tcpPort = np.payloadTransferInfo.getInt("port")
                val deviceAddress = socket!!.remoteSocketAddress as InetSocketAddress
                payloadSocket.connect(InetSocketAddress(deviceAddress.address, tcpPort))
                val sslPayloadSocket = SslHelper.convertToSslSocket(context, payloadSocket, deviceId, true, true)
                payloadSocket = sslPayloadSocket
                // ge.connect: handshake now, not on the first read: when a transfer waits for the owner's Accept
                // (IncomingConsent) the sender would otherwise time out after 10 s waiting for our ClientHello.
                sslPayloadSocket.startHandshake()
                np.payload = NetworkPacket.Payload(payloadSocket, np.payloadSize)
            } catch (e: Exception) {
                try { payloadSocket.close() } catch (ignored: Exception) { }
                Log.e("KDE/LanLink", "Exception connecting to payload remote socket", e)
            }

        }

        packetReceived(np)
    }

    companion object {
        internal const val MAX_PACKET_SIZE: Int = 32 * 1024 * 1024
    }
}
