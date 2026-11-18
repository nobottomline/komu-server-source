package xyz.nulldev.ts.config

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import ch.qos.logback.classic.Level
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigObject
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue
import com.typesafe.config.parser.ConfigDocument
import com.typesafe.config.parser.ConfigDocumentFactory
import io.github.config4k.toConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Manages app config.
 */
open class ConfigManager(
    // Komu: injectable for tests; the server's is `<rootDir>/server.conf`.
    private val userConfigFile: File = File(ApplicationRootDir, "server.conf"),
) {
    val logger = KotlinLogging.logger {}
    private val generatedModules = mutableMapOf<Class<out ConfigModule>, ConfigModule>()
    private var internalConfig = loadConfigs()
    val config: Config
        get() = internalConfig

    // Public read-only view of modules
    val loadedModules: Map<Class<out ConfigModule>, ConfigModule>
        get() = generatedModules

    private val mutex = Mutex()

    /**
     * Get a config module
     */
    inline fun <reified T : ConfigModule> module(): T = loadedModules[T::class.java] as T

    /**
     * Get a config module (Java API)
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : ConfigModule> module(type: Class<T>): T = loadedModules[type] as T

    // Komu (reviews 5 and 7): only `ENOENT` reads as an absent (empty) user
    // config; any other error throws. Parsing is Lightbend's own file parse,
    // so every include resolves as upstream resolves it (relative to the
    // including file, `required(...)`, `file(...)`, URLs).
    private fun getUserConfig(): Config = UserConfigFile.parse(userConfigFile.toPath()) ?: ConfigFactory.empty()

    /** The user config document; an absent file is an empty one. */
    private fun userConfigDocument(): ConfigDocument =
        ConfigDocumentFactory.parseString(UserConfigFile.read(userConfigFile.toPath()) ?: "")

    /** Komu (review 5): never truncates the file in place. */
    private fun writeUserConfig(text: String) {
        UserConfigFile.replace(userConfigFile.toPath(), text)
    }

    /**
     * Load configs
     */
    fun loadConfigs(): Config {
        // Load reference configs
        val compatConfig = ConfigFactory.parseResources("compat-reference.conf")
        val serverConfig = ConfigFactory.parseResources("server-reference.conf")
        val baseConfig =
            ConfigFactory.parseMap(
                mapOf(
                    // override AndroidCompat's rootDir
                    "androidcompat.rootDir" to "$ApplicationRootDir/android-compat",
                ),
            )

        // Load user config
        val userConfig = getUserConfig()

        val config =
            ConfigFactory
                .empty()
                .withFallback(baseConfig)
                .withFallback(userConfig)
                .withFallback(compatConfig)
                .withFallback(serverConfig)
                .resolve()

        // set log level early
        if (debugLogsEnabled(config)) {
            setLogLevelFor(BASE_LOGGER_NAME, Level.DEBUG)
        }

        return config
    }

    fun registerModule(module: ConfigModule) {
        generatedModules[module.javaClass] = module
    }

    fun registerModules(vararg modules: ConfigModule) {
        modules.forEach {
            registerModule(it)
        }
    }

    private fun updateUserConfigFile(
        path: String,
        value: ConfigValue,
    ) {
        val userConfigDoc = userConfigDocument()
        val updatedConfigDoc = userConfigDoc.withValue(path, value)
        val newFileContent = updatedConfigDoc.render()
        // Komu (review 7): Lightbend 1.4.9 renders `include required(...)` and
        // `include file(...)` without their closing parenthesis. Write only a
        // text that parses as server.conf would; otherwise fail and keep the file.
        //
        // Contract (review 8, Komu docs/problems/server-migration-interrupted-
        // relaunch-2026-11-17.md, "server.conf contract"): on Komu server.conf
        // is app-owned and machine-written, one assignment per line, no
        // include, no substitution; user-authored HOCON is out of contract and
        // this guard is only defensive. Residual (pre-existing upstream): a
        // caller that already changed the database (e.g. a repository list)
        // still reports success when this save fails, and the start-up
        // reconciliation then rolls the change back; follow-up: make the list
        // persistence part of the confirmed operation.
        UserConfigFile.parseAsFile(userConfigFile.toPath(), newFileContent)
        writeUserConfig(newFileContent)
    }

    suspend fun updateValue(
        path: String,
        value: Any,
    ) {
        mutex.withLock {
            val configValue = value.toConfig("internal").getValue("internal")

            updateUserConfigFile(path, configValue)
            internalConfig = internalConfig.withValue(path, configValue)
        }
    }

    private fun createConfigDocumentFromReference(): ConfigDocument {
        val serverConfigFileContent = this::class.java.getResource("/server-reference.conf")?.readText()
        return ConfigDocumentFactory.parseString(serverConfigFileContent)
    }

    fun resetUserConfig(): ConfigDocument {
        val serverConfigDoc = createConfigDocumentFromReference()

        writeUserConfig(serverConfigDoc.render())
        getUserConfig().entrySet().forEach { internalConfig = internalConfig.withValue(it.key, it.value) }

        return serverConfigDoc
    }

    /**
     * Makes sure the "UserConfig" is up-to-date.
     *
     *  - Adds missing settings
     *  - Migrates deprecated settings
     *  - Removes outdated settings
     */
    fun updateUserConfig(migrate: ConfigDocument.(Config) -> ConfigDocument) {
        val serverConfig = ConfigFactory.parseResources("server-reference.conf")
        val userConfig = getUserConfig()

        // NOTE: if more than 1 dot is included, that's a nested setting, which we need to filter out here
        val refKeys =
            serverConfig.root().entries.flatMap {
                (it.value as? ConfigObject)?.entries?.map { e -> "${it.key}.${e.key}" }.orEmpty()
            }
        val hasMissingSettings = refKeys.any { !userConfig.hasPath(it) }
        val hasOutdatedSettings = userConfig.entrySet().any { !refKeys.contains(it.key) && it.key.count { c -> c == '.' } <= 1 }

        val isUserConfigOutdated = hasMissingSettings || hasOutdatedSettings
        if (!isUserConfigOutdated) {
            return
        }

        logger.debug {
            "user config is out of date, updating... (missingSettings= $hasMissingSettings, outdatedSettings= $hasOutdatedSettings)"
        }

        // Migrate the actual user document before rebuilding it from the
        // latest reference file. Rebuilding first drops deprecated top-level
        // keys (because they no longer exist in the reference) and also makes
        // every replacement key look explicitly configured through its
        // reference default. That combination silently lost values such as
        // extensionRepos during extensionStores upgrades.
        //
        // Komu (review 7): the migration works on the user's values with every
        // include resolved by Lightbend's file semantics (`userConfig`), as a
        // document without include statements. A document of the file's own
        // text loses included values in the string parses below and in
        // `migrateConfig`, and Lightbend 1.4.9 renders qualified includes
        // without their closing parenthesis. The rebuilt file holds the values
        // themselves, as it did upstream.
        val resolvedUserConfigDoc =
            ConfigDocumentFactory.parseString(
                userConfig.root().render(ConfigRenderOptions.defaults().setOriginComments(false)),
            )
        val migratedUserConfigDoc = migrate(resolvedUserConfigDoc, internalConfig)
        val migratedUserConfig = ConfigFactory.parseString(migratedUserConfigDoc.render())

        var newUserConfigDoc: ConfigDocument = createConfigDocumentFromReference()
        migratedUserConfig
            .entrySet()
            .filter {
                serverConfig.hasPath(
                    it.key,
                ) ||
                    it.key.count { c -> c == '.' } > 1
            }.forEach { newUserConfigDoc = newUserConfigDoc.withValue(it.key, it.value) }

        writeUserConfig(newUserConfigDoc.render())
        getUserConfig().entrySet().forEach { internalConfig = internalConfig.withValue(it.key, it.value) }
    }

    fun getRedactedConfig(nonPrivacySafeKeys: List<String>): Config {
        val entries =
            config.entrySet().associate { entry ->
                val key = entry.key
                val value =
                    if (nonPrivacySafeKeys.any { key.split(".").getOrNull(1) == it }) {
                        "[REDACTED]"
                    } else {
                        entry.value.unwrapped()
                    }

                key to value
            }

        return ConfigFactory.parseMap(entries)
    }
}

object GlobalConfigManager : ConfigManager()
