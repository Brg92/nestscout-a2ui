package com.nestscout.a2ui.contract

import com.nestscout.a2ui.contract.protocol.A2uiCatalogIds
import com.nestscout.a2ui.contract.schema.ContractJsonSchemaGenerator
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractJsonSchemaGeneratorTest {

    @Test
    fun `v1 catalog schema contains all 4 v1 components`() {
        val schema = ContractJsonSchemaGenerator.generateCatalogSchema(A2uiCatalogIds.NESTSCOUT_V1_CATALOG_ID)
        val components = schema["components"]!!.jsonObject
        assertEquals(A2uiCatalogIds.V1.allComponents, components.keys)
    }

    @Test
    fun `v2 catalog schema contains all 4 v2 components`() {
        val schema = ContractJsonSchemaGenerator.generateCatalogSchema(A2uiCatalogIds.NESTSCOUT_V2_CATALOG_ID)
        val components = schema["components"]!!.jsonObject
        assertEquals(A2uiCatalogIds.V2.allComponents, components.keys)
        assertTrue(components.containsKey(A2uiCatalogIds.V2.PROPERTY_CARD))
    }

    @Test
    fun `unsupported catalogId throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            ContractJsonSchemaGenerator.generateCatalogSchema("urn:nestscout:catalog:v99")
        }
    }
}
