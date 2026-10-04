/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.setup

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import org.kde.kdeconnect.plugins.notifications.NotificationReceiver
import org.kde.kdeconnect_tp.R

/** How a step is granted: a runtime permission dialog, or a system settings screen. */
sealed interface Grant {
    class Permissions(val permissions: List<String>) : Grant
    class SettingsScreen(val intent: (Context) -> Intent) : Grant
}

class SetupStep(
    val id: String,
    @StringRes val title: Int,
    @StringRes val description: Int,
    @DrawableRes val icon: Int,
    val required: Boolean,
    val grant: Grant,
    val isGranted: (Context) -> Boolean,
)

object SetupGuide {
    private const val PREFS = "ge_connect_setup"
    private const val KEY_DONE = "done"
    private const val KEY_REQUESTED_PREFIX = "requested_"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun shouldShow(context: Context) = !prefs(context).getBoolean(KEY_DONE, false)
    fun markDone(context: Context) = prefs(context).edit { putBoolean(KEY_DONE, true) }

    /** Whether we already showed this step's permission dialog (to detect "denied, don't ask again"). */
    fun wasRequested(context: Context, step: SetupStep) = prefs(context).getBoolean(KEY_REQUESTED_PREFIX + step.id, false)
    fun markRequested(context: Context, step: SetupStep) = prefs(context).edit { putBoolean(KEY_REQUESTED_PREFIX + step.id, true) }

    fun steps(): List<SetupStep> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(permissionStep("notifications", R.string.setup_notifications_title, R.string.setup_notifications_desc,
                R.drawable.ms_notifications, required = true, Manifest.permission.POST_NOTIFICATIONS))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            add(permissionStep("local_network", R.string.setup_local_network_title, R.string.setup_local_network_desc,
                R.drawable.ms_wifi, required = true, Manifest.permission.ACCESS_LOCAL_NETWORK))
        }
        add(SetupStep(
            id = "battery",
            title = R.string.setup_battery_title,
            description = R.string.setup_battery_desc,
            icon = R.drawable.ms_battery_full,
            required = true,
            grant = Grant.SettingsScreen(::batteryIntent),
            isGranted = { it.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(it.packageName) },
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            add(SetupStep(
                id = "hide_connection_notification",
                title = R.string.setup_hide_notification_title,
                description = R.string.setup_hide_notification_desc,
                icon = R.drawable.ms_notifications_off,
                required = false,
                grant = Grant.SettingsScreen(ge.connect.ConnectionNotification::settingsIntent),
                isGranted = { !ge.connect.ConnectionNotification.isShown(it) },
            ))
        }
        add(SetupStep(
            id = "notification_access",
            title = R.string.setup_notification_access_title,
            description = R.string.setup_notification_access_desc,
            icon = R.drawable.ms_notifications_active,
            required = false,
            grant = Grant.SettingsScreen(::notificationAccessIntent),
            isGranted = { NotificationManagerCompat.getEnabledListenerPackages(it).contains(it.packageName) },
        ))
        add(SetupStep(
            id = "dnd",
            title = R.string.setup_dnd_title,
            description = R.string.setup_dnd_desc,
            icon = R.drawable.ms_do_not_disturb_on,
            required = false,
            grant = Grant.SettingsScreen { Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
            isGranted = { ge.connect.sync.DndSync.hasAccess(it) },
        ))
        add(permissionStep("sms", R.string.setup_sms_title, R.string.setup_sms_desc, R.drawable.ms_sms,
            required = false, Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_STATE))
        add(permissionStep("calls", R.string.setup_calls_title, R.string.setup_calls_desc, R.drawable.ms_call,
            required = false, *listOfNotNull(
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
                // Declining calls from the Mac (R16)
                Manifest.permission.ANSWER_PHONE_CALLS.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.P },
            ).toTypedArray()))
        add(permissionStep("contacts", R.string.setup_contacts_title, R.string.setup_contacts_desc, R.drawable.ms_contacts,
            required = false, Manifest.permission.READ_CONTACTS))
        // Android only tells apps the Wi-Fi name with location access (R24: shown on the Mac's phone card).
        add(permissionStep("wifi_name", R.string.setup_wifi_name_title, R.string.setup_wifi_name_desc, R.drawable.ms_wifi,
            required = false, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        // The phone's calendars on the Mac (R26).
        add(permissionStep("calendar", R.string.setup_calendar_title, R.string.setup_calendar_desc, R.drawable.ms_calendar_month,
            required = false, Manifest.permission.READ_CALENDAR))
        // Screen mirroring (R25): Wireless debugging; done once the Mac says it paired.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(SetupStep(
                id = "mirroring",
                title = R.string.setup_mirroring_title,
                description = R.string.setup_mirroring_desc,
                icon = R.drawable.ms_cast,
                required = false,
                grant = Grant.SettingsScreen(ge.connect.sync.Mirroring::settingsIntent),
                isGranted = ge.connect.sync.Mirroring::isPaired,
            ))
        }
    }

    private fun permissionStep(
        id: String, @StringRes title: Int, @StringRes description: Int, @DrawableRes icon: Int,
        required: Boolean, vararg permissions: String,
    ) = SetupStep(id, title, description, icon, required, Grant.Permissions(permissions.toList())) { context ->
        permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    // Direct "allow?" dialog; granting it makes the app "Unrestricted" in battery settings (incl. Samsung).
    @SuppressLint("BatteryLife") // Not distributed on Google Play; staying connected is the app's core job.
    private fun batteryIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    private fun notificationAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, NotificationReceiver::class.java).flattenToString(),
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }

    fun appInfoIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
}
