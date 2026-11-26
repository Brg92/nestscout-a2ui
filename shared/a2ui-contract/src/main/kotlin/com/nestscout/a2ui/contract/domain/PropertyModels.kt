package com.nestscout.a2ui.contract.domain

import kotlinx.serialization.Serializable

@Serializable
enum class EnergyClass {
    A4, A3, A2, A1, B, C, D, E, F, G
}

@Serializable
enum class VisitMode {
    IN_PERSON,
    VIDEO_CALL,
}

@Serializable
enum class VisitTimeSlot {
    MORNING,
    AFTERNOON,
    EVENING,
}

@Serializable
enum class SortOrder {
    RELEVANCE,
    PRICE_ASC,
    SQM_DESC,
}

@Serializable
enum class Amenity {
    BALCONY,
    ELEVATOR,
    METRO_NEAR,
    TERRACE,
    PARKING,
    AIR_CONDITIONING,
}

/**
 * Certified real estate listing stored in the server-side dataset.
 * Factual fields are hydrated deterministically and never synthesized by external planners.
 */
@Serializable
data class PropertyListing(
    val id: String,
    val title: String,
    val neighborhood: String,
    val city: String,
    val address: String,
    val priceEur: Int,
    val sqm: Int,
    val rooms: Int,
    val floor: Int,
    val condoFeesEur: Int,
    val transitMinutes: Int,
    val energyClass: EnergyClass,
    val imageUrl: String,
    val highlights: List<String> = emptyList(),
    val amenities: Set<Amenity> = emptySet(),
    val availableVisitDates: List<String> = emptyList(),
    val verified: Boolean = true,
) {
    val pricePerSqm: Int
        get() = if (sqm > 0) priceEur / sqm else 0
}

@Serializable
data class SearchCriteria(
    val query: String = "",
    val maxPriceEur: Int? = null,
    val minRooms: Int? = null,
    val minSqm: Int? = null,
    val neighborhood: String? = null,
    val energyClasses: Set<EnergyClass> = emptySet(),
    val amenities: Set<Amenity> = emptySet(),
    val sortBy: SortOrder = SortOrder.RELEVANCE,
)
