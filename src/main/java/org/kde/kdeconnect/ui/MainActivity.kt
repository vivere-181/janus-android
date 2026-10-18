/*
 * SPDX-FileCopyrightText: 2023 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ge.connect.PhoneName
import ge.connect.home.HomeActivity
import ge.connect.setup.SetupActivity
import ge.connect.setup.SetupGuide
import org.kde.kdeconnect.helpers.DeviceHelper

/**
 * The launcher entry point (and the target of older notifications and tiles). ge.connect: Janus's Home replaced the
 * drawer UI; this forwards to it, or to the first-run setup guide.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceHelper.initializeDeviceId(this)
        PhoneName.adoptSystemNameOnce(this) // announce the phone's own name
        if (SetupGuide.shouldShow(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
        } else {
            startActivity(Intent(this, HomeActivity::class.java).apply {
                intent.extras?.let(::putExtras)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
        finish()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "deviceId"
        const val PAIR_REQUEST_STATUS = "pair_req_status"
        const val PAIRING_ACCEPTED = "accepted"
        const val PAIRING_REJECTED = "rejected"
        const val PAIRING_PENDING = "pending"
        const val RESULT_NEEDS_RELOAD = RESULT_FIRST_USER
        const val RESULT_NOTIFICATIONS_ENABLED = RESULT_FIRST_USER + 1
        const val FLAG_FORCE_OVERVIEW = "forceOverview"
    }
}
