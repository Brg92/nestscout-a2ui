package com.nestscout.a2ui.server

import com.nestscout.a2ui.server.routes.configureHealthRoutes
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
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(
        factory = Netty,
        port = port,
        host = "0.0.0.0",
        module = Application::module,
    ).start(wait = true)
}

fun Application.module() {
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
    }

    routing {
        configureHealthRoutes()
    }
}
