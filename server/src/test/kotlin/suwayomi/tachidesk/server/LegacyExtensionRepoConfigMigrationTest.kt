package suwayomi.tachidesk.server

import com.typesafe.config.ConfigFactory
import com.typesafe.config.parser.ConfigDocumentFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.server.settings.SettingsRegistry

/** Protects Komu upgrades that still carry the pre-extension-store HOCON key. */
class LegacyExtensionRepoConfigMigrationTest {
    @BeforeEach
    fun registerServerSettings() {
        SettingsRegistry.clear()
        ServerConfig.register { ConfigFactory.empty() }
    }

    @AfterEach
    fun clearServerSettings() {
        SettingsRegistry.clear()
    }

    @Test
    fun `legacy extensionRepos are preserved as extensionStores`() {
        val legacyUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        val source =
            """
            server.extensionRepos = ["$legacyUrl"]
            server.port = 4567
            """.trimIndent()

        val migrated =
            migrateConfig(
                ConfigDocumentFactory.parseString(source),
                ConfigFactory.parseString(source),
            ).render()
        val parsed = ConfigFactory.parseString(migrated)

        assertEquals(listOf(legacyUrl), parsed.getStringList("server.extensionStores"))
        assertEquals(4567, parsed.getInt("server.port"))
        assertTrue(migrated.contains("server.extensionRepos"))
    }

    @Test
    fun `existing extensionStores are not replaced when no legacy key exists`() {
        val storeUrl = "https://raw.githubusercontent.com/example/extensions/repo/index.pb"
        val source = "server.extensionStores = [\"$storeUrl\"]"

        val migrated =
            migrateConfig(
                ConfigDocumentFactory.parseString(source),
                ConfigFactory.parseString(source),
            ).render()

        assertEquals(listOf(storeUrl), ConfigFactory.parseString(migrated).getStringList("server.extensionStores"))
        assertFalse(migrated.contains("extensionRepos"))
    }

    @Test
    fun `explicit extensionStores win when both old and new keys exist`() {
        val legacyUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
        val explicitStoreUrl = "https://raw.githubusercontent.com/example/extensions/repo/index.pb"
        val source =
            """
            server.extensionRepos = ["$legacyUrl"]
            server.extensionStores = ["$explicitStoreUrl"]
            """.trimIndent()

        val migrated =
            migrateConfig(
                ConfigDocumentFactory.parseString(source),
                ConfigFactory.parseString(source),
            ).render()

        assertEquals(
            listOf(explicitStoreUrl),
            ConfigFactory.parseString(migrated).getStringList("server.extensionStores"),
        )
    }
}
