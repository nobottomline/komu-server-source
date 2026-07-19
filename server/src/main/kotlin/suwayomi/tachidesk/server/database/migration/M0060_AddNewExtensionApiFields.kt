package suwayomi.tachidesk.server.database.migration

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import de.neonew.exposed.migrations.helpers.SQLMigration
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.server.database.migration.helpers.MAYBE_TYPE_PREFIX
import suwayomi.tachidesk.server.database.migration.helpers.UNLIMITED_TEXT
import suwayomi.tachidesk.server.database.migration.helpers.toSqlName
import suwayomi.tachidesk.server.serverConfig

/**
 * Collision-safe semantic port of upstream M0057_AddNewExtensionApiFields.
 *
 * Komu uses an expand/contract rollout here. The API 1.6 columns are added
 * and backfilled while released-client compatibility columns remain intact.
 * A later migration may remove them only after all REST, GraphQL and iOS
 * consumers have moved to the richer representation.
 */
@Suppress("ClassName", "unused")
class M0060_AddNewExtensionApiFields : SQLMigration() {
    override val sql by lazy {
        """
        ALTER TABLE MANGA ADD COLUMN memo $UNLIMITED_TEXT DEFAULT '{}' NOT NULL;
        ALTER TABLE CHAPTER ADD COLUMN memo $UNLIMITED_TEXT DEFAULT '{}' NOT NULL;

        ALTER TABLE EXTENSION ADD COLUMN store_index_url VARCHAR(2048);
        UPDATE EXTENSION SET store_index_url = ${"repo".toSqlName()} WHERE ${"repo".toSqlName()} IS NOT NULL;
        CREATE INDEX extension_store_index_url ON EXTENSION (store_index_url);

        ALTER TABLE EXTENSION ALTER COLUMN version_code ${MAYBE_TYPE_PREFIX}BIGINT;
        ALTER TABLE EXTENSION ALTER COLUMN apk_name DROP NOT NULL;
        ${extensionLibBackfill()}
        ALTER TABLE EXTENSION ADD COLUMN apk_url VARCHAR(2048);
        ALTER TABLE EXTENSION ADD COLUMN content_warning INTEGER DEFAULT ${ContentWarning.SAFE.ordinal} NOT NULL;
        UPDATE EXTENSION SET content_warning = ${ContentWarning.MIXED.ordinal} WHERE is_nsfw = TRUE;
        ALTER TABLE EXTENSION ALTER COLUMN is_nsfw SET DEFAULT FALSE;

        ALTER TABLE SOURCE ADD COLUMN content_warning INTEGER DEFAULT ${ContentWarning.SAFE.ordinal} NOT NULL;
        UPDATE SOURCE SET content_warning = ${ContentWarning.MIXED.ordinal} WHERE is_nsfw = TRUE;
        """.trimIndent()
    }

    private fun extensionLibBackfill(): String =
        when (serverConfig.databaseType.value) {
            DatabaseType.POSTGRESQL -> {
                """
                ALTER TABLE EXTENSION ADD COLUMN extension_lib VARCHAR(16);
                UPDATE EXTENSION
                SET extension_lib = CONCAT(
                    SPLIT_PART(version_name, '.', 1),
                    '.',
                    SPLIT_PART(version_name, '.', 2)
                );
                ALTER TABLE EXTENSION ALTER COLUMN extension_lib SET NOT NULL;
                """.trimIndent()
            }

            DatabaseType.H2 -> {
                """
                ALTER TABLE EXTENSION ADD COLUMN extension_lib VARCHAR(16);
                UPDATE EXTENSION
                SET extension_lib = CASE
                    WHEN LOCATE('.', version_name, LOCATE('.', version_name) + 1) > 0
                    THEN SUBSTRING(version_name, 1, LOCATE('.', version_name, LOCATE('.', version_name) + 1) - 1)
                    ELSE version_name
                END;
                ALTER TABLE EXTENSION ALTER COLUMN extension_lib SET NOT NULL;
                """.trimIndent()
            }
        }
}
