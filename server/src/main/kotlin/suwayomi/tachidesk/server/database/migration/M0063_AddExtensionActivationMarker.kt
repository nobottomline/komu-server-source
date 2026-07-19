package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.AddTableMigration
import org.jetbrains.exposed.v1.core.Table

/** Adds the internal half of the extension activation commit protocol. */
@Suppress("ClassName", "unused")
class M0063_AddExtensionActivationMarker : AddTableMigration() {
    private class ExtensionActivationMarkerTable : Table("extension_activation_marker") {
        val markerKey = varchar("marker_key", 64)
        val packageName = varchar("package_name", 255)

        override val primaryKey = PrimaryKey(markerKey)
    }

    override val tables: Array<Table>
        get() = arrayOf(ExtensionActivationMarkerTable())
}
