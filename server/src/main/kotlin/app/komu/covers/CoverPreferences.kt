/*
 * Copyright (C) Contributors to the Komu project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package app.komu.covers

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/**
 * The "Prefer full-size covers" server preference, a Komu-owned file in the
 * data root (`komu-covers/preferences.properties`). Absent or unreadable
 * means the default, [DEFAULT_PREFER_FULL_SIZE] (ADR-0084 §Default).
 */
class CoverPreferences(
    private val file: File,
) {
    @Volatile
    private var cached: Boolean? = null

    var preferFullSize: Boolean
        get() = cached ?: read().also { cached = it }
        set(value) {
            write(value)
            cached = value
        }

    private fun read(): Boolean {
        if (!file.isFile) return DEFAULT_PREFER_FULL_SIZE
        return runCatching {
            Properties()
                .apply { file.inputStream().use { load(it) } }
                .getProperty(KEY)
                ?.toBooleanStrictOrNull()
        }.getOrNull() ?: DEFAULT_PREFER_FULL_SIZE
    }

    @Synchronized
    private fun write(value: Boolean) {
        file.parentFile?.mkdirs()
        val staging = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}")
        try {
            staging.outputStream().use { Properties().apply { setProperty(KEY, value.toString()) }.store(it, null) }
            try {
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            staging.delete()
        }
    }

    companion object {
        const val DEFAULT_PREFER_FULL_SIZE = true
        private const val KEY = "preferFullSize"
    }
}
