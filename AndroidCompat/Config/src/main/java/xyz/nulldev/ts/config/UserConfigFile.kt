package xyz.nulldev.ts.config

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
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
 *   file, then the directory is synced where the platform has a directory
 *   barrier ([DirectoryBarrier]);
 * - it is parsed with Lightbend's file semantics ([parse], [parseAsFile]),
 *   so includes resolve exactly as upstream resolves them (review 7).
 */
object UserConfigFile {
    fun interface Attributes {
        fun read(path: Path): BasicFileAttributes
    }

    val systemAttributes =
        Attributes { Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }

    /**
     * The configuration of [file] with Lightbend's file semantics
     * (`ConfigFactory.parseFile`: includes resolve relative to the including
     * file, `required(...)`, `file(...)` and URLs as upstream); null only when
     * [file] does not exist. Any other error throws, including a read error
     * the parse meets (it does not allow a missing file).
     */
    fun parse(
        file: Path,
        attributes: Attributes = systemAttributes,
    ): Config? {
        if (!exists(file, attributes)) return null
        return ConfigFactory.parseFile(file.toFile(), strict)
    }

    /**
     * Parses [text] exactly as if it were the content of [file]: it is
     * written to a temporary sibling with the same extension and parsed as a
     * file there, so its includes resolve against the same directory; the
     * sibling is removed afterwards.
     */
    fun parseAsFile(
        file: Path,
        text: String,
        // Tests: sees the temporary file while it exists.
        observeTemporary: (Path) -> Unit = {},
    ): Config {
        val directory = file.toAbsolutePath().parent
        val name = file.fileName.toString()
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { ".$it" } ?: ""
        val temporary = directory.resolve(".$name.${UUID.randomUUID()}.parse$extension")
        try {
            writeOwnerOnly(temporary, text)
            observeTemporary(temporary)
            return ConfigFactory.parseFile(temporary.toFile(), strict)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private val strict: ConfigParseOptions = ConfigParseOptions.defaults().setAllowMissing(false)

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
            writeOwnerOnly(temporary, text, force = true)
            // Review 8: the rename publishes the temporary file's mode; keep the
            // original's (an absent original keeps the owner-only mode).
            if (supportsPosix(temporary) && exists(file)) {
                Files.setPosixFilePermissions(temporary, Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS))
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        syncDirectory(directory)
    }

    private fun syncDirectory(directory: Path) = DirectoryBarrier.sync(directory)

    private val ownerReadWrite = PosixFilePermissions.fromString("rw-------")

    private fun supportsPosix(path: Path): Boolean = "posix" in path.fileSystem.supportedFileAttributeViews()

    /**
     * Review 8: a temporary copy of server.conf (it holds credentials such as
     * repository tokens) is created owner-only (0600) from the start where
     * the file system has POSIX modes, never with the umask's default.
     */
    private fun writeOwnerOnly(
        path: Path,
        text: String,
        force: Boolean = false,
    ) {
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        val channel =
            if (supportsPosix(path)) {
                FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(ownerReadWrite))
            } else {
                FileChannel.open(path, options)
            }
        channel.use {
            val buffer = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) it.write(buffer)
            if (force) it.force(true)
        }
    }
}

/**
 * Komu: makes a rename (or creation) in [sync]'s directory durable. On
 * Unix-like systems (iOS, macOS, Linux) that is an `fsync` of the directory,
 * which the JDK allows by opening it for reading. Windows cannot open a
 * directory as a file channel and has no directory flush; NTFS journals the
 * rename itself, so the barrier is a documented no-op there (review 6).
 */
object DirectoryBarrier {
    val isSupported: Boolean = !System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)

    fun sync(
        directory: Path,
        supported: Boolean = isSupported,
    ) {
        if (!supported) return
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }
}
