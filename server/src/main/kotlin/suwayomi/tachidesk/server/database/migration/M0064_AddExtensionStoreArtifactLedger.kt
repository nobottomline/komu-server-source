package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.AddTableMigration
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** Adds a durable, signer-bound store snapshot without changing active extension rows. */
@Suppress("ClassName", "unused")
class M0064_AddExtensionStoreArtifactLedger : AddTableMigration() {
    private class ExtensionStoreTable : IntIdTable("extensionstore") {
        val indexUrl = varchar("index_url", 2048).uniqueIndex()
    }

    private class ExtensionStoreEntryTable(
        storeTable: ExtensionStoreTable,
    ) : LongIdTable("extension_store_entry") {
        val storeIndexUrl =
            reference(
                "store_index_url",
                storeTable.indexUrl,
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

    private class ExtensionStoreSourceTable(
        entryTable: ExtensionStoreEntryTable,
    ) : LongIdTable("extension_store_source") {
        val entry = reference("entry", entryTable, ReferenceOption.CASCADE)
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

    override val tables: Array<Table>
        get() {
            val storeTable = ExtensionStoreTable()
            val entryTable = ExtensionStoreEntryTable(storeTable)
            return arrayOf(entryTable, ExtensionStoreSourceTable(entryTable))
        }
}
