/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class OrderUpdatesTest {
    private val posted = Calendar.getInstance().apply { set(2026, 9, 2, 20, 7, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun at(hour: Int, minute: Int, dayOffset: Int = 0) = Calendar.getInstance().apply {
        timeInMillis = posted
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
    }.timeInMillis

    @Test fun channels() {
        assertTrue(OrderUpdates.isOrderChannel("order_updates_channel"))
        assertFalse(OrderUpdates.isOrderChannel("glovo_channel"))
        assertFalse(OrderUpdates.isOrderChannel("christmas_event_channel"))
        assertFalse(OrderUpdates.isOrderChannel(null))
    }

    @Test fun range() = assertEquals(OrderUpdates.Eta.Shown("15–25 min"), OrderUpdates.eta("Arriving in 15-25 min", posted))

    @Test fun inMinutes() {
        assertEquals(OrderUpdates.Eta.At(posted + 15 * 60_000L), OrderUpdates.eta("Your courier arrives in 15 minutes", posted))
        assertEquals(OrderUpdates.Eta.At(posted + 7 * 60_000L), OrderUpdates.eta("კურიერი მოვა 7 წუთში", posted))
    }

    @Test fun clock() {
        assertEquals(OrderUpdates.Eta.At(at(20, 45)), OrderUpdates.eta("Estimated arrival at 20:45", posted))
        assertEquals(OrderUpdates.Eta.At(at(20, 45)), OrderUpdates.eta("Arrives 8:45 PM", posted))
        assertNull(OrderUpdates.eta("Ordered at 19:50", posted)) // in the past
        assertNull(OrderUpdates.eta("Your order is being prepared", posted))
    }

    @Test fun done() {
        assertTrue(OrderUpdates.isDone("Your order has been delivered. Enjoy!"))
        assertTrue(OrderUpdates.isDone("Order cancelled"))
        assertFalse(OrderUpdates.isDone("Your courier has arrived"))
        assertFalse(OrderUpdates.isDone("The courier picked up your order"))
    }
}
