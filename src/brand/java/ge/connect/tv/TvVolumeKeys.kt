/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.tv

/** While a TV's remote is on screen: where the phone's volume keys go (HomeActivity.dispatchKeyEvent). */
object TvVolumeKeys {
    @Volatile var handler: ((Int) -> Unit)? = null
}
