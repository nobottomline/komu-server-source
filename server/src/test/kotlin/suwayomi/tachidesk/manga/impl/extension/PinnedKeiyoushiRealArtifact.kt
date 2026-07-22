package suwayomi.tachidesk.manga.impl.extension

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Assumptions.assumeTrue
import suwayomi.tachidesk.manga.impl.extension.github.NetworkExtensionStore
import suwayomi.tachidesk.manga.impl.extension.github.toExtensionInfos
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import suwayomi.tachidesk.manga.model.dataclass.ExtensionStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Exact external evidence used only by opt-in real-artifact tests. */
@OptIn(ExperimentalSerializationApi::class)
internal object PinnedKeiyoushiRealArtifact {
    data class Fixture(
        val root: Path,
        val jar: Path,
        val store: ExtensionStore,
        val expected: ExtensionInfo,
    )

    fun load(): Fixture {
        val configuredRoot =
            System.getProperty(REPOSITORY_PROPERTY)
                ?: System.getenv(REPOSITORY_ENVIRONMENT)
        assumeTrue(!configuredRoot.isNullOrBlank(), "Pinned Keiyoushi repository snapshot is not configured")

        val root = Path.of(configuredRoot).toAbsolutePath().normalize()
        val repoMetadata = requirePinnedFile(root, REPO_METADATA_NAME, REPO_METADATA_SIZE, REPO_METADATA_SHA256)
        val index = requirePinnedFile(root, INDEX_NAME, INDEX_SIZE, INDEX_SHA256)
        val jar = requirePinnedFile(root, "jar/$JAR_NAME", JAR_SIZE, JAR_SHA256)

        val advertised = Json.parseToJsonElement(Files.readString(repoMetadata)).jsonObject
        check(advertised.getValue("index_v2").jsonPrimitive.content == MUTABLE_INDEX_URL) {
            "Pinned Keiyoushi repo metadata changed its index_v2 location"
        }
        check(
            advertised
                .getValue("meta")
                .jsonObject
                .getValue("signingKeyFingerprint")
                .jsonPrimitive.content
                .lowercase() == SIGNING_KEY,
        ) {
            "Pinned Keiyoushi repo metadata changed its signing key"
        }

        val indexBytes = GZIPInputStream(Files.newInputStream(index, LinkOption.NOFOLLOW_LINKS)).use { it.readAllBytes() }
        val networkStore = ProtoBuf.decodeFromByteArray<NetworkExtensionStore>(indexBytes)
        val store = networkStore.toExtensionStore(PINNED_INDEX_URL)
        check(store.signingKey.lowercase() == SIGNING_KEY) { "Pinned Keiyoushi index changed its signing key" }
        val expected =
            requireNotNull(networkStore.extensionList) { "Pinned Keiyoushi index has no inline extension list" }
                .toExtensionInfos(store)
                .single { it.pkgName == PACKAGE_NAME }
                .copy(
                    storeIndexUrl = PINNED_INDEX_URL,
                    apkUrl = pinnedResourceUrl("apk/$APK_NAME"),
                    jarUrl = PINNED_JAR_URL,
                    iconUrl = pinnedResourceUrl("icon/$PACKAGE_NAME.png"),
                )
        requireExpectedIdentity(expected)

        return Fixture(root, jar, store, expected)
    }

    private fun requirePinnedFile(
        root: Path,
        relativeName: String,
        expectedSize: Long,
        expectedSha256: String,
    ): Path {
        val path = root.resolve(relativeName).normalize()
        check(path.startsWith(root)) { "Pinned fixture path escaped its configured root" }
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "Pinned fixture file is missing or unsafe: $relativeName"
        }
        check(Files.size(path) == expectedSize) { "Pinned fixture size changed: $relativeName" }
        check(StagedArtifact.sha256(path, MAX_FIXTURE_BYTES) == expectedSha256) {
            "Pinned fixture digest changed: $relativeName"
        }
        return path
    }

    private fun requireExpectedIdentity(expected: ExtensionInfo) {
        check(expected.name == "AHottie")
        check(expected.pkgName == PACKAGE_NAME)
        check(expected.versionCode == VERSION_CODE)
        check(expected.versionName == VERSION_NAME)
        check(expected.extensionLib == "1.4")
        check(expected.lang == "all")
        check(expected.contentWarning == ContentWarning.NSFW)
        check(expected.sources.size == 1)
        val source = expected.sources.single()
        check(source.id == SOURCE_ID)
        check(source.name == "AHottie")
        check(source.lang == "all")
        check(source.homeUrl == SOURCE_HOME_URL)
        check(source.contentWarning == ContentWarning.NSFW)
    }

    private fun pinnedResourceUrl(path: String): String = "https://raw.githubusercontent.com/keiyoushi/extensions/$REPOSITORY_COMMIT/$path"

    const val REPOSITORY_COMMIT = "6bb842a242c0b82e1600c78a485a81fcea1fddad"
    const val REPOSITORY_COMMIT_TIME = "2026-07-22T09:52:52Z"
    const val PACKAGE_NAME = "eu.kanade.tachiyomi.extension.all.ahottie"
    const val SOURCE_ID = 6289731484943315811L
    const val SOURCE_HOME_URL = "https://ahottie.top"
    const val VERSION_CODE = 3L
    const val VERSION_NAME = "1.4.3"
    const val JAR_NAME = "tachiyomi-all.ahottie-v1.4.3.jar"
    const val APK_NAME = "tachiyomi-all.ahottie-v1.4.3.apk"
    const val JAR_SIZE = 47_773L
    const val JAR_SHA256 = "2b36f1437e76ad2ff98beaaa07994f3fae853e3b2582f3cde14a1337e1d1dc34"
    const val JAR_GIT_BLOB = "966f3e2373fa64c8b9732ce20c33cae2844ee91e"
    const val INDEX_SIZE = 98_086L
    const val INDEX_SHA256 = "b3a0cc8c1e3251d0289b8979a17a62fa19fb7d217ab07ef78f64e7f3e31e53ba"
    const val INDEX_GIT_BLOB = "018aa5b02ae67cd7a2769efa6eefe386177422a8"
    const val REPO_METADATA_SIZE = 262L
    const val REPO_METADATA_SHA256 = "5e400e546b91b3977a463205c788a8fac744c1017c7f33b2770f1be14b26968e"
    const val SIGNING_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"
    const val MUTABLE_INDEX_URL = "https://github.com/keiyoushi/extensions/raw/repo/index.pb"
    const val PINNED_INDEX_URL =
        "https://raw.githubusercontent.com/keiyoushi/extensions/$REPOSITORY_COMMIT/index.pb"
    const val PINNED_JAR_URL =
        "https://raw.githubusercontent.com/keiyoushi/extensions/$REPOSITORY_COMMIT/jar/$JAR_NAME"
    const val REPOSITORY_ENVIRONMENT = "KOMU_KEIYOUSHI_REPO_DIR"
    const val REPOSITORY_PROPERTY = "komu.keiyoushiRepoDir"
    const val NETWORK_ENVIRONMENT = "KOMU_KEIYOUSHI_NETWORK_FIXTURE"

    private const val REPO_METADATA_NAME = "repo.json"
    private const val INDEX_NAME = "index.pb"
    private const val MAX_FIXTURE_BYTES = 4L * 1024L * 1024L
}
