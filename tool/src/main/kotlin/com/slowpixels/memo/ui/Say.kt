package com.slowpixels.memo.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How times and lengths are written. */
object Say {
    /** "8 Nov 2026 14:41", or "8 Nov 2026 2:41 PM" with a 12-hour clock. */
    fun date(ms: Long, hours24: Boolean = true): String =
        SimpleDateFormat(if (hours24) "d MMM yyyy HH:mm" else "d MMM yyyy h:mm a", Locale.getDefault()).format(Date(ms))

    /**
     * A memo's length in words, to the nearest second under a minute and the nearest minute above: "33 sec", "4 min",
     * "1 h 5 min". Short enough that a list's second line keeps it whole; the display shows it to the second.
     */
    fun length(ms: Long): String {
        val s = (ms + 500) / 1_000
        if (s < 60) return "$s sec"
        val m = (s + 30) / 60
        return when {
            m < 60 -> "$m min"
            m % 60 == 0L -> "${m / 60} h"
            else -> "${m / 60} h ${m % 60} min"
        }
    }

    /** Room left to record, roughly: "254 h" above ten hours, then "2 h 5 min", "38 min". */
    fun room(ms: Long): String = if (ms >= 10 * 3_600_000L) "${ms / 3_600_000} h" else length(ms)

    /** The counter: "00:33", "04:12", "1:02:33". Rounded down, as a counter counts. */
    fun counter(ms: Long): String {
        val s = maxOf(0L, ms) / 1_000
        return if (s < 3_600) "%02d:%02d".format(s / 60, s % 60) else "%d:%02d:%02d".format(s / 3_600, (s / 60) % 60, s % 60)
    }

    /** A time for a screen reader: "1 minute 23 seconds". */
    fun spoken(ms: Long): String {
        val s = ms / 1_000
        val h = s / 3_600
        val m = (s / 60) % 60
        val sec = s % 60
        val parts = ArrayList<String>()
        if (h > 0) parts += if (h == 1L) "1 hour" else "$h hours"
        if (m > 0) parts += if (m == 1L) "1 minute" else "$m minutes"
        if (sec > 0 || parts.isEmpty()) parts += if (sec == 1L) "1 second" else "$sec seconds"
        return parts.joinToString(" ")
    }

    /** "0.5×", "1×", "2×". */
    fun speed(x: Float): String = (if (x == x.toInt().toFloat()) x.toInt().toString() else x.toString()) + "×"

    /** A memo's second line in a list: when it was made and how long it is. */
    fun line(made: Long, length: Long, hours24: Boolean = true) = date(made, hours24) + " · " + length(length)
}
