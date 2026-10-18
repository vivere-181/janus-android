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
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ge.connect.devices.DeviceOwnership
import ge.connect.pairing.PairFlow
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

/**
 * First run (R41): connect to the Mac by scanning its code, allow the basics in one go, then turn on the features
 * wanted, each asking only for what it needs. Also reachable from Settings (then it starts at the features).
 */
class SetupActivity : AppCompatActivity() {
    // Bumped on every resume so statuses refresh after returning from system settings.
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Scan first when nothing is paired yet and this is the first run (not opened from Settings or after pairing).
        val startWithPairing = SetupGuide.shouldShow(this) && !intent.getBooleanExtra(EXTRA_PAIRED, false) && pairedMac() == null
        setContent {
            AppTheme {
                var pairing by remember { mutableStateOf(startWithPairing) }
                Surface(color = LocalAppSurfaces.current.background, modifier = Modifier.fillMaxSize()) {
                    if (pairing) PairStage(onDone = { pairing = false })
                    else SetupScreen(resumeCount, onScan = { pairing = true }, onFinish = ::finishSetup)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
        ge.connect.sync.CalendarSync.permissionMaybeChanged() // the calendar feature may just have been allowed
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

    companion object {
        const val EXTRA_PAIRED = "paired"
    }
}

/** The owner's paired Mac (or other computer), if any. */
private fun pairedMac(): Device? = KdeConnect.getInstance().devices.values
    .filter { it.isPaired && DeviceOwnership.isMine(it) }
    .firstOrNull { it.deviceType.toString().lowercase() in setOf("laptop", "desktop") }
    ?: KdeConnect.getInstance().devices.values.firstOrNull { it.isPaired }

@Composable
private fun PairStage(onDone: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ms_laptop_mac), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(stringResource(R.string.qr_pair_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.qr_pair_text), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        PairFlow(link = null, onPaired = { _, _ -> onDone() }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { context.startActivity(Intent(context, ge.connect.devices.DevicesActivity::class.java)) }) {
                Text(stringResource(R.string.qr_pair_wifi))
            }
            TextButton(onClick = onDone) { Text(stringResource(R.string.setup_skip)) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A feature as the owner sees it, made of one or more setup steps (permissions or settings screens). */
private class Feature(val id: String, @StringRes val title: Int, @StringRes val description: Int, @DrawableRes val icon: Int, val steps: List<String>)

/** The basics: what every Mac connection needs (asked for in one go). */
private val BASICS = listOf("notifications", "local_network", "battery", "notification_access")

private val FEATURES = listOf(
    Feature("messages", R.string.feature_messages_title, R.string.feature_messages_desc, R.drawable.ms_sms, listOf("sms", "contacts")),
    Feature("calls", R.string.feature_calls_title, R.string.feature_calls_desc, R.drawable.ms_call, listOf("calls", "contacts")),
    Feature("calendar", R.string.feature_calendar_title, R.string.feature_calendar_desc, R.drawable.ms_calendar_month, listOf("calendar")),
    Feature("dnd", R.string.setup_dnd_title, R.string.feature_dnd_desc, R.drawable.ms_do_not_disturb_on, listOf("dnd")),
    Feature("wifi_name", R.string.setup_wifi_name_title, R.string.feature_wifi_desc, R.drawable.ms_wifi, listOf("wifi_name")),
    Feature("mirroring", R.string.setup_mirroring_title, R.string.feature_mirroring_desc, R.drawable.ms_cast, listOf("mirroring")),
    Feature("quiet", R.string.feature_quiet_title, R.string.feature_quiet_desc, R.drawable.ms_notifications_off, listOf("hide_connection_notification")),
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SetupScreen(resumeCount: Int, onScan: () -> Unit, onFinish: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val steps = remember { SetupGuide.steps().associateBy { it.id } }
    var granted by remember { mutableStateOf(emptySet<String>()) }
    var mac by remember { mutableStateOf(pairedMac()?.name) }
    // "Allow the basics": the ones still to ask for in this round, one after another as the owner comes back.
    var walking by remember { mutableStateOf(emptyList<String>()) }

    fun missingPermissions(step: SetupStep) = (step.grant as? Grant.Permissions)?.permissions.orEmpty()
        .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    /** Denied before and Android won't show the dialog again: only App info can grant it now. */
    fun blocked(step: SetupStep) = activity != null && missingPermissions(step).let { missing ->
        missing.isNotEmpty() && SetupGuide.wasRequested(context, step) && missing.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }

    fun refresh() {
        granted = steps.values.filter { it.isGranted(context) }.map { it.id }.toSet()
        mac = pairedMac()?.name
    }

    val permissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { refresh() }

    /** Asks for [ids] (all permission dialogs at once; else the first settings screen). */
    fun ask(ids: List<String>) {
        val toAsk = ids.mapNotNull(steps::get).filter { it.id !in granted }
        if (toAsk.isEmpty()) return
        val permissionSteps = toAsk.filter { it.grant is Grant.Permissions }
        if (permissionSteps.isNotEmpty()) {
            if (permissionSteps.any(::blocked)) { context.startSafely(SetupGuide.appInfoIntent(context)); return }
            permissionSteps.forEach { SetupGuide.markRequested(context, it) }
            permissionLauncher.launch(permissionSteps.flatMap(::missingPermissions).distinct().toTypedArray())
            return
        }
        (toAsk.first().grant as Grant.SettingsScreen).let { context.startSafely(it.intent(context)) }
    }

    fun nextBasic() {
        val next = walking.firstOrNull { it in steps && it !in granted } ?: run { walking = emptyList(); return }
        walking = walking - next
        ask(listOf(next))
    }

    LaunchedEffect(resumeCount) {
        refresh()
        if (walking.isNotEmpty()) nextBasic() // back from one of the basics: on to the next
    }

    val basics = BASICS.filter { it in steps }
    val missingBasics = basics.filter { it !in granted }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.setup_title, stringResource(R.string.app_name)), style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
        }
        // The Mac.
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = LocalAppSurfaces.current.card, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (mac != null) R.drawable.ms_check_circle_filled else R.drawable.ms_laptop_mac), null,
                        Modifier.size(28.dp), tint = if (mac != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        mac?.let { stringResource(R.string.qr_paired, it) } ?: stringResource(R.string.qr_not_paired),
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    if (mac == null) FilledTonalButton(onClick = onScan) { Text(stringResource(R.string.qr_scan)) }
                }
            }
        }
        // The basics, in one go.
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = LocalAppSurfaces.current.card, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.setup_basics_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.setup_basics_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    basics.forEach { id ->
                        val step = steps.getValue(id)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(if (id in granted) R.drawable.ms_check_circle_filled else step.icon), null, Modifier.size(20.dp),
                                tint = if (id in granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(step.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    if (missingBasics.isEmpty()) {
                        Text(stringResource(R.string.setup_basics_done), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Button(onClick = { walking = missingBasics; nextBasic() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.setup_basics_allow, missingBasics.size))
                        }
                    }
                }
            }
        }
        // Sideloaded apps can't get notification access until restricted settings are allowed (Android 13+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && "notification_access" in missingBasics) {
            item { RestrictedSettingsHint { context.startSafely(SetupGuide.appInfoIntent(context)) } }
        }
        item {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp)) {
                Text(stringResource(R.string.setup_features_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.setup_features_text), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        FEATURES.filter { feature -> feature.steps.all { it in steps } }.forEach { feature ->
            item(key = feature.id) {
                val on = feature.steps.all { it in granted }
                Surface(
                    onClick = { if (on) context.startSafely(SetupGuide.appInfoIntent(context)) else ask(feature.steps) },
                    shape = RoundedCornerShape(24.dp), color = LocalAppSurfaces.current.card, modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(feature.icon), null, Modifier.size(22.dp),
                                tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(stringResource(feature.title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(feature.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // On = allowed. Turning one off goes to App info: Android only lets the owner take access back.
                        Switch(checked = on, onCheckedChange = { if (on) context.startSafely(SetupGuide.appInfoIntent(context)) else ask(feature.steps) })
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = onFinish) { Text(stringResource(R.string.setup_done)) }
            }
        }
    }
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
