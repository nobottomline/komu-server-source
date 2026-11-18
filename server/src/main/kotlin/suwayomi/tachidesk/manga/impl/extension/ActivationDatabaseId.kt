package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.global.model.table.GlobalMetaTable
import java.util.UUID

/**
 * Komu (review 5): the random id of this database for the activation
 * journal ([ActivationDatabaseIdentity]), stored once in the global meta
 * table under [ActivationDatabaseIdentity.DATABASE_ID_KEY].
 */
internal object ActivationDatabaseId {
    private val creation = Any()

    /** The id and whether it existed before this call. */
    fun getOrCreate(): Pair<String, Boolean> =
        synchronized(creation) {
            transaction {
                val existing =
                    GlobalMetaTable
                        .selectAll()
                        .where { GlobalMetaTable.key eq ActivationDatabaseIdentity.DATABASE_ID_KEY }
                        .orderBy(GlobalMetaTable.id)
                        .firstOrNull()
                        ?.get(GlobalMetaTable.value)
                if (existing != null) {
                    existing to true
                } else {
                    val created = UUID.randomUUID().toString()
                    GlobalMetaTable.insert {
                        it[key] = ActivationDatabaseIdentity.DATABASE_ID_KEY
                        it[value] = created
                    }
                    created to false
                }
            }
        }
}
