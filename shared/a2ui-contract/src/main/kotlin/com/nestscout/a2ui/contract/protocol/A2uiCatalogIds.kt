package com.nestscout.a2ui.contract.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class CatalogVersion {
    V1,
    V2,
}

object A2uiCatalogIds {
    const val PROTOCOL_VERSION = "0.9"
    const val BASIC_M3_CATALOG_ID = "https://a2ui.org/specification/v0_9/standard_catalog.json"
    const val NESTSCOUT_V1_CATALOG_ID = "urn:nestscout:catalog:v1"
    const val NESTSCOUT_V2_CATALOG_ID = "urn:nestscout:catalog:v2"

    object V1 {
        const val PROPERTY_CARD = "urn:nestscout:v1:PropertyCard"
        const val PROPERTY_COMPARISON_TABLE = "urn:nestscout:v1:PropertyComparisonTable"
        const val FILTER_REFINE_SHEET = "urn:nestscout:v1:FilterRefineSheet"
        const val VISIT_REQUEST_FORM = "urn:nestscout:v1:VisitRequestForm"

        val allComponents: Set<String> = setOf(
            PROPERTY_CARD,
            PROPERTY_COMPARISON_TABLE,
            FILTER_REFINE_SHEET,
            VISIT_REQUEST_FORM,
        )
    }

    object V2 {
        const val PROPERTY_CARD = "urn:nestscout:v2:PropertyCard"
        const val PROPERTY_COMPARISON_TABLE = "urn:nestscout:v2:PropertyComparisonTable"
        const val FILTER_REFINE_SHEET = "urn:nestscout:v2:FilterRefineSheet"
        const val VISIT_REQUEST_FORM = "urn:nestscout:v2:VisitRequestForm"

        val allComponents: Set<String> = setOf(
            PROPERTY_CARD,
            PROPERTY_COMPARISON_TABLE,
            FILTER_REFINE_SHEET,
            VISIT_REQUEST_FORM,
        )
    }
}
