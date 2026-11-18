package app.komu.library

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Komu: makes every committed transaction durable before irreversible work
 * relies on it (review 5). H2: `CHECKPOINT SYNC` writes and syncs the
 * store. Other databases (PostgreSQL) are durable when a commit returns.
 * Throws when the database cannot confirm it.
 */
object DatabaseDurability {
    fun syncCommitted() {
        transaction {
            if (db.vendor.equals("h2", ignoreCase = true)) exec("CHECKPOINT SYNC")
        }
    }
}
