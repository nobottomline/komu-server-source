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
            ExtensionJarVerifier.verify(jarPath, KEIYOUSHI_SIGNING_KEY)
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
            ExtensionJarVerifier.verify(jarPath, KEIYOUSHI_SIGNING_KEY)
        }
    }

    @Test
    fun `ambiguous archive paths are rejected before signer decision`() {
        val jarPath = createUnsignedJar("example/../Escaped.class")

        assertFailsWith<IllegalArgumentException> {
            ExtensionJarVerifier.verify(jarPath, KEIYOUSHI_SIGNING_KEY)
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
            ExtensionJarVerifier.verify(jarPath, KEIYOUSHI_SIGNING_KEY)
        }
    }

    @Test
    fun `symbolic link artifact is rejected`() {
        val jarPath = createUnsignedJar("example/Unsigned.class")
        val link = tempDirectory.resolve("linked.jar")
        runCatching { link.createSymbolicLinkPointingTo(jarPath) }
            .onFailure { assumeTrue(false, "Symbolic links are unavailable: ${it.message}") }

        assertFailsWith<IllegalArgumentException> {
            ExtensionJarVerifier.verify(link, KEIYOUSHI_SIGNING_KEY)
        }
    }

    @Test
    fun `signed Keiyoushi repository jar verifies against pinned store key`() {
        val jarPath = findKeiyoushiJar()

        val result = ExtensionJarVerifier.verify(jarPath, KEIYOUSHI_SIGNING_KEY.uppercase())

        assertEquals(KEIYOUSHI_SIGNING_KEY, result.signerFingerprint)
        assertTrue(result.verifiedEntries > 10)
        assertTrue(result.uncompressedBytes > 0)
    }

    @Test
    fun `signed Keiyoushi repository jar is rejected for another key`() {
        val jarPath = findKeiyoushiJar()

        assertFailsWith<SecurityException> {
            ExtensionJarVerifier.verify(jarPath, "0".repeat(64))
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

    private fun findKeiyoushiJar(): Path {
        val repoDirectory =
            System.getProperty("komu.keiyoushiRepoDir")
                ?: System.getenv("KOMU_KEIYOUSHI_REPO_DIR")
        assumeTrue(!repoDirectory.isNullOrBlank(), "Keiyoushi repository path is not configured")

        val jarDirectory = Path.of(repoDirectory).resolve("jar")
        assumeTrue(Files.isDirectory(jarDirectory), "Keiyoushi repository has no JAR directory")
        val jarPath =
            Files.list(jarDirectory).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    .sorted()
                    .findFirst()
                    .orElse(null)
            }
        assumeTrue(jarPath != null, "Keiyoushi repository has no signed JAR fixture")
        return jarPath
    }

    private companion object {
        const val KEIYOUSHI_SIGNING_KEY =
            "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
        const val MAX_ARCHIVE_BYTES = 32L * 1024L * 1024L
    }
}
