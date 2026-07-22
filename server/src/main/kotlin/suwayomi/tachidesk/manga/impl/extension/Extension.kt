package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.content.pm.PackageInfo
import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dongliu.apk.parser.ApkFile
import net.dongliu.apk.parser.bean.Icon
import okhttp3.CacheControl
import org.apache.commons.compress.archivers.zip.ZipFile
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.util.AndroidManifestParser
import suwayomi.tachidesk.manga.impl.util.PackageTools
import suwayomi.tachidesk.manga.impl.util.PackageTools.EXTENSION_FEATURE
import suwayomi.tachidesk.manga.impl.util.PackageTools.LIB_VERSION_MAX
import suwayomi.tachidesk.manga.impl.util.PackageTools.LIB_VERSION_MIN
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_CONTENT_WARNING
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_EXTENSION_LIB
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_NAME
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_NSFW
import suwayomi.tachidesk.manga.impl.util.PackageTools.METADATA_SOURCE_CLASS
import suwayomi.tachidesk.manga.impl.util.PackageTools.dex2jar
import suwayomi.tachidesk.manga.impl.util.PackageTools.getPackageInfo
import suwayomi.tachidesk.manga.impl.util.PackageTools.loadExtensionSources
import suwayomi.tachidesk.manga.impl.util.ResourceArscIconParser
import suwayomi.tachidesk.manga.impl.util.network.await
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse.clearCachedImage
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse.getImageResponse
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse.saveImage
import suwayomi.tachidesk.manga.model.dataclass.ContentWarning
import suwayomi.tachidesk.manga.model.table.ExtensionActivationMarkerTable
import suwayomi.tachidesk.manga.model.table.ExtensionTable
import suwayomi.tachidesk.manga.model.table.SourceTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.outputStream
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

object Extension {
    private val logger = KotlinLogging.logger {}
    private val applicationDirs: ApplicationDirs by injectLazy()
    private val installMutex = Mutex()

    private suspend fun fetchExtensionFile(url: String): Path {
        val name = Uri.parse(url).lastPathSegment!!
        val savePath = Path(applicationDirs.tempRoot) / "extensions" / name
        // download jar file
        downloadExtension(url, savePath)

        return savePath
    }

    suspend fun installExtension(pkgName: String): String = installPublishedExtension(pkgName, PublishedInstallOperation.INSTALL)

    private suspend fun installPublishedExtension(
        pkgName: String,
        operation: PublishedInstallOperation,
    ): String {
        logger.debug { "Installing $pkgName" }
        val extension =
            transaction {
                ExtensionTable
                    .select(ExtensionTable.apkUrl, ExtensionTable.jarUrl, ExtensionTable.isInstalled)
                    .where { ExtensionTable.pkgName eq pkgName }
                    .firstOrNull()
            } ?: throw NullPointerException("Could not find extension for $pkgName")
        val jarUrl = extension[ExtensionTable.jarUrl]
        val apkUrl = extension[ExtensionTable.apkUrl]
        val selectedArtifact = ExtensionInstallArtifactPolicy.selectPublishedArtifact(apkUrl, jarUrl)

        return when (selectedArtifact.kind) {
            ExtensionInstallArtifactPolicy.Kind.DIRECT_JAR -> {
                if (operation == PublishedInstallOperation.INSTALL && extension[ExtensionTable.isInstalled]) {
                    return pkgName
                }
                installAuthenticatedPublishedJar(pkgName, operation.jarIntent)
            }

            ExtensionInstallArtifactPolicy.Kind.APK -> {
                installExtension(operation.forceReinstall, InstallOrigin.PUBLISHED_STORE) {
                    val apk = fetchExtensionFile(selectedArtifact.url)
                    val packageInfo = getPackageInfo(apk)
                    ExtensionPackage.Apk(apk, packageInfo)
                }
            }
        }
    }

    private suspend fun installAuthenticatedPublishedJar(
        pkgName: String,
        intent: ExtensionJarInstallationCoordinator.Intent,
    ): String =
        installMutex.withLock {
            val extensionsRoot = Path(applicationDirs.extensionsRoot).toAbsolutePath().normalize()
            extensionsRoot.createDirectories()
            val outcome =
                ExtensionJarInstallationCoordinator(
                    extensionsRoot = extensionsRoot,
                    databaseIdentity = activationDatabaseIdentity(),
                ).execute(pkgName, intent)
            if (!outcome.postCommit.isComplete) {
                logger.warn {
                    "Authenticated direct-JAR commit completed with " +
                        "${outcome.postCommit.failures.size} deferred runtime effect failure(s) for $pkgName"
                }
            }
            pkgName
        }

    private fun copyToExtensionsRoot(
        inputStream: InputStream,
        extensionName: String,
    ): Path {
        val rootPath = Path(applicationDirs.tempRoot) / "extensions"
        val downloadedFile = rootPath.resolve(extensionName).normalize()
        check(downloadedFile.startsWith(rootPath) && downloadedFile.parent == rootPath) {
            "File '$extensionName' is not a valid extension file"
        }
        logger.debug { "Saving jar at $extensionName" }
        // download jar file
        downloadedFile.createParentDirectories()
        downloadedFile.outputStream().buffered().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
        return downloadedFile
    }

    suspend fun installExternalExtension(
        inputStream: InputStream,
        extensionName: String,
    ): String =
        when {
            extensionName.endsWith(".jar") -> {
                ExtensionInstallArtifactPolicy.requireExternalJarExecution()
                installExtension(
                    forceReinstall = true,
                    origin = InstallOrigin.EXTERNAL_UPLOAD,
                    fetchPackage = {
                        val jar = copyToExtensionsRoot(inputStream, extensionName)
                        val manifest = extractAndParseAndroidManifest(jar)
                        ExtensionPackage.Jar(jar, manifest)
                    },
                )
            }

            extensionName.endsWith(".apk") -> {
                installExtension(
                    forceReinstall = true,
                    origin = InstallOrigin.EXTERNAL_UPLOAD,
                    fetchPackage = {
                        val apk = copyToExtensionsRoot(inputStream, extensionName)
                        val packageInfo = getPackageInfo(apk)
                        ExtensionPackage.Apk(apk, packageInfo)
                    },
                )
            }

            else -> {
                throw NullPointerException("Could not find extension type for $extensionName")
            }
        }

    sealed class ExtensionPackage {
        abstract val file: Path
        abstract val metadata: PackageMetadata

        // Abstract hook for type-specific preprocessing. The destination is an
        // operation-owned staging path and must never be the active JAR.
        abstract suspend fun prepareJar(stagedJar: Path)

        class Apk(
            override val file: Path,
            val packageInfo: PackageInfo,
        ) : ExtensionPackage() {
            override val metadata =
                PackageMetadata(
                    packageName = packageInfo.packageName,
                    versionName = packageInfo.versionName,
                    versionCode = packageInfo.versionCode,
                    reqFeatures = packageInfo.reqFeatures.orEmpty().map { it.name },
                    metaData = MetadataProvider.FromPackageInfo(packageInfo.applicationInfo.metaData),
                    label = packageInfo.applicationInfo.nonLocalizedLabel?.toString(),
                )

            override suspend fun prepareJar(stagedJar: Path) {
                dex2jar(file, stagedJar)
                extractAssetsFromApk(file, stagedJar)
            }
        }

        class Jar(
            override val file: Path,
            val manifest: AndroidManifestParser.AndroidManifest,
        ) : ExtensionPackage() {
            override val metadata =
                PackageMetadata(
                    packageName = manifest.packageName,
                    versionName = manifest.versionName!!,
                    versionCode = manifest.versionCode!!,
                    reqFeatures = manifest.usesFeatures.mapNotNull { it.name },
                    metaData = MetadataProvider.FromManifest(manifest.application!!.metaData),
                    label = manifest.application.label,
                )

            override suspend fun prepareJar(stagedJar: Path) {
                file.copyTo(stagedJar, overwrite = true)
            }
        }
    }

    data class PackageMetadata(
        val packageName: String,
        val versionName: String,
        val versionCode: Int,
        val reqFeatures: List<String>,
        val metaData: MetadataProvider,
        val label: String?,
    )

    sealed interface MetadataProvider {
        fun getString(key: String): String?

        fun getInt(key: String): Int

        class FromPackageInfo(
            private val bundle: android.os.Bundle,
        ) : MetadataProvider {
            override fun getString(key: String): String? = bundle.getString(key)

            override fun getInt(key: String): Int = bundle.getInt(key) ?: 0
        }

        class FromManifest(
            private val list: List<AndroidManifestParser.MetaData>,
        ) : MetadataProvider {
            override fun getString(key: String): String? = list.find { it.name == key }?.value

            override fun getInt(key: String): Int = getString(key)?.toIntOrNull() ?: 0
        }
    }

    private suspend fun installExtension(
        forceReinstall: Boolean = false,
        origin: InstallOrigin = InstallOrigin.PUBLISHED_STORE,
        fetchPackage: suspend () -> ExtensionPackage,
    ): String =
        installMutex.withLock {
            val extPackage = fetchPackage()
            val metadata = extPackage.metadata
            val pkgName = metadata.packageName

            if (!metadata.reqFeatures.contains(EXTENSION_FEATURE)) {
                extPackage.file.deleteExisting()
                throw Exception("This file is not a Tachiyomi extension")
            }

            val libVersion = metadata.versionName.substringBeforeLast('.').toDouble()
            if (libVersion < LIB_VERSION_MIN || libVersion > LIB_VERSION_MAX) {
                extPackage.file.deleteExisting()
                throw Exception(
                    "Lib version is $libVersion, while only versions " +
                        "$LIB_VERSION_MIN to $LIB_VERSION_MAX are allowed",
                )
            }

            var contentWarning =
                extPackage.metadata
                    .metaData
                    .getInt(METADATA_CONTENT_WARNING)
            if (contentWarning == 0) {
                contentWarning = extPackage.metadata
                    .metaData
                    .getString(METADATA_CONTENT_WARNING)
                    ?.toIntOrNull() ?: 0
                if (contentWarning == 0) {
                    contentWarning = extPackage.metadata
                        .metaData
                        .getString(METADATA_NSFW)
                        ?.toIntOrNull() ?: 0
                }
            }

            val sourceClass =
                metadata.metaData
                    .getString(METADATA_SOURCE_CLASS)!!
                    .trim()

            val className =
                if (sourceClass.startsWith(".")) {
                    pkgName + sourceClass
                } else {
                    sourceClass
                }

            val extensionName =
                metadata.metaData.getString(METADATA_NAME)
                    ?: metadata.label?.substringAfter("Tachiyomi: ")
                    ?: throw Exception("Could not resolve extension name")

            val extensionLibVersion =
                metadata.metaData
                    .getString(METADATA_EXTENSION_LIB)
                    .takeUnless { it == "0" }
                    ?: metadata.versionName.substringBeforeLast('.')

            val previousRecord =
                transaction {
                    ExtensionTable
                        .selectAll()
                        .where { ExtensionTable.pkgName eq pkgName }
                        .firstOrNull()
                }
            val isInstalled = previousRecord?.get(ExtensionTable.isInstalled) ?: false

            if (isInstalled) {
                if (!forceReinstall) {
                    extPackage.file.deleteExisting()
                    return@withLock pkgName
                }
            }

            logger.debug { "Main class for extension is $className" }

            val extensionsRoot = Path(applicationDirs.extensionsRoot).toAbsolutePath().normalize()
            extensionsRoot.createDirectories()
            val stagedJar =
                java.nio.file.Files
                    .createTempFile(extensionsRoot, ".$pkgName.install-", ".staging.jar")
            val apkName =
                when (extPackage) {
                    is ExtensionPackage.Apk -> extPackage.file.name
                    is ExtensionPackage.Jar -> extPackage.file.name.removeSuffix(".jar") + ".apk"
                }
            val activeJar = extensionsRoot / (apkName.substringBefore(".apk") + ".jar")
            val previousJar =
                previousRecord
                    ?.get(ExtensionTable.apkName)
                    ?.substringBefore(".apk")
                    ?.let { extensionsRoot / "$it.jar" }
            val previousSourceIds =
                previousRecord
                    ?.let { record ->
                        transaction {
                            SourceTable
                                .selectAll()
                                .where { SourceTable.extension eq record[ExtensionTable.id].value }
                                .map { it[SourceTable.id].value }
                        }
                    }.orEmpty()

            try {
                // createTempFile reserves an unguessable name; converters expect
                // to create their output themselves.
                stagedJar.deleteExisting()
                extPackage.prepareJar(stagedJar)
                val sources = loadSources(stagedJar, className)

                // Validation must not leave a classloader cached under the staging
                // path. Runtime loading happens lazily from the committed path.
                PackageTools.jarLoaderMap.remove(stagedJar.absolutePathString())?.close()

                ExtensionArtifactActivation.commit(
                    packageName = pkgName,
                    databaseIdentity = activationDatabaseIdentity(),
                    stagedArtifact = stagedJar,
                    activeArtifact = activeJar,
                    previousArtifact = previousJar?.takeIf { isInstalled },
                    commitMetadata = { markerKey ->
                        setupJar(
                            sources = sources,
                            className = className,
                            extensionName = extensionName,
                            extensionLibVersion = extensionLibVersion,
                            apkName = apkName,
                            pkgName = pkgName,
                            versionName = metadata.versionName,
                            versionCode = metadata.versionCode,
                            contentWarning = contentWarning,
                            origin = origin,
                            activationMarkerKey = markerKey,
                        )
                    },
                    isCommitMarkerPresent = ::isActivationMarkerPresent,
                    removeCommitMarker = ::removeActivationMarker,
                )

                previousJar?.let { oldJar ->
                    runCatching { PackageTools.jarLoaderMap.remove(oldJar.absolutePathString())?.close() }
                        .onFailure { logger.warn(it) { "Failed to close the previous classloader for $pkgName" } }
                    if (oldJar != activeJar) {
                        runCatching { oldJar.deleteIfExists() }
                            .onFailure { logger.warn(it) { "Failed to remove the retired JAR for $pkgName" } }
                    }
                }
                previousSourceIds.forEach { sourceId ->
                    runCatching { GetSource.unregisterSource(sourceId) }
                }
                ExtensionsList.updateMap.remove(pkgName)
                runCatching { cachePackageIcon(extPackage) }
                    .onFailure { logger.warn(it) { "Failed to cache the activated extension icon for $pkgName" } }
            } catch (e: Throwable) {
                PackageTools.jarLoaderMap.remove(stagedJar.absolutePathString())?.close()
                stagedJar.deleteIfExists()
                throw e
            } finally {
                extPackage.file.deleteIfExists()
            }
            pkgName
        }

    private fun loadSources(
        jarFile: Path,
        className: String,
    ): List<Source> =
        when (val extensionMainClassInstance = loadExtensionSources(jarFile, className)) {
            is Source -> listOf(extensionMainClassInstance)
            is SourceFactory -> extensionMainClassInstance.createSources()
            else -> throw RuntimeException("Unknown source class type! ${extensionMainClassInstance.javaClass}")
        }

    private fun setupJar(
        sources: List<Source>,
        className: String,
        extensionName: String,
        extensionLibVersion: String,
        apkName: String,
        pkgName: String,
        versionName: String,
        versionCode: Int,
        contentWarning: Int,
        origin: InstallOrigin,
        activationMarkerKey: String,
    ) {
        val langs = sources.map { it.lang }.toSet()
        val extensionLang =
            when (langs.size) {
                0 -> ""
                1 -> langs.first()
                else -> "all"
            }
        val normalizedContentWarning = ContentWarning.valueOf(contentWarning)
        val isLegacyNsfw = normalizedContentWarning >= ContentWarning.MIXED
        val baseUrl = sources.filterIsInstance<HttpSource>().firstOrNull()?.baseUrl
        val sourceLangs =
            sources
                .map { it.lang }
                .distinct()
                .joinToString(",")
                .ifEmpty { null }
        val isCustomPinned = origin == InstallOrigin.EXTERNAL_UPLOAD

        // update extension info
        transaction {
            if (ExtensionTable.selectAll().where { ExtensionTable.pkgName eq pkgName }.firstOrNull() == null) {
                ExtensionTable.insert {
                    it[this.apkName] = apkName
                    it[name] = extensionName
                    it[this.pkgName] = pkgName
                    it[this.versionName] = versionName
                    it[this.versionCode] = versionCode.toLong()
                    it[extensionLib] = extensionLibVersion
                    it[lang] = extensionLang
                    it[this.contentWarning] = normalizedContentWarning.ordinal
                    it[isNsfw] = isLegacyNsfw
                    it[this.baseUrl] = baseUrl
                    it[this.sourceLangs] = sourceLangs
                    it[this.isCustomPinned] = isCustomPinned
                }
            }

            ExtensionTable.update({ ExtensionTable.pkgName eq pkgName }) {
                it[this.apkName] = apkName
                it[this.isInstalled] = true
                it[this.classFQName] = className
                it[this.versionName] = versionName
                it[this.versionCode] = versionCode.toLong()
                it[name] = extensionName
                it[extensionLib] = extensionLibVersion
                it[lang] = extensionLang
                it[this.contentWarning] = normalizedContentWarning.ordinal
                it[isNsfw] = isLegacyNsfw
                it[this.baseUrl] = baseUrl
                it[this.sourceLangs] = sourceLangs
                it[this.isCustomPinned] = isCustomPinned
                it[hasUpdate] = false
                it[isObsolete] = false
            }
            val extensionId =
                ExtensionTable
                    .selectAll()
                    .where { ExtensionTable.pkgName eq pkgName }
                    .first()[ExtensionTable.id]
                    .value

            SourceTable.deleteWhere { SourceTable.extension eq extensionId }
            sources.forEach { httpSource ->
                SourceTable.insert {
                    it[id] = httpSource.id
                    it[name] = httpSource.name
                    it[lang] = httpSource.lang
                    it[extension] = extensionId
                    it[this.contentWarning] = normalizedContentWarning.ordinal
                    it[isNsfw] = isLegacyNsfw
                }
                logger.debug { "Installed source ${httpSource.name} (${httpSource.lang}) with id:${httpSource.id}" }
            }
            ExtensionActivationMarkerTable.insert {
                it[markerKey] = activationMarkerKey
                it[packageName] = pkgName
            }
        }
    }

    internal fun recoverPendingActivations(): ExtensionActivationJournal.RecoveryReport {
        val journal = ExtensionActivationJournal(Path(applicationDirs.extensionsRoot))
        val report =
            journal.recoverAll(
                activationDatabaseIdentity(),
                ::activationMarkerOwner,
                ::removeActivationMarker,
            )

        // A marker can outlive its journal if the process dies after journal
        // deletion but before marker cleanup. With no remaining journal it is
        // no longer part of a recoverable operation and can be swept safely.
        val liveMarkers = journal.loadAll().map { it.record.markerKey }.toSet()
        transaction {
            ExtensionActivationMarkerTable
                .selectAll()
                .map { it[ExtensionActivationMarkerTable.markerKey] }
                .filter { it.startsWith(ExtensionActivationJournal.MARKER_PREFIX) && it !in liveMarkers }
                .forEach { staleMarker ->
                    ExtensionActivationMarkerTable.deleteWhere { markerKey eq staleMarker }
                }
        }
        return report
    }

    private fun isActivationMarkerPresent(markerKey: String): Boolean =
        transaction {
            ExtensionActivationMarkerTable.selectAll().where { ExtensionActivationMarkerTable.markerKey eq markerKey }.any()
        }

    private fun activationMarkerOwner(markerKey: String): String? =
        transaction {
            ExtensionActivationMarkerTable
                .selectAll()
                .where { ExtensionActivationMarkerTable.markerKey eq markerKey }
                .singleOrNull()
                ?.get(ExtensionActivationMarkerTable.packageName)
        }

    private fun removeActivationMarker(markerKey: String) {
        transaction {
            ExtensionActivationMarkerTable.deleteWhere { ExtensionActivationMarkerTable.markerKey eq markerKey }
        }
    }

    private fun activationDatabaseIdentity(): String {
        val material =
            listOf(
                serverConfig.databaseType.value.name,
                serverConfig.databaseUrl.value,
                serverConfig.databaseUsername.value,
                applicationDirs.dataRoot,
            ).joinToString("\u0000")
        return MessageDigest
            .getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun cachePackageIcon(extPackage: ExtensionPackage) {
        when (extPackage) {
            is ExtensionPackage.Apk -> {
                extractAndCacheApkIcon(extPackage.file, extPackage.metadata.packageName)
            }

            is ExtensionPackage.Jar -> {
                ZipFile.builder().setPath(extPackage.file).get().use { jarZip ->
                    try {
                        cacheIcon(
                            extPackage.metadata.packageName,
                            ResourceArscIconParser.extractIcon(jarZip),
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to extract icon from JAR ${extPackage.metadata.packageName}" }
                    }
                }
            }
        }
    }

    private fun extractAndCacheApkIcon(
        apkFile: Path,
        pkgName: String,
    ) {
        try {
            val iconData =
                ApkFile(apkFile.toFile()).use { apk ->
                    apk.allIcons
                        .filterIsInstance<Icon>()
                        .mapNotNull { it.data?.let { data -> data to it.density } }
                        .maxByOrNull { (_, density) -> density }
                        ?.first
                }
            if (iconData == null) {
                logger.warn { "No icon found in APK $pkgName" }
                return
            }
            cacheIcon(pkgName, iconData.inputStream())
        } catch (e: Exception) {
            logger.warn(e) { "Failed to extract icon from APK $pkgName" }
        }
    }

    private fun cacheIcon(
        pkgName: String,
        inputStream: InputStream,
    ) {
        val iconCacheDir = Path(applicationDirs.extensionsRoot) / "icon"
        iconCacheDir.createDirectories()
        clearCachedImage(iconCacheDir.absolutePathString(), pkgName)
        saveImage("$iconCacheDir/$pkgName", inputStream, null)
    }

    @OptIn(ExperimentalPathApi::class)
    private fun extractAssetsFromApk(
        apkFile: Path,
        jarFile: Path,
    ) {
        val assetsFolder = apkFile.parent / "${apkFile.nameWithoutExtension}_assets"
        assetsFolder.createDirectories()
        ZipInputStream(apkFile.inputStream()).use { zipInputStream ->
            var zipEntry = zipInputStream.nextEntry
            while (zipEntry != null) {
                if (zipEntry.name.startsWith("assets/") && !zipEntry.isDirectory) {
                    val assetFile = assetsFolder / zipEntry.name
                    assetFile.createParentDirectories()
                    assetFile.outputStream().use { outputStream ->
                        zipInputStream.copyTo(outputStream)
                    }
                }
                zipEntry = zipInputStream.nextEntry
            }
        }

        val tempJarFile = jarFile.parent / "${jarFile.nameWithoutExtension}_temp.jar"
        ZipInputStream(jarFile.inputStream()).use { jarZipInputStream ->
            ZipOutputStream(tempJarFile.outputStream()).use { jarZipOutputStream ->
                var zipEntry = jarZipInputStream.nextEntry
                while (zipEntry != null) {
                    if (!zipEntry.name.startsWith("META-INF/")) {
                        jarZipOutputStream.putNextEntry(ZipEntry(zipEntry.name))
                        jarZipInputStream.copyTo(jarZipOutputStream)
                    }
                    zipEntry = jarZipInputStream.nextEntry
                }
                assetsFolder.walk().forEach { file ->
                    if (file.isRegularFile()) {
                        jarZipOutputStream.putNextEntry(ZipEntry(file.relativeTo(assetsFolder).toString().replace("\\", "/")))
                        file.inputStream().use { inputStream ->
                            inputStream.copyTo(jarZipOutputStream)
                        }
                        jarZipOutputStream.closeEntry()
                    }
                }
            }
        }

        jarFile.deleteIfExists()
        tempJarFile.copyTo(jarFile)
        tempJarFile.deleteExisting()

        assetsFolder.deleteRecursively()
    }

    private fun extractAndParseAndroidManifest(jar: Path): AndroidManifestParser.AndroidManifest =
        ZipFile.builder().setPath(jar).get().use { jarZip ->
            jarZip.getInputStream(jarZip.getEntry("AndroidManifest.xml")).use {
                AndroidManifestParser.parse(it)
            }
        }

    private val network: NetworkHelper by injectLazy()

    private suspend fun downloadExtension(
        url: String,
        savePath: Path,
    ) {
        val response =
            network.client
                .newCall(
                    GET(url, cache = CacheControl.FORCE_NETWORK),
                ).await()

        savePath.createParentDirectories()
        response.body.byteStream().use {
            savePath.outputStream().buffered().use { out ->
                it.copyTo(out)
            }
        }
    }

    fun uninstallExtension(pkgName: String) {
        logger.debug { "Uninstalling $pkgName" }

        val extensionRecord = transaction { ExtensionTable.selectAll().where { ExtensionTable.pkgName eq pkgName }.first() }
        val fileNameWithoutType =
            extensionRecord[ExtensionTable.apkName]?.substringBefore(".apk")
                ?: throw NullPointerException("Missing $pkgName apkName")
        val jarPath = Path(applicationDirs.extensionsRoot) / "$fileNameWithoutType.jar"
        val sources =
            transaction {
                val extensionId = extensionRecord[ExtensionTable.id].value

                val sources = SourceTable.selectAll().where { SourceTable.extension eq extensionId }.map { it[SourceTable.id].value }

                SourceTable.deleteWhere { SourceTable.extension eq extensionId }

                val hasStoreCandidate =
                    extensionRecord[ExtensionTable.storeIndexUrl] != null &&
                        (extensionRecord[ExtensionTable.apkUrl] != null || extensionRecord[ExtensionTable.jarUrl] != null)
                if (extensionRecord[ExtensionTable.isObsolete] || !hasStoreCandidate) {
                    ExtensionTable.deleteWhere { ExtensionTable.pkgName eq pkgName }
                } else {
                    ExtensionTable.update({ ExtensionTable.pkgName eq pkgName }) {
                        it[isInstalled] = false
                        it[hasUpdate] = false
                        it[apkName] = null
                        it[isCustomPinned] = false
                    }
                }

                sources
            }

        if (jarPath.exists()) {
            // free up the file descriptor if exists
            PackageTools.jarLoaderMap.remove(jarPath.absolutePathString())?.close()

            // clear all loaded sources
            sources.forEach { GetSource.unregisterSource(it) }

            jarPath.deleteExisting()
        }
    }

    /**
     * Moves a custom upload back to the artifact advertised by its store.
     * The candidate is downloaded and its manifest is validated before the
     * active custom artifact is uninstalled by the force-reinstall path.
     */
    suspend fun switchExtensionToRepo(pkgName: String): String {
        val state =
            transaction {
                ExtensionTable
                    .select(
                        ExtensionTable.isCustomPinned,
                        ExtensionTable.storeIndexUrl,
                        ExtensionTable.apkUrl,
                        ExtensionTable.jarUrl,
                    ).where { ExtensionTable.pkgName eq pkgName }
                    .firstOrNull()
            } ?: throw NoSuchElementException("Could not find extension for $pkgName")

        if (!state[ExtensionTable.isCustomPinned]) return installExtension(pkgName)

        check(
            state[ExtensionTable.storeIndexUrl] != null &&
                (state[ExtensionTable.apkUrl] != null || state[ExtensionTable.jarUrl] != null),
        ) { "Custom extension $pkgName has no published store artifact" }

        return installPublishedExtension(pkgName, PublishedInstallOperation.REPLACE_CUSTOM)
    }

    suspend fun updateExtension(pkgName: String): String {
        check(ExtensionsList.updateMap.containsKey(pkgName)) { "No update is available for $pkgName" }
        return installPublishedExtension(pkgName, PublishedInstallOperation.UPGRADE)
    }

    suspend fun getExtensionIcon(pkgName: String): Pair<InputStream, String> {
        val cacheSaveDir = "${applicationDirs.extensionsRoot}/icon"

        if (pkgName == LocalSource::class.java.`package`.name) {
            return getImageResponse(cacheSaveDir, "localSource") {
                network.client
                    .newCall(GET("", cache = CacheControl.FORCE_NETWORK))
                    .await()
            }
        }

        val iconUrl =
            transaction { ExtensionTable.selectAll().where { ExtensionTable.pkgName eq pkgName }.first() }[ExtensionTable.iconUrl]

        return getImageResponse(cacheSaveDir, pkgName) {
            network.client
                .newCall(
                    GET(iconUrl, cache = CacheControl.FORCE_NETWORK),
                ).await()
        }
    }

    fun proxyExtensionIconUrl(pkgName: String): String = "/api/v1/extension/icon/$pkgName"

    enum class InstallOrigin {
        PUBLISHED_STORE,
        EXTERNAL_UPLOAD,
    }

    private enum class PublishedInstallOperation(
        val forceReinstall: Boolean,
        val jarIntent: ExtensionJarInstallationCoordinator.Intent,
    ) {
        INSTALL(false, ExtensionJarInstallationCoordinator.Intent.INSTALL),
        UPGRADE(true, ExtensionJarInstallationCoordinator.Intent.UPGRADE),
        REPLACE_CUSTOM(true, ExtensionJarInstallationCoordinator.Intent.REPLACE_CUSTOM),
    }
}
