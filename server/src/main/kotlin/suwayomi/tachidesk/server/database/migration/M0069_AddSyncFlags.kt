package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

// Tachiyomi sort/display/reader bitmasks, kept opaque so a sync merge won by Suwayomi
// does not zero them on other clients.
/** Collision-safe port of upstream M0064_AddSyncFlags; Komu has already released migrations through M0065. */
@Suppress("ClassName", "unused")
class M0069_AddSyncFlags : SQLMigration() {
    // H2 auto-commits DDL: a restart can observe only the first added columns.
    // language=sql
    override val sql: String =
        """
        ALTER TABLE category ADD COLUMN IF NOT EXISTS flags INT NOT NULL DEFAULT 0;
        ALTER TABLE manga ADD COLUMN IF NOT EXISTS viewer INT NOT NULL DEFAULT 0;
        ALTER TABLE manga ADD COLUMN IF NOT EXISTS viewer_flags INT NULL;
        ALTER TABLE manga ADD COLUMN IF NOT EXISTS chapter_flags INT NOT NULL DEFAULT 0;
        """.trimIndent()
}
