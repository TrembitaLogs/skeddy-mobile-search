package com.skeddy.util

import android.util.Log
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PickupTimeParserTest {

    @After
    fun tearDown() {
        PickupTimeParser.clock = Clock.systemDefaultZone()
    }

    /**
     * Helper to compute expected epoch millis for today at a given hour:minute.
     */
    private fun expectedTimestampToday(hour: Int, minute: Int): Long {
        val today = LocalDate.now()
        val time = LocalTime.of(hour, minute)
        return ZonedDateTime.of(today, time, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Helper to compute expected epoch millis for tomorrow at a given hour:minute.
     */
    private fun expectedTimestampTomorrow(hour: Int, minute: Int): Long {
        val tomorrow = LocalDate.now().plusDays(1)
        val time = LocalTime.of(hour, minute)
        return ZonedDateTime.of(tomorrow, time, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Helper to compute expected epoch millis for a specific date at a given hour:minute.
     */
    private fun expectedTimestampForDate(date: LocalDate, hour: Int, minute: Int): Long {
        val time = LocalTime.of(hour, minute)
        return ZonedDateTime.of(date, time, ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    // ==================== Today Format — Core Tests (from test strategy) ====================

    @Test
    fun `parsePickupTimestamp parses Today morning time correctly`() {
        val result = PickupTimeParser.parsePickupTimestamp("Today · 6:05AM")

        assertEquals(expectedTimestampToday(6, 5), result)
    }

    @Test
    fun `parsePickupTimestamp parses Today noon correctly`() {
        val result = PickupTimeParser.parsePickupTimestamp("Today · 12:00PM")

        assertEquals(expectedTimestampToday(12, 0), result)
    }

    @Test
    fun `parsePickupTimestamp parses Today midnight correctly`() {
        val result = PickupTimeParser.parsePickupTimestamp("Today · 12:00AM")

        assertEquals(expectedTimestampToday(0, 0), result)
    }

    @Test
    fun `parsePickupTimestamp returns null for Today without middle dot`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("Today 6:05AM"))
    }

    // ==================== PM Conversion Tests ====================

    @Test
    fun `parsePickupTimestamp parses afternoon time correctly`() {
        val result = PickupTimeParser.parsePickupTimestamp("Today · 3:00PM")

        assertEquals(expectedTimestampToday(15, 0), result)
    }

    @Test
    fun `parsePickupTimestamp parses 11 59 PM correctly`() {
        val result = PickupTimeParser.parsePickupTimestamp("Today · 11:59PM")

        assertEquals(expectedTimestampToday(23, 59), result)
    }

    @Test
    fun `parsePickupTimestamp parses 1 AM correctly`() {
        val result = PickupTimeParser.parsePickupTimestamp("Today · 1:00AM")

        assertEquals(expectedTimestampToday(1, 0), result)
    }

    // ==================== Case Insensitivity Tests ====================

    @Test
    fun `parsePickupTimestamp is case insensitive for today and am pm`() {
        val result = PickupTimeParser.parsePickupTimestamp("today · 6:05am")

        assertEquals(expectedTimestampToday(6, 5), result)
    }

    @Test
    fun `parsePickupTimestamp handles mixed case`() {
        val result = PickupTimeParser.parsePickupTimestamp("Today · 3:30Pm")

        assertEquals(expectedTimestampToday(15, 30), result)
    }

    // ==================== Whitespace Variations ====================

    @Test
    fun `parsePickupTimestamp handles no spaces around middle dot`() {
        val result = PickupTimeParser.parsePickupTimestamp("Today·6:05AM")

        assertEquals(expectedTimestampToday(6, 5), result)
    }

    @Test
    fun `parsePickupTimestamp handles extra spaces around middle dot`() {
        val result = PickupTimeParser.parsePickupTimestamp("Today  ·  6:05AM")

        assertEquals(expectedTimestampToday(6, 5), result)
    }

    // ==================== Invalid Input Tests ====================

    @Test
    fun `parsePickupTimestamp returns null for empty string`() {
        assertNull(PickupTimeParser.parsePickupTimestamp(""))
    }

    @Test
    fun `parsePickupTimestamp returns null for random text`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("random garbage"))
    }

    @Test
    fun `parsePickupTimestamp parses Tomorrow morning time correctly`() {
        val result = PickupTimeParser.parsePickupTimestamp("Tomorrow · 6:05AM")

        assertEquals(expectedTimestampTomorrow(6, 5), result)
    }

    @Test
    fun `parsePickupTimestamp parses weekday format`() {
        // Fix clock to Thursday Feb 12, 2026
        val zone = ZoneId.systemDefault()
        val thursday = LocalDate.of(2026, 2, 12)
        PickupTimeParser.clock = Clock.fixed(thursday.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("Mon · 6:05AM")

        // Next Monday from Thursday = 4 days ahead = Feb 16, 2026
        val expected = expectedTimestampForDate(LocalDate.of(2026, 2, 16), 6, 5)
        assertEquals(expected, result)
    }

    @Test
    fun `parsePickupTimestamp parses date format`() {
        // Fix clock to Feb 12, 2026
        val zone = ZoneId.systemDefault()
        val feb12 = LocalDate.of(2026, 2, 12)
        PickupTimeParser.clock = Clock.fixed(feb12.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("Feb 15 · 6:05AM")

        // Feb 15 is in the future → current year
        val expected = expectedTimestampForDate(LocalDate.of(2026, 2, 15), 6, 5)
        assertEquals(expected, result)
    }

    @Test
    fun `parsePickupTimestamp returns null for hour zero`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("Today · 0:05AM"))
    }

    @Test
    fun `parsePickupTimestamp returns null for hour 13`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("Today · 13:00PM"))
    }

    // ==================== Tomorrow Format — Core Tests (from test strategy) ====================

    @Test
    fun `parsePickupTimestamp parses Tomorrow afternoon time correctly`() {
        val result = PickupTimeParser.parsePickupTimestamp("Tomorrow · 3:00PM")

        assertEquals(expectedTimestampTomorrow(15, 0), result)
    }

    @Test
    fun `parsePickupTimestamp parses Tomorrow 11 59 PM correctly`() {
        val result = PickupTimeParser.parsePickupTimestamp("Tomorrow · 11:59PM")

        assertEquals(expectedTimestampTomorrow(23, 59), result)
    }

    @Test
    fun `parsePickupTimestamp parses Tomorrow midnight correctly`() {
        val result = PickupTimeParser.parsePickupTimestamp("Tomorrow · 12:00AM")

        assertEquals(expectedTimestampTomorrow(0, 0), result)
    }

    @Test
    fun `parsePickupTimestamp parses Tomorrow noon correctly`() {
        val result = PickupTimeParser.parsePickupTimestamp("Tomorrow · 12:00PM")

        assertEquals(expectedTimestampTomorrow(12, 0), result)
    }

    // ==================== Tomorrow Format — Year Boundary Test ====================

    @Test
    fun `parsePickupTimestamp handles year boundary Dec 31 to Jan 1 for Tomorrow`() {
        val zone = ZoneId.systemDefault()
        val dec31 = LocalDate.of(2025, 12, 31)
        val fixedInstant = dec31.atStartOfDay(zone).toInstant()
        PickupTimeParser.clock = Clock.fixed(fixedInstant, zone)

        val result = PickupTimeParser.parsePickupTimestamp("Tomorrow · 3:00PM")

        val expected = expectedTimestampForDate(LocalDate.of(2026, 1, 1), 15, 0)
        assertEquals(expected, result)
    }

    // ==================== Tomorrow Format — Case & Whitespace ====================

    @Test
    fun `parsePickupTimestamp is case insensitive for Tomorrow`() {
        val result = PickupTimeParser.parsePickupTimestamp("tomorrow · 6:05am")

        assertEquals(expectedTimestampTomorrow(6, 5), result)
    }

    @Test
    fun `parsePickupTimestamp handles no spaces around middle dot for Tomorrow`() {
        val result = PickupTimeParser.parsePickupTimestamp("Tomorrow·3:00PM")

        assertEquals(expectedTimestampTomorrow(15, 0), result)
    }

    @Test
    fun `parsePickupTimestamp returns null for Tomorrow without middle dot`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("Tomorrow 6:05AM"))
    }

    @Test
    fun `parsePickupTimestamp returns null for Tomorrow with invalid hour`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("Tomorrow · 0:05AM"))
    }

    // ==================== convertTo24Hour Unit Tests ====================

    @Test
    fun `convertTo24Hour converts 12 AM to 0`() {
        assertEquals(0, PickupTimeParser.convertTo24Hour(12, "AM"))
    }

    @Test
    fun `convertTo24Hour converts 12 PM to 12`() {
        assertEquals(12, PickupTimeParser.convertTo24Hour(12, "PM"))
    }

    @Test
    fun `convertTo24Hour converts 1 AM to 1`() {
        assertEquals(1, PickupTimeParser.convertTo24Hour(1, "AM"))
    }

    @Test
    fun `convertTo24Hour converts 1 PM to 13`() {
        assertEquals(13, PickupTimeParser.convertTo24Hour(1, "PM"))
    }

    @Test
    fun `convertTo24Hour converts 11 AM to 11`() {
        assertEquals(11, PickupTimeParser.convertTo24Hour(11, "AM"))
    }

    @Test
    fun `convertTo24Hour converts 11 PM to 23`() {
        assertEquals(23, PickupTimeParser.convertTo24Hour(11, "PM"))
    }

    @Test
    fun `convertTo24Hour returns null for hour 0`() {
        assertNull(PickupTimeParser.convertTo24Hour(0, "AM"))
    }

    @Test
    fun `convertTo24Hour returns null for hour 13`() {
        assertNull(PickupTimeParser.convertTo24Hour(13, "PM"))
    }

    @Test
    fun `convertTo24Hour returns null for negative hour`() {
        assertNull(PickupTimeParser.convertTo24Hour(-1, "AM"))
    }

    @Test
    fun `convertTo24Hour returns null for invalid amPm string`() {
        assertNull(PickupTimeParser.convertTo24Hour(6, "XM"))
    }

    // ==================== Weekday Format — Core Tests (from 7.3 test strategy) ====================

    @Test
    fun `parsePickupTimestamp weekday same day returns next week`() {
        // Fix clock to Thursday Feb 12, 2026
        val zone = ZoneId.systemDefault()
        val thursday = LocalDate.of(2026, 2, 12)
        PickupTimeParser.clock = Clock.fixed(thursday.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("Thu · 6:05AM")

        // Same day → 7 days ahead = Feb 19, 2026
        val expected = expectedTimestampForDate(LocalDate.of(2026, 2, 19), 6, 5)
        assertEquals(expected, result)
    }

    @Test
    fun `parsePickupTimestamp weekday next day`() {
        // Fix clock to Thursday Feb 12, 2026
        val zone = ZoneId.systemDefault()
        val thursday = LocalDate.of(2026, 2, 12)
        PickupTimeParser.clock = Clock.fixed(thursday.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("Fri · 3:00PM")

        // Friday from Thursday = 1 day ahead = Feb 13, 2026
        val expected = expectedTimestampForDate(LocalDate.of(2026, 2, 13), 15, 0)
        assertEquals(expected, result)
    }

    @Test
    fun `parsePickupTimestamp weekday from Monday parses Sun correctly`() {
        // Fix clock to Monday Feb 9, 2026
        val zone = ZoneId.systemDefault()
        val monday = LocalDate.of(2026, 2, 9)
        PickupTimeParser.clock = Clock.fixed(monday.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("Sun · 9:00AM")

        // Sunday from Monday = 6 days ahead = Feb 15, 2026
        val expected = expectedTimestampForDate(LocalDate.of(2026, 2, 15), 9, 0)
        assertEquals(expected, result)
    }

    @Test
    fun `parsePickupTimestamp weekday from Sunday parses Mon correctly`() {
        // Fix clock to Sunday Feb 15, 2026
        val zone = ZoneId.systemDefault()
        val sunday = LocalDate.of(2026, 2, 15)
        PickupTimeParser.clock = Clock.fixed(sunday.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("Mon · 6:05AM")

        // Monday from Sunday = 1 day ahead = Feb 16, 2026
        val expected = expectedTimestampForDate(LocalDate.of(2026, 2, 16), 6, 5)
        assertEquals(expected, result)
    }

    @Test
    fun `parsePickupTimestamp weekday is case insensitive`() {
        val zone = ZoneId.systemDefault()
        val thursday = LocalDate.of(2026, 2, 12)
        PickupTimeParser.clock = Clock.fixed(thursday.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("mon · 6:05am")

        val expected = expectedTimestampForDate(LocalDate.of(2026, 2, 16), 6, 5)
        assertEquals(expected, result)
    }

    @Test
    fun `parsePickupTimestamp returns null for invalid weekday abbreviation`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("Xyz · 6:05AM"))
    }

    // ==================== Date Format — Core Tests (from 7.3 test strategy) ====================

    @Test
    fun `parsePickupTimestamp date in the past this year uses next year`() {
        // Fix clock to March 1, 2026
        val zone = ZoneId.systemDefault()
        val march1 = LocalDate.of(2026, 3, 1)
        PickupTimeParser.clock = Clock.fixed(march1.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("Feb 15 · 6:05AM")

        // Feb 15 is past → next year = Feb 15, 2027
        val expected = expectedTimestampForDate(LocalDate.of(2027, 2, 15), 6, 5)
        assertEquals(expected, result)
    }

    @Test
    fun `parsePickupTimestamp Dec 31 in January uses current year`() {
        // Fix clock to January 15, 2026
        val zone = ZoneId.systemDefault()
        val jan15 = LocalDate.of(2026, 1, 15)
        PickupTimeParser.clock = Clock.fixed(jan15.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("Dec 31 · 11:59PM")

        // Dec 31 hasn't passed yet → current year
        val expected = expectedTimestampForDate(LocalDate.of(2026, 12, 31), 23, 59)
        assertEquals(expected, result)
    }

    @Test
    fun `parsePickupTimestamp returns null for invalid date Feb 30`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("Feb 30 · 6:05AM"))
    }

    @Test
    fun `parsePickupTimestamp date same day uses current year`() {
        // Fix clock to Feb 15, 2026
        val zone = ZoneId.systemDefault()
        val feb15 = LocalDate.of(2026, 2, 15)
        PickupTimeParser.clock = Clock.fixed(feb15.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("Feb 15 · 6:05AM")

        // Same date → not before today → current year
        val expected = expectedTimestampForDate(LocalDate.of(2026, 2, 15), 6, 5)
        assertEquals(expected, result)
    }

    @Test
    fun `parsePickupTimestamp date is case insensitive`() {
        val zone = ZoneId.systemDefault()
        val feb12 = LocalDate.of(2026, 2, 12)
        PickupTimeParser.clock = Clock.fixed(feb12.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("feb 15 · 6:05am")

        val expected = expectedTimestampForDate(LocalDate.of(2026, 2, 15), 6, 5)
        assertEquals(expected, result)
    }

    // ==================== Leap Year Edge Cases ====================

    @Test
    fun `parsePickupTimestamp parses Feb 29 correctly in leap year`() {
        val zone = ZoneId.systemDefault()
        val jan15 = LocalDate.of(2024, 1, 15)
        PickupTimeParser.clock = Clock.fixed(jan15.atStartOfDay(zone).toInstant(), zone)

        val result = PickupTimeParser.parsePickupTimestamp("Feb 29 · 6:05AM")

        val expected = expectedTimestampForDate(LocalDate.of(2024, 2, 29), 6, 5)
        assertEquals(expected, result)
    }

    @Test
    fun `parsePickupTimestamp returns null for Feb 29 in non-leap year`() {
        val zone = ZoneId.systemDefault()
        val jan15 = LocalDate.of(2026, 1, 15)
        PickupTimeParser.clock = Clock.fixed(jan15.atStartOfDay(zone).toInstant(), zone)

        assertNull(PickupTimeParser.parsePickupTimestamp("Feb 29 · 6:05AM"))
    }

    @Test
    fun `parsePickupTimestamp returns null for Feb 29 past in leap year when next year is not leap`() {
        // March 1, 2024: Feb 29 has passed this year, and 2025 is not a leap year
        val zone = ZoneId.systemDefault()
        val march1 = LocalDate.of(2024, 3, 1)
        PickupTimeParser.clock = Clock.fixed(march1.atStartOfDay(zone).toInstant(), zone)

        assertNull(PickupTimeParser.parsePickupTimestamp("Feb 29 · 6:05AM"))
    }

    // ==================== Additional Case Insensitivity Tests ====================

    @Test
    fun `parsePickupTimestamp handles full uppercase TODAY`() {
        val result = PickupTimeParser.parsePickupTimestamp("TODAY · 6:05AM")

        assertEquals(expectedTimestampToday(6, 5), result)
    }

    @Test
    fun `parsePickupTimestamp handles mixed case ToDay`() {
        val result = PickupTimeParser.parsePickupTimestamp("ToDay · 6:05AM")

        assertEquals(expectedTimestampToday(6, 5), result)
    }

    @Test
    fun `parsePickupTimestamp handles full uppercase TOMORROW`() {
        val result = PickupTimeParser.parsePickupTimestamp("TOMORROW · 3:00PM")

        assertEquals(expectedTimestampTomorrow(15, 0), result)
    }

    // ==================== Malformed Input Tests ====================

    @Test
    fun `parsePickupTimestamp returns null for malformed hour 25`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("Today · 25:00AM"))
    }

    @Test
    fun `parsePickupTimestamp returns null for malformed minute 60`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("Today · 6:60AM"))
    }

    @Test
    fun `parsePickupTimestamp returns null for unknown format Next week`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("Next week · 6:05AM"))
    }

    @Test
    fun `parsePickupTimestamp returns null when AM PM is missing`() {
        assertNull(PickupTimeParser.parsePickupTimestamp("Today · 6:05"))
    }

    // ==================== Log Warning Verification Tests ====================

    @Test
    fun `parsePickupTimestamp logs warning for unrecognized format`() {
        ShadowLog.reset()

        PickupTimeParser.parsePickupTimestamp("Next week · 6:05AM")

        val logs = ShadowLog.getLogsForTag("PickupTimeParser")
        assertTrue(
            "Expected Log.w containing the original string",
            logs.any { it.type == Log.WARN && it.msg.contains("Next week · 6:05AM") }
        )
    }

    @Test
    fun `parsePickupTimestamp does not log warning for valid input`() {
        ShadowLog.reset()

        PickupTimeParser.parsePickupTimestamp("Today · 6:05AM")

        val logs = ShadowLog.getLogsForTag("PickupTimeParser")
        assertTrue(
            "Expected no Log.w for valid input",
            logs.none { it.type == Log.WARN }
        )
    }
}
