package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import suwayomi.tachidesk.manga.impl.util.ChildFirstURLClassLoader
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import java.nio.file.Path

/**
 * Executes an authenticated candidate in a short-lived loader and returns only immutable metadata.
 *
 * A class loader is a namespace boundary, not a security sandbox. Calling this object authorizes the
 * already authenticated store signer to execute code in the server process. The loader is closed and
 * no source instance is published, but JVM-global side effects cannot be rolled back here.
 */
internal object ExtensionJarShadowValidator {
    data class SourceMetadata(
        val id: Long,
        val name: String,
        val lang: String,
    )

    data class Result(
        val sources: List<SourceMetadata>,
        val extensionLanguage: String,
    )

    fun validate(
        jarPath: Path,
        artifact: ExtensionJarArtifactInspector.Metadata,
        expected: ExtensionInfo,
        occupiedSourceOwners: Map<Long, String>,
    ): Result {
        ExtensionArtifactInputPolicy.requirePackageName(expected.pkgName)
        validateExpected(expected)
        require(artifact.packageName == expected.pkgName) { "Authenticated artifact package differs from store metadata" }
        require(artifact.classFQName.startsWith("${expected.pkgName}.")) {
            "Authenticated extension class escapes the expected package"
        }

        val sources =
            ChildFirstURLClassLoader(arrayOf(jarPath.toUri().toURL())).use { loader ->
                val sourceClass = Class.forName(artifact.classFQName, true, loader)
                check(sourceClass.classLoader === loader) {
                    "Extension source class resolved outside the candidate loader"
                }
                val mainInstance = sourceClass.getDeclaredConstructor().newInstance()
                val instances =
                    when (mainInstance) {
                        is Source -> listOf(mainInstance)
                        is SourceFactory -> mainInstance.createSources()
                        else -> throw IllegalStateException("Extension main class is neither Source nor SourceFactory")
                    }
                require(instances.size in 1..MAX_SOURCES) { "Extension candidate produced an invalid source count" }
                val instanceSnapshot = java.util.List.copyOf(instances)
                require(instanceSnapshot.size in 1..MAX_SOURCES) { "Extension candidate source count changed during probing" }
                instanceSnapshot.map { source -> source.toMetadata(loader) }
            }

        return validateResult(Result(sources, extensionLanguage(sources)), expected, occupiedSourceOwners)
    }

    /** Revalidates injected/test probe output at the orchestration boundary and freezes it. */
    internal fun validateResult(
        result: Result,
        expected: ExtensionInfo,
        occupiedSourceOwners: Map<Long, String>,
    ): Result {
        val expectedSources = expected.sources.associateByUniqueID("Store metadata") { it.id }
        val sources = java.util.List.copyOf(result.sources.map(SourceMetadata::copy))
        require(sources.size in 1..MAX_SOURCES) { "Extension candidate produced an invalid source count" }
        val sourcesByID = sources.associateByUniqueID("Extension candidate") { it.id }
        check(sourcesByID.keys == expectedSources.keys) {
            "Extension candidate source IDs do not match store metadata"
        }
        sourcesByID.forEach { (id, actual) ->
            validateSourceValue(actual.name, MAX_SOURCE_NAME_LENGTH, "name")
            validateSourceValue(actual.lang, MAX_SOURCE_LANGUAGE_LENGTH, "language")
            val expectedSource = expectedSources.getValue(id)
            check(actual.name == expectedSource.name) {
                "Extension candidate source name does not match store metadata for ID $id"
            }
            check(actual.lang == expectedSource.lang) {
                "Extension candidate source language does not match store metadata for ID $id"
            }
            val owner = occupiedSourceOwners[id]
            check(owner == null || owner == expected.pkgName) {
                "Extension candidate source ID $id conflicts with installed package $owner"
            }
        }

        val computedLanguage = extensionLanguage(sources)
        check(result.extensionLanguage == computedLanguage) { "Extension probe returned inconsistent language metadata" }
        check(computedLanguage == expected.lang) { "Extension candidate language does not match store metadata" }
        return Result(sources, computedLanguage)
    }

    internal fun validateExpected(expected: ExtensionInfo) {
        require(expected.sources.size in 1..MAX_SOURCES) { "Direct JAR store metadata has an invalid source count" }
        validateSourceValue(expected.lang, MAX_SOURCE_LANGUAGE_LENGTH, "extension language")
        expected.sources.associateByUniqueID("Store metadata") { it.id }.values.forEach { source ->
            validateSourceValue(source.name, MAX_SOURCE_NAME_LENGTH, "name")
            validateSourceValue(source.lang, MAX_SOURCE_LANGUAGE_LENGTH, "language")
            require(source.homeUrl.length <= MAX_SOURCE_URL_LENGTH && !source.homeUrl.hasControlCharacters()) {
                "Store source home URL is invalid"
            }
            require(source.message == null || source.message.length <= MAX_SOURCE_MESSAGE_LENGTH) {
                "Store source message is too long"
            }
        }
    }

    private fun Source.toMetadata(candidateLoader: ClassLoader): SourceMetadata {
        require(this is CatalogueSource) { "Extension candidate produced a non-catalogue source" }
        check(javaClass.classLoader === candidateLoader) {
            "Extension candidate produced a source outside its shadow loader"
        }
        val sourceID = id
        val sourceName = name
        val sourceLanguage = lang
        require(sourceID > 0) { "Extension source ID must be positive" }
        validateSourceValue(sourceName, MAX_SOURCE_NAME_LENGTH, "name")
        validateSourceValue(sourceLanguage, MAX_SOURCE_LANGUAGE_LENGTH, "language")
        return SourceMetadata(sourceID, sourceName, sourceLanguage)
    }

    private fun extensionLanguage(sources: List<SourceMetadata>): String {
        val languages = sources.mapTo(linkedSetOf()) { it.lang }
        return if (languages.size == 1) languages.first() else "all"
    }

    private fun validateSourceValue(
        value: String,
        maximumLength: Int,
        field: String,
    ) {
        require(value.isNotBlank() && value.length <= maximumLength && !value.hasControlCharacters()) {
            "Extension source $field is invalid"
        }
    }

    private fun String.hasControlCharacters(): Boolean = any(Char::isISOControl)

    private fun <T> List<T>.associateByUniqueID(
        owner: String,
        id: (T) -> Long,
    ): Map<Long, T> {
        val result = LinkedHashMap<Long, T>(size)
        forEach { item ->
            val itemID = id(item)
            require(itemID > 0) { "$owner contains a non-positive source ID" }
            check(result.put(itemID, item) == null) { "$owner contains duplicate source ID $itemID" }
        }
        return result
    }

    internal const val MAX_SOURCES = 256
    private const val MAX_SOURCE_NAME_LENGTH = 256
    private const val MAX_SOURCE_LANGUAGE_LENGTH = 32
    private const val MAX_SOURCE_URL_LENGTH = 4096
    private const val MAX_SOURCE_MESSAGE_LENGTH = 4096
}
