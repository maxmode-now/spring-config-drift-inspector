package io.github.configdrift.parser

import io.github.configdrift.model.ProfileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DotenvNamingTest {

    @Test
    fun `bare env file is the default profile`() {
        assertTrue(DotenvNaming.matches(".env"))
        assertEquals(ProfileId.DEFAULT, DotenvNaming.profileFor(".env"))
    }

    @Test
    fun `suffixed env file names its profile`() {
        assertTrue(DotenvNaming.matches(".env.production"))
        assertEquals(ProfileId("production"), DotenvNaming.profileFor(".env.production"))
    }

    @Test
    fun `local suffix merges into the same profile rather than creating its own`() {
        assertEquals(ProfileId.DEFAULT, DotenvNaming.profileFor(".env.local"))
        assertEquals(ProfileId("production"), DotenvNaming.profileFor(".env.production.local"))
    }

    @Test
    fun `unrelated filenames are rejected`() {
        assertFalse(DotenvNaming.matches("application.yml"))
        assertFalse(DotenvNaming.matches("env"))
        assertFalse(DotenvNaming.matches(".environment"))
        assertNull(DotenvNaming.profileFor("application.yml"))
    }
}
