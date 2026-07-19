package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.Table

/** Internal commit markers paired with durable extension activation journals. */
object ExtensionActivationMarkerTable : Table("extension_activation_marker") {
    val markerKey = varchar("marker_key", 64)
    val packageName = varchar("package_name", 255)

    override val primaryKey = PrimaryKey(markerKey)
}
