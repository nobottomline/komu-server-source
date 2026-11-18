package suwayomi.tachidesk.manga.impl.backup.proto

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AutomatedBackupOrderTest {
    @Test
    fun `old archives are pruned only after the new one was published`() {
        val events = mutableListOf<String>()
        val published = File("auto-new.tachibk")
        publishThenPrune(
            create = {
                events += "publish"
                published
            },
            prune = { keep ->
                events += "prune keeping ${keep.name}"
            },
        )
        assertEquals(listOf("publish", "prune keeping auto-new.tachibk"), events)
    }

    @Test
    fun `a failed backup prunes nothing`() {
        var pruned = false
        assertFailsWith<java.io.IOException> {
            publishThenPrune(
                create = { throw java.io.IOException("No space left on device") },
                prune = { pruned = true },
            )
        }
        assertEquals(false, pruned)
    }
}
