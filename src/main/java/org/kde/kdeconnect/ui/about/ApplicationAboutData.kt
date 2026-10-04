/*
 * SPDX-FileCopyrightText: 2021 Maxim Leshchenko <cnmaks90@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.about

import org.kde.kdeconnect_tp.BuildConfig
import org.kde.kdeconnect_tp.R

/**
* Add authors and credits here
 */
// Upstream credits and links live in src/brand/NOTICE.txt (shown on the Licenses screen).
val applicationAboutData = AboutData(
        name = R.string.app_name,
        icon = R.drawable.icon,
        versionName = BuildConfig.VERSION_NAME,
        bugURL = null,
        websiteURL = null,
        sourceCodeURL = null,
        donateURL = null,
        authorsFooterText = null,
        authors = emptyList(),
    )
