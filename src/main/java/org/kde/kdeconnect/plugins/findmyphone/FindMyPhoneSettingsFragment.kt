/*
 * SPDX-FileCopyrightText: 2018 Erik Duisters <e.duisters1@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.findmyphone

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreference
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R

class FindMyPhoneSettingsFragment : PluginSettingsFragment() {
    private lateinit var preferenceKeyRingtone: String
    private lateinit var preferenceKeyFlashlight: String
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var ringtonePreference: Preference
    private var flashlightPreference: SwitchPreference? = null

    private val requestCameraPermission: ActivityResultLauncher<String> = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            setFlashlightEnabled(true)
            return@registerForActivityResult
        }
        if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.setData(Uri.fromParts("package", requireContext().packageName, null))
            Toast.makeText(requireContext(), R.string.findmyphone_open_settings_for_camera, Toast.LENGTH_LONG).show()
            startActivity(intent)
            return@registerForActivityResult
        }
        Toast.makeText(requireContext(), R.string.findmyphone_camera_explanation, Toast.LENGTH_SHORT).show()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        preferenceKeyRingtone = getString(R.string.findmyphone_preference_key_ringtone)
        preferenceKeyFlashlight = getString(R.string.findmyphone_preference_key_flashlight)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        ringtonePreference = preferenceScreen.findPreference(preferenceKeyRingtone)!!
        flashlightPreference = preferenceScreen.findPreference(preferenceKeyFlashlight)

        setRingtoneSummary()

        flashlightPreference?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == true) {
                if (hasCameraPermission()) {
                    return@setOnPreferenceChangeListener true
                }
                requestCameraPermission.launch(Manifest.permission.CAMERA)
                return@setOnPreferenceChangeListener false
            }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        syncFlashlightPreferenceWithPermission()
    }

    private fun syncFlashlightPreferenceWithPermission() {
        val preferenceOn = sharedPreferences.getBoolean(preferenceKeyFlashlight, false)
        if (!hasCameraPermission() && preferenceOn) {
            setFlashlightEnabled(false)
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun setFlashlightEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(preferenceKeyFlashlight, enabled).apply()
        flashlightPreference!!.isChecked = enabled
    }

    private fun setRingtoneSummary() {
        val ringtone = sharedPreferences.getString(preferenceKeyRingtone, Settings.System.DEFAULT_RINGTONE_URI.toString())

        val ringtoneUri = Uri.parse(ringtone)

        ringtonePreference.summary = RingtoneManager.getRingtone(requireContext(), ringtoneUri).getTitle(requireContext())
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        /*
         * There is no RingtonePreference in support library nor androidx, this is the workaround proposed here:
         * https://issuetracker.google.com/issues/37057453
         */

        if (preference.hasKey() && preference.key == preferenceKeyRingtone) {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_NOTIFICATION_URI)

            val existingValue = sharedPreferences.getString(preferenceKeyRingtone, null)
            if (existingValue != null) {
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(existingValue))
            } else {
                intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Settings.System.DEFAULT_RINGTONE_URI)
            }

            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_CODE_SELECT_RINGTONE)
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_SELECT_RINGTONE && resultCode == Activity.RESULT_OK) {
            val uri = IntentCompat.getParcelableExtra(data!!, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)

            if (uri != null) {
                sharedPreferences.edit()
                    .putString(preferenceKeyRingtone, uri.toString())
                    .apply()

                setRingtoneSummary()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_SELECT_RINGTONE = 1000

        @JvmStatic
        fun newInstance(pluginKey: String, deviceId: String, layout: Int): FindMyPhoneSettingsFragment {
            val fragment = FindMyPhoneSettingsFragment()
            fragment.setArguments(pluginKey, deviceId, layout)

            return fragment
        }
    }
}
