package suwayomi.tachidesk.server.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import suwayomi.tachidesk.manga.impl.util.PackageTools
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Komu (ADR-0086): runs the WebView contract stub extension inside the embedded
 * runtime, where no REST client holds the session capability. Only debug builds
 * of the app pass `-Dkomu.diagnostics=allowed`; the stub JAR, its catalogue class
 * and the scenarios come from the environment. Loads the JAR through the
 * production extension class loader and logs one line per scenario.
 */
object WebViewContractDiagnostic {
    const val ALLOW_PROPERTY = "komu.diagnostics"
    const val JAR_KEY = "KOMU_WEBVIEW_CONTRACT_JAR"
    const val SCENARIOS_KEY = "KOMU_WEBVIEW_CONTRACT_SCENARIOS"
    private const val SOURCE_CLASS = "app.komu.webviewcontract.ContractSource"

    data class Configuration(
        val jar: Path,
        val scenarios: String,
    )

    fun configuration(
        allowed: String? = System.getProperty(ALLOW_PROPERTY),
        environment: Map<String, String> = System.getenv(),
    ): Configuration? {
        if (allowed != "allowed") return null
        val jar = environment[JAR_KEY]?.let(Path::of) ?: return null
        require(Files.isRegularFile(jar)) { "WebView contract stub is not a file" }
        return Configuration(jar, environment[SCENARIOS_KEY]?.takeIf { it.isNotBlank() } ?: "all")
    }

    fun runIfConfigured() {
        val configuration =
            runCatching { configuration() }.getOrElse {
                logger.error { "WebView contract diagnostic rejected: ${it.message}" }
                return
            } ?: return
        logger.warn { "WebView contract diagnostic is enabled (debug build)" }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            delay(3_000)
            try {
                val source = PackageTools.loadExtensionSources(configuration.jar, SOURCE_CLASS) as CatalogueSource
                val page = source.getSearchManga(1, configuration.scenarios, FilterList())
                for (outcome in page.mangas) logger.info { "WebView contract: ${outcome.title}" }
                val failed = page.mangas.count { it.title.startsWith("FAIL") }
                logger.info { "WebView contract finished: ${page.mangas.size - failed} passed, $failed failed" }
            } catch (t: Throwable) {
                logger.error(t) { "WebView contract diagnostic failed" }
            }
        }
    }
}
