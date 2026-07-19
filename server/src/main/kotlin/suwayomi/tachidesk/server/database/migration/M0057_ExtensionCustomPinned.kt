package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration

/**
 * Released Komu migration for sideloaded extension ownership.
 *
 * The flag prevents a repository refresh from silently replacing a custom
 * extension build. Its version and meaning are immutable.
 */
@Suppress("ClassName", "unused")
class M0057_ExtensionCustomPinned : SQLMigration() {
    override val sql: String =
        "ALTER TABLE Extension ADD COLUMN is_custom_pinned BOOLEAN DEFAULT FALSE NOT NULL;"
}
