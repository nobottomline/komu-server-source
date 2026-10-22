/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.network.controller

import io.github.oshai.kotlinlogging.KotlinLogging
import io.javalin.apibuilder.ApiBuilder.delete
import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.apibuilder.ApiBuilder.post
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.manga.impl.extension.ExtensionStoreService
import suwayomi.tachidesk.manga.impl.extension.ExtensionsList
import suwayomi.tachidesk.manga.impl.extension.repoMatchRegex
import suwayomi.tachidesk.server.serverConfig

/**
 * Compatibility REST adapter for Komu's iOS client.
 *
 * Suwayomi's current extension-store domain is intentionally authoritative:
 * POST fetches and validates store metadata before committing it, DELETE
 * updates the store table, and both operations synchronize preferences through
 * [ExtensionStoreService]. The endpoint shape stays compatible with the
 * existing app without reviving the retired repository implementation.
 */
object KomuRepoController {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class RepoBody(
        val url: String,
    )

    fun register() {
        path("komu/repos") {
            get { context ->
                context.json(serverConfig.extensionStores.value)
            }

            post { context ->
                val requestedUrl = context.repoUrlFromBody()
                if (requestedUrl == null) {
                    context.badRequest("url required")
                    return@post
                }
                if (!requestedUrl.matches(repoMatchRegex)) {
                    context.badRequest("invalid github repo URL")
                    return@post
                }
                if (requestedUrl in serverConfig.extensionStores.value) {
                    context.status(HttpStatus.CONFLICT).json(mapOf("error" to "repo already added"))
                    return@post
                }

                val store =
                    runCatching {
                        runBlocking { ExtensionStoreService.fetch(requestedUrl) }
                    }.getOrElse { error ->
                        logger.warn(error) { "Failed to validate extension store '$requestedUrl'" }
                        context
                            .status(HttpStatus.BAD_GATEWAY)
                            .json(mapOf("error" to "extension store unavailable"))
                        return@post
                    }

                ExtensionStoreService.upsert(store)
                ExtensionStoreService.syncDbToPrefs()
                refreshExtensions("post-add")

                logger.info { "Added extension store: ${store.indexUrl}" }
                context.status(HttpStatus.CREATED).json(serverConfig.extensionStores.value)
            }

            delete { context ->
                val requestedUrl = context.repoUrlFromBody() ?: context.queryParam("url")?.trim()
                if (requestedUrl.isNullOrBlank()) {
                    context.badRequest("url required")
                    return@delete
                }

                ExtensionStoreService.remove(requestedUrl)
                ExtensionStoreService.syncDbToPrefs()
                refreshExtensions("post-remove")

                logger.info { "Removed extension store: $requestedUrl" }
                context.json(serverConfig.extensionStores.value)
            }

            path("refresh") {
                post { context ->
                    refreshExtensions("manual-refresh")
                    context.status(HttpStatus.OK).json(serverConfig.extensionStores.value)
                }
            }
        }
    }

    private fun Context.repoUrlFromBody(): String? =
        runCatching { json.decodeFromString<RepoBody>(body()).url.trim() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

    private fun Context.badRequest(message: String) {
        status(HttpStatus.BAD_REQUEST).json(mapOf("error" to message))
    }

    private fun refreshExtensions(reason: String) {
        runCatching { runBlocking { ExtensionsList.fetchExtensions() } }
            .onFailure { error -> logger.warn(error) { "Extension refresh failed ($reason)" } }
    }
}
