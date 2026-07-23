/*
 * Copyright (C) 2026 Komu contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package okhttp3

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CompressionInterceptorTest {
    @Test
    fun `optional native decompressor is not invoked on embedded request`() {
        withPlatform("ios") {
            val request = Request.Builder().url("https://example.invalid/manga").build()
            val response =
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.EMPTY)
                    .build()
            val chain = mockk<Interceptor.Chain>()

            every { chain.request() } returns request
            every { chain.proceed(request) } returns response

            val result =
                CompressionInterceptor(
                    object : CompressionInterceptor.DecompressionAlgorithm {
                        override val encoding = "native"

                        override fun decompress(compressedSource: BufferedSource): Source {
                            error("native decompressor must not run on iOS")
                        }
                    },
                ).intercept(chain)

            assertSame(response, result)
            verify(exactly = 1) { chain.proceed(request) }
        }
    }

    @Test
    fun `desktop path preserves okhttp compression negotiation`() {
        withPlatform(null) {
            val request = Request.Builder().url("https://example.invalid/manga").build()
            val forwardedRequest = slot<Request>()
            val chain = mockk<Interceptor.Chain>()

            every { chain.request() } returns request
            every { chain.proceed(capture(forwardedRequest)) } answers {
                Response
                    .Builder()
                    .request(firstArg())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Encoding", "identity-test")
                    .header("Content-Length", "7")
                    .body("payload".toResponseBody())
                    .build()
            }

            val identityAlgorithm =
                object : CompressionInterceptor.DecompressionAlgorithm {
                    override val encoding = "identity-test"

                    override fun decompress(compressedSource: BufferedSource): Source = compressedSource
                }
            val result = CompressionInterceptor(identityAlgorithm).intercept(chain)

            assertEquals("identity-test", forwardedRequest.captured.header("Accept-Encoding"))
            assertEquals(null, result.header("Content-Encoding"))
            assertEquals(null, result.header("Content-Length"))
            assertEquals("payload", result.body.string())
        }
    }

    @Test
    fun `okhttp 5_4 binary compatibility methods remain available`() {
        val type = CompressionInterceptor::class.java

        type.getDeclaredMethod("getAcceptEncoding\$okhttp")
        type.getDeclaredMethod("decompress\$okhttp", Response::class.java)
        type.getDeclaredMethod(
            "lookupDecompressor\$okhttp",
            String::class.java,
        )
    }

    private fun <T> withPlatform(
        platform: String?,
        block: () -> T,
    ): T {
        val key = "komu.platform"
        val previous = System.getProperty(key)
        if (platform == null) {
            System.clearProperty(key)
        } else {
            System.setProperty(key, platform)
        }

        return try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, previous)
            }
        }
    }
}
