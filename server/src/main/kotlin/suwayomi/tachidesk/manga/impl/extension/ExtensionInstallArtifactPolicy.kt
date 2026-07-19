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
 * Upstream direct JAR installation executes repository bytes without binding
 * them to the store signing identity. JAR execution remains a compile-time
 * disabled capability until the Komu verifier, bounded transport and
 * pre-activation pipeline have been ported to this upstream base.
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
    ): Selection {
        val usableApkUrl = apkUrl?.takeIf(String::isNotBlank)
        val usableJarUrl = jarUrl?.takeIf(String::isNotBlank)

        return when {
            DIRECT_JAR_EXECUTION_ENABLED && usableJarUrl != null -> Selection(Kind.DIRECT_JAR, usableJarUrl)
            usableApkUrl != null -> Selection(Kind.APK, usableApkUrl)
            usableJarUrl != null -> throw DirectJarInstallationDisabledException()
            else -> throw IllegalArgumentException("Extension store record has no usable artifact URL")
        }
    }

    fun requireExternalJarExecution() {
        if (!DIRECT_JAR_EXECUTION_ENABLED) throw DirectJarInstallationDisabledException()
    }

    private const val DIRECT_JAR_EXECUTION_ENABLED = false
}

internal class DirectJarInstallationDisabledException :
    IllegalStateException(
        "Direct JAR installation is disabled until signer verification and pre-activation validation are available",
    )
