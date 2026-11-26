package com.nestscout.a2ui.contract.protocol

import com.nestscout.a2ui.contract.domain.SearchCriteria
import com.nestscout.a2ui.contract.domain.VisitMode
import com.nestscout.a2ui.contract.domain.VisitTimeSlot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ClientCapabilities(
    val protocolVersion: String = A2uiCatalogIds.PROTOCOL_VERSION,
    val supportedCatalogIds: Set<String> = setOf(
        A2uiCatalogIds.BASIC_M3_CATALOG_ID,
        A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID,
        A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID,
    ),
    val maxComparisonItems: Int = 3,
) {
    fun supportsV2(): Boolean = supportedCatalogIds.contains(A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID)
    fun supportsV1(): Boolean = supportedCatalogIds.contains(A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID)
}

@Serializable
data class UiActionEvent(
    val surfaceId: String,
    val componentId: String,
    val actionName: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class UiStreamRequest(
    val sessionId: String,
    val query: String,
    val criteria: SearchCriteria = SearchCriteria(),
    val capabilities: ClientCapabilities = ClientCapabilities(),
    val actionEvent: UiActionEvent? = null,
)

@Serializable
enum class UiErrorCode {
    UNKNOWN_COMPONENT,
    SCHEMA_VALIDATION_FAILED,
    UNSAFE_URL_BLOCKED,
    INVALID_DATA_BINDING,
    RENDER_EXCEPTION,
}

@Serializable
data class UiErrorReport(
    val sessionId: String,
    val surfaceId: String,
    val componentId: String,
    val catalogId: String,
    val errorCode: UiErrorCode,
    val message: String,
    val attempt: Int = 1,
)

@Serializable
enum class CorrectionStrategy {
    PATCH_COMPONENT,
    DOWNGRADE_TO_V1,
    FALLBACK_TO_BASIC_CATALOG,
    ABORT_RETRY_BUDGET_EXCEEDED,
}

@Serializable
data class UiErrorResolution(
    val accepted: Boolean,
    val strategy: CorrectionStrategy,
    val patchPayload: JsonObject? = null,
    val fallbackMessage: String? = null,
)

@Serializable
data class VisitRequestSubmission(
    val propertyId: String,
    val preferredDate: String,
    val timeSlot: VisitTimeSlot,
    val visitMode: VisitMode = VisitMode.IN_PERSON,
    val visitorName: String,
    val visitorEmail: String,
    val notes: String = "",
    val explicitConfirmed: Boolean,
)
