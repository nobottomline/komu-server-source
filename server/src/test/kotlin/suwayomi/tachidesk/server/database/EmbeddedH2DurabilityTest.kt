package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Komu review 5: on iOS a committed H2 transaction survives the process
 * ending right after the commit (iOS ends apps without warning).
 */
class EmbeddedH2DurabilityTest {
    @Test
    fun `the iOS URL writes every commit before it returns`() {
        assertEquals("jdbc:h2:/data/database;WRITE_DELAY=0", embeddedH2JdbcUrl("/data", isIOS = true))
        assertEquals("jdbc:h2:/data/database", embeddedH2JdbcUrl("/data", isIOS = false))
    }

    @Test
    fun `the setting reaches the database`(
        @TempDir directory: Path,
    ) {
        DriverManager.getConnection(embeddedH2JdbcUrl(directory.toString(), isIOS = true)).use { connection ->
            connection.createStatement().executeQuery(
                "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'WRITE_DELAY'",
            ).use { result ->
                assertTrue(result.next())
                assertEquals("0", result.getString(1))
            }
        }
    }

    /** A child JVM commits, then halts at once (no shutdown hooks, no close). */
    @Test
    fun `a commit survives an immediate halt of the process`(
        @TempDir directory: Path,
    ) {
        val url = embeddedH2JdbcUrl(directory.toString(), isIOS = true)
        val java = File(System.getProperty("java.home"), "bin/java").path
        repeat(3) { round ->
            val child =
                ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), H2CommitThenHalt::class.java.name, url, "$round")
                    .redirectErrorStream(true)
                    .start()
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "child finished")
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(H2CommitThenHalt.HALT_STATUS, child.exitValue(), output)
        }
        DriverManager.getConnection("jdbc:h2:$directory/database").use { connection ->
            connection.createStatement().executeQuery("SELECT COUNT(*) FROM CANARY").use { result ->
                assertTrue(result.next())
                assertEquals(3, result.getInt(1))
            }
        }
    }
}

object H2CommitThenHalt {
    const val HALT_STATUS = 37

    @JvmStatic
    fun main(args: Array<String>) {
        val connection = DriverManager.getConnection(args[0])
        connection.autoCommit = true
        connection.createStatement().execute("CREATE TABLE IF NOT EXISTS CANARY(ID INT PRIMARY KEY)")
        connection.createStatement().execute("INSERT INTO CANARY VALUES (${args[1].toInt()})")
        Runtime.getRuntime().halt(HALT_STATUS)
    }
}
