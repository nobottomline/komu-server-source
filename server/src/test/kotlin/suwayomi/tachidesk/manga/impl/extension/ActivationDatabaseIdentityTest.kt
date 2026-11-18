package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ActivationDatabaseIdentityTest {
    private fun configuration(root: String) = ActivationDatabaseIdentity.of("H2", "postgresql://localhost:5432/suwayomi", "", root)

    /** The 2.4.2468 form: the embedded H2 database relative to the data root. */
    private val rootRelative2468 = "1d6c1b5a3f4e8c2a9b7d0e6f5a4c3b2a1908f7e6d5c4b3a291807f6e5d4c3b2a"

    @Test
    fun `desktop identity keeps the data root and refuses another database`() {
        val old = configuration("/data/KomuServer")
        val moved = configuration("/data/KomuServer/server-data/active")
        assertNotEquals(old, moved)
        assertFalse(ActivationDatabaseIdentity.accepts(old, moved, "H2", embeddedIOS = false, databaseHadId = false))
        assertTrue(ActivationDatabaseIdentity.accepts(old, old, "H2", embeddedIOS = false, databaseHadId = true))
    }

    @Test
    fun `embedded H2 identity is the database's id, wherever the data root lives`() {
        assertEquals(ActivationDatabaseIdentity.ofEmbeddedDatabase("id-a"), ActivationDatabaseIdentity.ofEmbeddedDatabase("id-a"))
        assertNotEquals(ActivationDatabaseIdentity.ofEmbeddedDatabase("id-a"), ActivationDatabaseIdentity.ofEmbeddedDatabase("id-b"))
        assertTrue(ActivationDatabaseIdentity.usesDatabaseId("H2", embeddedIOS = true))
        assertFalse(ActivationDatabaseIdentity.usesDatabaseId("H2", embeddedIOS = false))
        assertFalse(ActivationDatabaseIdentity.usesDatabaseId("POSTGRESQL", embeddedIOS = true))
    }

    /** Review 5 P3: a journal of another database is refused once ids exist. */
    @Test
    fun `a journal of another database is refused once the database has its id`() {
        val current = ActivationDatabaseIdentity.ofEmbeddedDatabase("id-a")
        val foreign = ActivationDatabaseIdentity.ofEmbeddedDatabase("id-b")
        assertFalse(ActivationDatabaseIdentity.accepts(foreign, current, "H2", embeddedIOS = true, databaseHadId = true))
        assertTrue(ActivationDatabaseIdentity.accepts(current, current, "H2", embeddedIOS = true, databaseHadId = true))
        // A journal of an older server once ids exist: no older server writes to
        // this database any more, so it cannot be this database's.
        assertFalse(ActivationDatabaseIdentity.accepts(rootRelative2468, current, "H2", embeddedIOS = true, databaseHadId = true))
    }

    @Test
    fun `a journal written before the id existed is accepted only while there is none`() {
        // Written by 2.4.2466 before Komu adopted the old layout into server-data.
        val container = "/var/mobile/Containers/Data/Application"
        val legacy = configuration("$container/X/Library/Application Support/KomuServer")
        val current = ActivationDatabaseIdentity.ofEmbeddedDatabase("fresh-id")
        assertTrue(ActivationDatabaseIdentity.accepts(legacy, current, "H2", embeddedIOS = true, databaseHadId = false))
        assertTrue(ActivationDatabaseIdentity.accepts(rootRelative2468, current, "H2", embeddedIOS = true, databaseHadId = false))
        assertFalse(ActivationDatabaseIdentity.accepts("not-a-digest", current, "H2", embeddedIOS = true, databaseHadId = false))
        assertFalse(ActivationDatabaseIdentity.accepts(legacy, current, "POSTGRESQL", embeddedIOS = true, databaseHadId = false))
    }
}
