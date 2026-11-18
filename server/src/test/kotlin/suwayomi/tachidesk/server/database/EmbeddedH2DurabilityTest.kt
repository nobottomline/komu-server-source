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
 * Komu review 5: the embedded H2 keeps its default write delay (lead
 * decision, docs/problems/server-migration-interrupted-relaunch-2026-11-17.md);
 * work that must outlive an abrupt end of the process is made durable with
 * `CHECKPOINT SYNC` (`app.komu.library.DatabaseDurability`) before anything
 * irreversible relies on it. This is the contract those barriers rest on.
 */
class EmbeddedH2DurabilityTest {
    /** A child JVM commits, runs `CHECKPOINT SYNC`, then halts at once (no shutdown hooks, no close). */
    @Test
    fun `a checkpointed commit survives an immediate halt with the default write delay`(
        @TempDir directory: Path,
    ) {
        val url = "jdbc:h2:$directory/database"
        val java = File(System.getProperty("java.home"), "bin/java").path
        repeat(3) { round ->
            val child =
                ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), H2CheckpointThenHalt::class.java.name, url, "$round")
                    .redirectErrorStream(true)
                    .start()
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "child finished")
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(H2CheckpointThenHalt.HALT_STATUS, child.exitValue(), output)
        }
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().executeQuery("SELECT COUNT(*) FROM CANARY").use { result ->
                assertTrue(result.next())
                assertEquals(3, result.getInt(1))
            }
            connection.createStatement().executeQuery(
                "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'WRITE_DELAY'",
            ).use { result ->
                assertTrue(result.next())
                assertEquals("500", result.getString(1), "the default write delay")
            }
        }
    }
}

object H2CheckpointThenHalt {
    const val HALT_STATUS = 37

    @JvmStatic
    fun main(args: Array<String>) {
        val connection = DriverManager.getConnection(args[0])
        connection.autoCommit = true
        connection.createStatement().execute("CREATE TABLE IF NOT EXISTS CANARY(ID INT PRIMARY KEY)")
        connection.createStatement().execute("INSERT INTO CANARY VALUES (${args[1].toInt()})")
        connection.createStatement().execute("CHECKPOINT SYNC")
        Runtime.getRuntime().halt(HALT_STATUS)
    }
}
