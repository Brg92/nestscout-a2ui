package com.nestscout.a2ui.server.repository

import com.nestscout.a2ui.contract.domain.Amenity
import com.nestscout.a2ui.contract.domain.EnergyClass
import com.nestscout.a2ui.contract.domain.SearchCriteria
import com.nestscout.a2ui.contract.domain.SortOrder
import com.nestscout.a2ui.server.security.UrlAllowlistSanitizer
import com.nestscout.a2ui.server.security.UrlValidationResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropertyRepositoryTest {

    private val repository = PropertyRepository.fromResource()
    private val sanitizer = UrlAllowlistSanitizer()

    @Test
    fun `curated dataset loads 30 verified listings with allowlisted image URLs`() {
        val all = repository.findAll()
        assertEquals(30, all.size)
        all.forEach { listing ->
            assertTrue(listing.verified)
            assertTrue(listing.priceEur > 0)
            assertTrue(listing.pricePerSqm > 0)
            assertTrue(sanitizer.validate(listing.imageUrl) is UrlValidationResult.Allowed)
        }
    }

    @Test
    fun `findById and findByIds preserve order and ignore unknown IDs`() {
        assertNotNull(repository.findById("apt-01"))
        assertNull(repository.findById("apt-999"))

        val subset = repository.findByIds(listOf("apt-04", "unknown-id", "apt-01", "apt-04"))
        assertEquals(listOf("apt-04", "apt-01"), subset.map { it.id })
    }

    @Test
    fun `structured search filters by price rooms neighborhood energy and amenities`() {
        val criteria = SearchCriteria(
            maxPriceEur = 1700,
            minRooms = 2,
            neighborhood = "Isola",
            energyClasses = setOf(EnergyClass.A4, EnergyClass.A3, EnergyClass.A2, EnergyClass.A1),
            amenities = setOf(Amenity.METRO_NEAR),
            sortBy = SortOrder.PRICE_ASC,
        )

        val results = repository.search(criteria)
        assertEquals(listOf("apt-01", "apt-19"), results.map { it.id })
        assertTrue(results.zipWithNext().all { (a, b) -> a.priceEur <= b.priceEur })
    }

    @Test
    fun `keyword search matches room synonyms and amenities deterministically`() {
        val results = repository.search(
            SearchCriteria(
                query = "cerco bilocale a Isola con terrazzo",
                sortBy = SortOrder.RELEVANCE,
            ),
        )
        assertTrue(results.isNotEmpty())
        assertEquals("apt-01", results.first().id)
    }

    @Test
    fun `sortBy SQM_DESC orders largest listings first`() {
        val results = repository.search(SearchCriteria(sortBy = SortOrder.SQM_DESC))
        assertEquals(30, results.size)
        assertTrue(results.zipWithNext().all { (a, b) -> a.sqm >= b.sqm })
    }

    @Test
    fun `repository rejects duplicate IDs at initialization`() {
        val sample = repository.findById("apt-01")!!
        assertThrows<IllegalArgumentException> {
            PropertyRepository(listOf(sample, sample))
        }
    }
}
