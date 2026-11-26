package com.nestscout.a2ui.server.error

import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import com.nestscout.a2ui.contract.protocol.A2uiComponentNode
import com.nestscout.a2ui.contract.protocol.CatalogVersion
import com.nestscout.a2ui.contract.protocol.CorrectionStrategy
import com.nestscout.a2ui.contract.protocol.UiErrorCode
import com.nestscout.a2ui.contract.protocol.UiErrorReport
import com.nestscout.a2ui.contract.protocol.UiErrorResolution
import com.nestscout.a2ui.server.ServerJson
import com.nestscout.a2ui.server.grounding.GroundingHydrator
import com.nestscout.a2ui.server.repository.PropertyRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class SurfaceComponentContext(
    val componentKind: ComponentKind,
    val propertyIds: List<String>,
    val matchingCount: Int = 0,
)

enum class ComponentKind {
    PROPERTY_CARD,
    COMPARISON_TABLE,
    FILTER_SHEET,
    VISIT_FORM,
}

class SelfCorrectionService(
    private val repository: PropertyRepository,
    private val hydrator: GroundingHydrator,
    private val maxRetriesPerTurn: Int = DEFAULT_MAX_RETRIES_PER_TURN,
) {
    private val attemptCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val surfaceContexts = ConcurrentHashMap<String, Map<String, SurfaceComponentContext>>()

    fun registerTurnContext(
        sessionId: String,
        surfaceId: String,
        components: Map<String, SurfaceComponentContext>,
    ) {
        val surfaceKey = surfaceKey(sessionId, surfaceId)
        surfaceContexts[surfaceKey] = components
        val prefix = "$surfaceKey|"
        attemptCounters.keys.removeIf { it.startsWith(prefix) }
    }

    fun resolve(report: UiErrorReport): UiErrorResolution {
        require(report.sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(report.surfaceId.isNotBlank()) { "surfaceId must not be blank" }
        require(report.componentId.isNotBlank()) { "componentId must not be blank" }
        require(report.attempt >= 1) { "attempt must be >= 1" }

        val counterKey = "${surfaceKey(report.sessionId, report.surfaceId)}|${report.componentId}"
        val recordedAttempts = attemptCounters
            .computeIfAbsent(counterKey) { AtomicInteger(0) }
            .incrementAndGet()

        val effectiveAttempt = maxOf(report.attempt, recordedAttempts)
        if (effectiveAttempt > maxRetriesPerTurn) {
            return UiErrorResolution(
                accepted = false,
                strategy = CorrectionStrategy.ABORT_RETRY_BUDGET_EXCEEDED,
                patchPayload = null,
                fallbackMessage = "Retry budget exceeded ($maxRetriesPerTurn attempts). Use SafeComponentFallbackCard.",
            )
        }

        val context = lookupContext(report.sessionId, report.surfaceId, report.componentId)
        val isV2Catalog = report.catalogId == A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID

        if (effectiveAttempt == 2) {
            if (isV2Catalog && context != null) {
                val downgraded = buildGroundedNode(report.componentId, context, CatalogVersion.V1)
                if (downgraded != null &&
                    report.errorCode != UiErrorCode.UNKNOWN_COMPONENT &&
                    report.errorCode != UiErrorCode.RENDER_EXCEPTION
                ) {
                    return UiErrorResolution(
                        accepted = true,
                        strategy = CorrectionStrategy.DOWNGRADE_TO_V1,
                        patchPayload = wrapPatchPayload(A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID, downgraded),
                        fallbackMessage = "Downgraded component ${report.componentId} to v1 after first retry.",
                    )
                }
            }
            return basicCatalogFallback(report)
        }

        return when (report.errorCode) {
            UiErrorCode.UNKNOWN_COMPONENT,
            UiErrorCode.RENDER_EXCEPTION,
            -> {
                if (isV2Catalog && context != null) {
                    val downgradedNode = buildGroundedNode(report.componentId, context, CatalogVersion.V1)
                    if (downgradedNode != null) {
                        return UiErrorResolution(
                            accepted = true,
                            strategy = CorrectionStrategy.DOWNGRADE_TO_V1,
                            patchPayload = wrapPatchPayload(A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID, downgradedNode),
                            fallbackMessage = "Downgraded ${report.componentId} from v2 to v1 catalog.",
                        )
                    }
                }
                basicCatalogFallback(report)
            }

            UiErrorCode.SCHEMA_VALIDATION_FAILED,
            UiErrorCode.UNSAFE_URL_BLOCKED,
            UiErrorCode.INVALID_DATA_BINDING,
            -> {
                val targetVersion = if (isV2Catalog && report.errorCode != UiErrorCode.INVALID_DATA_BINDING) {
                    CatalogVersion.V2
                } else {
                    CatalogVersion.V1
                }
                val targetCatalogId = if (targetVersion == CatalogVersion.V2) {
                    A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID
                } else {
                    A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID
                }

                if (context != null) {
                    val patchedNode = buildGroundedNode(report.componentId, context, targetVersion)
                    if (patchedNode != null) {
                        val strategy = if (isV2Catalog && targetVersion == CatalogVersion.V1) {
                            CorrectionStrategy.DOWNGRADE_TO_V1
                        } else {
                            CorrectionStrategy.PATCH_COMPONENT
                        }
                        return UiErrorResolution(
                            accepted = true,
                            strategy = strategy,
                            patchPayload = wrapPatchPayload(targetCatalogId, patchedNode),
                            fallbackMessage = "Re-hydrated ${report.componentId} from certified dataset.",
                        )
                    }
                }
                basicCatalogFallback(report)
            }
        }
    }

    private fun lookupContext(
        sessionId: String,
        surfaceId: String,
        componentId: String,
    ): SurfaceComponentContext? {
        surfaceContexts[surfaceKey(sessionId, surfaceId)]?.get(componentId)?.let { return it }

        val inferredPropertyId = when {
            componentId.startsWith("card-") -> componentId.removePrefix("card-")
            repository.findById(componentId) != null -> componentId
            else -> null
        }

        if (inferredPropertyId != null && repository.findById(inferredPropertyId) != null) {
            return SurfaceComponentContext(
                componentKind = ComponentKind.PROPERTY_CARD,
                propertyIds = listOf(inferredPropertyId),
            )
        }

        if (componentId == "filter-sheet") {
            return SurfaceComponentContext(
                componentKind = ComponentKind.FILTER_SHEET,
                propertyIds = emptyList(),
                matchingCount = repository.findAll().size,
            )
        }

        return null
    }

    private fun buildGroundedNode(
        componentId: String,
        context: SurfaceComponentContext,
        version: CatalogVersion,
    ): A2uiComponentNode? = when (context.componentKind) {
        ComponentKind.PROPERTY_CARD -> {
            val propId = context.propertyIds.firstOrNull() ?: return null
            hydrator.buildPropertyCardNode(
                nodeId = componentId,
                propertyId = propId,
                version = version,
                matchScore = 88,
            )
        }
        ComponentKind.COMPARISON_TABLE -> hydrator.buildComparisonNode(
            nodeId = componentId,
            propertyIds = context.propertyIds,
            version = version,
        )
        ComponentKind.FILTER_SHEET -> hydrator.buildFilterSheetNode(
            nodeId = componentId,
            version = version,
            matchingCount = context.matchingCount,
        )
        ComponentKind.VISIT_FORM -> {
            val propId = context.propertyIds.firstOrNull() ?: return null
            hydrator.buildVisitFormNode(
                nodeId = componentId,
                propertyId = propId,
                version = version,
            )
        }
    }

    private fun basicCatalogFallback(report: UiErrorReport): UiErrorResolution {
        val context = lookupContext(report.sessionId, report.surfaceId, report.componentId)
        val listingSummary = context?.propertyIds?.firstOrNull()
            ?.let { repository.findById(it) }
            ?.let { "${it.title} (${it.neighborhood}) - EUR ${it.priceEur}/mese, ${it.sqm} mq" }
            ?: "Elemento UI sostituito con vista base verificata."

        val basicNode = A2uiComponentNode(
            id = report.componentId,
            component = mapOf(
                "Card" to buildJsonObject {
                    put("title", JsonPrimitive(" Scheda verificata NestScout".trim()))
                    put("body", JsonPrimitive(listingSummary))
                },
            ),
        )

        return UiErrorResolution(
            accepted = true,
            strategy = CorrectionStrategy.FALLBACK_TO_BASIC_CATALOG,
            patchPayload = wrapPatchPayload(A2uiCatalogIds.BASIC_M3_CATALOG_ID, basicNode),
            fallbackMessage = listingSummary,
        )
    }

    private fun wrapPatchPayload(
        catalogId: String,
        node: A2uiComponentNode,
    ): JsonObject = buildJsonObject {
        put("catalogId", JsonPrimitive(catalogId))
        put("componentNode", ServerJson.encodeToJsonElement(A2uiComponentNode.serializer(), node))
    }

    private fun surfaceKey(sessionId: String, surfaceId: String): String = "$sessionId::$surfaceId"

    companion object {
        const val DEFAULT_MAX_RETRIES_PER_TURN = 2
    }
}
