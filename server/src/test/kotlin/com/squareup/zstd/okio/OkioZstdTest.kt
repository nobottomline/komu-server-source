/*
 * Copyright (C) 2026 Komu contributors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.squareup.zstd.okio

import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class OkioZstdTest {
    @Test
    fun `iOS filter cache adapters pass through without loading native code`() {
        withPlatform("ios") {
            val encoded = Buffer()

            zstdCompress(encoded).use { sink ->
                val buffered = Buffer().writeUtf8("""{"filters":["all"]}""")
                sink.write(buffered, buffered.size)
            }

            assertEquals("""{"filters":["all"]}""", encoded.clone().readUtf8())
            zstdDecompress(encoded).use { source ->
                val decoded = Buffer()
                decoded.writeAll(source)
                assertEquals("""{"filters":["all"]}""", decoded.readUtf8())
            }
        }
    }

    @Test
    fun `desktop adapters preserve real zstd round trip`() {
        withPlatform(null) {
            val original = """{"filters":["all"],"items":[1,2,3,4,5]}"""
            val encoded = Buffer()

            zstdCompress(encoded).use { sink ->
                val buffered = Buffer().writeUtf8(original)
                sink.write(buffered, buffered.size)
            }

            assertFalse(encoded.clone().readByteArray().contentEquals(original.encodeToByteArray()))
            zstdDecompress(encoded).use { source ->
                val decoded = Buffer()
                decoded.writeAll(source)
                assertEquals(original, decoded.readUtf8())
            }
        }
    }

    @Test
    fun `zstd kmp okio binary surface remains available`() {
        val type = Class.forName("com.squareup.zstd.okio.OkioZstd")

        type.getDeclaredMethod("zstdCompress", okio.Sink::class.java)
        type.getDeclaredMethod("zstdDecompress", okio.Source::class.java)
        type.getDeclaredMethod("getEmptyByteArray")
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
