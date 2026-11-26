package com.nestscout.a2ui.contract.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Flat A2UI component node keyed by component type URN (or Basic Catalog name).
 */
@Serializable
data class A2uiComponentNode(
    val id: String,
    val component: Map<String, JsonObject>,
)

/**
 * Wire-level SSE events emitted by the backend and consumed by the A2UI renderer.
 */
@Serializable
sealed interface A2uiWireEvent {
    val surfaceId: String

    @Serializable
    @SerialName("createSurface")
    data class CreateSurface(
        override val surfaceId: String,
        val catalogId: String,
        val rootComponentId: String = "root",
    ) : A2uiWireEvent

    @Serializable
    @SerialName("updateComponents")
    data class UpdateComponents(
        override val surfaceId: String,
        val components: List<A2uiComponentNode>,
    ) : A2uiWireEvent

    @Serializable
    @SerialName("updateDataModel")
    data class UpdateDataModel(
        override val surfaceId: String,
        val path: String = "/",
        val contents: JsonElement,
    ) : A2uiWireEvent

    @Serializable
    @SerialName("deleteSurface")
    data class DeleteSurface(
        override val surfaceId: String,
    ) : A2uiWireEvent
}
