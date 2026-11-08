/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import io.javalin.apibuilder.ApiBuilder.get
import io.javalin.apibuilder.ApiBuilder.path
import io.javalin.apibuilder.ApiBuilder.put
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import suwayomi.tachidesk.server.JavalinSetup.Attribute
import suwayomi.tachidesk.server.JavalinSetup.getAttribute
import suwayomi.tachidesk.server.user.requireUser

/**
 * `GET/PUT /api/v1/komu/covers/preferences` — the additive "Prefer
 * full-size covers" preference. An older server answers 404, which the app
 * reads as "not supported".
 */
object KomuCoverController {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Serializable
    data class Preferences(
        val preferFullSize: Boolean,
        /** Rule-set version the server verifies originals with. */
        val rulesVersion: Int = CoverOriginalRules.VERSION,
        /** Token the app adds to cover URLs; `null` when the preference is off. */
        val cacheToken: String? = null,
    )

    @Serializable
    data class Update(
        val preferFullSize: Boolean,
    )

    fun register() {
        path("komu/covers/preferences") {
            get { context ->
                context.getAttribute(Attribute.TachideskUser).requireUser()
                context.respond()
            }
            put { context ->
                context.getAttribute(Attribute.TachideskUser).requireUser()
                val update = decodeUpdate(context.body())
                if (update == null) {
                    context.status(HttpStatus.BAD_REQUEST).json(mapOf("error" to "preferFullSize required"))
                    return@put
                }
                FullSizeCovers.service.setPreferFullSize(update.preferFullSize)
                context.respond()
            }
        }
    }

    private fun Context.respond() {
        val service = FullSizeCovers.service
        contentType("application/json")
        result(encode(service.preferFullSize, service.cacheToken))
    }

    /** Every field is written, `cacheToken: null` included. */
    internal fun encode(
        preferFullSize: Boolean,
        cacheToken: String?,
    ): String = json.encodeToString(Preferences.serializer(), Preferences(preferFullSize, CoverOriginalRules.VERSION, cacheToken))

    internal fun decodeUpdate(body: String): Update? = runCatching { json.decodeFromString<Update>(body) }.getOrNull()
}
