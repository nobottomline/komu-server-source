package suwayomi.tachidesk.manga.impl.extension

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.manga.impl.extension.github.NetworkExtensionStore
import suwayomi.tachidesk.manga.impl.extension.github.toExtensionInfos
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class KeiyoushiRepositoryCorpusTest {
    @Test
    fun `all pinned repository jars authenticate and match exact index identities`() {
        val fixture = PinnedKeiyoushiRealArtifact.load()
        val networkStore =
            GZIPInputStream(
                Files.newInputStream(
                    fixture.root.resolve(INDEX_NAME),
                    LinkOption.NOFOLLOW_LINKS,
                ),
            ).use { input ->
                ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(input.readAllBytes())
            }
        val extensions =
            requireNotNull(networkStore.extensionList)
                .toExtensionInfos(fixture.store)

        assertEquals(EXPECTED_EXTENSION_COUNT, extensions.size)
        assertEquals(EXPECTED_EXTENSION_COUNT, extensions.map { it.pkgName }.toSet().size)
        assertEquals(
            EXPECTED_API_DISTRIBUTION,
            extensions.groupingBy { it.extensionLib }.eachCount().toSortedMap(),
        )

        val failures = mutableListOf<String>()
        extensions.forEach { expected ->
            runCatching {
                val apkUrl = expected.apkUrl
                val jarUrl =
                    requireNotNull(expected.jarUrl) {
                        "${expected.pkgName}: direct JAR URL is absent"
                    }
                assertTrue(apkUrl.startsWith(HTTPS_PREFIX), "${expected.pkgName}: APK URL is not HTTPS")
                assertTrue(jarUrl.startsWith(HTTPS_PREFIX), "${expected.pkgName}: JAR URL is not HTTPS")

                val apkStem = artifactStem(apkUrl, APK_SUFFIX)
                val jarName = artifactName(jarUrl, JAR_SUFFIX)
                val jarStem = jarName.removeSuffix(JAR_SUFFIX)
                assertEquals(apkStem, jarStem, "${expected.pkgName}: APK/JAR artifact identity differs")

                val jarPath =
                    fixture.root
                        .resolve(JAR_DIRECTORY)
                        .resolve(jarName)
                        .normalize()
                assertTrue(jarPath.startsWith(fixture.root), "${expected.pkgName}: JAR path escaped fixture root")
                assertTrue(
                    Files.isRegularFile(jarPath, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(jarPath),
                    "${expected.pkgName}: JAR is missing or unsafe",
                )

                val metadata =
                    ExtensionJarArtifactInspector.inspect(
                        jarPath = jarPath,
                        expected = expected,
                        expectedSigningKey = fixture.store.signingKey,
                    )
                assertEquals(expected.pkgName, metadata.packageName)
                assertEquals(expected.versionCode, metadata.versionCode)
                assertEquals(expected.versionName, metadata.versionName)
                assertEquals(expected.extensionLib, metadata.extensionLib)
            }.onFailure { failure ->
                failures += "${expected.pkgName}: ${failure::class.java.simpleName}: ${failure.message}"
            }
        }

        assertEquals(
            emptyList(),
            failures,
            failures.take(MAX_REPORTED_FAILURES).joinToString(
                prefix = "Repository corpus failures:\n",
                separator = "\n",
            ),
        )
    }

    private fun artifactStem(
        url: String,
        suffix: String,
    ): String = artifactName(url, suffix).removeSuffix(suffix)

    private fun artifactName(
        url: String,
        suffix: String,
    ): String {
        val name = URI(url).path.substringAfterLast('/')
        require(name.endsWith(suffix)) { "Artifact URL does not end with $suffix" }
        return name
    }

    private companion object {
        const val INDEX_NAME = "index.pb"
        const val JAR_DIRECTORY = "jar"
        const val APK_SUFFIX = ".apk"
        const val JAR_SUFFIX = ".jar"
        const val HTTPS_PREFIX = "https://"
        const val EXPECTED_EXTENSION_COUNT = 1_369
        const val MAX_REPORTED_FAILURES = 25
        val EXPECTED_API_DISTRIBUTION = sortedMapOf("1.4" to 1_301, "1.6" to 68)
    }
}
