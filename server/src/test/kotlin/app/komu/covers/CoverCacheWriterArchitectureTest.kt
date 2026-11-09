package app.komu.covers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source check (ADR-0084 Review 2): nothing writes into the cover-cache
 * directories except through [CoverCacheWriter] or the gated publication in
 * [FullSizeCoverService]. A file that names a cover-cache root and also
 * writes files must be listed here with its reason.
 */
class CoverCacheWriterArchitectureTest {
    private val sources = File("src/main/kotlin")
    private val coverRoots = Regex("""thumbnailDownloadsRoot|tempThumbnailCacheRoot|getThumbnailDownloadPath""")
    private val writes =
        Regex("""saveImage\(|getImageResponse\(|writeBytes\(|writeText\(|outputStream\(|renameTo\(|Files\.move\(|Files\.copy\(|copyTo\(""")

    /** Writers allowed next to a cover-cache root, and why. */
    private val allowed =
        mapOf(
            // Start-up directory migration, before any reader or writer exists.
            "suwayomi/tachidesk/server/ServerSetup.kt" to "startup",
            // Moves manga download folders and skips the thumbnail root.
            "suwayomi/tachidesk/server/Migration.kt" to "download-dir migration",
        )

    @Test
    fun `cover caches are written only under the publication gate`() {
        assertTrue(sources.isDirectory, "run from the server module")
        val offenders =
            sources
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { it.relativeTo(sources).invariantSeparatorsPath to it.readText() }
                .filter { (path, text) -> coverRoots.containsMatchIn(text) && writes.containsMatchIn(text) && path !in allowed }
                .map { it.first }
                .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the upstream thumbnail writers go through the gated writer`() {
        val manga = File(sources, "suwayomi/tachidesk/manga/impl/Manga.kt").readText()
        val provider = File(sources, "suwayomi/tachidesk/manga/impl/download/fileProvider/impl/ThumbnailFileProvider.kt").readText()
        assertTrue("CoverCacheWriter.cachedOrFetched" in manga)
        assertTrue("CoverCacheWriter" in provider && "saveImage(" !in provider)
        val service = File(sources, "app/komu/covers/FullSizeCoverService.kt").readText()
        // Publication replaces files only inside the gate.
        assertTrue(Regex("""publishIfCurrent\([^)]*\)\s*\{\s*for \(existing in catalog\.cachedCovers""").containsMatchIn(service))
        assertEquals(1, Regex("""\breplace\(existing""").findAll(service).count())
    }
}
