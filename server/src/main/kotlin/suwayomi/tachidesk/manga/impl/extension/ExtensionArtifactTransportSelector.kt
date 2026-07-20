package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.server.EmbeddedRuntimePlatform

/** Selects a host transport without enabling any installer or direct-JAR call site. */
internal class ExtensionArtifactTransportSelector(
    private val platformProvider: () -> String? = { System.getProperty(PLATFORM_PROPERTY) },
    private val nativeTransportFactory: () -> NativeExtensionArtifactTransport = { NativeExtensionArtifactTransport() },
    private val desktopTransportFactory: () -> ExtensionArtifactTransport = { DesktopExtensionArtifactTransport() },
) {
    fun select(): ExtensionArtifactTransport {
        if (!EmbeddedRuntimePlatform.isIOS(platformProvider())) return desktopTransportFactory()

        val nativeTransport = nativeTransportFactory()
        check(nativeTransport.isAvailable()) {
            "Native extension artifact transport is unavailable for the iOS runtime"
        }
        return nativeTransport
    }

    private companion object {
        const val PLATFORM_PROPERTY = "komu.platform"
    }
}
