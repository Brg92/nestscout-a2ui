package com.nestscout.a2ui.contract

import com.nestscout.a2ui.contract.catalog.ComparisonItemV1
import com.nestscout.a2ui.contract.catalog.ComparisonItemV2
import com.nestscout.a2ui.contract.catalog.DataBindingPath
import com.nestscout.a2ui.contract.catalog.PropertyCardV1Props
import com.nestscout.a2ui.contract.catalog.PropertyCardV2Props
import com.nestscout.a2ui.contract.catalog.PropertyComparisonTableV1Props
import com.nestscout.a2ui.contract.catalog.PropertyComparisonTableV2Props
import com.nestscout.a2ui.contract.catalog.VisitRequestFormV2Props
import com.nestscout.a2ui.contract.domain.EnergyClass
import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import com.nestscout.a2ui.contract.protocol.ClientCapabilities
import com.nestscout.a2ui.contract.schema.ContractSchemaValidator
import com.nestscout.a2ui.contract.schema.ValidationResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContractSchemaValidatorTest {

    private val json = Json { encodeDefaults = true }

    @Test
    fun `valid PropertyCardV2Props passes validation`() {
        val props = PropertyCardV2Props(
            propertyId = "apt-01",
            title = "Bilocale luminoso con terrazzo",
            neighborhood = "Isola",
            priceEur = 1450,
            pricePerSqm = 22,
            sqm = 65,
            rooms = 2,
            energyClass = EnergyClass.A2,
            imageUrl = "https://images.unsplash.com/photo-1502672260266-1c1ef2d93688",
            matchScore = 94,
            highlights = listOf("Terrazzo", "Metro M5"),
            isFavoriteBinding = DataBindingPath("/favorites/apt-01"),
        )
        val element = json.encodeToJsonElement(props).jsonObject
        val result = ContractSchemaValidator.validateComponent(A2uiCatalogIds.V2.PROPERTY_CARD, element)
        assertIs<ValidationResult.Valid>(result)
    }

    @Test
    fun `PropertyCardV2Props with out-of-range matchScore fails validation`() {
        val props = PropertyCardV2Props(
            propertyId = "apt-01",
            title = "Attico",
            neighborhood = "Brera",
            priceEur = 2400,
            pricePerSqm = 28,
            sqm = 85,
            rooms = 3,
            energyClass = EnergyClass.A1,
            imageUrl = "https://images.unsplash.com/photo-1502672260266-1c1ef2d93688",
            matchScore = 105,
            isFavoriteBinding = DataBindingPath("/favorites/apt-01"),
        )
        val element = json.encodeToJsonElement(props).jsonObject
        val result = ContractSchemaValidator.validateComponent(A2uiCatalogIds.V2.PROPERTY_CARD, element)
        assertIs<ValidationResult.Invalid>(result)
        assertTrue(result.errors.any { it.contains("matchScore") })
    }

    @Test
    fun `unknown JSON properties are rejected to block hallucinated fields`() {
        val base = json.encodeToJsonElement(
            PropertyCardV1Props(
                propertyId = "apt-02",
                title = "Monolocale",
                neighborhood = "Navigli",
                priceEur = 950,
                sqm = 38,
                rooms = 1,
                energyClass = EnergyClass.C,
                imageUrl = "https://images.unsplash.com/photo-1502672260266-1c1ef2d93688",
            ),
        ).jsonObject

        val tampered = JsonObject(base + ("arbitraryScript" to JsonPrimitive("alert(1)")))
        val result = ContractSchemaValidator.validateComponent(A2uiCatalogIds.V1.PROPERTY_CARD, tampered)
        assertIs<ValidationResult.Invalid>(result)
    }

    @Test
    fun `comparison table enforces v1 max 2 items and v2 max 3 items`() {
        val item1 = ComparisonItemV1("a", "A", 1000, 50, 2, 1, 100)
        val item2 = ComparisonItemV1("b", "B", 1200, 60, 2, 2, 120)
        val item3 = ComparisonItemV1("c", "C", 1400, 70, 3, 3, 140)

        val v1TooMany = json.encodeToJsonElement(
            PropertyComparisonTableV1Props(listOf(item1, item2, item3)),
        ).jsonObject
        assertIs<ValidationResult.Invalid>(
            ContractSchemaValidator.validateComponent(A2uiCatalogIds.V1.PROPERTY_COMPARISON_TABLE, v1TooMany),
        )

        val v2Three = json.encodeToJsonElement(
            PropertyComparisonTableV2Props(
                items = listOf(
                    ComparisonItemV2("a", "A", 1000, 50, 2, 1, 100, EnergyClass.A2, 4),
                    ComparisonItemV2("b", "B", 1200, 60, 2, 2, 120, EnergyClass.B, 6),
                    ComparisonItemV2("c", "C", 1400, 70, 3, 3, 140, EnergyClass.A4, 3),
                ),
                showOnlyDiffBinding = DataBindingPath("/comparison/showOnlyDiff"),
            ),
        ).jsonObject
        assertIs<ValidationResult.Valid>(
            ContractSchemaValidator.validateComponent(A2uiCatalogIds.V2.PROPERTY_COMPARISON_TABLE, v2Three),
        )
    }

    @Test
    fun `VisitRequestFormV2Props rejects relative binding path without leading slash`() {
        val form = VisitRequestFormV2Props(
            propertyId = "apt-01",
            propertyTitle = "Bilocale Isola",
            availableDates = listOf("2026-12-01"),
            dateBinding = DataBindingPath("visitForm/date"),
            timeSlotBinding = DataBindingPath("/visitForm/slot"),
            visitModeBinding = DataBindingPath("/visitForm/mode"),
            visitorNameBinding = DataBindingPath("/visitForm/name"),
            visitorEmailBinding = DataBindingPath("/visitForm/email"),
            notesBinding = DataBindingPath("/visitForm/notes"),
            explicitConfirmedBinding = DataBindingPath("/visitForm/confirmed"),
            statusBinding = DataBindingPath("/visitForm/status"),
        )
        val result = ContractSchemaValidator.validateComponent(
            A2uiCatalogIds.V2.VISIT_REQUEST_FORM,
            json.encodeToJsonElement(form).jsonObject,
        )
        assertIs<ValidationResult.Invalid>(result)
        assertTrue(result.errors.any { it.contains("dateBinding.path") })
    }

    @Test
    fun `unknown component type returns Invalid`() {
        val result = ContractSchemaValidator.validateComponent("urn:unknown:Widget", buildJsonObject { })
        assertIs<ValidationResult.Invalid>(result)
    }

    @Test
    fun `ClientCapabilities detects v1-only vs v2 support`() {
        val v1Only = ClientCapabilities(
            supportedCatalogIds = setOf(
                A2uiCatalogIds.BASIC_M3_CATALOG_ID,
                A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID,
            ),
        )
        assertTrue(v1Only.supportsV1())
        assertFalse(v1Only.supportsV2())

        val v2Client = ClientCapabilities()
        assertTrue(v2Client.supportsV1())
        assertTrue(v2Client.supportsV2())
        assertEquals(3, v2Client.maxComparisonItems)
    }
}
