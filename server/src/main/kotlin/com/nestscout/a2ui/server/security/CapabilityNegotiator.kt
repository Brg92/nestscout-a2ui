package com.nestscout.a2ui.server.security

import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import com.nestscout.a2ui.contract.protocol.CatalogVersion
import com.nestscout.a2ui.contract.protocol.ClientCapabilities

sealed interface NegotiatedCatalog {
    val catalogId: String
    val maxComparisonItems: Int
    val downgradedFromV2: Boolean

    data class CustomCatalog(
        override val catalogId: String,
        val version: CatalogVersion,
        override val maxComparisonItems: Int,
        override val downgradedFromV2: Boolean,
    ) : NegotiatedCatalog

    data class BasicFallback(
        override val catalogId: String = A2uiCatalogIds.BASIC_M3_CATALOG_ID,
        override val maxComparisonItems: Int = 1,
        override val downgradedFromV2: Boolean = true,
    ) : NegotiatedCatalog
}

class CapabilityNegotiator {

    fun negotiate(capabilities: ClientCapabilities): NegotiatedCatalog {
        if (capabilities.supportsV2()) {
            return NegotiatedCatalog.CustomCatalog(
                catalogId = A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID,
                version = CatalogVersion.V2,
                maxComparisonItems = capabilities.maxComparisonItems.coerceIn(1, V2_MAX_COMPARISON_ITEMS),
                downgradedFromV2 = false,
            )
        }

        if (capabilities.supportsV1()) {
            return NegotiatedCatalog.CustomCatalog(
                catalogId = A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID,
                version = CatalogVersion.V1,
                maxComparisonItems = capabilities.maxComparisonItems.coerceIn(1, V1_MAX_COMPARISON_ITEMS),
                downgradedFromV2 = true,
            )
        }

        return NegotiatedCatalog.BasicFallback()
    }

    companion object {
        const val V1_MAX_COMPARISON_ITEMS = 2
        const val V2_MAX_COMPARISON_ITEMS = 3
    }
}
