package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Side-effect-free capability probe for the raw JNI entry point.
 *
 * It invokes only ABI and private temporary-root attestation: no executor,
 * network request, artifact file, or extension installer is activated.
 */
internal object NativeArtifactRuntimeProbe {
    fun probe(
        bridge: NativeArtifactBridge = JniNativeArtifactBridge,
        expectedNativeRoot: Path = defaultNativeTemporaryRoot(),
    ): NativeArtifactRuntimeStatus {
        var actualAbiVersion: Int? = null
        return try {
            actualAbiVersion = bridge.abiVersion()
            if (actualAbiVersion != NATIVE_ARTIFACT_ABI_VERSION) {
                NativeArtifactRuntimeStatus(
                    available = false,
                    actualAbiVersion = actualAbiVersion,
                )
            } else {
                val normalizedRoot = expectedNativeRoot.toAbsolutePath().normalize()
                val temporaryRootVerified =
                    bridge.temporaryRootMatches(normalizedRoot.toString().toByteArray(StandardCharsets.UTF_8))
                NativeArtifactRuntimeStatus(
                    available = temporaryRootVerified,
                    actualAbiVersion = actualAbiVersion,
                    temporaryRootVerified = temporaryRootVerified,
                )
            }
        } catch (failure: Throwable) {
            NativeArtifactRuntimeStatus(
                available = false,
                actualAbiVersion = actualAbiVersion,
                failureType = failure.javaClass.name,
            )
        }
    }
}

internal data class NativeArtifactRuntimeStatus(
    val available: Boolean,
    val expectedAbiVersion: Int = NATIVE_ARTIFACT_ABI_VERSION,
    val actualAbiVersion: Int? = null,
    val temporaryRootVerified: Boolean? = null,
    val failureType: String? = null,
)
