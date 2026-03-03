package com.skeddy.util

import android.util.Log
import java.time.Clock
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Parses pickup time strings from Lyft UI into epoch milliseconds for TTL calculations.
 *
 * Supported formats:
 * - "Today · 6:05AM" → today at 06:05
 * - "Tomorrow · 3:00PM" → tomorrow at 15:00
 * - "Mon · 6:05AM" → next Monday at 06:05
 * - "Feb 15 · 6:05AM" → Feb 15 at 06:05
 *
 * Returns null if parsing fails, with a warning logged for diagnostics.
 * Lyft may change the format without notice — logs help detect issues quickly.
 * When parsing fails, the caller should use a fallback TTL (e.g., 48h from addedAt).
 */
object PickupTimeParser {
    private const val TAG = "PickupTimeParser"

    /** Overridable clock for testing date-dependent logic. */
    internal var clock: Clock = Clock.systemDefaultZone()

    private val TODAY_PATTERN = Regex(
        """Today\s*·\s*(\d{1,2}):(\d{2})(AM|PM)""",
        RegexOption.IGNORE_CASE
    )

    private val TOMORROW_PATTERN = Regex(
        """Tomorrow\s*·\s*(\d{1,2}):(\d{2})(AM|PM)""",
        RegexOption.IGNORE_CASE
    )

    private val WEEKDAY_PATTERN = Regex(
        """(Mon|Tue|Wed|Thu|Fri|Sat|Sun)\s*·\s*(\d{1,2}):(\d{2})(AM|PM)""",
        RegexOption.IGNORE_CASE
    )

    private val DATE_PATTERN = Regex(
        """(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\s+(\d{1,2})\s*·\s*(\d{1,2}):(\d{2})(AM|PM)""",
        RegexOption.IGNORE_CASE
    )

    private val WEEKDAY_MAP = mapOf(
        "mon" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY
    )

    private val MONTH_MAP = mapOf(
        "jan" to Month.JANUARY,
        "feb" to Month.FEBRUARY,
        "mar" to Month.MARCH,
        "apr" to Month.APRIL,
        "may" to Month.MAY,
        "jun" to Month.JUNE,
        "jul" to Month.JULY,
        "aug" to Month.AUGUST,
        "sep" to Month.SEPTEMBER,
        "oct" to Month.OCTOBER,
        "nov" to Month.NOVEMBER,
        "dec" to Month.DECEMBER
    )

    /**
     * Parses a pickup time string into an epoch millisecond timestamp.
     *
     * @param pickupTime the raw pickup time string from Lyft UI (e.g., "Today · 6:05AM")
     * @return epoch milliseconds or null if parsing fails
     */
    fun parsePickupTimestamp(pickupTime: String): Long? {
        return parseTodayFormat(pickupTime)
            ?: parseTomorrowFormat(pickupTime)
            ?: parseWeekdayFormat(pickupTime)
            ?: parseDateFormat(pickupTime)
            ?: run {
                Log.w(TAG, "Failed to parse pickup time: '$pickupTime'")
                null
            }
    }

    /**
     * Parses "Today · HH:MMam/pm" format into epoch milliseconds.
     *
     * @return epoch milliseconds or null if the input doesn't match this format
     */
    private fun parseTodayFormat(pickupTime: String): Long? {
        val match = TODAY_PATTERN.find(pickupTime) ?: return null
        val (hourStr, minuteStr, amPm) = match.destructured
        val time = parseTime(hourStr.toInt(), minuteStr.toInt(), amPm) ?: return null

        val today = LocalDate.now(clock)
        val zonedDateTime = ZonedDateTime.of(today, time, ZoneId.systemDefault())
        return zonedDateTime.toInstant().toEpochMilli()
    }

    /**
     * Parses "Tomorrow · HH:MMam/pm" format into epoch milliseconds.
     *
     * @return epoch milliseconds or null if the input doesn't match this format
     */
    private fun parseTomorrowFormat(pickupTime: String): Long? {
        val match = TOMORROW_PATTERN.find(pickupTime) ?: return null
        val (hourStr, minuteStr, amPm) = match.destructured
        val time = parseTime(hourStr.toInt(), minuteStr.toInt(), amPm) ?: return null

        val tomorrow = LocalDate.now(clock).plusDays(1)
        val zonedDateTime = ZonedDateTime.of(tomorrow, time, ZoneId.systemDefault())
        return zonedDateTime.toInstant().toEpochMilli()
    }

    /**
     * Parses "Mon · HH:MMam/pm" format into epoch milliseconds.
     * If the target day matches today, returns next week (7 days ahead),
     * since Lyft uses "Today" for same-day rides.
     *
     * @return epoch milliseconds or null if the input doesn't match this format
     */
    private fun parseWeekdayFormat(pickupTime: String): Long? {
        val match = WEEKDAY_PATTERN.find(pickupTime) ?: return null
        val (dayStr, hourStr, minuteStr, amPm) = match.destructured
        val targetDay = WEEKDAY_MAP[dayStr.lowercase()] ?: return null
        val time = parseTime(hourStr.toInt(), minuteStr.toInt(), amPm) ?: return null

        val today = LocalDate.now(clock)
        val daysUntil = ((targetDay.value - today.dayOfWeek.value + 7) % 7).let {
            if (it == 0) 7L else it.toLong()
        }
        val targetDate = today.plusDays(daysUntil)
        return ZonedDateTime.of(targetDate, time, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Parses "Feb 15 · HH:MMam/pm" format into epoch milliseconds.
     * If the date has already passed this year, uses next year.
     * Returns null for invalid dates (e.g., Feb 30).
     *
     * @return epoch milliseconds or null if the input doesn't match this format
     */
    private fun parseDateFormat(pickupTime: String): Long? {
        val match = DATE_PATTERN.find(pickupTime) ?: return null
        val (monthStr, dayStr, hourStr, minuteStr, amPm) = match.destructured
        val month = MONTH_MAP[monthStr.lowercase()] ?: return null
        val day = dayStr.toInt()
        val time = parseTime(hourStr.toInt(), minuteStr.toInt(), amPm) ?: return null

        val today = LocalDate.now(clock)
        val targetDate = try {
            val dateThisYear = LocalDate.of(today.year, month, day)
            if (dateThisYear.isBefore(today)) {
                LocalDate.of(today.year + 1, month, day)
            } else {
                dateThisYear
            }
        } catch (_: DateTimeException) {
            return null
        }

        return ZonedDateTime.of(targetDate, time, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Converts 12h time components into a LocalTime.
     *
     * @param hour the hour in 12h format (expected 1-12)
     * @param minute the minute (expected 0-59)
     * @param amPm "AM" or "PM" (case-insensitive)
     * @return LocalTime or null if invalid
     */
    private fun parseTime(hour: Int, minute: Int, amPm: String): LocalTime? {
        val hour24 = convertTo24Hour(hour, amPm) ?: return null
        if (minute !in 0..59) return null
        return LocalTime.of(hour24, minute)
    }

    /**
     * Converts 12-hour format to 24-hour format.
     *
     * Rules: 12AM = 0 (midnight), 12PM = 12 (noon),
     * 1-11AM = 1-11, 1-11PM = 13-23.
     *
     * @param hour the hour in 12h format (expected 1-12)
     * @param amPm "AM" or "PM" (case-insensitive)
     * @return hour in 24h format (0-23) or null if invalid
     */
    internal fun convertTo24Hour(hour: Int, amPm: String): Int? {
        if (hour !in 1..12) return null
        return when (amPm.uppercase()) {
            "AM" -> if (hour == 12) 0 else hour
            "PM" -> if (hour == 12) 12 else hour + 12
            else -> null
        }
    }
}
