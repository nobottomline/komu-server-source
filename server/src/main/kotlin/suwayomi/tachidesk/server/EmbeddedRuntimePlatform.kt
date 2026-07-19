package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/** Immutable host marker supplied by Komu's native JVM launcher. */
internal object EmbeddedRuntimePlatform {
    fun isIOS(platform: String? = System.getProperty(PLATFORM_PROPERTY)): Boolean = platform == IOS_PLATFORM

    fun supportsClasspathScanning(platform: String? = System.getProperty(PLATFORM_PROPERTY)): Boolean = !isIOS(platform)

    fun supportsCEF(platform: String? = System.getProperty(PLATFORM_PROPERTY)): Boolean = !isIOS(platform)

    private const val PLATFORM_PROPERTY = "komu.platform"
    private const val IOS_PLATFORM = "ios"
}
