/* SPDX-FileCopyrightText: 2018 Nicolas Fella <nicolas.fella@gmx.de>
 * SPDX-FileCopyrightText: 2015 David Edmundson <david@davidedmundson.co.uk>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.findmyphone

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect.extensions.viewBinding
import org.kde.kdeconnect_tp.databinding.ActivityFindMyPhoneBinding
import java.lang.ref.WeakReference

class FindMyPhoneActivity : BaseActivity<ActivityFindMyPhoneBinding>() {

    internal var deviceId: String? = null

    override val binding: ActivityFindMyPhoneBinding by viewBinding(ActivityFindMyPhoneBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        val actionBar = checkNotNull(supportActionBar)
        actionBar.setDisplayHomeAsUpEnabled(true)
        actionBar.setDisplayShowHomeEnabled(true)

        if (!intent.hasExtra(EXTRA_DEVICE_ID)) {
            Log.e("FindMyPhoneActivity", "You must include the deviceId for which this activity is started as an intent EXTRA")
            finish()
        }

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)

        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        binding.bFindMyPhone.setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        val deviceId = deviceId
        val plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, FindMyPhonePlugin::class.java)
        if (plugin == null) {
            // ge.connect: rung through Janus Cloud while the Mac is away (R37)
            if (deviceId != null && ge.connect.cloud.CloudRinger.owns(deviceId)) {
                ge.connect.cloud.CloudRinger.ringingActivity = WeakReference(this)
                ge.connect.cloud.CloudRinger.start(this)
            }
            return
        }
        plugin.ringingActivity = WeakReference(this) // ge.connect: lets a second request close us
        plugin.startPlaying()
        plugin.startFlashing()
        plugin.hideNotification()
    }

    override fun onStop() {
        super.onStop()
        val deviceId = deviceId
        val plugin = KdeConnect.getInstance().getDevicePlugin(deviceId, FindMyPhonePlugin::class.java)
        if (plugin == null) {
            if (deviceId != null && ge.connect.cloud.CloudRinger.owns(deviceId)) ge.connect.cloud.CloudRinger.stop(this) // ge.connect (R37)
            return
        }
        plugin.ringingActivity = null // ge.connect
        plugin.stopPlaying()
        plugin.stopFlashing()
    }

    companion object {
        internal const val EXTRA_DEVICE_ID = "deviceId"
    }
}
