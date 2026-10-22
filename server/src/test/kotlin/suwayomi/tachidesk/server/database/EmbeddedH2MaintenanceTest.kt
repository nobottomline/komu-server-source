package suwayomi.tachidesk.server.database

import com.zaxxer.hikari.HikariConfig
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.types.DatabaseType
import kotlin.test.assertEquals

class EmbeddedH2MaintenanceTest {
    private fun config() =
        HikariConfig().apply {
            maximumPoolSize = 6
            minimumIdle = 2
            maxLifetime = 900_000
            keepaliveTime = 120_000
            leakDetectionThreshold = 60_000
        }

    @Test
    fun `iOS local H2 preserves connections without network keepalive or checkout diagnostics`() {
        val config = config().apply { configureEmbeddedH2Maintenance(true, DatabaseType.H2, false) }
        assertEquals(0L, config.keepaliveTime)
        assertEquals(0L, config.maxLifetime)
        assertEquals(0L, config.leakDetectionThreshold)
        assertEquals(2, config.minimumIdle)
        assertEquals(6, config.maximumPoolSize)
    }

    @Test
    fun `explicit diagnostics restores leak detection without connection churn`() {
        val config = config().apply { configureEmbeddedH2Maintenance(true, DatabaseType.H2, true) }
        assertEquals(60_000L, config.leakDetectionThreshold)
        assertEquals(0L, config.maxLifetime)
    }

    @Test
    fun `desktop and network database policies are unchanged`() {
        listOf(false to DatabaseType.H2, false to DatabaseType.POSTGRESQL, true to DatabaseType.POSTGRESQL).forEach { (ios, db) ->
            val config = config().apply { configureEmbeddedH2Maintenance(ios, db, false) }
            assertEquals(120_000L, config.keepaliveTime)
            assertEquals(900_000L, config.maxLifetime)
            assertEquals(60_000L, config.leakDetectionThreshold)
        }
    }
}
