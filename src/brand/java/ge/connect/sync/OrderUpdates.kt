/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

package ge.connect.sync

import java.util.Calendar

/**
 * Delivery orders from apps that don't post an ongoing tracker (R27): Glovo, for one, posts plain "order update"
 * notifications. Each new update becomes the text of one live activity per app; this reads what's in them.
 */
object OrderUpdates {
    /** Notification channels that carry order progress (Glovo: order_updates_channel), not promotions. */
    private val ORDER_CHANNEL = Regex("order|deliver|track|courier|rider|trip", RegexOption.IGNORE_CASE)

    /** The order is over: delivered, cancelled, refunded (English, Georgian, Russian). */
    private val DONE = Regex(
        "delivered|enjoy your|cancell?ed|refund|მიტანილია|ჩაბარდა|გაუქმდა|გაუქმებულია|доставлен|отмен",
        RegexOption.IGNORE_CASE,
    )

    private const val MINUTES = """(?:min(?:ute)?s?\b|წთ|წუთ\S*|мин\S*)"""
    private val RANGE = Regex("""(\d{1,3})\s*[–—-]\s*(\d{1,3})\s*$MINUTES""", RegexOption.IGNORE_CASE)
    private val IN_MINUTES = Regex("""(\d{1,3})\s*$MINUTES""", RegexOption.IGNORE_CASE)
    // "[.:]", not "[:.]": Android's ICU regex reads "[:" as the start of a POSIX class like [:alpha:] and throws.
    private val CLOCK = Regex("""\b([01]?\d|2[0-3])[.:]([0-5]\d)\s*([ap]\.?m\.?)?(?![\d:])""", RegexOption.IGNORE_CASE)

    fun isOrderChannel(channelId: String?) = channelId != null && ORDER_CHANNEL.containsMatchIn(channelId)

    fun isDone(text: String) = DONE.containsMatchIn(text)

    sealed interface Eta {
        /** Counts down to [whenMillis] on the Mac. */
        data class At(val whenMillis: Long) : Eta
        /** A range we can't count down ("15–25 min"), shown as is. */
        data class Shown(val text: String) : Eta
    }

    /** The arrival time in [text] posted at [postedAt]: "15–25 min", "in 15 min", "at 20:45" / "8:45 PM". */
    fun eta(text: String, postedAt: Long): Eta? {
        RANGE.find(text)?.let { m ->
            val (a, b) = m.destructured
            return Eta.Shown("$a–$b min")
        }
        IN_MINUTES.find(text)?.let { m ->
            val minutes = m.groupValues[1].toInt()
            if (minutes in 1..240) return Eta.At(postedAt + minutes * 60_000L)
        }
        CLOCK.find(text)?.let { m ->
            var hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            val suffix = m.groupValues[3].lowercase().replace(".", "")
            if (suffix == "pm" && hour < 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            val at = Calendar.getInstance().apply {
                timeInMillis = postedAt
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            // Just after midnight an evening time is yesterday's, a morning one may be tomorrow's: pick the closest.
            if (at.timeInMillis < postedAt - 12 * 3_600_000L) at.add(Calendar.DAY_OF_YEAR, 1)
            if (at.timeInMillis > postedAt + 12 * 3_600_000L) at.add(Calendar.DAY_OF_YEAR, -1)
            // A time in the past isn't an arrival time ("ordered at 20:05").
            if (at.timeInMillis > postedAt) return Eta.At(at.timeInMillis)
        }
        return null
    }
}
