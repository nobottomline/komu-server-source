package suwayomi.gradle

import java.nio.file.Files
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JavaArtifactCompatibilityVerifierTest {
    @Test
    fun `accepts Java 17 root classes and future multi-release classes`() {
        val archive =
            createArchive(
                multiRelease = true,
                "example/Main.class" to classHeader(61),
                "META-INF/versions/21/example/Main.class" to classHeader(65),
            )

        val report = JavaArtifactCompatibilityVerifier.verify(archive, targetJava = 17)

        assertEquals(2, report.classCount)
        assertEquals(1, report.targetVisibleClassCount)
        assertEquals(1, report.futureMultiReleaseClassCount)
        assertEquals(61, report.highestTargetVisibleMajor)
    }

    @Test
    fun `rejects root class newer than target`() {
        val archive = createArchive(multiRelease = false, "example/Main.class" to classHeader(65))

        val error =
            assertFailsWith<IllegalArgumentException> {
                JavaArtifactCompatibilityVerifier.verify(archive, targetJava = 17)
            }

        assertTrue(error.message.orEmpty().contains("class major 65"))
    }

    @Test
    fun `rejects target-visible versioned class newer than target`() {
        val archive =
            createArchive(
                multiRelease = true,
                "example/Main.class" to classHeader(61),
                "META-INF/versions/17/example/Main.class" to classHeader(65),
            )

        assertFailsWith<IllegalArgumentException> {
            JavaArtifactCompatibilityVerifier.verify(archive, targetJava = 17)
        }
    }

    @Test
    fun `rejects versioned classes without multi-release manifest`() {
        val archive =
            createArchive(
                multiRelease = false,
                "example/Main.class" to classHeader(61),
                "META-INF/versions/21/example/Main.class" to classHeader(65),
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                JavaArtifactCompatibilityVerifier.verify(archive, targetJava = 17)
            }

        assertTrue(error.message.orEmpty().contains("without Multi-Release manifest"))
    }

    @Test
    fun `rejects module descriptors in classpath artifact`() {
        val archive =
            createArchive(
                multiRelease = true,
                "example/Main.class" to classHeader(61),
                "META-INF/versions/21/module-info.class" to classHeader(65),
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                JavaArtifactCompatibilityVerifier.verify(archive, targetJava = 17)
            }

        assertTrue(error.message.orEmpty().contains("Module descriptor is not allowed"))
    }

    private fun createArchive(
        multiRelease: Boolean,
        vararg entries: Pair<String, ByteArray>,
    ) =
        Files.createTempFile("java-artifact-compatibility", ".jar").toFile().apply {
            deleteOnExit()
            val manifest =
                Manifest().apply {
                    mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                    if (multiRelease) mainAttributes.putValue("Multi-Release", "true")
                }

            outputStream().use { fileOutput ->
                ZipOutputStream(fileOutput).use { zip ->
                    zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
                    manifest.write(zip)
                    zip.closeEntry()
                    entries.forEach { (path, bytes) ->
                        zip.putNextEntry(ZipEntry(path))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }
        }

    private fun classHeader(major: Int): ByteArray =
        byteArrayOf(
            0xCA.toByte(),
            0xFE.toByte(),
            0xBA.toByte(),
            0xBE.toByte(),
            0,
            0,
            (major ushr 8).toByte(),
            major.toByte(),
        )
}
