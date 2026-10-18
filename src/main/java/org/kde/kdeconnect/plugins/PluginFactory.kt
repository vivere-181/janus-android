/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.kde.kdeconnect.plugins

import android.content.Context
import android.util.Log
import org.kde.kdeconnect.Device

object PluginFactory {
    /** Marks a plugin class; every one must also be in [PLUGINS] (PluginListTest checks). */
    annotation class LoadablePlugin

    // ge.connect: an explicit list instead of a build-time generated index (no annotation processor to fetch or run).
    val PLUGINS: List<kotlin.reflect.KClass<out Plugin>> = listOf(
        ge.connect.camera.GeCameraPlugin::class,
        ge.connect.tv.GeTvPlugin::class,
        org.kde.kdeconnect.plugins.battery.BatteryPlugin::class,
        org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin::class,
        org.kde.kdeconnect.plugins.connectivityreport.ConnectivityReportPlugin::class,
        org.kde.kdeconnect.plugins.connectivityreport.GePeerSignalPlugin::class,
        org.kde.kdeconnect.plugins.contacts.ContactsPlugin::class,
        org.kde.kdeconnect.plugins.findmyphone.FindMyPhonePlugin::class,
        org.kde.kdeconnect.plugins.findremotedevice.FindRemoteDevicePlugin::class,
        org.kde.kdeconnect.plugins.inputdevicesreceiver.InputDevicesReceiverPlugin::class,
        org.kde.kdeconnect.plugins.mousepad.MousePadPlugin::class,
        org.kde.kdeconnect.plugins.mousereceiver.MouseReceiverPlugin::class,
        org.kde.kdeconnect.plugins.mpris.MprisPlugin::class,
        org.kde.kdeconnect.plugins.mprisreceiver.MprisReceiverPlugin::class,
        org.kde.kdeconnect.plugins.notifications.GeRemoteNotificationsPlugin::class,
        org.kde.kdeconnect.plugins.notifications.NotificationsPlugin::class,
        org.kde.kdeconnect.plugins.ping.PingPlugin::class,
        org.kde.kdeconnect.plugins.presenter.PresenterPlugin::class,
        org.kde.kdeconnect.plugins.receivenotifications.ReceiveNotificationsPlugin::class,
        org.kde.kdeconnect.plugins.remotekeyboard.RemoteKeyboardPlugin::class,
        org.kde.kdeconnect.plugins.remotekeyboardime.RemoteKeyboardIMEPlugin::class,
        org.kde.kdeconnect.plugins.runcommand.RunCommandPlugin::class,
        org.kde.kdeconnect.plugins.share.SharePlugin::class,
        org.kde.kdeconnect.plugins.sms.SMSPlugin::class,
        org.kde.kdeconnect.plugins.systemvolume.SystemVolumePlugin::class,
        org.kde.kdeconnect.plugins.telephony.GeRemoteCallsPlugin::class,
        org.kde.kdeconnect.plugins.telephony.TelephonyPlugin::class,
    )

    private var pluginInfo: Map<String, PluginInfo> = mapOf()

    fun initPluginInfo(context: Context) {
        try {
            pluginInfo = PLUGINS
                .asSequence()
                .map { it.java.getDeclaredConstructor().newInstance() }
                .onEach { it.setContext(context, null) }
                .associate { Pair(it.pluginKey, PluginInfo(it)) }
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
        Log.i("PluginFactory", "Loaded " + pluginInfo.size + " plugins")
    }

    val availablePlugins: Set<String>
        get() = pluginInfo.keys
    val incomingCapabilities: Set<String>
        get() = pluginInfo.values.flatMap { plugin -> plugin.supportedPacketTypes }.toSet()
    val outgoingCapabilities: Set<String>
        get() = pluginInfo.values.flatMap { plugin -> plugin.outgoingPacketTypes }.toSet()

    @JvmStatic
    fun getPluginInfo(pluginKey: String): PluginInfo = pluginInfo[pluginKey]!!

    @JvmStatic
    fun sortPluginList(plugins: List<String>): List<String> {
        return plugins.sortedBy { pluginInfo[it]?.displayName }
    }

    fun instantiatePluginForDevice(context: Context, pluginKey: String, device: Device): Plugin? {
        try {
            val plugin = pluginInfo[pluginKey]?.instantiableClass?.getDeclaredConstructor()?.newInstance()?.apply { setContext(context, device) }
            return plugin
        } catch (e: Exception) {
            Log.e("PluginFactory", "Could not instantiate plugin: $pluginKey", e)
            return null
        }
    }

    fun pluginsForCapabilities(incoming: Set<String>, outgoing: Set<String>): Set<String> {
        fun hasCommonCapabilities(info: PluginInfo): Boolean =
            outgoing.any { it in info.supportedPacketTypes } ||
            incoming.any { it in info.outgoingPacketTypes }

        val (used, unused) = pluginInfo.entries.partition { hasCommonCapabilities(it.value) }

        for (pluginId in unused.map { it.key }) {
            Log.d("PluginFactory", "Won't load $pluginId because of unmatched capabilities")
        }

        return used.map { it.key }.toSet()
    }

    class PluginInfo private constructor(
        val displayName: String,
        val description: String,
        val isEnabledByDefault: Boolean,
        val hasSettings: Boolean,
        val listenToUnpaired: Boolean,
        supportedPacketTypes: Array<String>,
        outgoingPacketTypes: Array<String>,
        val instantiableClass: Class<out Plugin>,
    ) {
        internal constructor(p: Plugin) : this(p.displayName, p.description,
            p.isEnabledByDefault, p.hasSettings(), p.listensToUnpairedDevices(),
            p.supportedPacketTypes, p.outgoingPacketTypes, p.javaClass)

        val supportedPacketTypes: Set<String> = supportedPacketTypes.toSet()
        val outgoingPacketTypes: Set<String> = outgoingPacketTypes.toSet()
    }
}
