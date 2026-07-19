package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.manga.impl.util.PackageTools.EXTENSION_FEATURE
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_CONTENT_WARNING
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_EXTENSION_LIB
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_NAME
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_NSFW
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_SOURCE_CLASS
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.dataclass.ExtensionInfo
import java.nio.file.Path

/** Authenticates a JAR and binds its signed manifest to one store record. */
internal object ExtensionJarArtifactInspector {
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val extensionLib: String,
        val name: String,
        val classFQName: String,
        val contentWarning: ContentWarning,
        val signature: ExtensionJarVerifier.Result,
    )

    fun inspect(
        jarPath: Path,
        expected: ExtensionInfo,
        expectedSigningKey: String,
    ): Metadata {
        validateExpected(expected)
        val authenticatedManifest =
            ExtensionJarVerifier.verifyAndRead(
                jarPath = jarPath,
                expectedSigningKey = expectedSigningKey,
                entryName = ANDROID_MANIFEST,
                entrySizeLimit = ExtensionJarManifestParser.MAX_MANIFEST_BYTES.toLong(),
            )
        val manifest = ExtensionJarManifestParser.parse(authenticatedManifest.copyBytes())
        val application = requireNotNull(manifest.application) { "Extension JAR manifest has no application" }
        val metaData = application.metaData.associate { it.name to it.value }
        val versionCode = requireNotNull(manifest.versionCode) { "Extension JAR has no versionCode" }
        val versionName = requireNotNull(manifest.versionName) { "Extension JAR has no versionName" }
        val extensionLib =
            metaData[METADATA_EXTENSION_LIB]
                ?.takeUnless { it == "0" }
                ?: versionName.substringBeforeLast('.', missingDelimiterValue = "")
        val sourceClass = requireNotNull(metaData[METADATA_SOURCE_CLASS]) { "Extension JAR has no source class" }
        val classFQName = sourceClass.toClassFQName(manifest.packageName)
        val contentWarning = parseContentWarning(metaData)
        val extensionName =
            metaData[METADATA_NAME]
                ?: application.label?.substringAfter("Tachiyomi: ")
                ?: error("Extension JAR has no extension name")

        require(EXTENSION_FEATURE in manifest.usesFeatures.mapNotNull { it.name }) {
            "JAR is not a Tachiyomi extension"
        }
        requireIdentity(manifest.packageName == expected.pkgName, "packageName")
        requireIdentity(versionCode == expected.versionCode, "versionCode")
        requireIdentity(versionName == expected.versionName, "versionName")
        requireIdentity(extensionLib == expected.extensionLib, "extensionLib")
        requireIdentity(contentWarning == expected.contentWarning, "content warning")
        requireIdentity(extensionName == expected.name, "name")

        val classEntryName = classFQName.replace('.', '/') + ".class"
        if (classEntryName !in authenticatedManifest.signature.verifiedEntryNames) {
            throw SecurityException("Extension source class is absent from authenticated JAR entries")
        }

        return Metadata(
            packageName = manifest.packageName,
            versionCode = versionCode,
            versionName = versionName,
            extensionLib = extensionLib,
            name = extensionName,
            classFQName = classFQName,
            contentWarning = contentWarning,
            signature = authenticatedManifest.signature,
        )
    }

    private fun validateExpected(expected: ExtensionInfo) {
        require(PACKAGE_NAME_PATTERN.matches(expected.pkgName)) { "Store packageName is invalid" }
        require(expected.versionCode >= 0) { "Store versionCode is invalid" }
        require(expected.versionName.isBoundedValue(MAX_VERSION_NAME_LENGTH)) { "Store versionName is invalid" }
        require(expected.extensionLib.isBoundedValue(MAX_EXTENSION_LIB_LENGTH)) { "Store extensionLib is invalid" }
        require(expected.name.isBoundedValue(MAX_EXTENSION_NAME_LENGTH)) { "Store extension name is invalid" }
    }

    private fun parseContentWarning(metaData: Map<String, String?>): ContentWarning {
        val explicitValue = metaData[METADATA_CONTENT_WARNING]
        if (explicitValue != null) {
            val ordinal = requireNotNull(explicitValue.toIntOrNull()) { "Extension content warning is invalid" }
            return requireNotNull(ContentWarning.entries.getOrNull(ordinal)) { "Extension content warning is unknown" }
        }

        return when (val legacyValue = metaData[METADATA_NSFW]) {
            null, "0" -> ContentWarning.SAFE
            "1" -> ContentWarning.MIXED
            else -> throw IllegalArgumentException("Legacy extension content warning '$legacyValue' is invalid")
        }
    }

    private fun String.toClassFQName(packageName: String): String {
        require(PACKAGE_NAME_PATTERN.matches(packageName)) { "Extension packageName is invalid" }
        val candidate =
            when {
                startsWith('.') -> packageName + this
                startsWith("$packageName.") -> this
                else -> throw SecurityException("Extension source class escapes its package namespace")
            }
        require(CLASS_NAME_PATTERN.matches(candidate)) { "Extension source class is not a valid JVM class name" }
        return candidate
    }

    private fun requireIdentity(
        condition: Boolean,
        field: String,
    ) {
        if (!condition) throw SecurityException("JAR $field does not match store metadata")
    }

    private fun String.isBoundedValue(maxLength: Int): Boolean = isNotBlank() && length <= maxLength && none(Char::isISOControl)

    private const val ANDROID_MANIFEST = "AndroidManifest.xml"
    private const val MAX_VERSION_NAME_LENGTH = 64
    private const val MAX_EXTENSION_LIB_LENGTH = 32
    private const val MAX_EXTENSION_NAME_LENGTH = 256
    private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val CLASS_NAME_PATTERN = Regex("[A-Za-z_\$][A-Za-z0-9_\$]*(\\.[A-Za-z_\$][A-Za-z0-9_\$]*)+")
}
