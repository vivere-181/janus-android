/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.plugins.notifications

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class NotificationReceiver : NotificationListenerService() {

    var isConnected = false
        private set

    interface NotificationListener {
        fun onNotificationPosted(statusBarNotification: StatusBarNotification)

        fun onNotificationRemoved(statusBarNotification: StatusBarNotification)

        fun onListenerConnected(service: NotificationReceiver)
    }

    private val listeners = ArrayList<NotificationListener>()

    fun addListener(listener: NotificationListener) {
        val shouldRebind = listeners.isEmpty() || !isConnected
        listeners.add(listener)

        if (shouldRebind) {
            requestRebindSafe()
        }
    }

    fun removeListener(listener: NotificationListener) {
        if (listeners.remove(listener) && listeners.isEmpty()) {
            requestUnbindSafe()
        }
    }

    private fun requestRebindSafe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !hasReadNotificationsPermission(this)) {
            return
        }

        try {
            requestRebind(ComponentName(this, NotificationReceiver::class.java))
        } catch (ignored: Exception) {
            // Notification access may have been revoked since the permission check.
        }
    }

    private fun requestUnbindSafe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return
        }

        try {
            super.requestUnbind()
        } catch (ignored: Exception) {
            // The system may already have unbound the listener.
        }
    }

    override fun onNotificationPosted(statusBarNotification: StatusBarNotification) {
        //Log.e("NotificationReceiver.onNotificationPosted","listeners: " + listeners.size());
        for (listener in listeners) {
            listener.onNotificationPosted(statusBarNotification)
        }
    }

    override fun onNotificationRemoved(statusBarNotification: StatusBarNotification) {
        for (listener in listeners) {
            listener.onNotificationRemoved(statusBarNotification)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runPendingCallbacks() // ge.connect: also when the system bound us without a startService (see RunCommand)
        for (listener in listeners) {
            listener.onListenerConnected(this)
        }
        isConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false

        if (listeners.isNotEmpty()) {
            requestRebindSafe()
        }
    }

    //To use the service from the outer (name)space

    fun interface InstanceCallback {
        fun onServiceStart(service: NotificationReceiver)
    }

    //This will be called for each intent launch, even if the service is already started and is reused
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        //Log.e("NotificationReceiver", "onStartCommand");
        runPendingCallbacks()
        return Service.START_STICKY
    }

    private fun runPendingCallbacks() {
        mutex.withLock {
            for (c in callbacks) {
                c.onServiceStart(this)
            }
            callbacks.clear()
        }
    }

    companion object {
        // Reading notifications uses a different kind of permission, because it was added before the runtime permissions model
        @JvmStatic
        fun hasReadNotificationsPermission(context: Context): Boolean {
            val notificationListenerList = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: return false
            val thisComponentName = ComponentName(context, NotificationReceiver::class.java).flattenToString()
            return notificationListenerList.contains(thisComponentName)
        }

        private val callbacks = ArrayList<InstanceCallback>()

        private val mutex = ReentrantLock(true)

        @JvmStatic
        fun Start(c: Context) {
            RunCommand(c, null)
        }

        @JvmStatic
        fun RunCommand(c: Context, callback: InstanceCallback?) {
            if (callback != null) {
                mutex.withLock {
                    callbacks.add(callback)
                }
            }
            val serviceIntent = Intent(c, NotificationReceiver::class.java)
            // ge.connect: Android 12+ refuses to start a service from the background (e.g. the app started for a widget
            // update while notification access isn't granted), which crashed the app at startup. The system binds the
            // listener by itself once access is granted, and the queued callbacks run then.
            try {
                c.startService(serviceIntent)
            } catch (e: IllegalStateException) {
                Log.w("NotificationReceiver", "Not started now (" + e.javaClass.simpleName + "); waiting for the system to bind it")
            } catch (e: SecurityException) {
                Log.w("NotificationReceiver", "Not started now (" + e.javaClass.simpleName + "); waiting for the system to bind it")
            }
        }
    }

}
