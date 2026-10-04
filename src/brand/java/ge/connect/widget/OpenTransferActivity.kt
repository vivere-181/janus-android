/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import ge.connect.transfers.Transfers
import ge.connect.transfers.openTransfer

/** Invisible hop from a widget thumbnail to the file, link or text it shows (same as tapping it in the app). */
class OpenTransferActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(EXTRA_ID, -1)
        Transfers.items.value.firstOrNull { it.id == id }?.let { openTransfer(this, it) }
        finish()
    }

    companion object {
        private const val EXTRA_ID = "transferId"
        fun intent(context: Context, id: Long): Intent =
            Intent(context, OpenTransferActivity::class.java).putExtra(EXTRA_ID, id).setData(android.net.Uri.parse("ge-connect-transfer:$id"))
    }
}
