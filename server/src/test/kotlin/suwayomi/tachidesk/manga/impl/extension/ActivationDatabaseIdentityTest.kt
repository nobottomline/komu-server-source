package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ActivationDatabaseIdentityTest {
    private fun identity(
        root: String,
        ios: Boolean,
        type: String = "H2",
    ) = ActivationDatabaseIdentity.of(type, "postgresql://localhost:5432/suwayomi", "", root, ios)

    @Test
    fun `desktop identity keeps the data root and refuses another database`() {
        val old = identity("/data/KomuServer", ios = false)
        val moved = identity("/data/KomuServer/server-data/active", ios = false)
        assertNotEquals(old, moved)
        assertFalse(ActivationDatabaseIdentity.accepts(old, moved, "H2", embeddedIOS = false))
    }

    @Test
    fun `embedded H2 identity does not depend on where the data root lives`() {
        assertEquals(identity("/a/KomuServer", ios = true), identity("/b/KomuServer/server-data/active", ios = true))
    }

    @Test
    fun `a journal of an older embedded server is recovered after its data root moved`() {
        // Written by 2.4.2466 before Komu adopted the old layout into server-data.
        val container = "/var/mobile/Containers/Data/Application"
        val legacy = identity("$container/X/Library/Application Support/KomuServer", ios = false)
        val current = identity("$container/Y/Library/Application Support/KomuServer/server-data/active", ios = true)
        assertTrue(ActivationDatabaseIdentity.accepts(legacy, current, "H2", embeddedIOS = true))
        assertFalse(ActivationDatabaseIdentity.accepts("not-a-digest", current, "H2", embeddedIOS = true))
        assertFalse(ActivationDatabaseIdentity.accepts(legacy, current, "POSTGRESQL", embeddedIOS = true))
    }
}
