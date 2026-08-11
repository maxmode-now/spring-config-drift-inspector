plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// When platformLocalPath is set we build against the installed IDE, which skips the
// ~1.5 GB platform download. Unset (the default) resolves from the JetBrains repo so
// the build stays reproducible on CI.
val platformLocalPath: String? = providers.gradleProperty("platformLocalPath").orNull

dependencies {
    intellijPlatform {
        if (platformLocalPath.isNullOrBlank()) {
            // Community and Ultimate stopped being published separately at 2025.3 (253); there is
            // now a single IDEA artifact, so there is no IC/IU choice left to make.
            intellijIdea(providers.gradleProperty("platformVersion"))
        } else {
            local(platformLocalPath)
        }

        // YAML and Properties PSI give us exact offsets for "jump to source",
        // which snakeyaml-style line guessing cannot. Java/Kotlin PSI are for reading
        // @ConfigurationProperties classes directly (Configuration*PropertiesContractProvider).
        bundledPlugins(
            "org.jetbrains.plugins.yaml",
            "com.intellij.properties",
            "com.intellij.java",
            "org.jetbrains.kotlin",
        )

        pluginVerifier()

        // Not adding testFramework(TestFrameworkType.Platform) yet: it installs a JUnit 5
        // session listener that needs a full IDE fixture, which fails the plain unit tests that
        // exist today. Add it together with the first BasePlatformTestCase-style test.
    }

    testImplementation(kotlin("test"))

    // The platform's test class loader references JUnit 4 types even for plain unit tests that
    // never touch the IDE; without it the test executor fails to start on org/junit/rules/TestRule.
    testImplementation("junit:junit:4.13.2")
}

// Platform 2025.3 runs on Java 21, so the plugin must not emit newer bytecode. Gradle downloads
// this JDK via the toolchain resolver in settings.gradle.kts.
kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            // 2025.3. Left open at the top end so newer releases are not artificially excluded.
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
