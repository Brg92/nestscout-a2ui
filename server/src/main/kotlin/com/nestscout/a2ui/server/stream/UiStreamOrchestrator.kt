package com.nestscout.a2ui.server.stream

import com.nestscout.a2ui.contract.catalog.SubcomponentLoadingState
import com.nestscout.a2ui.contract.catalog.VisitSubmissionStatus
import com.nestscout.a2ui.contract.domain.VisitMode
import com.nestscout.a2ui.contract.domain.VisitTimeSlot
import com.nestscout.a2ui.contract.protocol.A2uiComponentNode
import com.nestscout.a2ui.contract.protocol.A2uiWireEvent
import com.nestscout.a2ui.contract.protocol.CatalogVersion
import com.nestscout.a2ui.contract.protocol.UiStreamRequest
import com.nestscout.a2ui.server.error.ComponentKind
import com.nestscout.a2ui.server.error.SelfCorrectionService
import com.nestscout.a2ui.server.error.SurfaceComponentContext
import com.nestscout.a2ui.server.grounding.GroundingHydrator
import com.nestscout.a2ui.server.planner.PlannedIntent
import com.nestscout.a2ui.server.planner.UiPlanner
import com.nestscout.a2ui.server.planner.UiTurnPlan
import com.nestscout.a2ui.server.repository.PropertyRepository
import com.nestscout.a2ui.server.security.CapabilityNegotiator
import com.nestscout.a2ui.server.security.NegotiatedCatalog
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class PreparedUiStream(
    val surfaceId: String,
    val catalogId: String,
    val events: List<A2uiWireEvent>,
)

class UiStreamOrchestrator(
    private val repository: PropertyRepository,
    private val capabilityNegotiator: CapabilityNegotiator,
    private val planner: UiPlanner,
    private val hydrator: GroundingHydrator,
    private val selfCorrectionService: SelfCorrectionService,
) {

    suspend fun prepareStream(request: UiStreamRequest): PreparedUiStream {
        require(request.sessionId.isNotBlank()) { "sessionId must not be blank" }

        val negotiated = capabilityNegotiator.negotiate(request.capabilities)
        val plan = planner.plan(request, negotiated)
        val matchingCount = repository.search(request.criteria).size

        val componentContexts = mutableMapOf<String, SurfaceComponentContext>()
        val events = mutableListOf<A2uiWireEvent>()

        events += A2uiWireEvent.CreateSurface(
            surfaceId = plan.surfaceId,
            catalogId = negotiated.catalogId,
            rootComponentId = ROOT_ID,
        )

        when (negotiated) {
            is NegotiatedCatalog.CustomCatalog -> {
                val skeletonNodes = buildSkeletonFrameIfApplicable(plan, negotiated.version)
                if (skeletonNodes != null) {
                    events += A2uiWireEvent.UpdateComponents(
                        surfaceId = plan.surfaceId,
                        components = skeletonNodes,
                    )
                }

                val hydratedNodes = buildCustomHydratedNodes(
                    plan = plan,
                    version = negotiated.version,
                    maxComparisonItems = negotiated.maxComparisonItems,
                    matchingCount = matchingCount,
                    contextSink = componentContexts,
                )
                events += A2uiWireEvent.UpdateComponents(
                    surfaceId = plan.surfaceId,
                    components = hydratedNodes,
                )
            }

            is NegotiatedCatalog.BasicFallback -> {
                val basicNodes = buildBasicFallbackNodes(plan)
                events += A2uiWireEvent.UpdateComponents(
                    surfaceId = plan.surfaceId,
                    components = basicNodes,
                )
            }
        }

        events += A2uiWireEvent.UpdateDataModel(
            surfaceId = plan.surfaceId,
            path = "/",
            contents = buildInitialDataModel(request, plan),
        )

        selfCorrectionService.registerTurnContext(
            sessionId = request.sessionId,
            surfaceId = plan.surfaceId,
            components = componentContexts,
        )

        return PreparedUiStream(
            surfaceId = plan.surfaceId,
            catalogId = negotiated.catalogId,
            events = events,
        )
    }

    private fun buildSkeletonFrameIfApplicable(
        plan: UiTurnPlan,
        version: CatalogVersion,
    ): List<A2uiComponentNode>? {
        if (plan.intent != PlannedIntent.SEARCH_RESULTS || plan.selections.isEmpty()) {
            return null
        }

        val skeletonCards = plan.selections.mapNotNull { selection ->
            hydrator.buildPropertyCardNode(
                nodeId = "card-${selection.propertyId}",
                propertyId = selection.propertyId,
                version = version,
                matchScore = selection.matchScore,
                isSkeleton = true,
                subcomponentLoading = SubcomponentLoadingState(
                    image = true,
                    price = true,
                    matchScore = true,
                ),
            )
        }
        if (skeletonCards.isEmpty()) return null

        val banner = buildTextNode(BANNER_ID, plan.summaryBanner)
        val root = buildColumnNode(
            nodeId = ROOT_ID,
            childIds = listOf(BANNER_ID) + skeletonCards.map { it.id },
        )
        return listOf(root, banner) + skeletonCards
    }

    private fun buildCustomHydratedNodes(
        plan: UiTurnPlan,
        version: CatalogVersion,
        maxComparisonItems: Int,
        matchingCount: Int,
        contextSink: MutableMap<String, SurfaceComponentContext>,
    ): List<A2uiComponentNode> {
        val childNodes = mutableListOf<A2uiComponentNode>()
        childNodes += buildTextNode(BANNER_ID, plan.summaryBanner)

        when (plan.intent) {
            PlannedIntent.SEARCH_RESULTS,
            PlannedIntent.FILTER_REFINE,
            -> {
                if (plan.showFilterSheet || plan.intent == PlannedIntent.FILTER_REFINE) {
                    childNodes += hydrator.buildFilterSheetNode(
                        nodeId = FILTER_SHEET_ID,
                        version = version,
                        matchingCount = matchingCount,
                    )
                    contextSink[FILTER_SHEET_ID] = SurfaceComponentContext(
                        componentKind = ComponentKind.FILTER_SHEET,
                        propertyIds = emptyList(),
                        matchingCount = matchingCount,
                    )
                }

                plan.selections.forEach { selection ->
                    val nodeId = "card-${selection.propertyId}"
                    val cardNode = hydrator.buildPropertyCardNode(
                        nodeId = nodeId,
                        propertyId = selection.propertyId,
                        version = version,
                        matchScore = selection.matchScore,
                        isSkeleton = false,
                    )
                    if (cardNode != null) {
                        childNodes += cardNode
                        contextSink[nodeId] = SurfaceComponentContext(
                            componentKind = ComponentKind.PROPERTY_CARD,
                            propertyIds = listOf(selection.propertyId),
                        )
                    }
                }
            }

            PlannedIntent.COMPARISON -> {
                val requestedIds = plan.selections.map { it.propertyId }
                val compNode = hydrator.buildComparisonNode(
                    nodeId = COMPARISON_TABLE_ID,
                    propertyIds = requestedIds,
                    version = version,
                    maxItems = maxComparisonItems,
                    requestedWinnerId = plan.winnerPropertyId,
                )
                if (compNode != null) {
                    childNodes += compNode
                    contextSink[COMPARISON_TABLE_ID] = SurfaceComponentContext(
                        componentKind = ComponentKind.COMPARISON_TABLE,
                        propertyIds = requestedIds.take(maxComparisonItems),
                    )
                }
            }

            PlannedIntent.VISIT_FORM -> {
                val targetId = plan.targetVisitPropertyId ?: plan.selections.firstOrNull()?.propertyId
                if (targetId != null) {
                    val formNode = hydrator.buildVisitFormNode(
                        nodeId = VISIT_FORM_ID,
                        propertyId = targetId,
                        version = version,
                        agencyNote = plan.agencyNote,
                    )
                    if (formNode != null) {
                        childNodes += formNode
                        contextSink[VISIT_FORM_ID] = SurfaceComponentContext(
                            componentKind = ComponentKind.VISIT_FORM,
                            propertyIds = listOf(targetId),
                        )
                    }
                }
            }

            PlannedIntent.VISIT_CONFIRMED -> {
                val targetId = plan.targetVisitPropertyId ?: plan.selections.firstOrNull()?.propertyId
                if (targetId != null) {
                    val cardId = "card-$targetId"
                    val cardNode = hydrator.buildPropertyCardNode(
                        nodeId = cardId,
                        propertyId = targetId,
                        version = version,
                        matchScore = 98,
                        isSkeleton = false,
                    )
                    if (cardNode != null) {
                        childNodes += cardNode
                        contextSink[cardId] = SurfaceComponentContext(
                            componentKind = ComponentKind.PROPERTY_CARD,
                            propertyIds = listOf(targetId),
                        )
                    }
                }
            }
        }

        val root = buildColumnNode(
            nodeId = ROOT_ID,
            childIds = childNodes.map { it.id },
        )
        return listOf(root) + childNodes
    }

    private fun buildBasicFallbackNodes(plan: UiTurnPlan): List<A2uiComponentNode> {
        val childNodes = mutableListOf<A2uiComponentNode>()
        childNodes += buildTextNode(BANNER_ID, plan.summaryBanner)

        val ids = plan.selections.map { it.propertyId }
            .ifEmpty { listOfNotNull(plan.targetVisitPropertyId) }

        repository.findByIds(ids).forEach { listing ->
            val nodeId = "basic-card-${listing.id}"
            childNodes += A2uiComponentNode(
                id = nodeId,
                component = mapOf(
                    "Card" to buildJsonObject {
                        put("title", JsonPrimitive(listing.title))
                        put(
                            "body",
                            JsonPrimitive(
                                "${listing.neighborhood} - EUR ${listing.priceEur}/mese - ${listing.sqm} mq (${listing.rooms} locali)",
                            ),
                        )
                    },
                ),
            )
        }

        val root = buildColumnNode(
            nodeId = ROOT_ID,
            childIds = childNodes.map { it.id },
        )
        return listOf(root) + childNodes
    }

    private fun buildInitialDataModel(
        request: UiStreamRequest,
        plan: UiTurnPlan,
    ): JsonObject {
        val criteria = request.criteria
        val visitTarget = (plan.targetVisitPropertyId ?: plan.selections.firstOrNull()?.propertyId)
            ?.let { repository.findById(it) }
        val defaultDate = plan.confirmedVisitDate
            ?: visitTarget?.availableVisitDates?.firstOrNull()
            ?: ""
        val visitStatus = if (plan.intent == PlannedIntent.VISIT_CONFIRMED) {
            VisitSubmissionStatus.CONFIRMED.name
        } else {
            VisitSubmissionStatus.IDLE.name
        }

        return buildJsonObject {
            put(
                "favorites",
                buildJsonObject {
                    plan.selections.forEach { sel ->
                        put(sel.propertyId, JsonPrimitive(false))
                    }
                },
            )
            put(
                "filters",
                buildJsonObject {
                    put("maxPriceEur", criteria.maxPriceEur?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("minRooms", criteria.minRooms?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("neighborhood", criteria.neighborhood?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("minSqm", criteria.minSqm?.let { JsonPrimitive(it) } ?: JsonNull)
                    put(
                        "energyClasses",
                        buildJsonArray { criteria.energyClasses.forEach { add(JsonPrimitive(it.name)) } },
                    )
                    put(
                        "amenities",
                        buildJsonArray { criteria.amenities.forEach { add(JsonPrimitive(it.name)) } },
                    )
                    put("sortBy", JsonPrimitive(criteria.sortBy.name))
                },
            )
            put(
                "comparison",
                buildJsonObject {
                    put("showOnlyDiff", JsonPrimitive(false))
                },
            )
            put(
                "visitForm",
                buildJsonObject {
                    put("preferredDate", JsonPrimitive(defaultDate))
                    put("timeSlot", JsonPrimitive(VisitTimeSlot.MORNING.name))
                    put("visitMode", JsonPrimitive(VisitMode.IN_PERSON.name))
                    put("visitorName", JsonPrimitive(""))
                    put("visitorEmail", JsonPrimitive(""))
                    put("notes", JsonPrimitive(""))
                    put("explicitConfirmed", JsonPrimitive(plan.intent == PlannedIntent.VISIT_CONFIRMED))
                    put("status", JsonPrimitive(visitStatus))
                },
            )
        }
    }

    private fun buildTextNode(nodeId: String, text: String): A2uiComponentNode = A2uiComponentNode(
        id = nodeId,
        component = mapOf(
            "Text" to buildJsonObject {
                put("text", JsonPrimitive(text))
            },
        ),
    )

    private fun buildColumnNode(nodeId: String, childIds: List<String>): A2uiComponentNode = A2uiComponentNode(
        id = nodeId,
        component = mapOf(
            "Column" to buildJsonObject {
                put(
                    "children",
                    buildJsonArray {
                        childIds.forEach { add(JsonPrimitive(it)) }
                    },
                )
            },
        ),
    )

    companion object {
        const val ROOT_ID = "root"
        const val BANNER_ID = "summary-banner"
        const val FILTER_SHEET_ID = "filter-sheet"
        const val COMPARISON_TABLE_ID = "comparison-table"
        const val VISIT_FORM_ID = "visit-form"
    }
}
