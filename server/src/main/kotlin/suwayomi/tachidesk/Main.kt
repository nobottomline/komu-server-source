package suwayomi.tachidesk

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import app.komu.security.EmbeddedSessionCapability
import suwayomi.tachidesk.graphql.server.GraphQLSchemaProvider
import suwayomi.tachidesk.server.EmbeddedRuntimePlatform
import suwayomi.tachidesk.server.JavalinSetup.javalinSetup
import suwayomi.tachidesk.server.JavalinSetup.javalinStartSocket
import suwayomi.tachidesk.server.JavalinSetup.javalinStopSocket
import suwayomi.tachidesk.server.applicationSetup

fun main(args: Array<String>) {
    EmbeddedSessionCapability.initialize(args)
    // Trigger graphql schema generation as early as possible, takes multiple seconds, delaying the javalin startup otherwise
    if (EmbeddedRuntimePlatform.supportsClasspathScanning()) {
        GraphQLSchemaProvider.init()
    }
    applicationSetup()
    javalinSetup()
}

/** Re-opens only Jetty's listener when the iOS host returns to foreground. */
fun startSocket() {
    javalinStartSocket()
}

/** Closes only Jetty's listener before the iOS process is suspended. */
fun stopSocket() {
    javalinStopSocket()
}
