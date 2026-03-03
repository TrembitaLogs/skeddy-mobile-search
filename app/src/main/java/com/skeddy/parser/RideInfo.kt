package com.skeddy.parser

/**
 * TODO: Data class для зберігання інформації про райд
 *
 * Призначення:
 * - Представляти parsed дані з Lyft UI
 * - Зберігати ціну, час, відстань, pickup/dropoff локації
 * - Використовується для фільтрації та збереження в базу даних
 */
data class RideInfo(
    val price: Double = 0.0,
    val estimatedTime: Int = 0, // in minutes
    val distance: Double = 0.0, // in miles
    val pickupLocation: String = "",
    val dropoffLocation: String = "",
    val rideType: String = "", // e.g., "Lyft", "Lyft XL", "Lux"
    val timestamp: Long = System.currentTimeMillis()
)
