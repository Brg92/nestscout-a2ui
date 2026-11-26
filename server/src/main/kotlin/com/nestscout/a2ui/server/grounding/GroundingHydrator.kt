package com.nestscout.a2ui.server.grounding

import com.nestscout.a2ui.contract.catalog.ComparisonItemV1
import com.nestscout.a2ui.contract.catalog.ComparisonItemV2
import com.nestscout.a2ui.contract.catalog.DataBindingPath
import com.nestscout.a2ui.contract.catalog.FilterRefineSheetV1Props
import com.nestscout.a2ui.contract.catalog.FilterRefineSheetV2Props
import com.nestscout.a2ui.contract.catalog.PropertyCardV1Props
import com.nestscout.a2ui.contract.catalog.PropertyCardV2Props
import com.nestscout.a2ui.contract.catalog.PropertyComparisonTableV1Props
import com.nestscout.a2ui.contract.catalog.PropertyComparisonTableV2Props
import com.nestscout.a2ui.contract.catalog.SubcomponentLoadingState
import com.nestscout.a2ui.contract.catalog.VisitRequestFormV1Props
import com.nestscout.a2ui.contract.catalog.VisitRequestFormV2Props
import com.nestscout.a2ui.contract.domain.PropertyListing
import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import com.nestscout.a2ui.contract.protocol.A2uiComponentNode
import com.nestscout.a2ui.contract.protocol.CatalogVersion
import com.nestscout.a2ui.contract.schema.ContractSchemaValidator
import com.nestscout.a2ui.contract.schema.ValidationResult
import com.nestscout.a2ui.server.repository.PropertyRepository
import com.nestscout.a2ui.server.security.CapabilityNegotiator
import com.nestscout.a2ui.server.security.UrlAllowlistSanitizer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Hydrates all factual real estate attributes strictly from [PropertyRepository]
 * and enforces image host allowlists via [UrlAllowlistSanitizer].
 */
class GroundingHydrator(
    private val repository: PropertyRepository,
    private val urlSanitizer: UrlAllowlistSanitizer = UrlAllowlistSanitizer(),
) {
    private val wireJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun hydratePropertyCardV1(
        propertyId: String,
        isSkeleton: Boolean = false,
    ): PropertyCardV1Props? {
        val listing = repository.findById(propertyId) ?: return null
        return PropertyCardV1Props(
            propertyId = listing.id,
            title = listing.title,
            neighborhood = listing.neighborhood,
            priceEur = listing.priceEur,
            sqm = listing.sqm,
            rooms = listing.rooms,
            energyClass = listing.energyClass,
            imageUrl = urlSanitizer.sanitizeOrFallback(listing.imageUrl),
            isSkeleton = isSkeleton,
        )
    }

    fun hydratePropertyCardV2(
        propertyId: String,
        matchScore: Int,
        isSkeleton: Boolean = false,
        subcomponentLoading: SubcomponentLoadingState = SubcomponentLoadingState(),
    ): PropertyCardV2Props? {
        val listing = repository.findById(propertyId) ?: return null
        return PropertyCardV2Props(
            propertyId = listing.id,
            title = listing.title,
            neighborhood = listing.neighborhood,
            priceEur = listing.priceEur,
            pricePerSqm = listing.pricePerSqm,
            sqm = listing.sqm,
            rooms = listing.rooms,
            energyClass = listing.energyClass,
            imageUrl = urlSanitizer.sanitizeOrFallback(listing.imageUrl),
            matchScore = matchScore.coerceIn(0, 100),
            highlights = listing.highlights.take(MAX_HIGHLIGHTS),
            isFavoriteBinding = DataBindingPath("/favorites/${listing.id}"),
            isSkeleton = isSkeleton,
            subcomponentLoading = subcomponentLoading,
        )
    }

    fun buildPropertyCardNode(
        nodeId: String,
        propertyId: String,
        version: CatalogVersion,
        matchScore: Int = DEFAULT_MATCH_SCORE,
        isSkeleton: Boolean = false,
        subcomponentLoading: SubcomponentLoadingState = SubcomponentLoadingState(),
    ): A2uiComponentNode? = when (version) {
        CatalogVersion.V1 -> {
            val props = hydratePropertyCardV1(propertyId, isSkeleton) ?: return null
            encodeVerifiedNode(nodeId, A2uiCatalogIds.V1.PROPERTY_CARD, PropertyCardV1Props.serializer(), props)
        }
        CatalogVersion.V2 -> {
            val props = hydratePropertyCardV2(propertyId, matchScore, isSkeleton, subcomponentLoading) ?: return null
            encodeVerifiedNode(nodeId, A2uiCatalogIds.V2.PROPERTY_CARD, PropertyCardV2Props.serializer(), props)
        }
    }

    fun hydrateComparisonV1(propertyIds: List<String>): PropertyComparisonTableV1Props? {
        val listings = repository.findByIds(propertyIds).take(CapabilityNegotiator.V1_MAX_COMPARISON_ITEMS)
        if (listings.isEmpty()) return null
        return PropertyComparisonTableV1Props(
            items = listings.map { it.toComparisonItemV1() },
        )
    }

    fun hydrateComparisonV2(
        propertyIds: List<String>,
        maxItems: Int = CapabilityNegotiator.V2_MAX_COMPARISON_ITEMS,
        highlightBestValue: Boolean = true,
        requestedWinnerId: String? = null,
    ): PropertyComparisonTableV2Props? {
        val clampedMax = maxItems.coerceIn(1, CapabilityNegotiator.V2_MAX_COMPARISON_ITEMS)
        val listings = repository.findByIds(propertyIds).take(clampedMax)
        if (listings.isEmpty()) return null

        val verifiedWinnerId = when {
            requestedWinnerId != null && listings.any { it.id == requestedWinnerId } -> requestedWinnerId
            highlightBestValue -> selectBestValuePropertyId(listings)
            else -> null
        }

        return PropertyComparisonTableV2Props(
            items = listings.map { it.toComparisonItemV2() },
            highlightBestValue = highlightBestValue,
            showOnlyDiffBinding = DataBindingPath("/comparison/showOnlyDiff"),
            winnerPropertyId = verifiedWinnerId,
        )
    }

    fun buildComparisonNode(
        nodeId: String,
        propertyIds: List<String>,
        version: CatalogVersion,
        maxItems: Int = CapabilityNegotiator.V2_MAX_COMPARISON_ITEMS,
        requestedWinnerId: String? = null,
    ): A2uiComponentNode? = when (version) {
        CatalogVersion.V1 -> {
            val props = hydrateComparisonV1(propertyIds) ?: return null
            encodeVerifiedNode(
                nodeId,
                A2uiCatalogIds.V1.PROPERTY_COMPARISON_TABLE,
                PropertyComparisonTableV1Props.serializer(),
                props,
            )
        }
        CatalogVersion.V2 -> {
            val props = hydrateComparisonV2(
                propertyIds = propertyIds,
                maxItems = maxItems,
                requestedWinnerId = requestedWinnerId,
            ) ?: return null
            encodeVerifiedNode(
                nodeId,
                A2uiCatalogIds.V2.PROPERTY_COMPARISON_TABLE,
                PropertyComparisonTableV2Props.serializer(),
                props,
            )
        }
    }

    fun hydrateFilterSheetV1(): FilterRefineSheetV1Props = FilterRefineSheetV1Props(
        availableNeighborhoods = repository.availableNeighborhoods(),
        maxPriceBinding = DataBindingPath("/filters/maxPriceEur"),
        minRoomsBinding = DataBindingPath("/filters/minRooms"),
        neighborhoodBinding = DataBindingPath("/filters/neighborhood"),
    )

    fun hydrateFilterSheetV2(matchingCount: Int): FilterRefineSheetV2Props = FilterRefineSheetV2Props(
        availableNeighborhoods = repository.availableNeighborhoods(),
        maxPriceBinding = DataBindingPath("/filters/maxPriceEur"),
        minRoomsBinding = DataBindingPath("/filters/minRooms"),
        neighborhoodBinding = DataBindingPath("/filters/neighborhood"),
        minSqmBinding = DataBindingPath("/filters/minSqm"),
        energyClassesBinding = DataBindingPath("/filters/energyClasses"),
        amenitiesBinding = DataBindingPath("/filters/amenities"),
        sortByBinding = DataBindingPath("/filters/sortBy"),
        matchingCount = matchingCount.coerceAtLeast(0),
    )

    fun buildFilterSheetNode(
        nodeId: String,
        version: CatalogVersion,
        matchingCount: Int,
    ): A2uiComponentNode = when (version) {
        CatalogVersion.V1 -> encodeVerifiedNode(
            nodeId,
            A2uiCatalogIds.V1.FILTER_REFINE_SHEET,
            FilterRefineSheetV1Props.serializer(),
            hydrateFilterSheetV1(),
        )
        CatalogVersion.V2 -> encodeVerifiedNode(
            nodeId,
            A2uiCatalogIds.V2.FILTER_REFINE_SHEET,
            FilterRefineSheetV2Props.serializer(),
            hydrateFilterSheetV2(matchingCount),
        )
    }

    fun hydrateVisitFormV1(propertyId: String): VisitRequestFormV1Props? {
        val listing = repository.findById(propertyId) ?: return null
        return VisitRequestFormV1Props(
            propertyId = listing.id,
            propertyTitle = listing.title,
            availableDates = listing.availableVisitDates,
            dateBinding = DataBindingPath("/visitForm/preferredDate"),
            timeSlotBinding = DataBindingPath("/visitForm/timeSlot"),
            visitorNameBinding = DataBindingPath("/visitForm/visitorName"),
            visitorEmailBinding = DataBindingPath("/visitForm/visitorEmail"),
            notesBinding = DataBindingPath("/visitForm/notes"),
            explicitConfirmedBinding = DataBindingPath("/visitForm/explicitConfirmed"),
        )
    }

    fun hydrateVisitFormV2(
        propertyId: String,
        agencyNote: String? = null,
    ): VisitRequestFormV2Props? {
        val listing = repository.findById(propertyId) ?: return null
        val sanitizedNote = agencyNote?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_AGENCY_NOTE_LENGTH)
        return VisitRequestFormV2Props(
            propertyId = listing.id,
            propertyTitle = listing.title,
            availableDates = listing.availableVisitDates,
            dateBinding = DataBindingPath("/visitForm/preferredDate"),
            timeSlotBinding = DataBindingPath("/visitForm/timeSlot"),
            visitModeBinding = DataBindingPath("/visitForm/visitMode"),
            visitorNameBinding = DataBindingPath("/visitForm/visitorName"),
            visitorEmailBinding = DataBindingPath("/visitForm/visitorEmail"),
            notesBinding = DataBindingPath("/visitForm/notes"),
            explicitConfirmedBinding = DataBindingPath("/visitForm/explicitConfirmed"),
            statusBinding = DataBindingPath("/visitForm/status"),
            agencyNote = sanitizedNote,
        )
    }

    fun buildVisitFormNode(
        nodeId: String,
        propertyId: String,
        version: CatalogVersion,
        agencyNote: String? = null,
    ): A2uiComponentNode? = when (version) {
        CatalogVersion.V1 -> {
            val props = hydrateVisitFormV1(propertyId) ?: return null
            encodeVerifiedNode(nodeId, A2uiCatalogIds.V1.VISIT_REQUEST_FORM, VisitRequestFormV1Props.serializer(), props)
        }
        CatalogVersion.V2 -> {
            val props = hydrateVisitFormV2(propertyId, agencyNote) ?: return null
            encodeVerifiedNode(nodeId, A2uiCatalogIds.V2.VISIT_REQUEST_FORM, VisitRequestFormV2Props.serializer(), props)
        }
    }

    private fun <T> encodeVerifiedNode(
        nodeId: String,
        componentType: String,
        serializer: KSerializer<T>,
        props: T,
    ): A2uiComponentNode {
        val jsonProps: JsonObject = wireJson.encodeToJsonElement(serializer, props).jsonObject
        val validation = ContractSchemaValidator.validateComponent(componentType, jsonProps)
        check(validation is ValidationResult.Valid) {
            val reasons = (validation as? ValidationResult.Invalid)?.errors?.joinToString("; ").orEmpty()
            "Hydrated component $componentType ($nodeId) failed contract validation: $reasons"
        }
        return A2uiComponentNode(
            id = nodeId,
            component = mapOf(componentType to jsonProps),
        )
    }

    private fun selectBestValuePropertyId(listings: List<PropertyListing>): String =
        listings.minWith(
            compareBy<PropertyListing> { it.pricePerSqm }
                .thenBy { it.energyClass.ordinal }
                .thenBy { it.transitMinutes }
                .thenBy { it.id },
        ).id

    private fun PropertyListing.toComparisonItemV1(): ComparisonItemV1 = ComparisonItemV1(
        propertyId = id,
        title = title,
        priceEur = priceEur,
        sqm = sqm,
        rooms = rooms,
        floor = floor,
        condoFeesEur = condoFeesEur,
    )

    private fun PropertyListing.toComparisonItemV2(): ComparisonItemV2 = ComparisonItemV2(
        propertyId = id,
        title = title,
        priceEur = priceEur,
        sqm = sqm,
        rooms = rooms,
        floor = floor,
        condoFeesEur = condoFeesEur,
        energyClass = energyClass,
        transitMinutes = transitMinutes,
    )

    companion object {
        private const val MAX_HIGHLIGHTS = 3
        private const val DEFAULT_MATCH_SCORE = 85
        private const val MAX_AGENCY_NOTE_LENGTH = 160
    }
}
