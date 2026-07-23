/*
 * Copyright (C) 2026 Komu contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package okhttp3

import okhttp3.ResponseBody.Companion.asResponseBody
import okio.BufferedSource
import okio.Source
import okio.buffer
import suwayomi.tachidesk.server.EmbeddedRuntimePlatform

/**
 * OkHttp-compatible optional compression interceptor with an iOS-safe path.
 *
 * Published extensions may construct this type with Brotli or Zstandard
 * algorithms. Their JVM implementations load native libraries at first
 * decompression, which are not part of Komu's statically linked iOS runtime.
 * Advertising those encodings would therefore turn an otherwise valid source
 * request into an unrecoverable [UnsatisfiedLinkError].
 *
 * The embedded iOS runtime therefore does not advertise optional compression;
 * OkHttp's regular bridge interceptor continues to handle gzip independently.
 * Outside iOS this class retains OkHttp 5.4's normal advertise-and-decompress
 * behavior so the shared server artifact does not change desktop semantics.
 */
@Suppress("ktlint:standard:function-naming")
open class CompressionInterceptor(
    vararg val algorithms: DecompressionAlgorithm,
) : Interceptor {
    private val acceptEncoding = algorithms.joinToString(",") { it.encoding }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (EmbeddedRuntimePlatform.isIOS()) {
            return chain.proceed(request)
        }

        if (algorithms.isEmpty() || request.header("Accept-Encoding") != null) {
            return chain.proceed(request)
        }

        val encodedRequest =
            request
                .newBuilder()
                .header("Accept-Encoding", acceptEncoding)
                .build()

        return `decompress$okhttp`(chain.proceed(encodedRequest))
    }

    /**
     * Retained for binary compatibility with OkHttp 5.4 internals.
     */
    fun `decompress$okhttp`(response: Response): Response {
        if (EmbeddedRuntimePlatform.isIOS() || !response.promisesBodyCompat()) {
            return response
        }

        val encoding = response.header("Content-Encoding") ?: return response
        val decompressor = `lookupDecompressor$okhttp`(encoding) ?: return response
        val body = response.body
        val decompressedSource = decompressor.decompress(body.source()).buffer()

        return response
            .newBuilder()
            .removeHeader("Content-Encoding")
            .removeHeader("Content-Length")
            .body(decompressedSource.asResponseBody(body.contentType(), -1L))
            .build()
    }

    /** Retained for binary compatibility with OkHttp 5.4 internals. */
    fun `lookupDecompressor$okhttp`(encoding: String): DecompressionAlgorithm? =
        algorithms.firstOrNull { it.encoding.equals(encoding, ignoreCase = true) }

    /** Retained for binary compatibility with OkHttp 5.4 internals. */
    fun `getAcceptEncoding$okhttp`(): String = acceptEncoding

    interface DecompressionAlgorithm {
        val encoding: String

        fun decompress(compressedSource: BufferedSource): Source
    }
}

/**
 * Local equivalent of OkHttp's internal `promisesBody` helper.
 *
 * Keeping this here avoids depending on an internal symbol while preserving
 * the public interceptor's response-body rules.
 */
private fun Response.promisesBodyCompat(): Boolean {
    if (request.method == "HEAD") {
        return false
    }

    if ((code < 100 || code >= 200) && code != 204 && code != 304) {
        return true
    }

    val contentLength = header("Content-Length")?.toLongOrNull() ?: -1L
    return contentLength != -1L ||
        header("Transfer-Encoding").equals("chunked", ignoreCase = true)
}
