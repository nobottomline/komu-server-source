package suwayomi.tachidesk.server

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigValue
import com.typesafe.config.parser.ConfigDocument
import io.github.config4k.toConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import suwayomi.tachidesk.server.settings.SettingsRegistry

private val logger = KotlinLogging.logger {  }

private fun migrateConfigValue(
    configDocument: ConfigDocument,
    config: Config,
    configKey: String,
    toConfigKey: String,
    toType: (ConfigValue) -> Any?,
): ConfigDocument {
    try {
        val configValue = config.getValue(configKey)
        val typedValue = toType(configValue)
        if (typedValue != null) {
            logger.debug { "Migrating config value: $configKey -> $toConfigKey" }
            return configDocument.withValue(
                toConfigKey,
                typedValue.toConfig("internal").getValue("internal"),
            )
        }
    } catch (_: ConfigException) {
        // ignore, likely already migrated
    }

    return configDocument
}

fun migrateConfig(
    configDocument: ConfigDocument,
    config: Config,
): ConfigDocument {
    var updatedConfig = configDocument
    // Only explicit user values participate: fallback defaults must not suppress
    // legacy migration or overwrite an explicitly configured replacement.
    val userConfig = ConfigFactory.parseString(configDocument.render())

    SettingsRegistry.getAll().forEach { (name, data) ->
        if (data.deprecated == null || data.deprecated is SettingsRegistry.SettingDeprecated.Remove) {
            return@forEach
        }

        val deprecated = data.deprecated as SettingsRegistry.SettingDeprecated.Migrate

        val configKey = "server.$name"
        val toConfigKey = "server.${deprecated.replaceWith}"

        try {
            userConfig.getValue(configKey)
        } catch (_: ConfigException) {
            // Ignore, no migration required
            return@forEach
        }

        try {
            userConfig.getValue(toConfigKey)
            return@forEach
        } catch (_: ConfigException) {
            // Replacement is absent from the user's document.
        }

        logger.debug { "Migrating config value: $configKey -> $toConfigKey" }

        when (deprecated) {
            is SettingsRegistry.SettingDeprecated.Migrate.Config -> {
                updatedConfig = deprecated.migrateConfig(userConfig.getValue(configKey), updatedConfig)
            }

            is SettingsRegistry.SettingDeprecated.Migrate.ConfigValue -> {
                updatedConfig =
                    migrateConfigValue(
                        updatedConfig,
                        userConfig,
                        configKey,
                        toConfigKey,
                        deprecated.migrateConfigValue,
                    )
            }
        }
    }

    return updatedConfig
}
