/*
 * SPDX-FileCopyrightText: 2017 Nicolas Fella <nicolas.fella@gmx.de>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.kde.kdeconnect.plugins.share

import android.content.ComponentName
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.chooser.ChooserTarget
import android.service.chooser.ChooserTargetService
import android.util.Log
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect_tp.R

class ShareChooserTargetService : ChooserTargetService() {
    @Deprecated("Deprecated in Java")
    override fun onGetChooserTargets(targetActivityName: ComponentName?, matchedFilter: IntentFilter?): List<ChooserTarget> {
        Log.d("DirectShare", "invoked")
        val targets: MutableList<ChooserTarget> = ArrayList()
        for (d in KdeConnect.getInstance().devices.values) {
            if (d.isReachable && d.isPaired) {
                Log.d("DirectShare", d.name)
                val targetName = d.name
                val targetIcon = Icon.createWithResource(this, R.drawable.icon)
                val targetRanking = 1f
                val targetComponentName = ComponentName(packageName,
                    ShareActivity::class.java.canonicalName!!)
                val targetExtras = Bundle()
                targetExtras.putString("deviceId", d.deviceId)
                targets.add(ChooserTarget(
                    targetName, targetIcon, targetRanking, targetComponentName, targetExtras
                ))
            }
        }

        return targets
    }
}
