package com.skeddy.model

import java.security.MessageDigest
import java.util.Calendar
import java.util.regex.Pattern

/**
 * Represents a scheduled ride from Lyft's Scheduled Rides screen.
 * Contains parsed data about the ride including rider information.
 */
data class ScheduledRide(
    val id: String,
    val price: Double,
    val bonus: Double?,
    val pickupTime: String,
    val pickupLocation: String,
    val dropoffLocation: String,
    val duration: String,
    val distance: String,
    val riderName: String,
    val riderRating: Double,
    val isVerified: Boolean,
    val foundAt: Long = System.currentTimeMillis(),
    val status: RideStatus = RideStatus.NEW
) {
    /**
     * Returns the pickup timestamp.
     * @return timestamp in milliseconds, or null if parsing fails
     */
    fun getPickupTimestamp(): Long? {
        return parsePickupTimestamp()
    }

    /**
     * Checks if this ride has expired (pickup time has passed).
     * @return true if ride is expired, false otherwise or if parsing fails
     */
    fun isExpired(): Boolean {
        val pickupTs = getPickupTimestamp() ?: return false
        return System.currentTimeMillis() >= pickupTs
    }

    /**
     * Parses pickupTime string to timestamp.
     * Supported formats:
     * - "Today 9:15 AM", "Today · 3:45 PM"
     * - "Tomorrow 10:00 AM", "Tomorrow · 2:30 PM"
     * - "Jan 15 at 10:00 AM"
     *
     * IMPORTANT: "Today" and "Tomorrow" are relative to foundAt timestamp,
     * not the current time. This ensures correct calculation even days later.
     */
    private fun parsePickupTimestamp(): Long? {
        // Start with foundAt date as base (not current time!)
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = foundAt

        // Extract time part (e.g., "9:15 AM", "3:45 PM")
        val timePattern = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(AM|PM)", Pattern.CASE_INSENSITIVE)
        val timeMatcher = timePattern.matcher(pickupTime)

        if (!timeMatcher.find()) return null

        var hour = timeMatcher.group(1)?.toIntOrNull() ?: return null
        val minute = timeMatcher.group(2)?.toIntOrNull() ?: return null
        val amPm = timeMatcher.group(3)?.uppercase() ?: return null

        // Convert to 24-hour format
        if (amPm == "PM" && hour != 12) hour += 12
        if (amPm == "AM" && hour == 12) hour = 0

        // Determine the date relative to foundAt
        val lowerPickup = pickupTime.lowercase()
        when {
            lowerPickup.contains("today") -> {
                // Keep foundAt date (the day ride was discovered)
            }
            lowerPickup.contains("tomorrow") -> {
                // Tomorrow relative to foundAt (the day after ride was discovered)
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
            else -> {
                // Try to parse date like "Jan 15"
                val monthPattern = Pattern.compile("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(\\d{1,2})", Pattern.CASE_INSENSITIVE)
                val monthMatcher = monthPattern.matcher(pickupTime)
                if (monthMatcher.find()) {
                    val monthStr = monthMatcher.group(1)?.lowercase() ?: return null
                    val day = monthMatcher.group(2)?.toIntOrNull() ?: return null
                    val month = when (monthStr) {
                        "jan" -> Calendar.JANUARY
                        "feb" -> Calendar.FEBRUARY
                        "mar" -> Calendar.MARCH
                        "apr" -> Calendar.APRIL
                        "may" -> Calendar.MAY
                        "jun" -> Calendar.JUNE
                        "jul" -> Calendar.JULY
                        "aug" -> Calendar.AUGUST
                        "sep" -> Calendar.SEPTEMBER
                        "oct" -> Calendar.OCTOBER
                        "nov" -> Calendar.NOVEMBER
                        "dec" -> Calendar.DECEMBER
                        else -> return null
                    }
                    calendar.set(Calendar.MONTH, month)
                    calendar.set(Calendar.DAY_OF_MONTH, day)

                    // If the date is in the past relative to foundAt, assume next year
                    if (calendar.timeInMillis < foundAt - 24 * 60 * 60 * 1000) {
                        calendar.add(Calendar.YEAR, 1)
                    }
                }
            }
        }

        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        return calendar.timeInMillis
    }

    companion object {
        /**
         * Generates a unique ride ID using SHA-256 hash of 7 ride attributes.
         *
         * This is the single source of truth for ride identity — used for both
         * in-cycle deduplication and blacklist keying.
         *
         * @return 64-character lowercase hex string (SHA-256 hash)
         */
        fun generateId(
            pickupTime: String,
            price: Double,
            riderName: String,
            pickupLocation: String,
            dropoffLocation: String,
            duration: String,
            distance: String
        ): String {
            val input = "${pickupTime}|${price}|${riderName}|${pickupLocation}|${dropoffLocation}|${duration}|${distance}"
                .lowercase()
            val hashBytes = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Status of a scheduled ride in the application lifecycle.
 */
enum class RideStatus {
    /** Newly discovered ride, not yet processed */
    NEW,
    /** User has been notified about this ride */
    NOTIFIED,
    /** Ride was accepted by the driver */
    ACCEPTED,
    /** Ride is no longer available or has passed */
    EXPIRED,
    /** Ride was cancelled by the driver */
    CANCELLED
}
