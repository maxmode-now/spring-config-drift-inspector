package io.github.configdrift.parser

import io.github.configdrift.model.ProfileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerComposeNamingTest {

    @Test
    fun `classic docker-compose naming is recognised`() {
        assertTrue(DockerComposeNaming.matches("docker-compose.yml"))
        assertEquals(ProfileId.DEFAULT, DockerComposeNaming.profileFor("docker-compose.yml"))
        assertEquals(ProfileId("prod"), DockerComposeNaming.profileFor("docker-compose.prod.yml"))
    }

    @Test
    fun `compose spec naming is recognised too`() {
        assertTrue(DockerComposeNaming.matches("compose.yml"))
        assertEquals(ProfileId.DEFAULT, DockerComposeNaming.profileFor("compose.yml"))
        assertEquals(ProfileId("staging"), DockerComposeNaming.profileFor("compose.staging.yaml"))
    }

    @Test
    fun `override file is its own profile, not merged into default`() {
        assertEquals(ProfileId("override"), DockerComposeNaming.profileFor("docker-compose.override.yml"))
    }

    @Test
    fun `unrelated filenames are rejected`() {
        assertFalse(DockerComposeNaming.matches("application.yml"))
        assertFalse(DockerComposeNaming.matches("compose.txt"))
        assertFalse(DockerComposeNaming.matches("decompose.yml"))
    }
}
