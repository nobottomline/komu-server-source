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
        assumePosix(directory)
        val file = directory.resolve("server.conf")
        Files.writeString(file, userSettings)
        val manager = ConfigManager(file.toFile())
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("-w-------"))
        try {
            // Lightbend's file parse reports the read error (review 7: never an empty config).
            assertFailsWith<com.typesafe.config.ConfigException.IO> { manager.updateUserConfig { this } }
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

    private val stores = listOf("https://example.org/index.min.json")
    private val storesLine = "server.extensionStores = [\"https://example.org/index.min.json\"]\n"

    /** Writes [serverConf] and its included files, then checks load and migration. */
    private fun assertStoresSurviveLoadAndMigration(
        directory: Path,
        serverConf: String,
    ) {
        val file = directory.resolve("server.conf")
        Files.writeString(file, serverConf + "server.port = 4999\n")
        val manager = ConfigManager(file.toFile())
        assertEquals(stores, manager.config.getStringList("server.extensionStores"), "load")

        manager.updateUserConfig { this }
        val reloaded = ConfigManager(file.toFile())
        assertEquals(stores, reloaded.config.getStringList("server.extensionStores"), "after migration: ${Files.readString(file)}")
        assertEquals(4999, reloaded.config.getInt("server.port"))
        assertEquals(
            listOf("server.conf"),
            directory.toFile().list()!!.filter { it.startsWith(".") || it == "server.conf" },
            "no temporary file is left",
        )
    }

    /** Review 7: an include nested in an included file resolves next to that file. */
    @Test
    fun `a nested relative include resolves next to the including file`(
        @TempDir directory: Path,
    ) {
        Files.createDirectories(directory.resolve("conf"))
        Files.writeString(directory.resolve("conf/repos.conf"), "include \"stores.conf\"\n")
        Files.writeString(directory.resolve("conf/stores.conf"), storesLine)
        assertStoresSurviveLoadAndMigration(directory, "include \"conf/repos.conf\"\n")
    }

    @Test
    fun `a required nested include resolves and loads`(
        @TempDir directory: Path,
    ) {
        Files.createDirectories(directory.resolve("conf"))
        Files.writeString(directory.resolve("conf/repos.conf"), "include required(\"stores.conf\")\n")
        Files.writeString(directory.resolve("conf/stores.conf"), storesLine)
        assertStoresSurviveLoadAndMigration(directory, "include required(\"conf/repos.conf\")\n")
    }

    @Test
    fun `an absolute file include without extension finds the conf file`(
        @TempDir directory: Path,
    ) {
        val repos = Files.createDirectories(directory.resolve("opt")).resolve("repos.conf")
        Files.writeString(repos, storesLine)
        val base = repos.toString().removeSuffix(".conf")
        // A HOCON string literal: backslashes and quotes of the path escaped.
        assertStoresSurviveLoadAndMigration(directory, "include file(${com.typesafe.config.ConfigUtil.quoteString(base)})\n")
    }

    @Test
    fun `a file URL include is read as a URL`(
        @TempDir directory: Path,
    ) {
        val repos = Files.createDirectories(directory.resolve("opt")).resolve("repos.conf")
        Files.writeString(repos, storesLine)
        assertStoresSurviveLoadAndMigration(directory, "include \"${repos.toUri()}\"\n")
    }

    @Test
    fun `a missing required include fails the load instead of reading as empty`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("server.conf")
        Files.writeString(file, "include required(\"conf/absent.conf\")\n")
        assertFailsWith<com.typesafe.config.ConfigException> { ConfigManager(file.toFile()) }
    }

    @Test
    fun `the directory barrier syncs a directory on Unix-like systems`(
        @TempDir directory: Path,
    ) {
        org.junit.jupiter.api.Assumptions.assumeTrue(xyz.nulldev.ts.config.DirectoryBarrier.isSupported, "Unix-like only")
        xyz.nulldev.ts.config.DirectoryBarrier.sync(directory)
    }

    /** Windows cannot open a directory as a channel: the barrier must do nothing there. */
    @Test
    fun `the directory barrier is a no-op where it is unsupported`(
        @TempDir directory: Path,
    ) {
        xyz.nulldev.ts.config.DirectoryBarrier.sync(directory.resolve("not-even-there"), supported = false)
    }

    /** Review 7: a value change never writes a server.conf that would not parse back. */
    @Test
    fun `a value change keeps a qualified include parseable`(
        @TempDir directory: Path,
    ) {
        Files.createDirectories(directory.resolve("conf"))
        Files.writeString(directory.resolve("conf/stores.conf"), storesLine)
        val file = directory.resolve("server.conf")
        val original = "include required(\"conf/stores.conf\")\nserver.port = 4999\n"
        Files.writeString(file, original)
        val manager = ConfigManager(file.toFile())
        // Lightbend 1.4.9 renders the include without its ')': the write is refused.
        assertFailsWith<com.typesafe.config.ConfigException.Parse> {
            kotlinx.coroutines.runBlocking { manager.updateValue("server.port", 5000) }
        }
        assertEquals(original, Files.readString(file), "the file is untouched")
        assertEquals(4999, manager.config.getInt("server.port"), "the in-memory value is unchanged")
        val reloaded = ConfigManager(file.toFile())
        assertEquals(stores, reloaded.config.getStringList("server.extensionStores"))
    }

    private fun assumePosix(directory: Path) =
        org.junit.jupiter.api.Assumptions.assumeTrue(
            "posix" in directory.fileSystem.supportedFileAttributeViews(),
            "POSIX file modes only",
        )

    /** Review 8: the atomic write never widens the mode of server.conf (umask 022 by default). */
    @Test
    fun `a rewrite keeps an owner-only mode and its temporary files are owner-only`(
        @TempDir directory: Path,
    ) {
        assumePosix(directory)
        val file = directory.resolve("server.conf")
        Files.writeString(file, "server.port = 4999\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))

        ConfigManager(file.toFile()).updateUserConfig { this }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))

        var temporaryMode: String? = null
        UserConfigFile.parseAsFile(file, "server.port = 1\n") { temporary ->
            temporaryMode = PosixFilePermissions.toString(Files.getPosixFilePermissions(temporary))
        }
        assertEquals("rw-------", temporaryMode)
    }

    @Test
    fun `a rewrite keeps the original mode`(
        @TempDir directory: Path,
    ) {
        assumePosix(directory)
        val file = directory.resolve("server.conf")
        Files.writeString(file, "server.port = 4999\n")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"))
        UserConfigFile.replace(file, "server.port = 5000\n")
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }
}
