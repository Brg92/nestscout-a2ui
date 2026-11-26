package com.nestscout.a2ui.server.config

import com.nestscout.a2ui.server.security.UrlAllowlistSanitizer

data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val mockMode: Boolean = true,
    val remoteApiKey: String? = null,
    val plannerBaseUrl: String = DEFAULT_PLANNER_BASE_URL,
    val plannerModelId: String = DEFAULT_PLANNER_MODEL_ID,
    val allowedImageHosts: Set<String> = UrlAllowlistSanitizer.DEFAULT_ALLOWED_HOSTS,
) {
    init {
        require(port in 1..65535) { "PORT must be between 1 and 65535, got: $port" }
        require(plannerBaseUrl.startsWith("https://") || plannerBaseUrl.startsWith("http://")) {
            "PLANNER_API_BASE_URL must start with http:// or https://"
        }
        require(plannerModelId.isNotBlank()) { "PLANNER_MODEL_ID must not be blank" }
        if (!mockMode) {
            require(!remoteApiKey.isNullOrBlank()) {
                "GEMINI_API_KEY must be configured when A2UI_MOCK_MODE=false"
            }
        }
    }

    override fun toString(): String = buildString {
        append("ServerConfig(port=").append(port)
        append(", mockMode=").append(mockMode)
        append(", remoteApiKey=").append(if (remoteApiKey.isNullOrBlank()) "unset" else "***")
        append(", plannerBaseUrl=").append(plannerBaseUrl)
        append(", plannerModelId=").append(plannerModelId)
        append(", allowedImageHosts=").append(allowedImageHosts)
        append(')')
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_PLANNER_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_PLANNER_MODEL_ID = "gemini-2.5-flash"

        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val port = env["PORT"]?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: DEFAULT_PORT
            val mockRaw = env["A2UI_MOCK_MODE"]?.trim()?.lowercase()
            val mockMode = when (mockRaw) {
                null, "", "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> throw IllegalArgumentException("Invalid boolean for A2UI_MOCK_MODE: $mockRaw")
            }
            val apiKey = env["GEMINI_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }
            val baseUrl = (env["PLANNER_API_BASE_URL"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PLANNER_BASE_URL)
                .trimEnd('/')
            val modelId = env["PLANNER_MODEL_ID"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PLANNER_MODEL_ID
            val hostsRaw = env["ALLOWED_IMAGE_HOSTS"]?.trim()
            val hosts = if (hostsRaw.isNullOrEmpty()) {
                UrlAllowlistSanitizer.DEFAULT_ALLOWED_HOSTS
            } else {
                hostsRaw.split(',')
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet() + "cdn.nestscout.example"
            }

            return ServerConfig(
                port = port,
                mockMode = mockMode,
                remoteApiKey = apiKey,
                plannerBaseUrl = baseUrl,
                plannerModelId = modelId,
                allowedImageHosts = hosts,
            )
        }
    }
}
