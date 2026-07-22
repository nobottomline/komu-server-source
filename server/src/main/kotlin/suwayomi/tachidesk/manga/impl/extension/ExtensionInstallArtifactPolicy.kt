package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Fail-closed artifact selection for the Komu reconciliation line.
 *
 * Published direct JARs are available only under an exact process-start
 * opt-in. External JAR uploads remain outside this capability because they do
 * not have an authenticated store identity.
 */
internal object ExtensionInstallArtifactPolicy {
    enum class Kind {
        APK,
        DIRECT_JAR,
    }

    data class Selection(
        val kind: Kind,
        val url: String,
    )

    fun selectPublishedArtifact(
        apkUrl: String?,
        jarUrl: String?,
        directJarEnabled: Boolean = publishedDirectJarEnabled(),
    ): Selection {
        val usableApkUrl = apkUrl?.takeIf(String::isNotBlank)
        val usableJarUrl = jarUrl?.takeIf(String::isNotBlank)

        return when {
            directJarEnabled && usableJarUrl != null -> Selection(Kind.DIRECT_JAR, usableJarUrl)
            usableApkUrl != null -> Selection(Kind.APK, usableApkUrl)
            usableJarUrl != null -> throw DirectJarInstallationDisabledException()
            else -> throw IllegalArgumentException("Extension store record has no usable artifact URL")
        }
    }

    fun requireExternalJarExecution(): Nothing =
        throw DirectJarInstallationDisabledException("External JAR uploads are not authenticated by an extension store")

    internal fun publishedDirectJarEnabled(environment: Map<String, String> = System.getenv()): Boolean {
        val mode = environment[PUBLISHED_DIRECT_JAR_MODE] ?: return false
        require(mode == PUBLISHED_DIRECT_JAR_MODE_VALUE) { "Unsupported published direct-JAR mode" }
        return true
    }

    internal const val PUBLISHED_DIRECT_JAR_MODE = "KOMU_PUBLISHED_DIRECT_JAR_MODE"
    internal const val PUBLISHED_DIRECT_JAR_MODE_VALUE = "authenticated-v1"
}

internal class DirectJarInstallationDisabledException(
    message: String =
        "Published direct JAR installation requires " +
            "${ExtensionInstallArtifactPolicy.PUBLISHED_DIRECT_JAR_MODE}=" +
            ExtensionInstallArtifactPolicy.PUBLISHED_DIRECT_JAR_MODE_VALUE,
) : IllegalStateException(message)
