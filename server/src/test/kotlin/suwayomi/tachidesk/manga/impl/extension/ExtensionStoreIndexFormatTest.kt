package suwayomi.tachidesk.manga.impl.extension

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.impl.extension.github.NetworkExtensionStore
import suwayomi.tachidesk.manga.impl.extension.github.NetworkLegacyExtensionRepo
import suwayomi.tachidesk.test.ApplicationTest
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every repository URL form Komu's repository screen accepts reaches the same
 * current catalogue: the v2 JSON index (`index.json`), the gzip protobuf index
 * (`index.pb`), the `repo.json` descriptor and the legacy `index.min.json`.
 *
 * The v2 fixture is a trimmed byte-for-byte copy of Keiyoushi's live
 * `index.json` (2026-11-09). Keiyoushi now publishes `index.min.json` only as
 * a two-entry "Outdated App" sentinel, so the legacy URL must follow
 * `repo.json` to `index_v2` instead of listing that sentinel.
 */
@OptIn(ExperimentalSerializationApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtensionStoreIndexFormatTest : ApplicationTest() {
    private val json: Json by injectLazy()
    private val protoBuf: ProtoBuf by injectLazy()

    private val indexJson: ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/extension-store/keiyoushi-index-v2-20261109.json")).use {
            it.readBytes()
        }

    private val fixture: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
            fun serve(
                path: String,
                body: () -> ByteArray,
            ) = createContext(path) { exchange ->
                val bytes = body()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            serve("/repo/index.json") { indexJson }
            serve("/repo/index.pb") { gzip(protoBuf.encodeToByteArray(decodedIndex())) }
            serve("/repo/repo.json") {
                """
                {
                  "index_v2": "${base()}/repo/index.pb",
                  "meta": {
                    "name": "Keiyoushi",
                    "website": "https://keiyoushi.github.io",
                    "signingKeyFingerprint": "${decodedIndex().signingKey}"
                  }
                }
                """.trimIndent().toByteArray()
            }
            serve("/repo/index.min.json") { LEGACY_SENTINEL.toByteArray() }
            start()
        }

    private fun base() = "http://127.0.0.1:${fixture.address.port}"

    private fun decodedIndex(): NetworkExtensionStore = json.decodeFromString(indexJson.decodeToString())

    @AfterAll
    fun stopFixture() = fixture.stop(0)

    @Test
    fun `v2 json index decodes as the current catalogue, not as a legacy descriptor`() {
        assertFalse(
            runCatching { json.decodeFromString<NetworkLegacyExtensionRepo>(indexJson.decodeToString()) }.isSuccess,
            "the v2 object must not be mistaken for a repo.json descriptor",
        )
        val store = runBlocking { ExtensionStoreService.fetch("${base()}/repo/index.json") }
        assertEquals("${base()}/repo/index.json", store.indexUrl)
        assertFalse(store.isLegacy)
        assertCurrentCatalogue(store)
    }

    @Test
    fun `protobuf, descriptor and legacy URLs reach the same catalogue`() {
        val expected = runBlocking { ExtensionStoreService.fetch("${base()}/repo/index.json") }
        for (url in listOf("index.pb", "repo.json", "index.min.json").map { "${base()}/repo/$it" }) {
            val store = runBlocking { ExtensionStoreService.fetch(url) }
            assertEquals("${base()}/repo/index.pb", store.indexUrl, url)
            assertFalse(store.isLegacy, url)
            assertEquals(expected.signingKey, store.signingKey, url)
            assertCurrentCatalogue(store)
        }
    }

    private fun assertCurrentCatalogue(store: suwayomi.tachidesk.manga.model.dataclass.ExtensionStore) {
        assertEquals("9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2", store.signingKey)
        assertNull(store.extensionListUrl)
        val extensions = runBlocking { ExtensionStoreService.getExtensions(store) }
        assertEquals(
            setOf(
                "eu.kanade.tachiyomi.extension.all.mangadex",
                "eu.kanade.tachiyomi.extension.en.asurascans",
                "eu.kanade.tachiyomi.extension.en.mangarawclub",
                "eu.kanade.tachiyomi.extension.zh.baozimanhua",
            ),
            extensions.map { it.pkgName }.toSet(),
        )
        assertTrue(extensions.all { it.extensionLib == "1.6" }, "every current extension is API 1.6")
        assertTrue(extensions.all { it.jarUrl?.startsWith("https://github.com/keiyoushi/extensions/releases/") == true })
        val asura = extensions.single { it.pkgName.endsWith(".asurascans") }
        assertEquals(106071L, asura.versionCode)
        assertEquals("1.6.71", asura.versionName)
        assertEquals(6247824327199706550L, asura.sources.single().id)
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private companion object {
        const val LEGACY_SENTINEL =
            """[{"name":"Outdated App","pkg":"eu.kanade.tachiyomi.extension.all.keiyoushi",""" +
                """"apk":"tachiyomi-all.keiyoushi-v1.4.1.apk","lang":"all","code":1,"version":"1.4.1","nsfw":0,""" +
                """"sources":[{"name":"Outdated App","lang":"all","id":"1","baseUrl":"https://keiyoushi.github.io"}]}]"""
    }
}
