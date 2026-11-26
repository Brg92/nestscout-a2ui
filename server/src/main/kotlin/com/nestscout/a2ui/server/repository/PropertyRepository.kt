package com.nestscout.a2ui.server.repository

import com.nestscout.a2ui.contract.domain.Amenity
import com.nestscout.a2ui.contract.domain.PropertyListing
import com.nestscout.a2ui.contract.domain.SearchCriteria
import com.nestscout.a2ui.contract.domain.SortOrder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class ScoredProperty(
    val listing: PropertyListing,
    val matchScore: Int,
)

class PropertyRepository(
    listings: List<PropertyListing>,
) {
    private val orderedListings: List<PropertyListing>
    private val byId: Map<String, PropertyListing>

    init {
        require(listings.isNotEmpty()) { "Certified property dataset must not be empty" }
        val duplicateIds = listings.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicateIds.isEmpty()) { "Duplicate property IDs in dataset: $duplicateIds" }

        listings.forEach { item ->
            require(item.id.isNotBlank()) { "Property id must not be blank" }
            require(item.priceEur > 0) { "Property ${item.id} must have positive priceEur" }
            require(item.sqm > 0) { "Property ${item.id} must have positive sqm" }
            require(item.pricePerSqm > 0) { "Property ${item.id} must have positive pricePerSqm" }
            require(item.rooms > 0) { "Property ${item.id} must have positive rooms" }
            require(item.availableVisitDates.isNotEmpty()) { "Property ${item.id} must declare visit dates" }
            require(item.verified) { "Property ${item.id} in curated dataset must be verified" }
        }

        orderedListings = listings.toList()
        byId = orderedListings.associateBy { it.id }
    }

    fun findAll(): List<PropertyListing> = orderedListings

    fun findById(id: String): PropertyListing? = byId[id]

    fun findByIds(ids: List<String>): List<PropertyListing> =
        ids.distinct().mapNotNull { byId[it] }

    fun availableNeighborhoods(): List<String> =
        orderedListings.map { it.neighborhood }.distinct().sorted()

    fun search(criteria: SearchCriteria): List<PropertyListing> =
        searchScored(criteria).map { it.listing }

    fun searchScored(
        criteria: SearchCriteria,
        extraQuery: String = "",
    ): List<ScoredProperty> {
        val combinedQuery = listOf(criteria.query, extraQuery)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        val keywords = extractKeywords(combinedQuery)

        val filtered = orderedListings.mapNotNull { listing ->
            if (!matchesStructuredCriteria(listing, criteria)) {
                return@mapNotNull null
            }

            if (keywords.isEmpty()) {
                val baseScore = computeBaseQualityScore(listing)
                return@mapNotNull ScoredProperty(listing = listing, matchScore = baseScore)
            }

            val matchedCount = keywords.count { token -> matchesKeyword(listing, token) }
            if (matchedCount == 0) {
                return@mapNotNull null
            }

            val ratioBoost = (matchedCount * 28) / keywords.size
            val score = (68 + ratioBoost + energyBonus(listing)).coerceIn(60, 99)
            ScoredProperty(listing = listing, matchScore = score)
        }

        return when (criteria.sortBy) {
            SortOrder.RELEVANCE -> filtered.sortedWith(
                compareByDescending<ScoredProperty> { it.matchScore }
                    .thenBy { it.listing.energyClass.ordinal }
                    .thenBy { it.listing.priceEur }
                    .thenBy { it.listing.id },
            )
            SortOrder.PRICE_ASC -> filtered.sortedWith(
                compareBy<ScoredProperty> { it.listing.priceEur }
                    .thenByDescending { it.matchScore }
                    .thenBy { it.listing.id },
            )
            SortOrder.SQM_DESC -> filtered.sortedWith(
                compareByDescending<ScoredProperty> { it.listing.sqm }
                    .thenBy { it.listing.priceEur }
                    .thenBy { it.listing.id },
            )
        }
    }

    private fun matchesStructuredCriteria(
        listing: PropertyListing,
        criteria: SearchCriteria,
    ): Boolean {
        if (criteria.maxPriceEur != null && listing.priceEur > criteria.maxPriceEur!!) return false
        if (criteria.minRooms != null && listing.rooms < criteria.minRooms!!) return false
        if (criteria.minSqm != null && listing.sqm < criteria.minSqm!!) return false
        if (!criteria.neighborhood.isNullOrBlank() &&
            !listing.neighborhood.equals(criteria.neighborhood!!.trim(), ignoreCase = true)
        ) {
            return false
        }
        if (criteria.energyClasses.isNotEmpty() && listing.energyClass !in criteria.energyClasses) return false
        if (criteria.amenities.isNotEmpty() && !listing.amenities.containsAll(criteria.amenities)) return false
        return true
    }

    private fun matchesKeyword(listing: PropertyListing, token: String): Boolean {
        val searchableText = buildString {
            append(listing.title).append(' ')
            append(listing.neighborhood).append(' ')
            append(listing.address).append(' ')
            append(listing.highlights.joinToString(" "))
        }.lowercase()

        if (searchableText.contains(token)) return true

        return when (token) {
            "monolocale", "monolocali" -> listing.rooms == 1
            "bilocale", "bilocali" -> listing.rooms == 2
            "trilocale", "trilocali" -> listing.rooms == 3
            "quadrilocale", "quadrilocali" -> listing.rooms == 4
            "pentalocale", "pentalocali" -> listing.rooms >= 5
            "terrazzo", "terrazza", "terrazzino" -> Amenity.TERRACE in listing.amenities
            "balcone", "balconi" -> Amenity.BALCONY in listing.amenities
            "metro", "metropolitana" -> Amenity.METRO_NEAR in listing.amenities
            "box", "garage", "parcheggio", "auto" -> Amenity.PARKING in listing.amenities
            "ascensore" -> Amenity.ELEVATOR in listing.amenities
            "climatizzato", "condizionata", "condizionatore" -> Amenity.AIR_CONDITIONING in listing.amenities
            else -> false
        }
    }

    private fun computeBaseQualityScore(listing: PropertyListing): Int {
        val amenityScore = (listing.amenities.size * 2).coerceAtMost(8)
        val transitScore = if (listing.transitMinutes <= 4) 4 else 1
        return (80 + energyBonus(listing) + amenityScore + transitScore).coerceIn(70, 98)
    }

    private fun energyBonus(listing: PropertyListing): Int = when {
        listing.energyClass.ordinal <= 3 -> 5
        listing.energyClass.ordinal <= 5 -> 2
        else -> 0
    }

    private fun extractKeywords(rawQuery: String): List<String> {
        if (rawQuery.isBlank()) return emptyList()
        return rawQuery
            .lowercase()
            .split(NON_ALPHANUMERIC_REGEX)
            .filter { it.length >= 3 && it !in STOPWORDS }
            .distinct()
    }

    companion object {
        const val DEFAULT_DATASET_RESOURCE = "/dataset/properties.json"

        private val NON_ALPHANUMERIC_REGEX = Regex("[^\\p{L}\\p{N}]+")

        private val STOPWORDS = setOf(
            "con", "per", "una", "uno", "nel", "nella", "nei", "nelle", "dei", "del", "della", "delle",
            "degli", "che", "non", "piu", "meno", "cerco", "cerca", "vorrei", "mostra", "mostrami",
            "trova", "appartamento", "appartamenti", "casa", "case", "affitto", "milano", "zona", "quartiere",
        )

        private val datasetJson = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }

        fun fromResource(resourcePath: String = DEFAULT_DATASET_RESOURCE): PropertyRepository {
            val stream = PropertyRepository::class.java.getResourceAsStream(resourcePath)
                ?: throw IllegalStateException("Dataset resource not found: $resourcePath")
            val content = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = datasetJson.decodeFromString(ListSerializer(PropertyListing.serializer()), content)
            return PropertyRepository(parsed)
        }
    }
}
