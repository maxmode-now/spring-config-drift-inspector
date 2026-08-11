package com.example.sample

/**
 * Deliberately declared in a separate file from [ServerProperties] — used as a property type
 * there specifically to demonstrate that KotlinConfigurationPropertiesContractProvider does not
 * recurse into a type declared outside the annotated class's own file.
 */
data class DatabaseSettings(val url: String, val poolSize: Int)
