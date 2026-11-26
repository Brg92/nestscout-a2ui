package com.nestscout.a2ui.server.planner

import com.nestscout.a2ui.contract.domain.VisitMode
import com.nestscout.a2ui.contract.domain.VisitTimeSlot
import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import com.nestscout.a2ui.contract.protocol.ClientCapabilities
import com.nestscout.a2ui.contract.protocol.UiActionEvent
import com.nestscout.a2ui.contract.protocol.UiStreamRequest
import com.nestscout.a2ui.contract.protocol.VisitRequestSubmission
import com.nestscout.a2ui.server.config.ServerConfig
import com.nestscout.a2ui.server.repository.PropertyRepository
import com.nestscout.a2ui.server.security.CapabilityNegotiator
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerTest {

    private val repository = PropertyRepository.fromResource()
    private val negotiator = CapabilityNegotiator()
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `ServerConfig defaults to A2UI_MOCK_MODE true and masks remoteApiKey in toString`() {
        val defaultCfg = ServerConfig.fromEnv(emptyMap())
        assertTrue(defaultCfg.mockMode)
        assertNull(defaultCfg.remoteApiKey)

        assertThrows<IllegalArgumentException> {
            ServerConfig.fromEnv(mapOf("A2UI_MOCK_MODE" to "false"))
        }

        val liveCfg = ServerConfig.fromEnv(
            mapOf(
                "A2UI_MOCK_MODE" to "false",
                "GEMINI_API_KEY" to "test-key-not-real",
                "PLANNER_MODEL_ID" to "gemini-2.5-flash",
            ),
        )
        assertFalse(liveCfg.mockMode)
        assertFalse(liveCfg.toString().contains("test-key-not-real"))
        assertTrue(liveCfg.toString().contains("***"))
    }

    @Test
    fun `MockUiPlanner handles search comparison and respects v1 comparison item cap`() = runBlocking {
        val planner = MockUiPlanner(repository)
        val v1Negotiated = negotiator.negotiate(
            ClientCapabilities(
                supportedCatalogIds = setOf(
                    A2uiCatalogIds.BASIC_M3_CATALOG_ID,
                    A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID,
                ),
            ),
        )

        val comparisonPlan = planner.plan(
            request = UiStreamRequest(
                sessionId = "s-1",
                query = "Confronta apt-01, apt-04 e apt-09",
            ),
            negotiated = v1Negotiated,
        )
        assertEquals(PlannedIntent.COMPARISON, comparisonPlan.intent)
        assertEquals(2, comparisonPlan.selections.size)
        assertEquals(listOf("apt-01", "apt-04"), comparisonPlan.selections.map { it.propertyId })
    }

    @Test
    fun `MockUiPlanner enforces explicitConfirmed and valid date on submit_visit`() = runBlocking {
        val planner = MockUiPlanner(repository)
        val v2Negotiated = negotiator.negotiate(ClientCapabilities())
        val listing = repository.findById("apt-01")!!

        val unconfirmed = VisitRequestSubmission(
            propertyId = "apt-01",
            preferredDate = listing.availableVisitDates.first(),
            timeSlot = VisitTimeSlot.MORNING,
            visitMode = VisitMode.IN_PERSON,
            visitorName = "Giulia Bianchi",
            visitorEmail = "giulia@example.com",
            explicitConfirmed = false,
        )

        assertThrows<IllegalArgumentException> {
            planner.plan(
                request = UiStreamRequest(
                    sessionId = "s-2",
                    query = "",
                    actionEvent = UiActionEvent(
                        surfaceId = "visit-request",
                        componentId = "visit-form",
                        actionName = "submit_visit",
                        payload = json.encodeToJsonElement(unconfirmed).jsonObject,
                    ),
                ),
                negotiated = v2Negotiated,
            )
        }

        val confirmed = unconfirmed.copy(explicitConfirmed = true)
        val plan = planner.plan(
            request = UiStreamRequest(
                sessionId = "s-2",
                query = "",
                actionEvent = UiActionEvent(
                    surfaceId = "visit-request",
                    componentId = "visit-form",
                    actionName = "submit_visit",
                    payload = json.encodeToJsonElement(confirmed).jsonObject,
                ),
            ),
            negotiated = v2Negotiated,
        )
        assertEquals(PlannedIntent.VISIT_CONFIRMED, plan.intent)
        assertEquals(listing.availableVisitDates.first(), plan.confirmedVisitDate)
    }

    @Test
    fun `HttpStructuredUiPlanner sends generateContent request with schema and strips unknown IDs`() = runBlocking {
        var capturedApiKey: String? = null
        var capturedPath: String? = null
        var capturedBodyText: String? = null

        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            capturedApiKey = request.headers[HttpStructuredUiPlanner.API_KEY_HEADER]
            capturedPath = request.url.encodedPath
            assertNull(request.url.parameters["key"])

            val bodyBytes = (request.body as OutgoingContent.ByteArrayContent).bytes()
            capturedBodyText = bodyBytes.decodeToString()

            val structuredInner = """
                {
                  "intent": "SEARCH_RESULTS",
                  "summaryBanner": "Selezione bilocali a Isola",
                  "selections": [
                    { "propertyId": "apt-01", "matchScore": 96 },
                    { "propertyId": "non-existent-id", "matchScore": 99 },
                    { "propertyId": "apt-19", "matchScore": 89 }
                  ],
                  "showFilterSheet": false
                }
            """.trimIndent()

            val upstreamJson = """
                {
                  "candidates": [
                    {
                      "content": {
                        "role": "model",
                        "parts": [
                          { "text": ${Json.encodeToString(structuredInner)} }
                        ]
                      }
                    }
                  ]
                }
            """.trimIndent()

            respond(
                content = upstreamJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val remotePlanner = HttpStructuredUiPlanner(
            apiKey = "ephemeral-unit-test-key",
            repository = repository,
            httpClient = httpClient,
            baseUrl = "https://generativelanguage.googleapis.com",
            modelId = "gemini-2.5-flash",
        )

        val plan = remotePlanner.plan(
            request = UiStreamRequest(sessionId = "s-remote", query = "bilocale Isola"),
            negotiated = negotiator.negotiate(ClientCapabilities()),
        )

        assertEquals("ephemeral-unit-test-key", capturedApiKey)
        assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", capturedPath)

        val sentJson = Json.parseToJsonElement(capturedBodyText!!).jsonObject
        val genConfig = sentJson["generationConfig"]!!.jsonObject
        assertEquals("application/json", genConfig["responseMimeType"]!!.jsonPrimitive.content)
        assertTrue(genConfig.containsKey("responseJsonSchema"))

        assertEquals(PlannedIntent.SEARCH_RESULTS, plan.intent)
        assertEquals(listOf("apt-01", "apt-19"), plan.selections.map { it.propertyId })
    }

    @Test
    fun `HttpStructuredUiPlanner falls back cleanly when fallbackPlanner is provided`() = runBlocking {
        val failingEngine = MockEngine {
            respond(
                content = """{"error":"service unavailable"}""",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(failingEngine) {
            install(ContentNegotiation) { json(json) }
        }

        val plannerWithFallback = HttpStructuredUiPlanner(
            apiKey = "ephemeral-unit-test-key",
            repository = repository,
            httpClient = httpClient,
            fallbackPlanner = MockUiPlanner(repository),
        )

        val plan = plannerWithFallback.plan(
            request = UiStreamRequest(sessionId = "s-fallback", query = "bilocale Isola"),
            negotiated = negotiator.negotiate(ClientCapabilities()),
        )
        assertEquals(PlannedIntent.SEARCH_RESULTS, plan.intent)
        assertTrue(plan.selections.isNotEmpty())
    }
}
