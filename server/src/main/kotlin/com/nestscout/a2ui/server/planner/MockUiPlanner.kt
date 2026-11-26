package com.nestscout.a2ui.server.planner

import com.nestscout.a2ui.contract.protocol.UiActionEvent
import com.nestscout.a2ui.contract.protocol.UiStreamRequest
import com.nestscout.a2ui.contract.protocol.VisitRequestSubmission
import com.nestscout.a2ui.contract.schema.ContractSchemaValidator
import com.nestscout.a2ui.contract.schema.ValidationResult
import com.nestscout.a2ui.server.repository.PropertyRepository
import com.nestscout.a2ui.server.security.NegotiatedCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Deterministic local UI planner enabled when A2UI_MOCK_MODE=true.
 */
class MockUiPlanner(
    private val repository: PropertyRepository,
) : UiPlanner {

    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
    }

    override suspend fun plan(
        request: UiStreamRequest,
        negotiated: NegotiatedCatalog,
    ): UiTurnPlan {
        request.actionEvent?.let { action ->
            return resolveActionEvent(request, action, negotiated)
        }

        val normalizedQuery = request.query.trim().lowercase()
        val explicitPropertyIds = extractExplicitPropertyIds(normalizedQuery)
        val ranked = repository.searchScored(request.criteria, request.query)

        if (isComparisonQuery(normalizedQuery)) {
            val candidateIds = if (explicitPropertyIds.size >= 2) {
                explicitPropertyIds.take(negotiated.maxComparisonItems)
            } else {
                val base = ranked.ifEmpty { repository.searchScored(request.criteria) }
                base.take(negotiated.maxComparisonItems).map { it.listing.id }
            }
            val selections = candidateIds.map { id ->
                val score = ranked.find { it.listing.id == id }?.matchScore ?: 88
                PlannedPropertySelection(propertyId = id, matchScore = score)
            }
            return UiTurnPlan(
                surfaceId = SURFACE_COMPARISON,
                intent = PlannedIntent.COMPARISON,
                summaryBanner = "Confronto certificato su ${selections.size} immobili selezionati",
                selections = selections,
            )
        }

        if (isVisitQuery(normalizedQuery)) {
            val targetId = explicitPropertyIds.firstOrNull()
                ?: ranked.firstOrNull()?.listing?.id
                ?: repository.findAll().first().id
            val listing = repository.findById(targetId) ?: repository.findAll().first()
            return UiTurnPlan(
                surfaceId = SURFACE_VISIT,
                intent = PlannedIntent.VISIT_FORM,
                summaryBanner = "Prenotazione visita per ${listing.title} (${listing.neighborhood})",
                selections = listOf(PlannedPropertySelection(propertyId = listing.id, matchScore = 95)),
                targetVisitPropertyId = listing.id,
                agencyNote = "Conferma esplicita richiesta prima dell'invio all'ufficio visite.",
            )
        }

        if (isFilterQuery(normalizedQuery)) {
            val matches = ranked.ifEmpty { repository.searchScored(request.criteria) }
            return UiTurnPlan(
                surfaceId = SURFACE_SEARCH,
                intent = PlannedIntent.FILTER_REFINE,
                summaryBanner = "${matches.size} immobili disponibili con i filtri correnti",
                selections = matches.take(MAX_SEARCH_CARDS).map {
                    PlannedPropertySelection(propertyId = it.listing.id, matchScore = it.matchScore)
                },
                showFilterSheet = true,
            )
        }

        if (ranked.isEmpty()) {
            return UiTurnPlan(
                surfaceId = SURFACE_SEARCH,
                intent = PlannedIntent.SEARCH_RESULTS,
                summaryBanner = "Nessun immobile corrisponde esattamente ai criteri: prova ad ampliare i filtri",
                selections = emptyList(),
                showFilterSheet = true,
            )
        }

        val topSelections = ranked.take(MAX_SEARCH_CARDS).map {
            PlannedPropertySelection(propertyId = it.listing.id, matchScore = it.matchScore)
        }

        return UiTurnPlan(
            surfaceId = SURFACE_SEARCH,
            intent = PlannedIntent.SEARCH_RESULTS,
            summaryBanner = "Trovati ${ranked.size} appartamenti certificati a Milano",
            selections = topSelections,
            showFilterSheet = false,
        )
    }

    fun resolveActionEvent(
        request: UiStreamRequest,
        action: UiActionEvent,
        negotiated: NegotiatedCatalog,
    ): UiTurnPlan {
        return when (action.actionName.trim().lowercase()) {
            "open_filters", "refine_filters" -> {
                val matches = repository.searchScored(request.criteria, request.query)
                UiTurnPlan(
                    surfaceId = action.surfaceId.ifBlank { SURFACE_SEARCH },
                    intent = PlannedIntent.FILTER_REFINE,
                    summaryBanner = "${matches.size} immobili corrispondenti ai criteri attivi",
                    selections = matches.take(MAX_SEARCH_CARDS).map {
                        PlannedPropertySelection(propertyId = it.listing.id, matchScore = it.matchScore)
                    },
                    showFilterSheet = true,
                )
            }

            "compare", "compare_properties" -> {
                val requestedIds = (action.payload["propertyIds"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.let { repository.findByIds(it) }
                    ?.map { it.id }
                    .orEmpty()

                val effectiveIds = requestedIds.ifEmpty {
                    repository.searchScored(request.criteria, request.query)
                        .take(negotiated.maxComparisonItems)
                        .map { it.listing.id }
                }.take(negotiated.maxComparisonItems)

                require(effectiveIds.isNotEmpty()) { "No valid propertyIds available for comparison" }

                UiTurnPlan(
                    surfaceId = SURFACE_COMPARISON,
                    intent = PlannedIntent.COMPARISON,
                    summaryBanner = "Confronto su ${effectiveIds.size} immobili certificati",
                    selections = effectiveIds.map { PlannedPropertySelection(propertyId = it, matchScore = 90) },
                )
            }

            "request_visit", "open_visit_form" -> {
                val requestedId = action.payload["propertyId"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("Missing propertyId in request_visit action payload")
                val listing = repository.findById(requestedId)
                    ?: throw IllegalArgumentException("Unknown propertyId: $requestedId")

                UiTurnPlan(
                    surfaceId = SURFACE_VISIT,
                    intent = PlannedIntent.VISIT_FORM,
                    summaryBanner = "Richiesta visita per ${listing.title}",
                    selections = listOf(PlannedPropertySelection(propertyId = listing.id, matchScore = 96)),
                    targetVisitPropertyId = listing.id,
                    agencyNote = "Seleziona data e fascia oraria disponibili dal calendario certificato.",
                )
            }

            "submit_visit" -> {
                val submission = strictJson.decodeFromJsonElement(
                    VisitRequestSubmission.serializer(),
                    action.payload,
                )
                val validation = ContractSchemaValidator.validateVisitSubmission(submission)
                if (validation is ValidationResult.Invalid) {
                    throw IllegalArgumentException(
                        "Invalid visit submission: ${validation.errors.joinToString("; ")}",
                    )
                }

                val listing = repository.findById(submission.propertyId)
                    ?: throw IllegalArgumentException("Unknown propertyId: ${submission.propertyId}")
                if (submission.preferredDate !in listing.availableVisitDates) {
                    throw IllegalArgumentException(
                        "Date ${submission.preferredDate} is not available for ${listing.id}",
                    )
                }

                UiTurnPlan(
                    surfaceId = SURFACE_VISIT,
                    intent = PlannedIntent.VISIT_CONFIRMED,
                    summaryBanner = "Visita confermata per ${listing.title} il ${submission.preferredDate}",
                    selections = listOf(PlannedPropertySelection(propertyId = listing.id, matchScore = 98)),
                    targetVisitPropertyId = listing.id,
                    confirmedVisitDate = submission.preferredDate,
                )
            }

            else -> throw IllegalArgumentException("Unsupported UI action: ${action.actionName}")
        }
    }

    private fun extractExplicitPropertyIds(query: String): List<String> =
        APT_ID_REGEX.findAll(query)
            .map { it.value.lowercase() }
            .distinct()
            .filter { repository.findById(it) != null }
            .toList()

    private fun isComparisonQuery(query: String): Boolean =
        COMPARISON_KEYWORDS.any { query.contains(it) }

    private fun isVisitQuery(query: String): Boolean =
        VISIT_KEYWORDS.any { query.contains(it) }

    private fun isFilterQuery(query: String): Boolean =
        FILTER_KEYWORDS.any { query.contains(it) }

    companion object {
        const val SURFACE_SEARCH = "search-results"
        const val SURFACE_COMPARISON = "property-comparison"
        const val SURFACE_VISIT = "visit-request"
        const val MAX_SEARCH_CARDS = 6

        private val APT_ID_REGEX = Regex("\\bapt-\\d{2}\\b", RegexOption.IGNORE_CASE)
        private val COMPARISON_KEYWORDS = listOf("confronta", "confronto", "paragona", "differenze")
        private val VISIT_KEYWORDS = listOf("visita", "prenota", "appuntamento", "visitare")
        private val FILTER_KEYWORDS = listOf("filtri", "filtro", "affina", "raffina")
    }
}
