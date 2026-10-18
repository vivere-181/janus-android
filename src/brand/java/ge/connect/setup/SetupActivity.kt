/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.setup

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ge.connect.ui.components.ListGroup
import ge.connect.ui.components.NavRow
import ge.connect.ui.components.SectionHeader
import ge.connect.ui.theme.AppTheme
import ge.connect.ui.theme.LocalAppSurfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.ui.MainActivity
import org.kde.kdeconnect_tp.R

/** First-run checklist of everything the app needs; also reachable from Settings. */
class SetupActivity : AppCompatActivity() {
    // Bumped on every resume so statuses refresh after returning from system settings.
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                SetupScreen(resumeCount = resumeCount, onFinish = ::finishSetup)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
        ge.connect.sync.CalendarSync.permissionMaybeChanged() // the calendar step may just have been granted
    }

    private fun finishSetup() {
        SetupGuide.markDone(this)
        // Let plugins pick up newly granted permissions, and reconnect with them.
        CoroutineScope(Dispatchers.IO).launch {
            KdeConnect.getInstance().devices.values.forEach(Device::reloadPluginsFromSettings)
        }
        BackgroundService.ForceRefreshConnections(this)
        // First run: MainActivity finished itself to show us. From Settings: it's still underneath.
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

private data class StepState(val granted: Boolean, val needsSettings: Boolean)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SetupScreen(resumeCount: Int, onFinish: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val steps = remember { SetupGuide.steps() }
    var states by remember { mutableStateOf(emptyMap<String, StepState>()) }

    fun missingPermissions(step: SetupStep) = (step.grant as? Grant.Permissions)?.permissions.orEmpty()
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun refresh() {
        states = steps.associate { step ->
            val missing = missingPermissions(step)
            // Denied before and Android won't show the dialog again: only App info can grant it now.
            val blocked = activity != null && missing.isNotEmpty() && SetupGuide.wasRequested(context, step) &&
                missing.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            step.id to StepState(step.isGranted(context), blocked)
        }
    }

    LaunchedEffect(resumeCount) { refresh() }
    val permissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { refresh() }

    fun onAllow(step: SetupStep) {
        when (val grant = step.grant) {
            is Grant.Permissions -> if (states[step.id]?.needsSettings == true) {
                context.startActivity(SetupGuide.appInfoIntent(context))
            } else {
                SetupGuide.markRequested(context, step)
                permissionLauncher.launch(missingPermissions(step).toTypedArray())
            }
            is Grant.SettingsScreen -> context.startSafely(grant.intent(context))
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val requiredDone = steps.filter { it.required }.all { states[it.id]?.granted == true }
    val background = LocalAppSurfaces.current.background

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = background,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setup_title, stringResource(R.string.app_name))) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            Surface(color = background) {
                Row(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (requiredDone) {
                        Button(onClick = onFinish) { Text(stringResource(R.string.setup_done)) }
                    } else {
                        OutlinedButton(onClick = onFinish) { Text(stringResource(R.string.setup_skip)) }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            // Below the title bar rather than under it: overlapping, a TV remote's focus can't leave the bar.
            modifier = Modifier.padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 0.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item {
                Text(
                    stringResource(R.string.setup_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            for ((header, group) in listOf(
                R.string.setup_section_required to steps.filter { it.required },
                R.string.setup_section_features to steps.filterNot { it.required },
            )) {
                item {
                    SectionHeader(stringResource(header))
                    ListGroup(group.map { step -> { shapes -> StepRow(shapes, step, states[step.id], ::onAllow) } })
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    Spacer(Modifier.height(24.dp))
                    RestrictedSettingsHint { context.startSafely(SetupGuide.appInfoIntent(context)) }
                }
            }
        }
    }
}

@Composable
private fun StepRow(shapes: ListItemShapes, step: SetupStep, state: StepState?, onAllow: (SetupStep) -> Unit) {
    val granted = state?.granted == true
    NavRow(
        shapes, step.icon, stringResource(step.title), stringResource(step.description),
        iconContainer = if (granted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        iconColor = if (granted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        trailing = {
            if (granted) {
                Icon(
                    painterResource(R.drawable.ms_check_circle_filled),
                    contentDescription = stringResource(R.string.setup_granted),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                FilledTonalButton(onClick = { onAllow(step) }) {
                    Text(stringResource(if (state?.needsSettings == true) R.string.setup_open_settings else R.string.setup_allow))
                }
            }
        },
    ) { if (!granted) onAllow(step) }
}

@Composable
private fun RestrictedSettingsHint(onOpenAppInfo: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)) {
            Icon(
                painterResource(R.drawable.ms_info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp).size(24.dp),
            )
            Column {
                Text(stringResource(R.string.setup_restricted_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.setup_restricted_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenAppInfo, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.setup_restricted_action))
                }
            }
        }
    }
}

/** Some OEMs lack a specific settings screen; fall back to the app's info page. */
private fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }.onFailure { startActivity(SetupGuide.appInfoIntent(this)) }
}
