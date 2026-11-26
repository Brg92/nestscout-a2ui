package com.nestscout.a2ui.server.planner

import com.nestscout.a2ui.contract.protocol.UiStreamRequest
import com.nestscout.a2ui.server.security.NegotiatedCatalog
import kotlinx.serialization.Serializable

@Serializable
enum class PlannedIntent {
    SEARCH_RESULTS,
    COMPARISON,
    FILTER_REFINE,
    VISIT_FORM,
    VISIT_CONFIRMED,
}

@Serializable
data class PlannedPropertySelection(
    val propertyId: String,
    val matchScore: Int = 85,
)

/**
 * Non-factual UI orchestration plan produced for a single interaction turn.
 * Factual listing fields are never carried here and are always hydrated from PropertyRepository.
 */
@Serializable
data class UiTurnPlan(
    val surfaceId: String,
    val intent: PlannedIntent,
    val summaryBanner: String,
    val selections: List<PlannedPropertySelection> = emptyList(),
    val showFilterSheet: Boolean = false,
    val winnerPropertyId: String? = null,
    val targetVisitPropertyId: String? = null,
    val agencyNote: String? = null,
    val confirmedVisitDate: String? = null,
)

class PlannerUpstreamException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

interface UiPlanner {
    suspend fun plan(
        request: UiStreamRequest,
        negotiated: NegotiatedCatalog,
    ): UiTurnPlan
}
