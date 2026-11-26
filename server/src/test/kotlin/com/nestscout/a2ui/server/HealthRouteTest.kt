package com.nestscout.a2ui.server

import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import com.nestscout.a2ui.server.routes.HealthResponse
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HealthRouteTest {

    @Test
    fun `GET health returns UP and protocol version`() = testApplication {
        application {
            module()
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = response.contentType()
        assertNotNull(contentType)
        assertTrue(contentType.match(ContentType.Application.Json))

        val decoded = ServerJson.decodeFromString(HealthResponse.serializer(), response.bodyAsText())
        assertEquals("UP", decoded.status)
        assertEquals("nestscout-server", decoded.service)
        assertEquals(A2uiCatalogIds.PROTOCOL_VERSION, decoded.protocolVersion)
    }
}
