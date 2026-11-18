package suwayomi.tachidesk.manga.impl.backup.proto.handlers

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.global.impl.GlobalMeta
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.extension.ActivationDatabaseIdentity

object BackupGlobalMetaHandler {
    fun backup(flags: BackupFlags): Map<String, String> {
        if (!flags.includeClientData) {
            return emptyMap()
        }

        // Komu: the database's own id never travels to another database.
        return GlobalMeta.getMetaMap() - ActivationDatabaseIdentity.DATABASE_ID_KEY
    }

    fun restore(meta: Map<String, String>) {
        GlobalMeta.modifyMetas(meta - ActivationDatabaseIdentity.DATABASE_ID_KEY)
    }
}
