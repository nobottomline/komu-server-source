package suwayomi.tachidesk.manga.impl.extension

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtensionJarVerifierTest {
    @TempDir
    lateinit var tempDirectory: Path

    @Test
    fun `unsigned content is rejected`() {
        val jarPath = createUnsignedJar("example/Unsigned.class")

        assertFailsWith<SecurityException> {
            ExtensionJarVerifier.verify(jarPath, PinnedKeiyoushiRealArtifact.SIGNING_KEY)
        }
    }

    @Test
    fun `invalid signing fingerprint is rejected before artifact access`() {
        assertFailsWith<IllegalArgumentException> {
            ExtensionJarVerifier.verify(tempDirectory.resolve("missing.jar"), "not-a-sha256-fingerprint")
        }
    }

    @Test
    fun `manifest is mandatory`() {
        val jarPath = tempDirectory.resolve("no-manifest.jar")
        JarOutputStream(Files.newOutputStream(jarPath)).use { jar ->
            jar.putNextEntry(JarEntry("example/Unsigned.class"))
            jar.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
            jar.closeEntry()
        }

        assertFailsWith<IllegalArgumentException> {
            ExtensionJarVerifier.verify(jarPath, PinnedKeiyoushiRealArtifact.SIGNING_KEY)
        }
    }

    @Test
    fun `ambiguous archive paths are rejected before signer decision`() {
        val jarPath = createUnsignedJar("example/../Escaped.class")

        assertFailsWith<IllegalArgumentException> {
            ExtensionJarVerifier.verify(jarPath, PinnedKeiyoushiRealArtifact.SIGNING_KEY)
        }
    }

    @Test
    fun `archive larger than transport ceiling is rejected before parsing`() {
        val jarPath = tempDirectory.resolve("oversized.jar")
        FileChannel.open(jarPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            channel.position(MAX_ARCHIVE_BYTES)
            channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
        }

        assertFailsWith<IllegalArgumentException> {
            ExtensionJarVerifier.verify(jarPath, PinnedKeiyoushiRealArtifact.SIGNING_KEY)
        }
    }

    @Test
    fun `symbolic link artifact is rejected`() {
        val jarPath = createUnsignedJar("example/Unsigned.class")
        val link = tempDirectory.resolve("linked.jar")
        runCatching { link.createSymbolicLinkPointingTo(jarPath) }
            .onFailure { assumeTrue(false, "Symbolic links are unavailable: ${it.message}") }

        assertFailsWith<IllegalArgumentException> {
            ExtensionJarVerifier.verify(link, PinnedKeiyoushiRealArtifact.SIGNING_KEY)
        }
    }

    @Test
    fun `signed Keiyoushi repository jar verifies against pinned store key`() {
        val jarPath = PinnedKeiyoushiRealArtifact.load().jar

        val result = ExtensionJarVerifier.verify(jarPath, PinnedKeiyoushiRealArtifact.SIGNING_KEY.uppercase())

        assertEquals(PinnedKeiyoushiRealArtifact.SIGNING_KEY, result.signerFingerprint)
        assertTrue(result.verifiedEntries > 10)
        assertTrue(result.uncompressedBytes > 0)
    }

    @Test
    fun `signed Keiyoushi repository jar is rejected for another key`() {
        val jarPath = PinnedKeiyoushiRealArtifact.load().jar

        assertFailsWith<SecurityException> {
            ExtensionJarVerifier.verify(jarPath, "0".repeat(64))
        }
    }

    @Test
    fun `authenticated entry result is an immutable defensive snapshot`() {
        val jarPath = PinnedKeiyoushiRealArtifact.load().jar
        val authenticated =
            ExtensionJarVerifier.verifyAndRead(
                jarPath = jarPath,
                expectedSigningKey = PinnedKeiyoushiRealArtifact.SIGNING_KEY,
                entryName = "AndroidManifest.xml",
                entrySizeLimit = ExtensionJarManifestParser.MAX_MANIFEST_BYTES.toLong(),
            )
        val firstCopy = authenticated.copyBytes()
        val originalFirstByte = firstCopy.first()
        firstCopy[0] = (originalFirstByte.toInt() xor 0xFF).toByte()

        assertEquals(originalFirstByte, authenticated.copyBytes().first())
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (authenticated.signature.verifiedEntryNames as MutableSet<String>).clear()
        }
    }

    private fun createUnsignedJar(entryName: String): Path {
        val jarPath = tempDirectory.resolve("unsigned-${entryName.hashCode()}.jar")
        val manifest = Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0" }
        JarOutputStream(Files.newOutputStream(jarPath), manifest).use { jar ->
            jar.putNextEntry(JarEntry(entryName))
            jar.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
            jar.closeEntry()
        }
        return jarPath
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 32L * 1024L * 1024L
    }
}
