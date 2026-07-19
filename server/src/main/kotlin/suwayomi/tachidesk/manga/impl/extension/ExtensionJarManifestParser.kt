package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.ExperimentalXmlUtilApi
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.KtXmlReader
import nl.adaptivity.xmlutil.serialization.UnknownChildHandler
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Minimal, bounded Android-manifest surface needed to identify an extension JAR. */
internal object ExtensionJarManifestParser {
    @Serializable
    @XmlSerialName("manifest", "", "")
    data class Manifest(
        @XmlSerialName("package", "", "")
        val packageName: String,
        @XmlSerialName("versionCode", ANDROID_NS, "android")
        val versionCode: Long? = null,
        @XmlSerialName("versionName", ANDROID_NS, "android")
        val versionName: String? = null,
        @XmlElement(true)
        @XmlSerialName("uses-feature", "", "")
        val usesFeatures: List<UsesFeature> = emptyList(),
        @XmlElement(true)
        @XmlSerialName("application", "", "")
        val application: Application? = null,
    )

    @Serializable
    @XmlSerialName("uses-feature", "", "")
    data class UsesFeature(
        @XmlSerialName("name", ANDROID_NS, "android")
        val name: String? = null,
    )

    @Serializable
    @XmlSerialName("application", "", "")
    data class Application(
        @XmlSerialName("label", ANDROID_NS, "android")
        val label: String? = null,
        @XmlElement(true)
        @XmlSerialName("meta-data", "", "")
        val metaData: List<MetaData> = emptyList(),
    )

    @Serializable
    @XmlSerialName("meta-data", "", "")
    data class MetaData(
        @XmlSerialName("name", ANDROID_NS, "android")
        val name: String,
        @XmlSerialName("value", ANDROID_NS, "android")
        val value: String? = null,
    )

    @OptIn(ExperimentalXmlUtilApi::class)
    private val xml =
        XML {
            autoPolymorphic = false
            repairNamespaces = true
            xmlDeclMode = XmlDeclMode.Minimal
            defaultPolicy {
                unknownChildHandler = UnknownChildHandler { _, _, _, _, _ -> emptyList() }
            }
        }

    @OptIn(ExperimentalXmlUtilApi::class)
    fun parse(bytes: ByteArray): Manifest {
        require(bytes.size in 1..MAX_MANIFEST_BYTES) { "Extension manifest exceeds the parser size limit" }
        val text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        require('\u0000' !in text) { "Extension manifest contains NUL characters" }
        val uppercaseText = text.uppercase()
        require("<!DOCTYPE" !in uppercaseText && "<!ENTITY" !in uppercaseText) {
            "Extension manifest contains forbidden XML declarations"
        }

        val manifest =
            ByteArrayInputStream(bytes).use { input ->
                xml.decodeFromReader(Manifest.serializer(), KtXmlReader(input, StandardCharsets.UTF_8.name()))
            }
        require(manifest.usesFeatures.size <= MAX_USES_FEATURES) { "Extension manifest has too many uses-feature entries" }
        val application = manifest.application
        if (application != null) {
            require(application.metaData.size <= MAX_METADATA_ENTRIES) { "Extension manifest has too many metadata entries" }
            require(application.metaData.all { it.name.isNotBlank() }) { "Extension manifest has blank metadata names" }
            val duplicateKeys =
                application.metaData
                    .groupingBy(MetaData::name)
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
            require(duplicateKeys.isEmpty()) { "Extension manifest has duplicate metadata: ${duplicateKeys.sorted()}" }
        }
        return manifest
    }

    const val MAX_MANIFEST_BYTES = 1024 * 1024
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val MAX_USES_FEATURES = 64
    private const val MAX_METADATA_ENTRIES = 128
}
