package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.security.MessageDigest

/**
 * The database a pending extension activation belongs to.
 *
 * Desktop: the database configuration including the data root, compared
 * exactly (a journal written against another database is refused).
 *
 * Komu on iOS (`komu.platform=ios`): the launcher forces H2, whose database
 * is `<dataRoot>/database`, next to the journal in `<dataRoot>/extensions`.
 * The two move together (Komu's copy-on-migrate library copies, its one-time
 * adoption of the old layout, an iOS container move), so no path names the
 * database (Komu review 4). Since review 5 the identity is a random id stored
 * once in the database itself ([DATABASE_ID_KEY] in the global meta table,
 * kept out of backups), so it travels with every copy of that database and
 * no other database has it: a journal restored without its database, or
 * next to another one, is refused.
 *
 * Transition: journals written before the id existed carry a path-based
 * identity (the absolute data root up to 2.4.2466, the root-relative constant
 * of 2.4.2468). They are accepted only while the database has no id yet,
 * i.e. on the first launch of this server on that database; on iOS no other
 * database could have written them then. Once the id exists, only this
 * server writes journals, and only with it.
 */
internal object ActivationDatabaseIdentity {
    private const val H2 = "H2"
    private const val EMBEDDED_H2_ID = "komu-ios-embedded-h2-id"
    const val DATABASE_ID_KEY = "komu.activationDatabaseId"
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** Desktop, or any non-embedded database: the configuration. */
    fun of(
        databaseType: String,
        databaseUrl: String,
        databaseUsername: String,
        dataRoot: String,
    ): String = digest(databaseType, databaseUrl, databaseUsername, dataRoot)

    /** Embedded iOS H2: the id stored in the database. */
    fun ofEmbeddedDatabase(databaseId: String): String = digest(H2, EMBEDDED_H2_ID, databaseId)

    fun usesDatabaseId(
        databaseType: String,
        embeddedIOS: Boolean,
    ): Boolean = embeddedIOS && databaseType == H2

    /**
     * Whether a journal that recorded [recorded] belongs to the database
     * whose identity is [current]. [databaseHadId]: the database already
     * had its id before this launch asked for it.
     */
    fun accepts(
        recorded: String,
        current: String,
        databaseType: String,
        embeddedIOS: Boolean,
        databaseHadId: Boolean,
    ): Boolean =
        recorded == current ||
            (usesDatabaseId(databaseType, embeddedIOS) && !databaseHadId && SHA256.matches(recorded))

    private fun digest(vararg material: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(material.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
