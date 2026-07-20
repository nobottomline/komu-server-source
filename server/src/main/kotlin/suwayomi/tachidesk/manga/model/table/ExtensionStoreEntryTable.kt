package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/**
 * Durable store metadata used as the security identity for artifact preflight.
 *
 * This is intentionally separate from [ExtensionTable]. An installed extension row describes the
 * active runtime version, while this table describes the exact version currently advertised by a
 * store. Combining those lifecycles would let an update mix active metadata with a newer artifact.
 */
object ExtensionStoreEntryTable : LongIdTable("extension_store_entry") {
    val storeIndexUrl =
        reference(
            "store_index_url",
            ExtensionStoreTable.indexUrl,
            onDelete = ReferenceOption.CASCADE,
            onUpdate = ReferenceOption.CASCADE,
        )
    val signingKey = varchar("signing_key", 512)
    val snapshotToken = varchar("snapshot_token", 36)
    val name = varchar("name", 128)
    val pkgName = varchar("pkg_name", 128)
    val apkUrl = varchar("apk_url", 2048)
    val jarUrl = varchar("jar_url", 2048).nullable()
    val iconUrl = varchar("icon_url", 2048)
    val extensionLib = varchar("extension_lib", 16)
    val versionCode = long("version_code")
    val versionName = varchar("version_name", 16)
    val lang = varchar("lang", 32)
    val contentWarning = integer("content_warning")

    init {
        uniqueIndex(storeIndexUrl, pkgName)
        index(false, pkgName)
    }
}
