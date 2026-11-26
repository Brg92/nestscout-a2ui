package com.nestscout.a2ui.server

import com.nestscout.a2ui.contract.catalog.PropertyCardV2Props
import com.nestscout.a2ui.contract.domain.VisitMode
import com.nestscout.a2ui.contract.domain.VisitTimeSlot
import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import com.nestscout.a2ui.contract.protocol.A2uiComponentNode
import com.nestscout.a2ui.contract.protocol.A2uiWireEvent
import com.nestscout.a2ui.contract.protocol.ClientCapabilities
import com.nestscout.a2ui.contract.protocol.CorrectionStrategy
import com.nestscout.a2ui.contract.protocol.UiActionEvent
import com.nestscout.a2ui.contract.protocol.UiErrorCode
import com.nestscout.a2ui.contract.protocol.UiErrorReport
import com.nestscout.a2ui.contract.protocol.UiErrorResolution
import com.nestscout.a2ui.contract.protocol.UiStreamRequest
import com.nestscout.a2ui.contract.protocol.VisitRequestSubmission
import com.nestscout.a2ui.contract.schema.ContractSchemaValidator
import com.nestscout.a2ui.contract.schema.ValidationResult
import com.nestscout.a2ui.server.config.ServerConfig
import com.nestscout.a2ui.server.planner.PlannedIntent
import com.nestscout.a2ui.server.planner.PlannedPropertySelection
import com.nestscout.a2ui.server.planner.UiPlanner
import com.nestscout.a2ui.server.planner.UiTurnPlan
import com.nestscout.a2ui.server.repository.PropertyRepository
import com.nestscout.a2ui.server.security.NegotiatedCatalog
import com.nestscout.a2ui.server.security.UrlAllowlistSanitizer
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiEndpointsIntegrationTest {

    private val mockConfig = ServerConfig(mockMode = true)

    private data class ParsedSseFrame(
        val id: String?,
        val event: String?,
        val wireEvent: A2uiWireEvent,
    )

    private fun parseSseStream(rawBody: String): List<ParsedSseFrame> {
        return rawBody
            .trim()
            .split("\n\n")
            .filter { it.isNotBlank() }
            .map { block ->
                var id: String? = null
                var event: String? = null
                val dataLines = mutableListOf<String>()
                block.lines().forEach { line ->
                    when {
                        line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> dataLines += line.removePrefix("data:").trim()
                    }
                }
                val dataPayload = dataLines.joinToString("\n")
                ParsedSseFrame(
                    id = id,
                    event = event,
                    wireEvent = ServerJson.decodeFromString(A2uiWireEvent.serializer(), dataPayload),
                )
            }
    }

    @Test
    fun `POST stream emits progressive SSE frames CreateSurface Skeleton Hydrated and UpdateDataModel`() = testApplication {
        application {
            module(config = mockConfig)
        }

        val requestPayload = UiStreamRequest(
            sessionId = "sess-sse-1",
            query = "bilocale luminoso Isola con terrazzo",
            capabilities = ClientCapabilities(),
        )

        val response = client.post("/api/v1/ui/stream") {
            contentType(ContentType.Application.Json)
            setBody(ServerJson.encodeToString(UiStreamRequest.serializer(), requestPayload))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val responseType = response.contentType()
        assertNotNull(responseType)
        assertTrue(responseType.match(ContentType.Text.EventStream))

        val frames = parseSseStream(response.bodyAsText())
        assertEquals(4, frames.size)
        assertEquals(listOf("1", "2", "3", "4"), frames.map { it.id })
        assertEquals(
            listOf("createSurface", "updateComponents", "updateComponents", "updateDataModel"),
            frames.map { it.event },
        )

        val createSurface = assertIs<A2uiWireEvent.CreateSurface>(frames[0].wireEvent)
        assertEquals(A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID, createSurface.catalogId)

        val skeletonFrame = assertIs<A2uiWireEvent.UpdateComponents>(frames[1].wireEvent)
        val skeletonCard = skeletonFrame.components.first { it.id.startsWith("card-") }
        val skeletonProps = skeletonCard.component.getValue(A2uiCatalogIds.V2.PROPERTY_CARD)
        assertTrue(skeletonProps["isSkeleton"]!!.jsonPrimitive.boolean)

        val hydratedFrame = assertIs<A2uiWireEvent.UpdateComponents>(frames[2].wireEvent)
        val hydratedCard = hydratedFrame.components.first { it.id == skeletonCard.id }
        val hydratedProps = hydratedCard.component.getValue(A2uiCatalogIds.V2.PROPERTY_CARD)
        assertFalse(hydratedProps["isSkeleton"]!!.jsonPrimitive.boolean)
        assertIs<ValidationResult.Valid>(
            ContractSchemaValidator.validateComponent(A2uiCatalogIds.V2.PROPERTY_CARD, hydratedProps),
        )

        val dataModel = assertIs<A2uiWireEvent.UpdateDataModel>(frames[3].wireEvent)
        assertEquals("/", dataModel.path)
        assertTrue(dataModel.contents.jsonObject.containsKey("favorites"))
        assertTrue(dataModel.contents.jsonObject.containsKey("filters"))
    }

    @Test
    fun `POST stream negotiates v2 vs v1 downgrade and Basic fallback deterministically`() = testApplication {
        application {
            module(config = mockConfig)
        }

        val v2Request = UiStreamRequest(
            sessionId = "sess-v2",
            query = "Confronta apt-01, apt-04 e apt-09",
            capabilities = ClientCapabilities(),
        )
        val v2Frames = parseSseStream(
            client.post("/api/v1/ui/stream") {
                contentType(ContentType.Application.Json)
                setBody(ServerJson.encodeToString(UiStreamRequest.serializer(), v2Request))
            }.bodyAsText(),
        )
        val v2Surface = assertIs<A2uiWireEvent.CreateSurface>(v2Frames[0].wireEvent)
        assertEquals(A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID, v2Surface.catalogId)
        val v2Update = assertIs<A2uiWireEvent.UpdateComponents>(v2Frames[1].wireEvent)
        val v2Comp = v2Update.components.first { it.id == "comparison-table" }
        assertEquals(
            3,
            v2Comp.component.getValue(A2uiCatalogIds.V2.PROPERTY_COMPARISON_TABLE)["items"]!!.jsonArray.size,
        )

        val v1OnlyRequest = v2Request.copy(
            sessionId = "sess-v1",
            capabilities = ClientCapabilities(
                supportedCatalogIds = setOf(
                    A2uiCatalogIds.BASIC_M3_CATALOG_ID,
                    A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID,
                ),
            ),
        )
        val v1Frames = parseSseStream(
            client.post("/api/v1/ui/stream") {
                contentType(ContentType.Application.Json)
                setBody(ServerJson.encodeToString(UiStreamRequest.serializer(), v1OnlyRequest))
            }.bodyAsText(),
        )
        val v1Surface = assertIs<A2uiWireEvent.CreateSurface>(v1Frames[0].wireEvent)
        assertEquals(A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID, v1Surface.catalogId)
        val v1Update = assertIs<A2uiWireEvent.UpdateComponents>(v1Frames[1].wireEvent)
        val v1Comp = v1Update.components.first { it.id == "comparison-table" }
        assertEquals(
            2,
            v1Comp.component.getValue(A2uiCatalogIds.V1.PROPERTY_COMPARISON_TABLE)["items"]!!.jsonArray.size,
        )

        val basicRequest = v2Request.copy(
            sessionId = "sess-basic",
            capabilities = ClientCapabilities(
                supportedCatalogIds = setOf(A2uiCatalogIds.BASIC_M3_CATALOG_ID),
            ),
        )
        val basicFrames = parseSseStream(
            client.post("/api/v1/ui/stream") {
                contentType(ContentType.Application.Json)
                setBody(ServerJson.encodeToString(UiStreamRequest.serializer(), basicRequest))
            }.bodyAsText(),
        )
        val basicSurface = assertIs<A2uiWireEvent.CreateSurface>(basicFrames[0].wireEvent)
        assertEquals(A2uiCatalogIds.BASIC_M3_CATALOG_ID, basicSurface.catalogId)
    }

    @Test
    fun `POST stream enforces deterministic grounding and URL allowlist over planner output`() = testApplication {
        val baseRepo = PropertyRepository.fromResource()
        val certifiedApt01 = baseRepo.findById("apt-01")!!
        val repoWithUntrustedImage = PropertyRepository(
            listOf(
                certifiedApt01.copy(imageUrl = "https://unapproved-host.example/malicious.jpg"),
                baseRepo.findById("apt-04")!!,
            ),
        )

        val adversarialPlanner = object : UiPlanner {
            override suspend fun plan(
                request: UiStreamRequest,
                negotiated: NegotiatedCatalog,
            ): UiTurnPlan = UiTurnPlan(
                surfaceId = "search-results",
                intent = PlannedIntent.SEARCH_RESULTS,
                summaryBanner = "Risultati test",
                selections = listOf(
                    PlannedPropertySelection(propertyId = "non-existent-999", matchScore = 99),
                    PlannedPropertySelection(propertyId = "apt-01", matchScore = 500),
                ),
            )
        }

        application {
            module(
                config = mockConfig,
                repository = repoWithUntrustedImage,
                plannerOverride = adversarialPlanner,
            )
        }

        val response = client.post("/api/v1/ui/stream") {
            contentType(ContentType.Application.Json)
            setBody(
                ServerJson.encodeToString(
                    UiStreamRequest.serializer(),
                    UiStreamRequest(sessionId = "sess-grounding", query = "test"),
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val frames = parseSseStream(response.bodyAsText())
        val hydrated = assertIs<A2uiWireEvent.UpdateComponents>(frames[2].wireEvent)

        assertTrue(hydrated.components.none { it.id == "card-non-existent-999" })
        val apt01Node = hydrated.components.single { it.id == "card-apt-01" }
        val decodedCard = ServerJson.decodeFromJsonElement(
            PropertyCardV2Props.serializer(),
            apt01Node.component.getValue(A2uiCatalogIds.V2.PROPERTY_CARD),
        )

        assertEquals(certifiedApt01.priceEur, decodedCard.priceEur)
        assertEquals(certifiedApt01.sqm, decodedCard.sqm)
        assertEquals(certifiedApt01.neighborhood, decodedCard.neighborhood)
        assertEquals(100, decodedCard.matchScore)
        assertEquals(UrlAllowlistSanitizer.DEFAULT_FALLBACK_IMAGE_URL, decodedCard.imageUrl)
    }

    @Test
    fun `POST stream rejects unconfirmed visit submission and accepts confirmed submission`() = testApplication {
        val repo = PropertyRepository.fromResource()
        val validDate = repo.findById("apt-01")!!.availableVisitDates.first()

        application {
            module(config = mockConfig, repository = repo)
        }

        val unconfirmed = VisitRequestSubmission(
            propertyId = "apt-01",
            preferredDate = validDate,
            timeSlot = VisitTimeSlot.AFTERNOON,
            visitMode = VisitMode.IN_PERSON,
            visitorName = "Luca Verdi",
            visitorEmail = "luca.verdi@example.com",
            explicitConfirmed = false,
        )

        val rejectedResp = client.post("/api/v1/ui/stream") {
            contentType(ContentType.Application.Json)
            setBody(
                ServerJson.encodeToString(
                    UiStreamRequest.serializer(),
                    UiStreamRequest(
                        sessionId = "sess-visit",
                        query = "",
                        actionEvent = UiActionEvent(
                            surfaceId = "visit-request",
                            componentId = "visit-form",
                            actionName = "submit_visit",
                            payload = ServerJson.encodeToJsonElement(
                                VisitRequestSubmission.serializer(),
                                unconfirmed,
                            ).jsonObject,
                        ),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, rejectedResp.status)

        val confirmed = unconfirmed.copy(explicitConfirmed = true)
        val acceptedResp = client.post("/api/v1/ui/stream") {
            contentType(ContentType.Application.Json)
            setBody(
                ServerJson.encodeToString(
                    UiStreamRequest.serializer(),
                    UiStreamRequest(
                        sessionId = "sess-visit",
                        query = "",
                        actionEvent = UiActionEvent(
                            surfaceId = "visit-request",
                            componentId = "visit-form",
                            actionName = "submit_visit",
                            payload = ServerJson.encodeToJsonElement(
                                VisitRequestSubmission.serializer(),
                                confirmed,
                            ).jsonObject,
                        ),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, acceptedResp.status)
        val frames = parseSseStream(acceptedResp.bodyAsText())
        val dataModel = assertIs<A2uiWireEvent.UpdateDataModel>(frames.last().wireEvent)
        val visitStatus = dataModel.contents.jsonObject["visitForm"]!!
            .jsonObject["status"]!!.jsonPrimitive.content
        assertEquals("CONFIRMED", visitStatus)
    }

    @Test
    fun `POST error executes self-correction strategies and enforces retry budget`() = testApplication {
        application {
            module(config = mockConfig)
        }

        // Prime session surface context via stream request
        client.post("/api/v1/ui/stream") {
            contentType(ContentType.Application.Json)
            setBody(
                ServerJson.encodeToString(
                    UiStreamRequest.serializer(),
                    UiStreamRequest(sessionId = "sess-err", query = "bilocale Isola"),
                ),
            )
        }

        // 1st attempt on card-apt-01 (UNKNOWN_COMPONENT on v2) -> DOWNGRADE_TO_V1
        val attempt1Resp = client.post("/api/v1/ui/error") {
            contentType(ContentType.Application.Json)
            setBody(
                ServerJson.encodeToString(
                    UiErrorReport.serializer(),
                    UiErrorReport(
                        sessionId = "sess-err",
                        surfaceId = "search-results",
                        componentId = "card-apt-01",
                        catalogId = A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID,
                        errorCode = UiErrorCode.UNKNOWN_COMPONENT,
                        message = "Renderer missing v2 card",
                        attempt = 1,
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, attempt1Resp.status)
        val res1 = ServerJson.decodeFromString(UiErrorResolution.serializer(), attempt1Resp.bodyAsText())
        assertTrue(res1.accepted)
        assertEquals(CorrectionStrategy.DOWNGRADE_TO_V1, res1.strategy)
        val patchedNode1 = ServerJson.decodeFromJsonElement(
            A2uiComponentNode.serializer(),
            res1.patchPayload!!["componentNode"]!!,
        )
        assertTrue(patchedNode1.component.containsKey(A2uiCatalogIds.V1.PROPERTY_CARD))

        // 2nd attempt on card-apt-01 -> FALLBACK_TO_BASIC_CATALOG
        val attempt2Resp = client.post("/api/v1/ui/error") {
            contentType(ContentType.Application.Json)
            setBody(
                ServerJson.encodeToString(
                    UiErrorReport.serializer(),
                    UiErrorReport(
                        sessionId = "sess-err",
                        surfaceId = "search-results",
                        componentId = "card-apt-01",
                        catalogId = A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID,
                        errorCode = UiErrorCode.RENDER_EXCEPTION,
                        message = "v1 render failed",
                        attempt = 2,
                    ),
                ),
            )
        }
        val res2 = ServerJson.decodeFromString(UiErrorResolution.serializer(), attempt2Resp.bodyAsText())
        assertTrue(res2.accepted)
        assertEquals(CorrectionStrategy.FALLBACK_TO_BASIC_CATALOG, res2.strategy)
        assertEquals(
            A2uiCatalogIds.BASIC_M3_CATALOG_ID,
            res2.patchPayload!!["catalogId"]!!.jsonPrimitive.content,
        )

        // 3rd attempt on card-apt-01 -> ABORT_RETRY_BUDGET_EXCEEDED
        val attempt3Resp = client.post("/api/v1/ui/error") {
            contentType(ContentType.Application.Json)
            setBody(
                ServerJson.encodeToString(
                    UiErrorReport.serializer(),
                    UiErrorReport(
                        sessionId = "sess-err",
                        surfaceId = "search-results",
                        componentId = "card-apt-01",
                        catalogId = A2uiCatalogIds.BASIC_M3_CATALOG_ID,
                        errorCode = UiErrorCode.RENDER_EXCEPTION,
                        message = "Repeated failure",
                        attempt = 3,
                    ),
                ),
            )
        }
        val res3 = ServerJson.decodeFromString(UiErrorResolution.serializer(), attempt3Resp.bodyAsText())
        assertFalse(res3.accepted)
        assertEquals(CorrectionStrategy.ABORT_RETRY_BUDGET_EXCEEDED, res3.strategy)
        assertNull(res3.patchPayload)

        // Independent component card-apt-19 with UNSAFE_URL_BLOCKED -> PATCH_COMPONENT
        val patchResp = client.post("/api/v1/ui/error") {
            contentType(ContentType.Application.Json)
            setBody(
                ServerJson.encodeToString(
                    UiErrorReport.serializer(),
                    UiErrorReport(
                        sessionId = "sess-err",
                        surfaceId = "search-results",
                        componentId = "card-apt-19",
                        catalogId = A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID,
                        errorCode = UiErrorCode.UNSAFE_URL_BLOCKED,
                        message = "Blocked image host",
                        attempt = 1,
                    ),
                ),
            )
        }
        val resPatch = ServerJson.decodeFromString(UiErrorResolution.serializer(), patchResp.bodyAsText())
        assertTrue(resPatch.accepted)
        assertEquals(CorrectionStrategy.PATCH_COMPONENT, resPatch.strategy)
    }
}
