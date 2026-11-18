package xyz.nulldev.ts.config

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Komu: the I/O of `server.conf`. The file holds the user's settings and
 * extension repositories, and the server reconciles its database with them,
 * so it is never truncated in place and an unreadable file is never taken
 * for an empty one.
 *
 * - "absent" is decided only by `ENOENT` ([NoSuchFileException] from
 *   `readAttributes`); `File.exists()` reports `false` for any `stat` error;
 * - a missing file is created exclusively (`CREATE_NEW`), never over one
 *   that appeared meanwhile;
 * - a rewrite goes to a synced temporary sibling that is renamed over the
 *   file, then the directory is synced.
 */
object UserConfigFile {
    fun interface Attributes {
        fun read(path: Path): BasicFileAttributes
    }

    val systemAttributes =
        Attributes { Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }

    /** Whether something exists at [file]; throws for any error but `ENOENT`. */
    fun exists(
        file: Path,
        attributes: Attributes = systemAttributes,
    ): Boolean =
        try {
            attributes.read(file)
            true
        } catch (_: NoSuchFileException) {
            false
        }

    /** The file's text; null only when it does not exist. */
    fun read(
        file: Path,
        attributes: Attributes = systemAttributes,
    ): String? {
        if (!exists(file, attributes)) return null
        return String(Files.readAllBytes(file), Charsets.UTF_8)
    }

    /**
     * Creates [file] from [content] when nothing exists there; true when it
     * was created. An existing file is never written.
     */
    fun createIfAbsent(
        file: Path,
        content: () -> InputStream,
        attributes: Attributes = systemAttributes,
    ): Boolean {
        if (exists(file, attributes)) return false
        Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
            content().use { it.copyTo(output) }
        }
        FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        syncDirectory(file.toAbsolutePath().parent)
        return true
    }

    /** Replaces [file] with [text] atomically and durably. */
    fun replace(
        file: Path,
        text: String,
        syncDirectory: (Path) -> Unit = ::syncDirectory,
    ) {
        val directory = file.toAbsolutePath().parent
        val temporary = directory.resolve(".${file.fileName}.${UUID.randomUUID()}.tmp")
        try {
            FileChannel
                .open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                .use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        syncDirectory(directory)
    }

    private fun syncDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }
}
