/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import app.komu.bridge.NativeCoverImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import suwayomi.tachidesk.server.EmbeddedRuntimePlatform
import java.io.File
import java.nio.file.Files

/**
 * Runtime qualification of the full-size cover chain inside the embedded
 * runtime (ADR-0084 Review 1). Unit tests call the native decoder directly
 * and the host JVM decodes with javax.imageio; this drives the production
 * classes the way the server does on iOS: [CoverImageInspector.platform]
 * resolves the `NativeCoverImage` JNI natives in OpenJ9, and a real
 * [FullSizeCoverService] verifies, publishes and classifies covers whose
 * candidate bytes come over HTTP from a loopback fixture the host serves.
 * [CoverEgress] must refuse that loopback fixture under its production
 * address policy.
 *
 * Not reachable over HTTP. The iOS host calls [run] through JNI in
 * development builds only (`-KomuCoverQualificationFixture`); it touches a
 * temporary directory and nothing of the server's data.
 */
object CoverQualification {
    private const val SITE = "qualification.invalid"
    private const val ORIGINAL_ID = 1
    private const val OTHER_ID = 2

    /** A JSON report; `"ok": true` when every check passed. Never throws. */
    @JvmStatic
    fun run(fixtureBaseUrl: String): String =
        runCatching { runBlocking { execute(fixtureBaseUrl.trimEnd('/') + "/") } }
            .getOrElse { error ->
                buildJsonObject {
                    put("ok", false)
                    put("error", "${error.javaClass.name}: ${error.message}")
                }.toString()
            }

    private suspend fun execute(base: String): String {
        val plain = OkHttpClient.Builder().followRedirects(false).build()

        fun fetch(path: String): ByteArray =
            plain.newCall(Request.Builder().url(base + path).build()).execute().use { response ->
                check(response.code == 200) { "Fixture $path answered ${response.code}" }
                response.body.bytes()
            }

        val root = Files.createTempDirectory("komu-cover-qualification").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            var ok = true
            val report =
                buildJsonObject {
                    fun verifyEqual(
                        key: String,
                        actual: Any?,
                        expected: Any?,
                    ) {
                        putAny(key, actual)
                        if (actual != expected) {
                            ok = false
                            putAny("$key.expected", expected)
                        }
                    }

                    // 1. Runtime and JNI resolution.
                    put("runtimeIOS", EmbeddedRuntimePlatform.isIOS())
                    val platform = CoverImageInspector.platform
                    verifyEqual(
                        "inspector",
                        platform?.javaClass?.simpleName,
                        if (EmbeddedRuntimePlatform.isIOS()) "NativeCoverImageInspector" else "ImageIoCoverImageInspector",
                    )
                    put("nativeAbi", runCatching { NativeCoverImage.abiVersion() }.getOrNull())

                    val thumbnail = fetch("q/thumbnail.jpg")
                    val original = fetch("q/original.png")
                    val other = fetch("q/other.png")
                    val thumbnailInspection = platform?.inspect(thumbnail)
                    val originalInspection = platform?.inspect(original)
                    put("thumbnailSize", thumbnailInspection?.let { "${it.width}x${it.height}" })
                    put("originalSize", originalInspection?.let { "${it.width}x${it.height}" })
                    verifyEqual("notImageInspection", platform?.inspect("<html></html>".toByteArray()), null)
                    if (thumbnailInspection != null && originalInspection != null) {
                        val comparison = CoverIdentity.compare(thumbnailInspection, originalInspection, 0.5)
                        put("identityKind", comparison.kind.name)
                        put("identityGain", comparison.resolutionGain)
                        put("identityHashDistance", comparison.hashDistance)
                    } else {
                        ok = false
                    }

                    // 2. Egress: the production address policy refuses the loopback fixture.
                    val fixtureHost = base.toHttpUrl().host
                    val port = base.toHttpUrl().port
                    put(
                        "egressLiteralLoopback",
                        refusal {
                            CoverEgress(requireHttps = false).get(
                                plain,
                                Headers.headersOf(),
                                "${base}egress/original.png",
                                setOf(fixtureHost),
                                1 shl 20,
                            )
                        },
                    )
                    put(
                        "egressLocalhostName",
                        refusal {
                            CoverEgress(requireHttps = false).get(
                                plain,
                                Headers.headersOf(),
                                "http://localhost:$port/egress/original.png",
                                setOf("localhost"),
                                1 shl 20,
                            )
                        },
                    )
                    put(
                        "egressHttpsRequired",
                        refusal {
                            CoverEgress().get(
                                plain,
                                Headers.headersOf(),
                                "${base}egress/original.png",
                                setOf(fixtureHost),
                                1 shl 20,
                            )
                        },
                    )

                    // 3. The real service: pending -> verified -> published -> original.
                    val temp = File(root, "tmp/thumbnails").apply { mkdirs() }
                    val downloads = File(root, "downloads/thumbnails").apply { mkdirs() }
                    val subjects =
                        mapOf(
                            ORIGINAL_ID to CoverSubject(ORIGINAL_ID, 1L, "https://$SITE", "https://$SITE/wp-content/uploads/q-193x278.jpg"),
                            OTHER_ID to CoverSubject(OTHER_ID, 1L, "https://$SITE", "https://$SITE/wp-content/uploads/other-193x278.jpg"),
                        )
                    for (id in subjects.keys) for (dir in listOf(temp, downloads)) File(dir, "$id.jpg").writeBytes(thumbnail)
                    val catalog =
                        object : CoverCatalog {
                            override suspend fun subject(mangaId: Int) = subjects[mangaId]

                            override fun cachedCovers(mangaId: Int) =
                                listOf(temp, downloads).map { File(it, "$mangaId.jpg") }.filter { it.isFile }
                        }
                    val http =
                        CoverHttp { _, url, allowedHosts, _ ->
                            check(url.toHttpUrl().host in allowedHosts) { "Candidate outside its allowed hosts" }
                            when {
                                url.endsWith("/q.jpg") -> CoverFetch(200, fetch("q/original.png"), "image/png")
                                url.endsWith("/other.jpg") -> CoverFetch(200, fetch("q/other.png"), "image/png")
                                else -> CoverFetch(404)
                            }
                        }
                    val service =
                        FullSizeCoverService(
                            preferences = CoverPreferences(File(root, "preferences.properties")),
                            store = CoverVerdictStore(File(root, "verdicts")),
                            inspector = { CoverImageInspector.platform },
                            http = http,
                            catalog = catalog,
                            scope = scope,
                            sourceSpacingMillis = 0,
                        )

                    suspend fun serve(id: Int): ServedCover {
                        val file = File(downloads, "$id.jpg")
                        return service.serve(id, file.inputStream() to "image/jpeg")
                    }

                    suspend fun settle(id: Int) {
                        repeat(1200) {
                            if (!service.isScheduled(id)) return
                            delay(50)
                        }
                        error("Verification of $id did not finish")
                    }

                    verifyEqual("originalFirstState", serve(ORIGINAL_ID).state, CoverServeState.PENDING)
                    settle(ORIGINAL_ID)
                    val store = CoverVerdictStore(File(root, "verdicts"))
                    verifyEqual("originalVerdict", store.get(subjects.getValue(ORIGINAL_ID).thumbnailUrl)?.kind, CoverVerdictKind.ACCEPTED)
                    val served = serve(ORIGINAL_ID)
                    verifyEqual("originalFinalState", served.state, CoverServeState.ORIGINAL)
                    verifyEqual("originalServedType", served.contentType, "image/png")
                    verifyEqual("originalServedBytesAreOriginal", served.stream.readAllBytes().contentEquals(original), true)
                    verifyEqual(
                        "originalCacheFiles",
                        listOf(temp, downloads).joinToString {
                            it
                                .list()
                                .orEmpty()
                                .sorted()
                                .filter { n ->
                                    n.startsWith("1.")
                                }.joinToString()
                        },
                        "1.jpg, 1.jpg",
                    )

                    verifyEqual("otherFirstState", serve(OTHER_ID).state, CoverServeState.PENDING)
                    settle(OTHER_ID)
                    verifyEqual(
                        "otherVerdict",
                        store.get(subjects.getValue(OTHER_ID).thumbnailUrl)?.kind,
                        CoverVerdictKind.DIFFERENT_PICTURE,
                    )
                    verifyEqual("otherFinalState", serve(OTHER_ID).state, CoverServeState.THUMBNAIL)
                    verifyEqual("otherCacheUnchanged", File(downloads, "$OTHER_ID.jpg").readBytes().contentEquals(thumbnail), true)
                    put("otherBytes", other.size)
                }
            return buildJsonObject {
                put("ok", ok && report["egressLiteralLoopback"].toString().contains("refused"))
                report.forEach { (key, value) -> put(key, value) }
            }.toString()
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private suspend fun refusal(block: suspend () -> CoverFetch): String =
        try {
            "not refused: HTTP ${block().status}"
        } catch (error: CoverEgressRefused) {
            "refused: ${error.message}"
        } catch (error: Exception) {
            "failed: ${error.javaClass.simpleName}"
        }

    private fun JsonObjectBuilder.putAny(
        key: String,
        value: Any?,
    ) {
        when (value) {
            null -> put(key, null as String?)
            is Boolean -> put(key, value)
            is Number -> put(key, value)
            else -> put(key, value.toString())
        }
    }
}
