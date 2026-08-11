package io.github.configdrift.config

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigDriftProjectConfigTest {

    @Test
    fun parsesFullDocument() {
        val config = ConfigDriftProjectConfig.parse(
            """
            fail-on: warning
            path: services/api
            profiles:
              complete: [prod, stage]
              overlay: [local]
            """.trimIndent(),
        )
        assertEquals(FailOnThreshold.WARNING, config.failOn)
        assertEquals("services/api", config.path)
        assertEquals(setOf("prod", "stage"), config.completeProfiles)
        assertEquals(setOf("local"), config.overlayProfiles)
    }

    @Test
    fun absentKeysStayNull() {
        val config = ConfigDriftProjectConfig.parse("fail-on: never\n")
        assertEquals(FailOnThreshold.NEVER, config.failOn)
        assertNull(config.path)
        assertNull(config.completeProfiles)
        assertNull(config.overlayProfiles)
    }

    @Test
    fun rejectsUnknownTopLevelKey() {
        val error = assertFailsWith<IllegalArgumentException> {
            ConfigDriftProjectConfig.parse("fail-on: error\nunknown: true\n")
        }
        assertTrue(error.message!!.contains("unknown"))
    }

    @Test
    fun rejectsInvalidFailOn() {
        assertFailsWith<IllegalArgumentException> {
            ConfigDriftProjectConfig.parse("fail-on: fatal\n")
        }
    }

    @Test
    fun rejectsProfileInBothLists() {
        val error = assertFailsWith<IllegalArgumentException> {
            ConfigDriftProjectConfig.parse(
                """
                profiles:
                  complete: [local]
                  overlay: [local, dev]
                """.trimIndent(),
            )
        }
        assertTrue(error.message!!.contains("local"))
    }

    @Test
    fun discoverPrefersYml() {
        val dir = createTempDirectory("config-drift-")
        try {
            dir.resolve(".config-drift.yml").writeText("fail-on: error\n")
            dir.resolve(".config-drift.yaml").writeText("fail-on: never\n")
            val found = ConfigDriftProjectConfig.discover(dir)
            assertEquals(dir.resolve(".config-drift.yml"), found)
            assertEquals(FailOnThreshold.ERROR, ConfigDriftProjectConfig.load(found!!).failOn)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

class ConfigDriftSettingsResolverTest {

    @Test
    fun fileFailOnUsedWhenCliOmitsFlag() {
        val dir = createTempDirectory("config-drift-")
        try {
            dir.resolve(".config-drift.yml").writeText("fail-on: never\n")
            val resolved = ConfigDriftSettingsResolver.resolve(
                initialPath = dir,
                pathExplicit = false,
                failOnFromCli = null,
            )
            assertEquals(FailOnThreshold.NEVER, resolved.failOn)
            assertEquals(dir.toAbsolutePath().normalize(), resolved.analysisRoot)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun cliFailOnOverridesFile() {
        val dir = createTempDirectory("config-drift-")
        try {
            dir.resolve(".config-drift.yml").writeText("fail-on: never\n")
            val resolved = ConfigDriftSettingsResolver.resolve(
                initialPath = dir,
                pathExplicit = false,
                failOnFromCli = FailOnThreshold.ERROR,
            )
            assertEquals(FailOnThreshold.ERROR, resolved.failOn)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun cliCompleteAxisReplacesFileComplete() {
        val dir = createTempDirectory("config-drift-")
        try {
            dir.resolve(".config-drift.yml").writeText(
                """
                profiles:
                  complete: [prod]
                  overlay: [local]
                """.trimIndent(),
            )
            val resolved = ConfigDriftSettingsResolver.resolve(
                initialPath = dir,
                pathExplicit = false,
                completeFromCli = setOf("stage"),
                overlayFromCli = null,
            )
            assertEquals(setOf("stage"), resolved.completeProfiles)
            assertEquals(setOf("local"), resolved.overlayProfiles)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun filePathResolvesRelativeToConfigParent() {
        val dir = createTempDirectory("config-drift-")
        try {
            val nested = Files.createDirectories(dir.resolve("services/api"))
            dir.resolve(".config-drift.yml").writeText("path: services/api\n")
            val resolved = ConfigDriftSettingsResolver.resolve(
                initialPath = dir,
                pathExplicit = false,
            )
            assertEquals(nested.toAbsolutePath().normalize(), resolved.analysisRoot)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun explicitCliPathIgnoresFilePath() {
        val dir = createTempDirectory("config-drift-")
        try {
            Files.createDirectories(dir.resolve("services/api"))
            dir.resolve(".config-drift.yml").writeText("path: services/api\n")
            val resolved = ConfigDriftSettingsResolver.resolve(
                initialPath = dir,
                pathExplicit = true,
            )
            assertEquals(dir.toAbsolutePath().normalize(), resolved.analysisRoot)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun noConfigSkipsFile() {
        val dir = createTempDirectory("config-drift-")
        try {
            dir.resolve(".config-drift.yml").writeText("fail-on: never\n")
            val resolved = ConfigDriftSettingsResolver.resolve(
                initialPath = dir,
                pathExplicit = false,
                useConfig = false,
            )
            assertEquals(FailOnThreshold.ERROR, resolved.failOn)
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
