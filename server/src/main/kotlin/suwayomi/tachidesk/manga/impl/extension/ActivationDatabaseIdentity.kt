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
 * The two only ever move together (Komu's copy-on-migrate library copies,
 * its one-time adoption of the old layout, an iOS container move), so the
 * absolute data root says nothing about which database a journal belongs to
 * and made recovery refuse a journal after every such move (Komu review 4).
 * There the identity names the embedded H2 database relative to the data
 * root, and a journal written by an older server (whose identity included the
 * then absolute data root) is accepted: on iOS no other database could have
 * written it.
 */
internal object ActivationDatabaseIdentity {
    private const val H2 = "H2"
    private const val EMBEDDED_H2_LOCATION = "komu-ios-embedded:<dataRoot>/database"
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun of(
        databaseType: String,
        databaseUrl: String,
        databaseUsername: String,
        dataRoot: String,
        embeddedIOS: Boolean,
    ): String =
        if (embeddedIOS && databaseType == H2) {
            digest(H2, EMBEDDED_H2_LOCATION)
        } else {
            digest(databaseType, databaseUrl, databaseUsername, dataRoot)
        }

    fun accepts(
        recorded: String,
        current: String,
        databaseType: String,
        embeddedIOS: Boolean,
    ): Boolean = recorded == current || (embeddedIOS && databaseType == H2 && SHA256.matches(recorded))

    private fun digest(vararg material: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(material.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
