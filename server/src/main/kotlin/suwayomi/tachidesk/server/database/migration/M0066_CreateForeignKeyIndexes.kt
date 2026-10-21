package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

/** Collision-safe port of upstream M0061_CreateForeignKeyIndexes; Komu has already released migrations through M0065. */
@Suppress("ClassName", "unused")
class M0066_CreateForeignKeyIndexes : SQLMigration() {
    // H2 commits each DDL statement before the migration ledger row. Retry safely
    // if termination left only a prefix of these indexes on disk.
    override val sql by lazy {
        """
        CREATE INDEX IF NOT EXISTS idx_categorymanga_category ON CATEGORYMANGA (category);
        CREATE INDEX IF NOT EXISTS idx_categorymanga_manga ON CATEGORYMANGA (manga);

        CREATE INDEX IF NOT EXISTS idx_categorymeta_category ON CATEGORYMETA (category_ref);

        CREATE INDEX IF NOT EXISTS idx_chapter_manga ON CHAPTER (manga);
        CREATE INDEX IF NOT EXISTS idx_chaptermeta_chapter ON CHAPTERMETA (chapter_ref);

        CREATE INDEX IF NOT EXISTS idx_mangameta_manga ON MANGAMETA (manga_ref);

        CREATE INDEX IF NOT EXISTS idx_page_chapter ON PAGE (chapter);

        CREATE INDEX IF NOT EXISTS idx_source_extension ON SOURCE (extension);
        CREATE INDEX IF NOT EXISTS idx_sourcemeta_source ON SOURCEMETA (source_ref);

        CREATE INDEX IF NOT EXISTS idx_trackrecord_manga ON TRACKRECORD (manga_id);
        """.trimIndent()
    }
}
