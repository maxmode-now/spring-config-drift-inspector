package com.example.sample

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * No spring-configuration-metadata.json entry exists for these — this class exists specifically
 * to verify KotlinConfigurationPropertiesContractProvider fills that gap the same way the Java
 * MailProperties fixture does for ConfigurationPropertiesContractProvider.
 */
@ConfigurationProperties(prefix = "app.server")
data class ServerProperties(
    val host: String,           // app.server.host — leaf
    val timeout: Duration,      // app.server.timeout — leaf (known value type)
    val advanced: Advanced,     // recursed into — Advanced is declared in this same file
    val database: DatabaseSettings, // NOT recursed into — DatabaseSettings lives in another file
) {
    data class Advanced(
        val retries: Int,       // app.server.advanced.retries
        val backoffMs: Long,    // app.server.advanced.backoffMs
    )
}
