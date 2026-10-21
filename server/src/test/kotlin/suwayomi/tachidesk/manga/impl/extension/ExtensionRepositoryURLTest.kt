package suwayomi.tachidesk.manga.impl.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionRepositoryURLTest {
    @Test
    fun `canonical protobuf and descriptors are accepted without breaking migration capture groups`() {
        for (file in listOf("index.pb", "repo.json", "index.min.json", "index.json")) {
            val url = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/$file"
            val match = requireNotNull(repoMatchRegex.matchEntire(url))
            assertEquals("keiyoushi", match.groupValues[2])
            assertEquals("extensions", match.groupValues[3])
            assertEquals("repo", match.groupValues[4])
            assertEquals(file, match.groupValues[5])
            assertTrue("https://github.com/keiyoushi/extensions/tree/repo/$file".matches(repoMatchRegex))
        }
    }

    @Test
    fun `protobuf support does not widen the trusted host and scheme policy`() {
        for (url in listOf(
            "http://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
            "https://example.com/keiyoushi/extensions/repo/index.pb",
            "https://github.com.evil.example/keiyoushi/extensions/repo/index.pb",
            "file:///tmp/index.pb",
        )) {
            assertFalse(url.matches(repoMatchRegex), url)
        }
    }
}
