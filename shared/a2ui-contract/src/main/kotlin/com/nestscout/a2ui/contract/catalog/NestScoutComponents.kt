package com.nestscout.a2ui.contract.catalog

import com.nestscout.a2ui.contract.domain.EnergyClass
import kotlinx.serialization.Serializable

/**
 * Reference to a reactive path in the A2UI data model (e.g. "/favorites/apt-01").
 */
@Serializable
data class DataBindingPath(
    val path: String,
)

@Serializable
data class SubcomponentLoadingState(
    val image: Boolean = false,
    val price: Boolean = false,
    val matchScore: Boolean = false,
)

@Serializable
data class PropertyCardV1Props(
    val propertyId: String,
    val title: String,
    val neighborhood: String,
    val priceEur: Int,
    val sqm: Int,
    val rooms: Int,
    val energyClass: EnergyClass,
    val imageUrl: String,
    val isSkeleton: Boolean = false,
)

@Serializable
data class PropertyCardV2Props(
    val propertyId: String,
    val title: String,
    val neighborhood: String,
    val priceEur: Int,
    val pricePerSqm: Int,
    val sqm: Int,
    val rooms: Int,
    val energyClass: EnergyClass,
    val imageUrl: String,
    val matchScore: Int,
    val highlights: List<String> = emptyList(),
    val isFavoriteBinding: DataBindingPath,
    val isSkeleton: Boolean = false,
    val subcomponentLoading: SubcomponentLoadingState = SubcomponentLoadingState(),
)

@Serializable
data class ComparisonItemV1(
    val propertyId: String,
    val title: String,
    val priceEur: Int,
    val sqm: Int,
    val rooms: Int,
    val floor: Int,
    val condoFeesEur: Int,
)

@Serializable
data class PropertyComparisonTableV1Props(
    val items: List<ComparisonItemV1>,
)

@Serializable
data class ComparisonItemV2(
    val propertyId: String,
    val title: String,
    val priceEur: Int,
    val sqm: Int,
    val rooms: Int,
    val floor: Int,
    val condoFeesEur: Int,
    val energyClass: EnergyClass,
    val transitMinutes: Int,
)

@Serializable
data class PropertyComparisonTableV2Props(
    val items: List<ComparisonItemV2>,
    val highlightBestValue: Boolean = true,
    val showOnlyDiffBinding: DataBindingPath,
    val winnerPropertyId: String? = null,
)

@Serializable
data class FilterRefineSheetV1Props(
    val availableNeighborhoods: List<String>,
    val maxPriceBinding: DataBindingPath,
    val minRoomsBinding: DataBindingPath,
    val neighborhoodBinding: DataBindingPath,
)

@Serializable
data class FilterRefineSheetV2Props(
    val availableNeighborhoods: List<String>,
    val maxPriceBinding: DataBindingPath,
    val minRoomsBinding: DataBindingPath,
    val neighborhoodBinding: DataBindingPath,
    val minSqmBinding: DataBindingPath,
    val energyClassesBinding: DataBindingPath,
    val amenitiesBinding: DataBindingPath,
    val sortByBinding: DataBindingPath,
    val matchingCount: Int,
)

@Serializable
enum class VisitSubmissionStatus {
    IDLE,
    CONFIRMING,
    SUBMITTING,
    CONFIRMED,
    ERROR,
}

@Serializable
data class VisitRequestFormV1Props(
    val propertyId: String,
    val propertyTitle: String,
    val availableDates: List<String>,
    val dateBinding: DataBindingPath,
    val timeSlotBinding: DataBindingPath,
    val visitorNameBinding: DataBindingPath,
    val visitorEmailBinding: DataBindingPath,
    val notesBinding: DataBindingPath,
    val explicitConfirmedBinding: DataBindingPath,
)

@Serializable
data class VisitRequestFormV2Props(
    val propertyId: String,
    val propertyTitle: String,
    val availableDates: List<String>,
    val dateBinding: DataBindingPath,
    val timeSlotBinding: DataBindingPath,
    val visitModeBinding: DataBindingPath,
    val visitorNameBinding: DataBindingPath,
    val visitorEmailBinding: DataBindingPath,
    val notesBinding: DataBindingPath,
    val explicitConfirmedBinding: DataBindingPath,
    val statusBinding: DataBindingPath,
    val agentNote: String? = null,
)
