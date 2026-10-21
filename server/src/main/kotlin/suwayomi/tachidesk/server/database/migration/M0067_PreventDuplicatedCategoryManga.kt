package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.database.migration.helpers.toSqlName
import suwayomi.tachidesk.server.serverConfig

/** Collision-safe port of upstream M0062_PreventDuplicatedCategoryManga; Komu has already released migrations through M0065. */
@Suppress("ClassName", "unused")
class M0067_PreventDuplicatedCategoryManga : SQLMigration() {
    override val sql: String by lazy {
        val table = "CATEGORYMANGA".toSqlName()
        val manga = "MANGA".toSqlName()
        val category = "CATEGORY".toSqlName()
        // PostgreSQL rolls DDL back with the ledger transaction. H2 commits it
        // immediately, so a kill after constraint creation must allow replay.
        val ifNotExists = if (serverConfig.databaseType.value == DatabaseType.H2) "IF NOT EXISTS " else ""

        """
        DELETE FROM $table
        WHERE ID NOT IN (
            SELECT MIN(ID)
            FROM $table
            GROUP BY $manga, $category
        );

        ALTER TABLE $table
            ADD CONSTRAINT ${ifNotExists}UC_${"CATEGORYMANGA"} UNIQUE ($manga, $category);
        """.trimIndent()
    }
}
