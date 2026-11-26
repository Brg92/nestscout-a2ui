package com.nestscout.a2ui.contract.schema

import com.nestscout.a2ui.contract.catalog.PropertyCardV1Props
import com.nestscout.a2ui.contract.catalog.PropertyCardV2Props
import com.nestscout.a2ui.contract.catalog.PropertyComparisonTableV1Props
import com.nestscout.a2ui.contract.catalog.PropertyComparisonTableV2Props
import com.nestscout.a2ui.contract.catalog.FilterRefineSheetV1Props
import com.nestscout.a2ui.contract.catalog.FilterRefineSheetV2Props
import com.nestscout.a2ui.contract.catalog.VisitRequestFormV1Props
import com.nestscout.a2ui.contract.catalog.VisitRequestFormV2Props
import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val errors: List<String>) : ValidationResult
}

object ContractSchemaValidator {

    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
    }

    fun validateComponent(componentType: String, props: JsonObject): ValidationResult {
        return try {
            when (componentType) {
                A2uiCatalogIds.V1.PROPERTY_CARD -> {
                    val parsed = strictJson.decodeFromJsonElement(PropertyCardV1Props.serializer(), props)
                    validatePropertyCardV1(parsed)
                }
                A2uiCatalogIds.V2.PROPERTY_CARD -> {
                    val parsed = strictJson.decodeFromJsonElement(PropertyCardV2Props.serializer(), props)
                    validatePropertyCardV2(parsed)
                }
                A2uiCatalogIds.V1.PROPERTY_COMPARISON_TABLE -> {
                    val parsed = strictJson.decodeFromJsonElement(PropertyComparisonTableV1Props.serializer(), props)
                    validateComparisonV1(parsed)
                }
                A2uiCatalogIds.V2.PROPERTY_COMPARISON_TABLE -> {
                    val parsed = strictJson.decodeFromJsonElement(PropertyComparisonTableV2Props.serializer(), props)
                    validateComparisonV2(parsed)
                }
                A2uiCatalogIds.V1.FILTER_REFINE_SHEET -> {
                    val parsed = strictJson.decodeFromJsonElement(FilterRefineSheetV1Props.serializer(), props)
                    validateFilterV1(parsed)
                }
                A2uiCatalogIds.V2.FILTER_REFINE_SHEET -> {
                    val parsed = strictJson.decodeFromJsonElement(FilterRefineSheetV2Props.serializer(), props)
                    validateFilterV2(parsed)
                }
                A2uiCatalogIds.V1.VISIT_REQUEST_FORM -> {
                    val parsed = strictJson.decodeFromJsonElement(VisitRequestFormV1Props.serializer(), props)
                    validateVisitFormV1(parsed)
                }
                A2uiCatalogIds.V2.VISIT_REQUEST_FORM -> {
                    val parsed = strictJson.decodeFromJsonElement(VisitRequestFormV2Props.serializer(), props)
                    validateVisitFormV2(parsed)
                }
                else -> ValidationResult.Invalid(listOf("Unknown component type: $componentType"))
            }
        } catch (e: SerializationException) {
            ValidationResult.Invalid(listOf("Schema deserialization error: ${e.message ?: "unknown"}"))
        } catch (e: IllegalArgumentException) {
            ValidationResult.Invalid(listOf("Schema argument error: ${e.message ?: "unknown"}"))
        }
    }

    private fun validatePropertyCardV1(p: PropertyCardV1Props): ValidationResult {
        val errors = buildList {
            if (p.propertyId.isBlank()) add("propertyId must not be blank")
            if (p.title.isBlank()) add("title must not be blank")
            if (!p.isSkeleton) {
                if (p.priceEur <= 0) add("priceEur must be positive")
                if (p.sqm <= 0) add("sqm must be positive")
                if (p.rooms <= 0) add("rooms must be positive")
                if (p.imageUrl.isBlank()) add("imageUrl must not be blank")
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validatePropertyCardV2(p: PropertyCardV2Props): ValidationResult {
        val errors = buildList {
            if (p.propertyId.isBlank()) add("propertyId must not be blank")
            if (p.title.isBlank()) add("title must not be blank")
            if (!p.isSkeleton) {
                if (p.priceEur <= 0) add("priceEur must be positive")
                if (p.pricePerSqm <= 0) add("pricePerSqm must be positive")
                if (p.sqm <= 0) add("sqm must be positive")
                if (p.rooms <= 0) add("rooms must be positive")
                if (p.imageUrl.isBlank()) add("imageUrl must not be blank")
            }
            if (p.matchScore !in 0..100) add("matchScore must be between 0 and 100")
            if (p.highlights.size > 3) add("highlights must contain at most 3 items")
            if (!p.isFavoriteBinding.path.startsWith("/")) add("isFavoriteBinding.path must start with '/'")
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validateComparisonV1(p: PropertyComparisonTableV1Props): ValidationResult {
        val errors = buildList {
            if (p.items.isEmpty() || p.items.size > 2) add("v1 comparison supports 1 to 2 items")
            p.items.forEachIndexed { idx, item ->
                if (item.propertyId.isBlank()) add("items[$idx].propertyId must not be blank")
                if (item.priceEur <= 0) add("items[$idx].priceEur must be positive")
                if (item.sqm <= 0) add("items[$idx].sqm must be positive")
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validateComparisonV2(p: PropertyComparisonTableV2Props): ValidationResult {
        val errors = buildList {
            if (p.items.isEmpty() || p.items.size > 3) add("v2 comparison supports 1 to 3 items")
            if (!p.showOnlyDiffBinding.path.startsWith("/")) add("showOnlyDiffBinding.path must start with '/'")
            p.items.forEachIndexed { idx, item ->
                if (item.propertyId.isBlank()) add("items[$idx].propertyId must not be blank")
                if (item.priceEur <= 0) add("items[$idx].priceEur must be positive")
                if (item.sqm <= 0) add("items[$idx].sqm must be positive")
                if (item.transitMinutes < 0) add("items[$idx].transitMinutes must be >= 0")
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validateFilterV1(p: FilterRefineSheetV1Props): ValidationResult {
        val errors = buildList {
            if (!p.maxPriceBinding.path.startsWith("/")) add("maxPriceBinding.path must start with '/'")
            if (!p.minRoomsBinding.path.startsWith("/")) add("minRoomsBinding.path must start with '/'")
            if (!p.neighborhoodBinding.path.startsWith("/")) add("neighborhoodBinding.path must start with '/'")
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validateFilterV2(p: FilterRefineSheetV2Props): ValidationResult {
        val errors = buildList {
            if (!p.maxPriceBinding.path.startsWith("/")) add("maxPriceBinding.path must start with '/'")
            if (!p.minRoomsBinding.path.startsWith("/")) add("minRoomsBinding.path must start with '/'")
            if (!p.neighborhoodBinding.path.startsWith("/")) add("neighborhoodBinding.path must start with '/'")
            if (!p.minSqmBinding.path.startsWith("/")) add("minSqmBinding.path must start with '/'")
            if (!p.energyClassesBinding.path.startsWith("/")) add("energyClassesBinding.path must start with '/'")
            if (!p.amenitiesBinding.path.startsWith("/")) add("amenitiesBinding.path must start with '/'")
            if (!p.sortByBinding.path.startsWith("/")) add("sortByBinding.path must start with '/'")
            if (p.matchingCount < 0) add("matchingCount must be >= 0")
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validateVisitFormV1(p: VisitRequestFormV1Props): ValidationResult {
        val errors = buildList {
            if (p.propertyId.isBlank()) add("propertyId must not be blank")
            if (p.propertyTitle.isBlank()) add("propertyTitle must not be blank")
            if (p.availableDates.isEmpty()) add("availableDates must not be empty")
            listOf(
                "dateBinding" to p.dateBinding.path,
                "timeSlotBinding" to p.timeSlotBinding.path,
                "visitorNameBinding" to p.visitorNameBinding.path,
                "visitorEmailBinding" to p.visitorEmailBinding.path,
                "notesBinding" to p.notesBinding.path,
                "explicitConfirmedBinding" to p.explicitConfirmedBinding.path,
            ).forEach { (name, path) ->
                if (!path.startsWith("/")) add("$name.path must start with '/'")
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }

    private fun validateVisitFormV2(p: VisitRequestFormV2Props): ValidationResult {
        val errors = buildList {
            if (p.propertyId.isBlank()) add("propertyId must not be blank")
            if (p.propertyTitle.isBlank()) add("propertyTitle must not be blank")
            if (p.availableDates.isEmpty()) add("availableDates must not be empty")
            listOf(
                "dateBinding" to p.dateBinding.path,
                "timeSlotBinding" to p.timeSlotBinding.path,
                "visitModeBinding" to p.visitModeBinding.path,
                "visitorNameBinding" to p.visitorNameBinding.path,
                "visitorEmailBinding" to p.visitorEmailBinding.path,
                "notesBinding" to p.notesBinding.path,
                "explicitConfirmedBinding" to p.explicitConfirmedBinding.path,
                "statusBinding" to p.statusBinding.path,
            ).forEach { (name, path) ->
                if (!path.startsWith("/")) add("$name.path must start with '/'")
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}
