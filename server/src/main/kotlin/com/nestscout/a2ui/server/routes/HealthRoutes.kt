package com.nestscout.a2ui.server.routes

import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
    val protocolVersion: String,
)

fun Route.configureHealthRoutes() {
    get("/health") {
        call.respond(
            HealthResponse(
                status = "UP",
                service = "nestscout-server",
                protocolVersion = A2uiCatalogIds.PROTOCOL_VERSION,
            ),
        )
    }
}
