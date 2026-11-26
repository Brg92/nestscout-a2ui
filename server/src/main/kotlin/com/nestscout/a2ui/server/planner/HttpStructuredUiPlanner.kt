package com.nestscout.a2ui.server.planner

import com.nestscout.a2ui.contract.protocol.UiStreamRequest
import com.nestscout.a2ui.server.config.ServerConfig
import com.nestscout.a2ui.server.repository.PropertyRepository
import com.nestscout.a2ui.server.security.NegotiatedCatalog
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
internal data class GenerateContentRequest(
    val systemInstruction: ContentEnvelope,
    val contents: List<ContentEnvelope>,
    val generationConfig: StructuredGenerationConfig,
)

@Serializable
internal data class ContentEnvelope(
    val role: String? = null,
    val parts: List<TextPart>,
)

@Serializable
internal data class TextPart(
    val text: String,
)

@Serializable
internal data class StructuredGenerationConfig(
    val temperature: Double = 0.1,
    val responseMimeType: String = "application/json",
    val responseJsonSchema: JsonObject,
)

@Serializable
internal data class GenerateContentResponse(
    val candidates: List<CandidateEnvelope> = emptyList(),
)

@Serializable
internal data class CandidateEnvelope(
    val content: ContentEnvelope? = null,
)

@Serializable
internal data class RemoteStructuredPlan(
    val intent: PlannedIntent,
    val summaryBanner: String,
    val selections: List<PlannedPropertySelection> = emptyList(),
    val showFilterSheet: Boolean = false,
    val winnerPropertyId: String? = null,
    val targetVisitPropertyId: String? = null,
    val agencyNote: String? = null,
)

/**
 * Remote structured output planner targeting REST generateContent with Draft 2020-12 responseJsonSchema.
 * Never embeds credentials in source code or URLs; factual fields remain grounded by GroundingHydrator.
 */
class HttpStructuredUiPlanner(
    private val apiKey: String,
    private val repository: PropertyRepository,
    private val httpClient: HttpClient,
    private val baseUrl: String = ServerConfig.DEFAULT_PLANNER_BASE_URL,
    private val modelId: String = ServerConfig.DEFAULT_PLANNER_MODEL_ID,
    private val fallbackPlanner: UiPlanner? = null,
) : UiPlanner {

    private val localActionDelegate = MockUiPlanner(repository)

    private val envelopeJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val strictPlanJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        encodeDefaults = true
    }

    init {
        require(apiKey.isNotBlank()) { "API key must not be blank for HttpStructuredUiPlanner" }
    }

    override suspend fun plan(
        request: UiStreamRequest,
        negotiated: NegotiatedCatalog,
    ): UiTurnPlan {
        // UI mutations and deterministic actions are always validated and resolved locally.
        request.actionEvent?.let { action ->
            return localActionDelegate.resolveActionEvent(request, action, negotiated)
        }

        val candidates = repository.searchScored(request.criteria, request.query)
            .ifEmpty { repository.searchScored(request.criteria) }
            .take(MAX_CANDIDATE_CONTEXT)

        val endpoint = "${baseUrl.trimEnd('/')}/v1beta/models/$modelId:generateContent"
        val requestPayload = GenerateContentRequest(
            systemInstruction = ContentEnvelope(
                parts = listOf(
                    TextPart(
                        text = buildSystemDirective(negotiated.maxComparisonItems),
                    ),
                ),
            ),
            contents = listOf(
                ContentEnvelope(
                    role = "user",
                    parts = listOf(
                        TextPart(
                            text = buildTurnPrompt(request, candidates.map { it.listing.id to it.matchScore }),
                        ),
                    ),
                ),
            ),
            generationConfig = StructuredGenerationConfig(
                temperature = 0.1,
                responseMimeType = "application/json",
                responseJsonSchema = RESPONSE_JSON_SCHEMA,
            ),
        )

        return try {
            val response = httpClient.post(endpoint) {
                header(API_KEY_HEADER, apiKey)
                contentType(ContentType.Application.Json)
                setBody(requestPayload)
            }

            if (!response.status.isSuccess()) {
                throw PlannerUpstreamException("Upstream planner returned HTTP ${response.status.value}")
            }

            val parsedEnvelope = envelopeJson.decodeFromString(
                GenerateContentResponse.serializer(),
                response.bodyAsText(),
            )
            val rawPlanJson = parsedEnvelope.candidates
                .firstOrNull()
                ?.content
                ?.parts
                ?.firstOrNull()
                ?.text
                ?.takeIf { it.isNotBlank() }
                ?: throw PlannerUpstreamException("Upstream structured response did not contain a text part")

            val decoded = strictPlanJson.decodeFromString(RemoteStructuredPlan.serializer(), rawPlanJson)
            sanitizeRemotePlan(decoded, negotiated)
        } catch (ex: Exception) {
            val wrapped = ex as? PlannerUpstreamException
                ?: PlannerUpstreamException("Failed to obtain structured UI plan: ${ex.message}", ex)
            fallbackPlanner?.plan(request, negotiated) ?: throw wrapped
        }
    }

    private fun sanitizeRemotePlan(
        remote: RemoteStructuredPlan,
        negotiated: NegotiatedCatalog,
    ): UiTurnPlan {
        val verifiedSelections = remote.selections
            .distinctBy { it.propertyId }
            .filter { repository.findById(it.propertyId) != null }
            .map { it.copy(matchScore = it.matchScore.coerceIn(0, 100)) }

        val effectiveIntent = if (remote.intent == PlannedIntent.VISIT_CONFIRMED) {
            // Confirmations can never originate from unstructured queries without a verified submission.
            PlannedIntent.VISIT_FORM
        } else {
            remote.intent
        }

        val cappedSelections = when (effectiveIntent) {
            PlannedIntent.COMPARISON -> verifiedSelections.take(negotiated.maxComparisonItems)
            else -> verifiedSelections.take(MockUiPlanner.MAX_SEARCH_CARDS)
        }

        val surfaceId = when (effectiveIntent) {
            PlannedIntent.COMPARISON -> MockUiPlanner.SURFACE_COMPARISON
            PlannedIntent.VISIT_FORM -> MockUiPlanner.SURFACE_VISIT
            else -> MockUiPlanner.SURFACE_SEARCH
        }

        val verifiedVisitId = remote.targetVisitPropertyId
            ?.takeIf { repository.findById(it) != null }
            ?: cappedSelections.firstOrNull()?.propertyId

        val verifiedWinner = remote.winnerPropertyId
            ?.takeIf { winner -> cappedSelections.any { it.propertyId == winner } }

        return UiTurnPlan(
            surfaceId = surfaceId,
            intent = effectiveIntent,
            summaryBanner = remote.summaryBanner.trim().ifEmpty { "Risultati certificati disponibili" }.take(180),
            selections = cappedSelections,
            showFilterSheet = remote.showFilterSheet || (effectiveIntent == PlannedIntent.FILTER_REFINE),
            winnerPropertyId = verifiedWinner,
            targetVisitPropertyId = verifiedVisitId,
            agencyNote = remote.agencyNote?.trim()?.take(160),
        )
    }

    private fun buildSystemDirective(maxComparisonItems: Int): String =
        "Select property IDs strictly from the supplied certified catalog digest. " +
            "Never invent IDs or factual prices. Comparison views support at most $maxComparisonItems items."

    private fun buildTurnPrompt(
        request: UiStreamRequest,
        scoredIds: List<Pair<String, Int>>,
    ): String {
        val digest = scoredIds.mapNotNull { (id, score) ->
            val listing = repository.findById(id) ?: return@mapNotNull null
            "${listing.id}|${listing.neighborhood}|rooms=${listing.rooms}|sqm=${listing.sqm}|" +
                "eur=${listing.priceEur}|energy=${listing.energyClass}|score=$score"
        }.joinToString("\n")

        return buildString {
            appendLine("Query: ${request.query}")
            appendLine("Criteria: ${request.criteria}")
            appendLine("CertifiedCandidates:")
            append(digest)
        }
    }

    companion object {
        const val API_KEY_HEADER = "x-goog-api-key"
        private const val MAX_CANDIDATE_CONTEXT = 12

        val RESPONSE_JSON_SCHEMA: JsonObject = buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("type", "object")
            put("additionalProperties", false)
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("intent"))
                    add(JsonPrimitive("summaryBanner"))
                    add(JsonPrimitive("selections"))
                },
            )
            put(
                "properties",
                buildJsonObject {
                    put(
                        "intent",
                        buildJsonObject {
                            put("type", "string")
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("SEARCH_RESULTS"))
                                    add(JsonPrimitive("COMPARISON"))
                                    add(JsonPrimitive("FILTER_REFINE"))
                                    add(JsonPrimitive("VISIT_FORM"))
                                },
                            )
                        },
                    )
                    put("summaryBanner", buildJsonObject { put("type", "string") })
                    put(
                        "selections",
                        buildJsonObject {
                            put("type", "array")
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", "object")
                                    put("additionalProperties", false)
                                    put(
                                        "required",
                                        buildJsonArray {
                                            add(JsonPrimitive("propertyId"))
                                            add(JsonPrimitive("matchScore"))
                                        },
                                    )
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("propertyId", buildJsonObject { put("type", "string") })
                                            put(
                                                "matchScore",
                                                buildJsonObject {
                                                    put("type", "integer")
                                                    put("minimum", 0)
                                                    put("maximum", 100)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put("showFilterSheet", buildJsonObject { put("type", "boolean") })
                    put("winnerPropertyId", buildJsonObject { put("type", "string") })
                    put("targetVisitPropertyId", buildJsonObject { put("type", "string") })
                    put("agencyNote", buildJsonObject { put("type", "string") })
                },
            )
        }

        fun createDefaultHttpClient(): HttpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        encodeDefaults = true
                        explicitNulls = false
                        ignoreUnknownKeys = true
                    },
                )
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 15_000
            }
        }
    }
}

object UiPlannerFactory {
    fun create(
        config: ServerConfig,
        repository: PropertyRepository,
        httpClient: HttpClient? = null,
    ): UiPlanner {
        val mockPlanner = MockUiPlanner(repository)
        if (config.mockMode) {
            return mockPlanner
        }
        val client = httpClient ?: HttpStructuredUiPlanner.createDefaultHttpClient()
        return HttpStructuredUiPlanner(
            apiKey = requireNotNull(config.remoteApiKey),
            repository = repository,
            httpClient = client,
            baseUrl = config.plannerBaseUrl,
            modelId = config.plannerModelId,
            fallbackPlanner = mockPlanner,
        )
    }
}
