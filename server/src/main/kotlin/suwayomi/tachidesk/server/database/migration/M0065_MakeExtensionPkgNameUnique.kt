package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.server.database.migration.helpers.toSqlName

/**
 * Upstream introduced this migration as M0060 after Komu had already
 * published its M0060-M0064 extension-store migration line. Keep the SQL
 * unchanged, but append it as M0065 so existing Komu databases retain their
 * immutable migration history and new databases still execute every step in
 * one consecutive sequence.
 */
@Suppress("ClassName", "unused")
class M0065_MakeExtensionPkgNameUnique : SQLMigration() {
    override val sql by lazy {
        """
        ALTER TABLE ${"extension".toSqlName()}
        ADD CONSTRAINT uc_pkg_name
        UNIQUE (${"pkg_name".toSqlName()});
        """.trimIndent()
    }
}
