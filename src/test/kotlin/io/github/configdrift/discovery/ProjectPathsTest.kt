package io.github.configdrift.discovery

import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectPathsTest {

    @Test
    fun `strips the project base and returns a forward-slash path`() {
        assertEquals(
            "src/main/resources/application.yml",
            ProjectPaths.relativize("D:/proj", "D:/proj/src/main/resources/application.yml"),
        )
    }

    @Test
    fun `windows separators normalise on both sides`() {
        // The parser sees VFS paths (forward slashes) while a base path may arrive with
        // backslashes; both must produce the identical key or highlights silently never match.
        assertEquals(
            "src/main/resources/application.yml",
            ProjectPaths.relativize("""D:\proj""", """D:\proj\src\main\resources\application.yml"""),
        )
    }

    @Test
    fun `a trailing separator on the base path does not leak into the result`() {
        assertEquals(
            "application.yml",
            ProjectPaths.relativize("D:/proj/", "D:/proj/application.yml"),
        )
    }

    @Test
    fun `a file outside the project keeps its absolute path`() {
        assertEquals(
            "C:/elsewhere/application.yml",
            ProjectPaths.relativize("D:/proj", "C:/elsewhere/application.yml"),
        )
    }

    @Test
    fun `a sibling directory sharing a name prefix is not treated as inside the project`() {
        // "D:/proj2" starts with "D:/proj" as a string but is a different directory.
        assertEquals(
            "D:/proj2/application.yml",
            ProjectPaths.relativize("D:/proj", "D:/proj2/application.yml"),
        )
    }

    @Test
    fun `a missing base path leaves the path absolute`() {
        assertEquals(
            "D:/proj/application.yml",
            ProjectPaths.relativize(null, "D:/proj/application.yml"),
        )
    }
}
