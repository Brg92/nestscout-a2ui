package com.nestscout.a2ui.contract.schema

import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Generates JSON Schema Draft 2020-12 descriptors for NestScout A2UI custom catalogs (v1 and v2).
 */
object ContractJsonSchemaGenerator {

    private const val SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema"

    fun generateCatalogSchema(catalogId: String): JsonObject {
        val components = when (catalogId) {
            A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID -> v1Schemas()
            A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID -> v2Schemas()
            else -> throw IllegalArgumentException("Unsupported catalogId: $catalogId")
        }

        return buildJsonObject {
            put("\$schema", SCHEMA_DIALECT)
            put("\$id", catalogId)
            put("title", "NestScout A2UI Catalog ($catalogId)")
            put("type", "object")
            put("components", components)
        }
    }

    private fun v1Schemas(): JsonObject = buildJsonObject {
        put(
            A2uiCatalogIds.V1.PROPERTY_CARD,
            componentSchema(
                required = listOf("propertyId", "title", "neighborhood", "priceEur", "sqm", "rooms", "energyClass", "imageUrl"),
                properties = buildJsonObject {
                    put("propertyId", stringType())
                    put("title", stringType())
                    put("neighborhood", stringType())
                    put("priceEur", positiveIntType())
                    put("sqm", positiveIntType())
                    put("rooms", positiveIntType())
                    put("energyClass", stringType())
                    put("imageUrl", stringType())
                    put("isSkeleton", boolType())
                },
            ),
        )
        put(
            A2uiCatalogIds.V1.PROPERTY_COMPARISON_TABLE,
            componentSchema(
                required = listOf("items"),
                properties = buildJsonObject {
                    put("items", buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", 2) })
                },
            ),
        )
        put(
            A2uiCatalogIds.V1.FILTER_REFINE_SHEET,
            componentSchema(
                required = listOf("availableNeighborhoods", "maxPriceBinding", "minRoomsBinding", "neighborhoodBinding"),
                properties = buildJsonObject {
                    put("availableNeighborhoods", buildJsonObject { put("type", "array") })
                    put("maxPriceBinding", bindingRefSchema())
                    put("minRoomsBinding", bindingRefSchema())
                    put("neighborhoodBinding", bindingRefSchema())
                },
            ),
        )
        put(
            A2uiCatalogIds.V1.VISIT_REQUEST_FORM,
            componentSchema(
                required = listOf(
                    "propertyId", "propertyTitle", "availableDates",
                    "dateBinding", "timeSlotBinding", "visitorNameBinding",
                    "visitorEmailBinding", "notesBinding", "explicitConfirmedBinding",
                ),
                properties = buildJsonObject {
                    put("propertyId", stringType())
                    put("propertyTitle", stringType())
                    put("availableDates", buildJsonObject { put("type", "array"); put("minItems", 1) })
                    put("dateBinding", bindingRefSchema())
                    put("timeSlotBinding", bindingRefSchema())
                    put("visitorNameBinding", bindingRefSchema())
                    put("visitorEmailBinding", bindingRefSchema())
                    put("notesBinding", bindingRefSchema())
                    put("explicitConfirmedBinding", bindingRefSchema())
                },
            ),
        )
    }

    private fun v2Schemas(): JsonObject = buildJsonObject {
        put(
            A2uiCatalogIds.V2.PROPERTY_CARD,
            componentSchema(
                required = listOf(
                    "propertyId", "title", "neighborhood", "priceEur", "pricePerSqm",
                    "sqm", "rooms", "energyClass", "imageUrl", "matchScore", "isFavoriteBinding",
                ),
                properties = buildJsonObject {
                    put("propertyId", stringType())
                    put("title", stringType())
                    put("neighborhood", stringType())
                    put("priceEur", positiveIntType())
                    put("pricePerSqm", positiveIntType())
                    put("sqm", positiveIntType())
                    put("rooms", positiveIntType())
                    put("energyClass", stringType())
                    put("imageUrl", stringType())
                    put("matchScore", buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 100) })
                    put("highlights", buildJsonObject { put("type", "array"); put("maxItems", 3) })
                    put("isFavoriteBinding", bindingRefSchema())
                    put("isSkeleton", boolType())
                    put("subcomponentLoading", buildJsonObject { put("type", "object") })
                },
            ),
        )
        put(
            A2uiCatalogIds.V2.PROPERTY_COMPARISON_TABLE,
            componentSchema(
                required = listOf("items", "showOnlyDiffBinding"),
                properties = buildJsonObject {
                    put("items", buildJsonObject { put("type", "array"); put("minItems", 1); put("maxItems", 3) })
                    put("highlightBestValue", boolType())
                    put("showOnlyDiffBinding", bindingRefSchema())
                    put("winnerPropertyId", stringType())
                },
            ),
        )
        put(
            A2uiCatalogIds.V2.FILTER_REFINE_SHEET,
            componentSchema(
                required = listOf(
                    "availableNeighborhoods", "maxPriceBinding", "minRoomsBinding",
                    "neighborhoodBinding", "minSqmBinding", "energyClassesBinding",
                    "amenitiesBinding", "sortByBinding", "matchingCount",
                ),
                properties = buildJsonObject {
                    put("availableNeighborhoods", buildJsonObject { put("type", "array") })
                    put("maxPriceBinding", bindingRefSchema())
                    put("minRoomsBinding", bindingRefSchema())
                    put("neighborhoodBinding", bindingRefSchema())
                    put("minSqmBinding", bindingRefSchema())
                    put("energyClassesBinding", bindingRefSchema())
                    put("amenitiesBinding", bindingRefSchema())
                    put("sortByBinding", bindingRefSchema())
                    put("matchingCount", buildJsonObject { put("type", "integer"); put("minimum", 0) })
                },
            ),
        )
        put(
            A2uiCatalogIds.V2.VISIT_REQUEST_FORM,
            componentSchema(
                required = listOf(
                    "propertyId", "propertyTitle", "availableDates",
                    "dateBinding", "timeSlotBinding", "visitModeBinding",
                    "visitorNameBinding", "visitorEmailBinding", "notesBinding",
                    "explicitConfirmedBinding", "statusBinding",
                ),
                properties = buildJsonObject {
                    put("propertyId", stringType())
                    put("propertyTitle", stringType())
                    put("availableDates", buildJsonObject { put("type", "array"); put("minItems", 1) })
                    put("dateBinding", bindingRefSchema())
                    put("timeSlotBinding", bindingRefSchema())
                    put("visitModeBinding", bindingRefSchema())
                    put("visitorNameBinding", bindingRefSchema())
                    put("visitorEmailBinding", bindingRefSchema())
                    put("notesBinding", bindingRefSchema())
                    put("explicitConfirmedBinding", bindingRefSchema())
                    put("statusBinding", bindingRefSchema())
                    put("agencyNote", stringType())
                },
            ),
        )
    }

    private fun componentSchema(required: List<String>, properties: JsonObject): JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("properties", properties)
    }

    private fun stringType(): JsonObject = buildJsonObject { put("type", "string") }
    private fun boolType(): JsonObject = buildJsonObject { put("type", "boolean") }
    private fun positiveIntType(): JsonObject = buildJsonObject { put("type", "integer"); put("minimum", 1) }

    private fun bindingRefSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("required", buildJsonArray { add(JsonPrimitive("path")) })
        put("properties", buildJsonObject { put("path", stringType()) })
        put("additionalProperties", false)
    }
}
