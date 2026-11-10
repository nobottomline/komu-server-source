/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.webview

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import suwayomi.tachidesk.manga.impl.util.PackageTools
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Runs the WebView contract stub extension inside the embedded runtime (ADR-0087),
 * where no REST client holds the session capability: loads the stub JAR through the
 * production extension class loader and runs its scenarios against the iOS WebView
 * provider and the app's native engine.
 *
 * Not part of the server JAR (the `qualification` source set, ADR-0087 Review 1):
 * `:server:webviewQualificationJar` packages it, a DEBUG iOS host puts that JAR on
 * the class path (`-KomuServerQualificationJar`) and calls [run] through JNI
 * (`-KomuWebViewContract`). A release build has neither the class nor a caller,
 * so no system property or environment variable can run it there.
 */
object WebViewContractQualification {
    private const val SOURCE_CLASS = "app.komu.webviewcontract.ContractSource"

    /** The stub JAR must be a regular file; [scenarios] is the stub's comma-separated list (`all` by default). */
    fun validated(
        stubJar: String,
        scenarios: String,
    ): Pair<Path, String> {
        val jar = Path.of(stubJar)
        require(Files.isRegularFile(jar)) { "WebView contract stub is not a file" }
        return jar to scenarios.ifBlank { "all" }
    }

    /** Runs the scenarios; a JSON report (`passed`, `failed`, `outcomes`). Logs one line per scenario. */
    @JvmStatic
    fun run(
        stubJar: String,
        scenarios: String,
    ): String =
        try {
            val (jar, names) = validated(stubJar, scenarios)
            val source = PackageTools.loadExtensionSources(jar, SOURCE_CLASS) as CatalogueSource
            val page = runBlocking { source.getSearchManga(1, names, FilterList()) }
            val titles = page.mangas.map { it.title }
            titles.forEach { logger.info { "WebView contract: $it" } }
            val failed = titles.count { it.startsWith("FAIL") }
            logger.info { "WebView contract finished: ${titles.size - failed} passed, $failed failed" }
            buildJsonObject {
                put("passed", titles.size - failed)
                put("failed", failed)
                put("outcomes", JsonArray(titles.map(::JsonPrimitive)))
            }.toString()
        } catch (t: Throwable) {
            logger.error(t) { "WebView contract diagnostic failed" }
            buildJsonObject { put("error", "${t::class.java.name}: ${t.message}") }.toString()
        }
}
