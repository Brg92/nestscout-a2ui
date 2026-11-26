package com.nestscout.a2ui.server

import com.nestscout.a2ui.server.config.ServerConfig
import com.nestscout.a2ui.server.error.SelfCorrectionService
import com.nestscout.a2ui.server.grounding.GroundingHydrator
import com.nestscout.a2ui.server.planner.PlannerUpstreamException
import com.nestscout.a2ui.server.planner.UiPlanner
import com.nestscout.a2ui.server.planner.UiPlannerFactory
import com.nestscout.a2ui.server.repository.PropertyRepository
import com.nestscout.a2ui.server.routes.configureHealthRoutes
import com.nestscout.a2ui.server.routes.configureUiRoutes
import com.nestscout.a2ui.server.security.CapabilityNegotiator
import com.nestscout.a2ui.server.security.UrlAllowlistSanitizer
import com.nestscout.a2ui.server.stream.UiStreamOrchestrator
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class ApiErrorResponse(
    val error: String,
    val message: String,
)

val ServerJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = false
}

fun main() {
    val config = ServerConfig.fromEnv()
    embeddedServer(
        factory = Netty,
        port = config.port,
        host = "0.0.0.0",
        module = { module(config = config) },
    ).start(wait = true)
}

fun Application.module(
    config: ServerConfig = ServerConfig.fromEnv(),
    repository: PropertyRepository = PropertyRepository.fromResource(),
    plannerOverride: UiPlanner? = null,
) {
    val urlSanitizer = UrlAllowlistSanitizer(allowedHosts = config.allowedImageHosts)
    val capabilityNegotiator = CapabilityNegotiator()
    val groundingHydrator = GroundingHydrator(
        repository = repository,
        urlSanitizer = urlSanitizer,
    )
    val planner = plannerOverride ?: UiPlannerFactory.create(
        config = config,
        repository = repository,
    )
    val selfCorrectionService = SelfCorrectionService(
        repository = repository,
        hydrator = groundingHydrator,
    )
    val streamOrchestrator = UiStreamOrchestrator(
        repository = repository,
        capabilityNegotiator = capabilityNegotiator,
        planner = planner,
        hydrator = groundingHydrator,
        selfCorrectionService = selfCorrectionService,
    )

    install(ContentNegotiation) {
        json(ServerJson)
    }
    install(SSE)
    install(StatusPages) {
        exception<SerializationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse(
                    error = "INVALID_PAYLOAD",
                    message = cause.message ?: "Malformed JSON request payload",
                ),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiErrorResponse(
                    error = "BAD_REQUEST",
                    message = cause.message ?: "Invalid request argument",
                ),
            )
        }
        exception<PlannerUpstreamException> { call, cause ->
            call.respond(
                HttpStatusCode.BadGateway,
                ApiErrorResponse(
                    error = "UPSTREAM_PLANNER_ERROR",
                    message = cause.message ?: "Upstream structured planner failure",
                ),
            )
        }
    }

    routing {
        configureHealthRoutes()
        configureUiRoutes(
            orchestrator = streamOrchestrator,
            selfCorrectionService = selfCorrectionService,
        )
    }
}
