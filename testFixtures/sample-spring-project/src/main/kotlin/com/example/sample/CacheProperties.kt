package com.example.sample

/**
 * No `prefix`/`value` argument at all — only `ignoreUnknownFields`, which is the case that used to
 * make `KotlinConfigurationPropertiesContractProvider` misread the prefix as `"true"` instead of
 * the empty string. See EXPECTED.md for how to verify this by hand.
 */
@org.springframework.boot.context.properties.ConfigurationProperties(ignoreUnknownFields = true)
data class CacheProperties(
    val enabled: Boolean, // top-level key: "enabled" — NOT "true.enabled"
)
