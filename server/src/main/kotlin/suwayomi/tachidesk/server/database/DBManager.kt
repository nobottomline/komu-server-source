package suwayomi.tachidesk.server.database

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import de.neonew.exposed.migrations.loadMigrationsFrom
import de.neonew.exposed.migrations.runMigrations
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.core.Schema
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.graphql.types.DatabaseType
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.EmbeddedRuntimePlatform
import suwayomi.tachidesk.server.ServerConfig
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.server.util.ExitCode
import suwayomi.tachidesk.server.util.shutdownApp
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object DBManager {
    var db: Database? = null
        private set

    @Volatile
    private var hikariDataSource: HikariDataSource? = null

    private fun createHikariDataSource(): HikariDataSource {
        val applicationDirs = Injekt.get<ApplicationDirs>()
        val config =
            HikariConfig().apply {
                when (serverConfig.databaseType.value) {
                    DatabaseType.POSTGRESQL -> {
                        jdbcUrl = "jdbc:${serverConfig.databaseUrl.value}"
                        driverClassName = "org.postgresql.Driver"
                        username = serverConfig.databaseUsername.value
                        password = serverConfig.databasePassword.value
                        // PostgreSQL specific optimizations
                        addDataSourceProperty("cachePrepStmts", "true")
                        addDataSourceProperty("useServerPrepStmts", "true")
                    }

                    DatabaseType.H2 -> {
                        jdbcUrl = embeddedH2JdbcUrl(applicationDirs.dataRoot, EmbeddedRuntimePlatform.isIOS())
                        driverClassName = "org.h2.Driver"
                        // H2 specific optimizations
                        addDataSourceProperty("cachePrepStmts", "true")
                        addDataSourceProperty("prepStmtCacheSize", "25")
                        addDataSourceProperty("prepStmtCacheSqlLimit", "256")
                    }
                }

                // Optimized for Raspberry Pi / Low memory environments
                maximumPoolSize = 6 // Moderate pool for better concurrency
                minimumIdle = 2 // Keep 2 idle connections for responsiveness
                connectionTimeout = 45.seconds.inWholeMilliseconds // more tolerance for slow devices
                idleTimeout = 5.minutes.inWholeMilliseconds // close idle connections faster
                maxLifetime = 15.minutes.inWholeMilliseconds // recycle connections more often
                leakDetectionThreshold = 1.minutes.inWholeMilliseconds
                configureEmbeddedH2Maintenance(
                    isIOS = EmbeddedRuntimePlatform.isIOS(),
                    databaseType = serverConfig.databaseType.value,
                    diagnostics = java.lang.Boolean.getBoolean("komu.database.diagnostics"),
                )

                // Pool name for monitoring
                poolName = "Suwayomi-DB-Pool"
            }
        return HikariDataSource(config)
    }

    fun setupDatabase(): Database {
        // Clean up existing connections
        if (TransactionManager.currentOrNull() != null) {
            val currentDatabase = TransactionManager.currentOrNull()?.db
            if (currentDatabase != null) {
                TransactionManager.closeAndUnregister(currentDatabase)
            }
        }

        // Close the existing pool if any
        shutdown()

        val dbConfig =
            DatabaseConfig {
                useNestedTransactions = true
                @OptIn(ExperimentalKeywordApi::class)
                preserveKeywordCasing = false
                defaultSchema =
                    when (serverConfig.databaseType.value) {
                        DatabaseType.POSTGRESQL -> Schema("suwayomi")
                        DatabaseType.H2 -> null
                    }
            }

        return if (serverConfig.useHikariConnectionPool.value) {
            // Create a new HikariCP pool
            hikariDataSource = createHikariDataSource()

            return Database
                .connect(hikariDataSource!!, databaseConfig = dbConfig)
        } else {
            when (serverConfig.databaseType.value) {
                DatabaseType.POSTGRESQL -> {
                    Database.connect(
                        "jdbc:${serverConfig.databaseUrl.value}",
                        "org.postgresql.Driver",
                        user = serverConfig.databaseUsername.value,
                        password = serverConfig.databasePassword.value,
                        databaseConfig = dbConfig,
                    )
                }

                DatabaseType.H2 -> {
                    Database.connect(
                        embeddedH2JdbcUrl(Injekt.get<ApplicationDirs>().dataRoot, EmbeddedRuntimePlatform.isIOS()),
                        "org.h2.Driver",
                        databaseConfig = dbConfig,
                    )
                }
            }
        }.also { db = it }
    }

    fun shutdown() {
        hikariDataSource?.close()
        hikariDataSource = null
    }

    fun getPoolStats(): String? =
        hikariDataSource?.let { ds ->
            "DB Pool Stats - Active: ${ds.hikariPoolMXBean.activeConnections}, " +
                "Idle: ${ds.hikariPoolMXBean.idleConnections}, " +
                "Waiting: ${ds.hikariPoolMXBean.threadsAwaitingConnection}"
        }

    val format = Json { prettyPrint = false }
}

private val logger = KotlinLogging.logger {}

fun databaseUp(givenDb: Database? = null) {
    val db =
        givenDb
            ?: try {
                DBManager.setupDatabase()
            } catch (e: Exception) {
                logger.error(e) { "Failed to setup Database" }
                return
            }

    logger.info {
        "Using ${db.vendor} database version ${db.version}"
    }

    // Log pool statistics
    DBManager.getPoolStats()?.let { stats ->
        logger.debug { "HikariCP initialized: $stats" }
    }

    // Add shutdown hook to properly close HikariCP pool
    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.debug { "Shutting down HikariCP connection pool..." }
            DBManager.shutdown()
        },
    )

    try {
        if (serverConfig.databaseType.value == DatabaseType.POSTGRESQL) {
            transaction {
                val schema =
                    Schema(
                        "suwayomi",
                        serverConfig.databaseUsername.value.takeIf { it.isNotBlank() },
                    )
                SchemaUtils.createSchema(schema)
            }
        }
        val migrations = loadMigrationsFrom("suwayomi.tachidesk.server.database.migration", ServerConfig::class.java)
        runMigrations(migrations)
    } catch (e: Exception) {
        logger.error(e) { "Error up-to-database migration" }
        shutdownApp(ExitCode.DbMigrationFailure)
    }
}

/**
 * Komu (review 5): on iOS every H2 commit is written to the database file
 * before it returns. H2 2.4.240 maps `WRITE_DELAY` to the MVStore
 * auto-commit delay (`Database.setWriteDelay`); with 0,
 * `TransactionStore.endTransaction` runs `MVStore.commit()`, which stores
 * the chunk synchronously (`FileStore.storeIt(…, syncWrite = true)`). The
 * default 500 ms leaves a committed transaction in memory, and iOS ends an
 * app without warning (a kill after a reply lost it). This bounds loss on
 * app termination, not on power loss (no `fsync` per commit).
 */
internal fun embeddedH2JdbcUrl(
    dataRoot: String,
    isIOS: Boolean,
): String = "jdbc:h2:$dataRoot/database" + if (isIOS) ";WRITE_DELAY=0" else ""

/** Local file connections have no network keepalive or server-enforced lifetime. */
internal fun HikariConfig.configureEmbeddedH2Maintenance(
    isIOS: Boolean,
    databaseType: DatabaseType,
    diagnostics: Boolean,
) {
    if (isIOS && databaseType == DatabaseType.H2) {
        keepaliveTime = 0
        maxLifetime = 0
        // Leak checks schedule a task per checkout, including short-lived progress reads.
        leakDetectionThreshold = if (diagnostics) 1.minutes.inWholeMilliseconds else 0
        // Keep the existing pool bounds/minimum: closing every H2 connection can close
        // the database, and changing concurrency requires separate contention measurements.
    }
}
