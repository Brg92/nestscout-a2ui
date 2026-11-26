package com.nestscout.a2ui.server.routes

import com.nestscout.a2ui.contract.protocol.A2uiWireEvent
import com.nestscout.a2ui.contract.protocol.UiErrorReport
import com.nestscout.a2ui.contract.protocol.UiStreamRequest
import com.nestscout.a2ui.server.ServerJson
import com.nestscout.a2ui.server.error.SelfCorrectionService
import com.nestscout.a2ui.server.stream.UiStreamOrchestrator
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.configureUiRoutes(
    orchestrator: UiStreamOrchestrator,
    selfCorrectionService: SelfCorrectionService,
) {
    route("/api/v1/ui") {
        post("/stream") {
            val request = call.receive<UiStreamRequest>()
            val prepared = orchestrator.prepareStream(request)

            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.header("X-Accel-Buffering", "no")
            call.respondTextWriter(
                contentType = ContentType.Text.EventStream,
                status = HttpStatusCode.OK,
            ) {
                prepared.events.forEachIndexed { idx, wireEvent ->
                    val encodedData = ServerJson.encodeToString(A2uiWireEvent.serializer(), wireEvent)
                    write("id: ${idx + 1}\n")
                    write("event: ${eventNameFor(wireEvent)}\n")
                    write("data: $encodedData\n\n")
                    flush()
                }
            }
        }

        post("/error") {
            val report = call.receive<UiErrorReport>()
            val resolution = selfCorrectionService.resolve(report)
            call.respond(HttpStatusCode.OK, resolution)
        }
    }
}

private fun eventNameFor(event: A2uiWireEvent): String = when (event) {
    is A2uiWireEvent.CreateSurface -> "createSurface"
    is A2uiWireEvent.UpdateComponents -> "updateComponents"
    is A2uiWireEvent.UpdateDataModel -> "updateDataModel"
    is A2uiWireEvent.DeleteSurface -> "deleteSurface"
}
