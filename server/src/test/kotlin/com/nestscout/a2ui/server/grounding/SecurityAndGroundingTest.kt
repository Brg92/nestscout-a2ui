package com.nestscout.a2ui.server.grounding

import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import com.nestscout.a2ui.contract.protocol.CatalogVersion
import com.nestscout.a2ui.contract.protocol.ClientCapabilities
import com.nestscout.a2ui.contract.schema.ContractSchemaValidator
import com.nestscout.a2ui.contract.schema.ValidationResult
import com.nestscout.a2ui.server.repository.PropertyRepository
import com.nestscout.a2ui.server.security.CapabilityNegotiator
import com.nestscout.a2ui.server.security.NegotiatedCatalog
import com.nestscout.a2ui.server.security.UrlAllowlistSanitizer
import com.nestscout.a2ui.server.security.UrlValidationResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityAndGroundingTest {

    private val repository = PropertyRepository.fromResource()
    private val sanitizer = UrlAllowlistSanitizer()
    private val negotiator = CapabilityNegotiator()
    private val hydrator = GroundingHydrator(repository, sanitizer)

    @Test
    fun `UrlAllowlistSanitizer enforces https and blocks untrusted hosts userinfo IPs and traversal`() {
        assertIs<UrlValidationResult.Allowed>(
            sanitizer.validate("https://images.unsplash.com/photo-1502672260266-1c1ef2d93688"),
        )
        assertIs<UrlValidationResult.Blocked>(
            sanitizer.validate("http://images.unsplash.com/photo-1502672260266-1c1ef2d93688"),
        )
        assertIs<UrlValidationResult.Blocked>(
            sanitizer.validate("javascript:alert(1)"),
        )
        assertIs<UrlValidationResult.Blocked>(
            sanitizer.validate("https://images.unsplash.com@untrusted.example/photo.jpg"),
        )
        assertIs<UrlValidationResult.Blocked>(
            sanitizer.validate("https://192.168.1.10/photo.jpg"),
        )
        assertIs<UrlValidationResult.Blocked>(
            sanitizer.validate("https://images.unsplash.com/photos/../secret"),
        )
        assertIs<UrlValidationResult.Blocked>(
            sanitizer.validate("https://malicious.example.org/phishing.png"),
        )

        assertEquals(
            sanitizer.fallbackImageUrl,
            sanitizer.sanitizeOrFallback("https://malicious.example.org/phishing.png"),
        )
    }

    @Test
    fun `CapabilityNegotiator selects v2 downgrades v1-only and falls back to basic catalog`() {
        val v2Result = negotiator.negotiate(ClientCapabilities())
        assertIs<NegotiatedCatalog.CustomCatalog>(v2Result)
        assertEquals(CatalogVersion.V2, v2Result.version)
        assertEquals(A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID, v2Result.catalogId)
        assertEquals(3, v2Result.maxComparisonItems)
        assertFalse(v2Result.downgradedFromV2)

        val v1OnlyCaps = ClientCapabilities(
            supportedCatalogIds = setOf(
                A2uiCatalogIds.BASIC_M3_CATALOG_ID,
                A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID,
            ),
            maxComparisonItems = 3,
        )
        val v1Result = negotiator.negotiate(v1OnlyCaps)
        assertIs<NegotiatedCatalog.CustomCatalog>(v1Result)
        assertEquals(CatalogVersion.V1, v1Result.version)
        assertEquals(A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID, v1Result.catalogId)
        assertEquals(2, v1Result.maxComparisonItems)
        assertTrue(v1Result.downgradedFromV2)

        val basicCaps = ClientCapabilities(
            supportedCatalogIds = setOf(A2uiCatalogIds.BASIC_M3_CATALOG_ID),
        )
        val basicResult = negotiator.negotiate(basicCaps)
        assertIs<NegotiatedCatalog.BasicFallback>(basicResult)
        assertEquals(A2uiCatalogIds.BASIC_M3_CATALOG_ID, basicResult.catalogId)
    }

    @Test
    fun `GroundingHydrator hydrates factual fields strictly from dataset and clamps matchScore`() {
        val certified = repository.findById("apt-01")!!
        val cardV2 = hydrator.hydratePropertyCardV2(
            propertyId = "apt-01",
            matchScore = 140,
        )!!

        assertEquals(certified.priceEur, cardV2.priceEur)
        assertEquals(certified.pricePerSqm, cardV2.pricePerSqm)
        assertEquals(certified.sqm, cardV2.sqm)
        assertEquals(certified.rooms, cardV2.rooms)
        assertEquals(certified.neighborhood, cardV2.neighborhood)
        assertEquals(100, cardV2.matchScore)
        assertNull(hydrator.hydratePropertyCardV2("non-existent-apt", matchScore = 80))
    }

    @Test
    fun `GroundingHydrator replaces untrusted dataset URL with safe allowlisted fallback`() {
        val base = repository.findById("apt-01")!!
        val tamperedRepo = PropertyRepository(
            listOf(base.copy(imageUrl = "https://untrusted-tracker.example/pixel.png")),
        )
        val isolatedHydrator = GroundingHydrator(tamperedRepo, sanitizer)

        val card = isolatedHydrator.hydratePropertyCardV1("apt-01")!!
        assertEquals(sanitizer.fallbackImageUrl, card.imageUrl)
    }

    @Test
    fun `GroundingHydrator builds schema-valid v1 and v2 comparison filter and visit nodes`() {
        val compV1 = hydrator.buildComparisonNode(
            nodeId = "comp-1",
            propertyIds = listOf("apt-01", "apt-04", "apt-09"),
            version = CatalogVersion.V1,
        )!!
        val v1Props = compV1.component.getValue(A2uiCatalogIds.V1.PROPERTY_COMPARISON_TABLE)
        assertEquals(2, v1Props["items"]!!.jsonArray.size)
        assertIs<ValidationResult.Valid>(
            ContractSchemaValidator.validateComponent(A2uiCatalogIds.V1.PROPERTY_COMPARISON_TABLE, v1Props),
        )

        val compV2 = hydrator.buildComparisonNode(
            nodeId = "comp-2",
            propertyIds = listOf("apt-01", "apt-04", "apt-09"),
            version = CatalogVersion.V2,
            requestedWinnerId = "hallucinated-id",
        )!!
        val v2Props = compV2.component.getValue(A2uiCatalogIds.V2.PROPERTY_COMPARISON_TABLE)
        assertEquals(3, v2Props["items"]!!.jsonArray.size)
        val winner = v2Props["winnerPropertyId"]!!.jsonPrimitive.content
        assertTrue(winner in setOf("apt-01", "apt-04", "apt-09"))

        val filterV2 = hydrator.buildFilterSheetNode("filter-1", CatalogVersion.V2, matchingCount = 12)
        assertIs<ValidationResult.Valid>(
            ContractSchemaValidator.validateComponent(
                A2uiCatalogIds.V2.FILTER_REFINE_SHEET,
                filterV2.component.getValue(A2uiCatalogIds.V2.FILTER_REFINE_SHEET),
            ),
        )

        val visitV2 = hydrator.buildVisitFormNode(
            nodeId = "visit-1",
            propertyId = "apt-04",
            version = CatalogVersion.V2,
            agencyNote = "Disponibilita anche sabato mattina",
        )!!
        assertIs<ValidationResult.Valid>(
            ContractSchemaValidator.validateComponent(
                A2uiCatalogIds.V2.VISIT_REQUEST_FORM,
                visitV2.component.getValue(A2uiCatalogIds.V2.VISIT_REQUEST_FORM),
            ),
        )
    }
}
