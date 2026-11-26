package com.nestscout.a2ui.server.security

import java.net.URI
import java.net.URISyntaxException

sealed interface UrlValidationResult {
    data class Allowed(val normalizedUrl: String) : UrlValidationResult
    data class Blocked(val reason: String) : UrlValidationResult
}

class UrlAllowlistSanitizer(
    allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
    val fallbackImageUrl: String = DEFAULT_FALLBACK_IMAGE_URL,
) {
    private val normalizedAllowedHosts: Set<String> = allowedHosts
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    init {
        require(normalizedAllowedHosts.isNotEmpty()) { "allowedHosts must not be empty" }
        val fallbackCheck = validateInternal(fallbackImageUrl, normalizedAllowedHosts)
        require(fallbackCheck is UrlValidationResult.Allowed) {
            "fallbackImageUrl must satisfy allowlist policy: $fallbackImageUrl"
        }
    }

    fun validate(rawUrl: String): UrlValidationResult =
        validateInternal(rawUrl, normalizedAllowedHosts)

    fun sanitizeOrFallback(rawUrl: String): String =
        when (val result = validate(rawUrl)) {
            is UrlValidationResult.Allowed -> result.normalizedUrl
            is UrlValidationResult.Blocked -> fallbackImageUrl
        }

    companion object {
        val DEFAULT_ALLOWED_HOSTS: Set<String> = setOf(
            "images.unsplash.com",
            "cdn.nestscout.example",
        )

        const val DEFAULT_FALLBACK_IMAGE_URL: String =
            "https://cdn.nestscout.example/placeholders/apartment-verified.jpg"

        private val IPV4_LITERAL_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}\$")
        private val WHITESPACE_REGEX = Regex("\\s")

        fun fromCommaSeparated(rawHosts: String?): UrlAllowlistSanitizer {
            if (rawHosts.isNullOrBlank()) return UrlAllowlistSanitizer()
            val parsed = rawHosts.split(',')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            return UrlAllowlistSanitizer(
                allowedHosts = parsed + "cdn.nestscout.example",
            )
        }

        private fun validateInternal(
            rawUrl: String,
            allowedHosts: Set<String>,
        ): UrlValidationResult {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) {
                return UrlValidationResult.Blocked("URL must not be blank")
            }
            if (WHITESPACE_REGEX.containsMatchIn(trimmed)) {
                return UrlValidationResult.Blocked("URL must not contain whitespace")
            }

            val uri = try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                return UrlValidationResult.Blocked("Malformed URI syntax")
            }

            val scheme = uri.scheme?.lowercase()
            if (scheme != "https") {
                return UrlValidationResult.Blocked("Only https scheme is permitted, got: ${uri.scheme ?: "none"}")
            }

            if (uri.rawUserInfo != null) {
                return UrlValidationResult.Blocked("Embedded userinfo credentials are not permitted")
            }

            val host = uri.host?.lowercase()
            if (host.isNullOrBlank()) {
                return UrlValidationResult.Blocked("Missing or invalid host")
            }

            if (host.startsWith("[") || IPV4_LITERAL_REGEX.matches(host)) {
                return UrlValidationResult.Blocked("Raw IP address hosts are not permitted")
            }

            val rawPath = uri.rawPath ?: ""
            if (rawPath.split('/').any { it == ".." }) {
                return UrlValidationResult.Blocked("Path traversal segments are not permitted")
            }

            if (host !in allowedHosts) {
                return UrlValidationResult.Blocked("Host '$host' is not in approved allowlist")
            }

            return UrlValidationResult.Allowed(uri.toASCIIString())
        }
    }
}
