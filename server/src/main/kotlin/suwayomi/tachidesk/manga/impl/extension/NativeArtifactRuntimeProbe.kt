package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * Side-effect-free capability probe for the raw JNI entry point.
 *
 * It deliberately invokes only [NativeArtifactBridge.abiVersion]: no executor,
 * temporary directory, network request, or extension installer is activated.
 */
internal object NativeArtifactRuntimeProbe {
    fun probe(bridge: NativeArtifactBridge = JniNativeArtifactBridge): NativeArtifactRuntimeStatus =
        try {
            val actualAbiVersion = bridge.abiVersion()
            NativeArtifactRuntimeStatus(
                available = actualAbiVersion == NATIVE_ARTIFACT_ABI_VERSION,
                actualAbiVersion = actualAbiVersion,
            )
        } catch (failure: Throwable) {
            NativeArtifactRuntimeStatus(
                available = false,
                failureType = failure.javaClass.name,
            )
        }
}

internal data class NativeArtifactRuntimeStatus(
    val available: Boolean,
    val expectedAbiVersion: Int = NATIVE_ARTIFACT_ABI_VERSION,
    val actualAbiVersion: Int? = null,
    val failureType: String? = null,
)
