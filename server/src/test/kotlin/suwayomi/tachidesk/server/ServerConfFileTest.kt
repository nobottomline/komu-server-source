package suwayomi.tachidesk.server

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.ts.config.ConfigManager
import xyz.nulldev.ts.config.UserConfigFile
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Komu review 5: the JVM never truncates or replaces a `server.conf` it could not read. */
class ServerConfFileTest {
    private val userSettings = "server.port = 4567\nserver.extensionRepos = [\"https://example.org/index.min.json\"]\n"

    private fun reference() = "server.port = 4567\n".byteInputStream()

    @Test
    fun `an attribute error other than ENOENT writes nothing`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("server.conf")
        Files.writeString(file, userSettings)
        var updated = false
        assertFailsWith<IOException> {
            prepareServerConfFile(
                file,
                reference = ::reference,
                update = { updated = true },
                attributes = { throw IOException("Input/output error") },
            )
        }
        assertFalse(updated)
        assertEquals(userSettings, Files.readString(file))
    }

    @Test
    fun `an absent file is created exclusively and never over one that appeared`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("server.conf")
        var updated = false
        prepareServerConfFile(file, reference = ::reference, update = { updated = true })
        assertEquals("server.port = 4567\n", Files.readString(file))
        assertFalse(updated)

        // `stat` claims absence, but a file is there: CREATE_NEW refuses it.
        Files.writeString(file, userSettings)
        assertFailsWith<FileAlreadyExistsException> {
            prepareServerConfFile(
                file,
                reference = ::reference,
                update = { updated = true },
                attributes = { throw NoSuchFileException(it.toString()) },
            )
        }
        assertEquals(userSettings, Files.readString(file))
    }

    @Test
    fun `an existing file is only migrated`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("server.conf")
        Files.writeString(file, userSettings)
        var updated = false
        prepareServerConfFile(file, reference = ::reference, update = { updated = true })
        assertTrue(updated)
        assertEquals(userSettings, Files.readString(file))
    }

    @Test
    fun `an unreadable user config is never migrated into a reference copy`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("server.conf")
        Files.writeString(file, userSettings)
        val manager = ConfigManager(file.toFile())
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w-------"))
        try {
            assertFailsWith<IOException> { manager.updateUserConfig { this } }
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
        assertEquals(userSettings, Files.readString(file))
    }

    @Test
    fun `a migration replaces the file atomically and keeps the user's values`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("server.conf")
        Files.writeString(file, "server.port = 4999\nserver.ip = \"127.0.0.1\"\n")
        val before = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        ConfigManager(file.toFile()).updateUserConfig { this }
        val text = Files.readString(file)
        assertTrue(text.contains("server.port = 4999"), text)
        assertTrue(text.contains("server.downloadsPath"), "the reference keys were added")
        val after = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        assertTrue(before != after, "a new file was renamed into place, not the old one truncated")
        assertEquals(listOf("server.conf"), directory.toFile().list()!!.toList())
    }

    @Test
    fun `a failed directory sync after the replace still leaves a complete file`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("server.conf")
        Files.writeString(file, userSettings)
        assertFailsWith<IOException> {
            UserConfigFile.replace(file, "server.port = 1\n", syncDirectory = { throw IOException("Input/output error") })
        }
        assertEquals("server.port = 1\n", Files.readString(file))
        assertEquals(listOf("server.conf"), directory.toFile().list()!!.toList())
    }
}
