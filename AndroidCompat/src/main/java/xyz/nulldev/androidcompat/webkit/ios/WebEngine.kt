package xyz.nulldev.androidcompat.webkit.ios

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import app.komu.bridge.JsExecutor
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/**
 * Komu: the off-screen web engine behind [WKWebViewProvider]. In the app it is the
 * native WKWebView engine ([NativeWebEngine]); tests use a scripted fake. Every call
 * returns without waiting for the engine's UI thread.
 */
interface WebEngine {
    fun availability(): Int

    fun create(): Long

    fun destroy(handle: Long)

    fun submit(
        handle: Long,
        op: Int,
        id: Long,
        fields: List<String>,
    )

    /** Encoded events ([WebEngineFrames.decodeEvents]), or null after [timeoutMs] without any. */
    fun nextEvents(timeoutMs: Int): ByteArray?
}

/** [JsExecutor] as a [WebEngine]; null when the process does not link the native engine. */
object NativeWebEngine : WebEngine {
    fun linkedOrNull(): WebEngine? = if (runCatching { JsExecutor.isLinked() }.getOrDefault(false)) this else null

    override fun availability(): Int = JsExecutor.availability()

    override fun create(): Long = JsExecutor.create()

    override fun destroy(handle: Long) = JsExecutor.destroy(handle)

    override fun submit(
        handle: Long,
        op: Int,
        id: Long,
        fields: List<String>,
    ) = JsExecutor.submit(handle, op, id, WebEngineFrames.encodeFields(fields))

    override fun nextEvents(timeoutMs: Int): ByteArray? = JsExecutor.nextEvents(timeoutMs)
}

/** One engine event: [kind] is a `JsExecutor.EV_*` constant. */
data class WebEngineEvent(
    val handle: Long,
    val kind: Int,
    val id: Long,
    val fields: List<String>,
) {
    fun field(index: Int): String = fields.getOrElse(index) { "" }
}

/**
 * Wire format shared with the native engine (big-endian):
 * fields = int32 count, then per field int32 byte length + UTF-8 bytes;
 * event = int64 handle, int32 kind, int64 id, fields; a batch is events back to back.
 */
object WebEngineFrames {
    fun encodeFields(fields: List<String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(fields.size)
            for (field in fields) {
                val utf8 = field.toByteArray(Charsets.UTF_8)
                out.writeInt(utf8.size)
                out.write(utf8)
            }
        }
        return bytes.toByteArray()
    }

    fun encodeEvent(event: WebEngineEvent): ByteArray {
        val fields = encodeFields(event.fields)
        return ByteBuffer
            .allocate(8 + 4 + 8 + fields.size)
            .putLong(event.handle)
            .putInt(event.kind)
            .putLong(event.id)
            .put(fields)
            .array()
    }

    fun decodeEvents(batch: ByteArray): List<WebEngineEvent> {
        val buffer = ByteBuffer.wrap(batch)
        val events = ArrayList<WebEngineEvent>()
        while (buffer.remaining() >= 24) {
            val handle = buffer.long
            val kind = buffer.int
            val id = buffer.long
            val count = buffer.int
            require(count in 0..MAX_FIELDS) { "Malformed engine event: $count fields" }
            val fields = ArrayList<String>(count)
            repeat(count) {
                val length = buffer.int
                require(length in 0..buffer.remaining()) { "Malformed engine event field" }
                val bytes = ByteArray(length)
                buffer.get(bytes)
                fields += String(bytes, Charsets.UTF_8)
            }
            events += WebEngineEvent(handle, kind, id, fields)
        }
        require(!buffer.hasRemaining()) { "Trailing bytes in engine event batch" }
        return events
    }

    private const val MAX_FIELDS = 4096
}
