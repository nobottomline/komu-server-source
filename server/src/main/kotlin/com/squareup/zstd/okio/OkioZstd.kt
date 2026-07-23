/*
 * Copyright (C) 2026 Komu contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@file:JvmName("OkioZstd")

package com.squareup.zstd.okio

import okio.Sink
import okio.Source
import okio.buffer
import suwayomi.tachidesk.server.EmbeddedRuntimePlatform

/**
 * ABI-compatible iOS fallback for zstd-kmp's Okio adapters.
 *
 * API 1.6 extension support uses these adapters for disposable local filter
 * caches. zstd-kmp's JVM implementation extracts and loads a native dynamic
 * library, which is not available to Komu's statically linked iOS process.
 * Passing the stream through stores the same JSON without compression and
 * keeps that cache functional. Network content encoding is handled separately
 * by Komu's iOS-safe [okhttp3.CompressionInterceptor].
 *
 * The fallback is selected only for the embedded iOS runtime. Desktop builds
 * retain zstd-kmp's normal native compression behavior.
 */
val emptyByteArray: ByteArray = byteArrayOf()

fun zstdCompress(sink: Sink): Sink =
    if (EmbeddedRuntimePlatform.isIOS()) {
        sink
    } else {
        createDesktopAdapter(
            adapterClassName = "com.squareup.zstd.okio.ZstdCompressSink",
            engineFactoryName = "zstdCompressor",
            stream = sink.buffer(),
        ) as Sink
    }

fun zstdDecompress(source: Source): Source =
    if (EmbeddedRuntimePlatform.isIOS()) {
        source
    } else {
        createDesktopAdapter(
            adapterClassName = "com.squareup.zstd.okio.ZstdDecompressSource",
            engineFactoryName = "zstdDecompressor",
            stream = source.buffer(),
        ) as Source
    }

/**
 * OkHttp declares zstd-kmp as a runtime-only dependency, so its implementation
 * types intentionally are not on this module's compile classpath. Resolve the
 * two public JVM adapters at the same boundary while preserving the exact
 * upstream implementation outside iOS.
 */
private fun createDesktopAdapter(
    adapterClassName: String,
    engineFactoryName: String,
    stream: Any,
): Any {
    val classLoader =
        Thread.currentThread().contextClassLoader
            ?: Source::class.java.classLoader
    val zstdClass = Class.forName("com.squareup.zstd.Zstd", true, classLoader)
    val engine = zstdClass.getMethod(engineFactoryName).invoke(null)
    val adapterClass = Class.forName(adapterClassName, true, classLoader)
    val constructor =
        adapterClass.constructors.singleOrNull { it.parameterCount == 2 }
            ?: error("Expected one two-argument constructor for $adapterClassName")

    return constructor.newInstance(stream, engine)
}
