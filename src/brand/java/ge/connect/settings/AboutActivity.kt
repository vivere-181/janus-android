/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3Api::class)

package ge.connect.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import org.kde.kdeconnect.ui.about.EasterEggActivity
import org.kde.kdeconnect.ui.about.LicensesActivity
import org.kde.kdeconnect.ui.about.applicationAboutData
import org.kde.kdeconnect.ui.compose.screen.about.AboutScreen
import org.kde.kdeconnect_tp.R

/** Upstream's About content (name, version, licenses) inside our theme and top bar. */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val background = LocalAppSurfaces.current.background
                Scaffold(
                    containerColor = background,
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.settings_about_app, stringResource(R.string.app_name))) },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.navigate_back))
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
                        )
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        AboutScreen(
                            aboutData = applicationAboutData,
                            onEasterEggTriggered = { startActivity(Intent(this@AboutActivity, EasterEggActivity::class.java)) },
                            onReportBugClicked = {},
                            onDonateClicked = {},
                            onSourceCodeClicked = {},
                            onLicensesClicked = { startActivity(Intent(this@AboutActivity, LicensesActivity::class.java)) },
                            onAboutKdeClicked = {},
                            onWebsiteClicked = {},
                        )
                    }
                }
            }
        }
    }
}
