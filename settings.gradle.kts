plugins {
    // Lets Gradle download the JDK the target platform requires. Platform 2025.3 needs Java 21,
    // and this machine has only JBR 25 (not a JDK) and OpenJDK 26, so without a resolver the
    // build cannot satisfy its own toolchain request.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "spring-config-drift-inspector"
