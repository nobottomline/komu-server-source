package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** Expected source manifest belonging to one immutable store-entry generation. */
object ExtensionStoreSourceTable : LongIdTable("extension_store_source") {
    val entry = reference("entry", ExtensionStoreEntryTable, ReferenceOption.CASCADE)
    val sourceId = long("source_id")
    val name = varchar("name", 256)
    val lang = varchar("lang", 32)
    val homeUrl = varchar("home_url", 4096)
    val message = text("message").nullable()
    val contentWarning = integer("content_warning")

    init {
        uniqueIndex(entry, sourceId)
    }
}
