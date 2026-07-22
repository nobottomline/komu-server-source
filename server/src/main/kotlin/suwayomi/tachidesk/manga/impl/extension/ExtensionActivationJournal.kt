package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

/**
 * Durable filesystem ledger for one extension activation.
 *
 * A database marker with [Record.markerKey] must be inserted in the same
 * transaction as extension/source metadata. Recovery treats that marker, not
 * version metadata, as the authority for deciding roll-forward vs rollback.
 */
internal class ExtensionActivationJournal(
    extensionsRoot: Path,
) {
    private val root = extensionsRoot.toAbsolutePath().normalize()
    private val journalRoot = root.resolve(JOURNAL_DIRECTORY)

    init {
        Files.createDirectories(root)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Extension root must be a real directory"
        }
        Files.createDirectories(journalRoot)
        require(Files.isDirectory(journalRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(journalRoot)) {
            "Extension activation journal must be a real directory"
        }
    }

    fun prepare(
        packageName: String,
        databaseIdentity: String,
        stagedArtifact: Path,
        activeArtifact: Path,
        previousArtifact: Path?,
    ): Entry =
        prepareInternal(
            packageName,
            databaseIdentity,
            stagedArtifact,
            activeArtifact,
            previousArtifact,
            storeTrustBinding = null,
        )

    fun prepareAuthenticated(
        packageName: String,
        databaseIdentity: String,
        stagedArtifact: Path,
        activeArtifact: Path,
        previousArtifact: Path?,
        storeTrustBinding: AuthenticatedStoreBinding,
    ): Entry =
        prepareInternal(
            packageName,
            databaseIdentity,
            stagedArtifact,
            activeArtifact,
            previousArtifact,
            storeTrustBinding,
        )

    private fun prepareInternal(
        packageName: String,
        databaseIdentity: String,
        stagedArtifact: Path,
        activeArtifact: Path,
        previousArtifact: Path?,
        storeTrustBinding: AuthenticatedStoreBinding?,
    ): Entry {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid extension package name" }
        require(SHA256.matches(databaseIdentity)) { "Invalid database identity" }
        val staged = requireRootFile(stagedArtifact, "staged")
        val active = requireRootFile(activeArtifact, "active", mustExist = false)
        val previous = previousArtifact?.let { requireRootFile(it, "previous", mustExist = true) }
        require(staged != active) { "Staged and active extension artifacts must differ" }

        val operationId = UUID.randomUUID().toString()
        acquirePackageLease(packageName, operationId)
        var entry: Entry? = null
        try {
            if (loadAll().any { it.record.packageName == packageName }) {
                throw PendingExtensionActivationException(packageName)
            }
            val candidateSize = Files.size(staged)
            val candidateSha256 = sha256(staged)
            storeTrustBinding?.let { binding ->
                require(binding.packageName == packageName) { "Authenticated store package identity changed" }
                require(binding.candidateSize == candidateSize) { "Authenticated artifact size changed before journaling" }
                require(binding.candidateSha256 == candidateSha256) {
                    "Authenticated artifact digest changed before journaling"
                }
            }
            val rollback =
                if (previous == active) {
                    root.resolve(".${active.fileName}.rollback-$operationId.jar")
                } else {
                    null
                }
            val record =
                Record(
                    operationId = operationId,
                    packageName = packageName,
                    databaseIdentity = databaseIdentity,
                    markerKey = MARKER_PREFIX + operationId,
                    state = State.PREPARED,
                    stagedName = staged.fileName.toString(),
                    activeName = active.fileName.toString(),
                    previousName = previous?.fileName?.toString(),
                    rollbackName = rollback?.fileName?.toString(),
                    candidateSha256 = candidateSha256,
                    previousSha256 = previous?.let(::sha256),
                    storeTrustBinding = storeTrustBinding,
                )
            entry = Entry(journalRoot.resolve("$operationId.properties"), record).also(::persist)

            // The journal is durable before the backup is created. A crash in
            // this interval is recoverable because PREPARED still sees the
            // untouched previous digest and does not require the backup.
            rollback?.let { backup ->
                Files.copy(active, backup, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                forceFile(backup)
                forceDirectory(root)
            }
            return checkNotNull(entry)
        } catch (failure: Throwable) {
            if (entry != null) {
                runCatching { rollback(entry) }.onFailure(failure::addSuppressed)
            } else {
                runCatching { releasePackageLease(packageName, operationId) }.onFailure(failure::addSuppressed)
            }
            throw failure
        }
    }

    fun transition(
        entry: Entry,
        state: State,
    ): Entry {
        requirePackageLease(entry)
        require(state.ordinal == entry.record.state.ordinal + 1) {
            "Invalid activation transition ${entry.record.state} -> $state"
        }
        return entry.copy(record = entry.record.copy(state = state)).also(::persist)
    }

    fun publish(entry: Entry) {
        requirePackageLease(entry)
        val staged = artifact(entry.record.stagedName)
        val active = artifact(entry.record.activeName)
        require(sha256(staged) == entry.record.candidateSha256) { "Staged extension artifact changed before activation" }
        moveReplacing(staged, active)
        forceDirectory(root)
        require(sha256(active) == entry.record.candidateSha256) { "Published extension artifact digest mismatch" }
    }

    fun rollback(entry: Entry) {
        requirePackageLease(entry)
        val record = entry.record
        val active = artifact(record.activeName)
        val previous = record.previousName?.let(::artifact)
        val rollback = record.rollbackName?.let(::artifact)
        val activeDigest = active.takeIf(::isRegularFile)?.let(::sha256)

        when {
            previous == null -> {
                if (activeDigest != null) {
                    require(activeDigest == record.candidateSha256) { "Refusing to delete an unknown active artifact" }
                    Files.delete(active)
                }
            }

            previous == active -> {
                when (activeDigest) {
                    record.previousSha256 -> {}

                    record.candidateSha256 -> {
                        require(rollback != null && isRegularFile(rollback)) { "Missing rollback artifact" }
                        require(sha256(rollback) == record.previousSha256) { "Rollback artifact digest mismatch" }
                        Files.delete(active)
                        moveReplacing(rollback, active)
                    }

                    else -> {
                        error("Active artifact matches neither candidate nor previous digest")
                    }
                }
            }

            else -> {
                require(isRegularFile(previous)) { "Previous extension artifact is missing" }
                require(sha256(previous) == record.previousSha256) { "Previous extension artifact digest mismatch" }
                if (activeDigest != null) {
                    require(activeDigest == record.candidateSha256) { "Refusing to delete an unknown active artifact" }
                    Files.delete(active)
                }
            }
        }

        artifact(record.stagedName).takeIf(::isRegularFile)?.let(Files::delete)
        rollback?.takeIf(::isRegularFile)?.let(Files::delete)
        forceDirectory(root)
        deleteJournal(entry)
    }

    fun finalizeCommitted(entry: Entry) {
        requirePackageLease(entry)
        val record = entry.record
        val active = artifact(record.activeName)
        require(isRegularFile(active)) { "Committed extension artifact is missing" }
        require(sha256(active) == record.candidateSha256) { "Committed extension artifact digest mismatch" }

        val previous = record.previousName?.let(::artifact)
        if (previous != null && previous != active) Files.deleteIfExists(previous)
        record.rollbackName?.let(::artifact)?.let(Files::deleteIfExists)
        Files.deleteIfExists(artifact(record.stagedName))
        forceDirectory(root)
        deleteJournal(entry)
    }

    fun recoverAll(
        databaseIdentity: String,
        databaseMarkerOwner: (markerKey: String) -> String?,
        removeDatabaseMarker: (markerKey: String) -> Unit,
    ): RecoveryReport {
        sweepTemporaryJournalFiles()
        val entries = loadAll()
        reconcilePackageLeases(entries)
        require(entries.all { it.record.databaseIdentity == databaseIdentity }) {
            "Pending extension activation belongs to a different database configuration"
        }
        var rolledBack = 0
        var finalized = 0
        entries.forEach { entry ->
            val markerOwner = databaseMarkerOwner(entry.record.markerKey)
            if (markerOwner != null) {
                require(markerOwner == entry.record.packageName) {
                    "Extension activation marker belongs to a different package"
                }
                finalizeCommitted(entry)
                removeDatabaseMarker(entry.record.markerKey)
                finalized++
            } else {
                rollback(entry)
                rolledBack++
            }
        }
        return RecoveryReport(entries.map { it.record.markerKey }.toSet(), rolledBack, finalized)
    }

    private fun sweepTemporaryJournalFiles() {
        Files.list(journalRoot).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".tmp") }
                .forEach(Files::deleteIfExists)
        }
        forceDirectory(journalRoot)
    }

    /**
     * Restores the one-pending-operation-per-package invariant before recovery.
     * Version-1 journals predate lease files, so a missing lease is recreated.
     * A lease without a journal is an interrupted pre-prepare operation and can
     * be removed only during startup recovery, never by a competing installer.
     */
    private fun reconcilePackageLeases(entries: List<Entry>) {
        val duplicatePackage =
            entries
                .groupingBy { it.record.packageName }
                .eachCount()
                .entries
                .firstOrNull { it.value > 1 }
        require(duplicatePackage == null) {
            "Multiple pending extension activations exist for package ${duplicatePackage?.key}"
        }

        val expectedLeases = entries.associateBy { packageLeasePath(it.record.packageName) }
        Files.list(journalRoot).use { paths ->
            paths
                .filter { isPackageLeasePath(it) && it !in expectedLeases }
                .forEach(Files::deleteIfExists)
        }
        entries.forEach { entry ->
            val leasePath = packageLeasePath(entry.record.packageName)
            if (Files.exists(leasePath, LinkOption.NOFOLLOW_LINKS)) {
                require(readPackageLease(leasePath) == PackageLease(entry.record.packageName, entry.record.operationId)) {
                    "Extension activation lease does not match its journal"
                }
            } else {
                acquirePackageLease(entry.record.packageName, entry.record.operationId)
            }
        }
        forceDirectory(journalRoot)
    }

    private fun acquirePackageLease(
        packageName: String,
        operationId: String,
    ) {
        val leasePath = packageLeasePath(packageName)
        val bytes = "$operationId\n$packageName\n".toByteArray(StandardCharsets.UTF_8)
        try {
            FileChannel
                .open(
                    leasePath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
            forceDirectory(journalRoot)
        } catch (_: FileAlreadyExistsException) {
            throw PendingExtensionActivationException(packageName)
        }
    }

    private fun releasePackageLease(
        packageName: String,
        operationId: String,
    ) {
        val leasePath = packageLeasePath(packageName)
        if (!Files.exists(leasePath, LinkOption.NOFOLLOW_LINKS)) return
        require(readPackageLease(leasePath) == PackageLease(packageName, operationId)) {
            "Refusing to release a different extension activation lease"
        }
        Files.delete(leasePath)
    }

    private fun readPackageLease(path: Path): PackageLease {
        require(isPackageLeasePath(path) && isRegularFile(path)) { "Invalid extension activation lease" }
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
        require(lines.size == 2) { "Invalid extension activation lease contents" }
        val operationId = lines[0]
        val packageName = lines[1]
        require(runCatching { UUID.fromString(operationId).toString() }.getOrNull() == operationId) {
            "Invalid activation lease operation ID"
        }
        require(PACKAGE_NAME.matches(packageName)) { "Invalid activation lease package name" }
        require(path == packageLeasePath(packageName)) { "Activation lease package/path mismatch" }
        return PackageLease(packageName, operationId)
    }

    private fun requirePackageLease(entry: Entry) {
        val leasePath = packageLeasePath(entry.record.packageName)
        require(Files.exists(leasePath, LinkOption.NOFOLLOW_LINKS)) { "Extension activation lease is missing" }
        require(readPackageLease(leasePath) == PackageLease(entry.record.packageName, entry.record.operationId)) {
            "Extension activation lease does not match its journal"
        }
    }

    /** Fences a cross-component handoff against a stale in-memory entry or lost package lease. */
    fun requireCurrentOwnership(entry: Entry) {
        requirePackageLease(entry)
        require(load(entry.path).record == entry.record) { "Extension activation journal changed during handoff" }
    }

    private fun packageLeasePath(packageName: String): Path =
        journalRoot.resolve("$LEASE_PREFIX${sha256(packageName.toByteArray(StandardCharsets.UTF_8))}$LEASE_SUFFIX")

    private fun isPackageLeasePath(path: Path): Boolean {
        if (path.toAbsolutePath().normalize().parent != journalRoot) return false
        val name = path.fileName.toString()
        return name.startsWith(LEASE_PREFIX) && name.endsWith(LEASE_SUFFIX)
    }

    fun deleteJournal(entry: Entry) {
        requirePackageLease(entry)
        Files.deleteIfExists(entry.path)
        releasePackageLease(entry.record.packageName, entry.record.operationId)
        forceDirectory(journalRoot)
    }

    fun loadAll(): List<Entry> =
        Files
            .list(journalRoot)
            .use { paths ->
                paths
                    .filter { it.fileName.toString().endsWith(".properties") }
                    .sorted()
                    .map(::load)
                    .toList()
            }

    private fun persist(entry: Entry) {
        val temporary = Files.createTempFile(journalRoot, ".${entry.record.operationId}-", ".tmp")
        val properties = entry.record.toProperties()
        try {
            FileChannel
                .open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                .use { channel ->
                    val output = Channels.newOutputStream(channel)
                    properties.store(output, null)
                    output.flush()
                    channel.force(true)
                }
            moveReplacing(temporary, entry.path)
            forceDirectory(journalRoot)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun load(path: Path): Entry {
        require(path.toAbsolutePath().normalize().parent == journalRoot && !Files.isSymbolicLink(path)) {
            "Invalid extension activation journal path"
        }
        val properties = Properties()
        FileChannel
            .open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            .use { channel -> properties.load(Channels.newInputStream(channel)) }
        val record = Record.fromProperties(properties)
        require(runCatching { UUID.fromString(record.operationId).toString() }.getOrNull() == record.operationId) {
            "Invalid activation operation ID"
        }
        require(path.fileName.toString() == "${record.operationId}.properties") { "Journal operation ID/path mismatch" }
        listOfNotNull(record.stagedName, record.activeName, record.previousName, record.rollbackName).forEach(::validateFileName)
        require(PACKAGE_NAME.matches(record.packageName)) { "Invalid package name in activation journal" }
        require(SHA256.matches(record.databaseIdentity)) { "Invalid database identity in activation journal" }
        require(record.markerKey == MARKER_PREFIX + record.operationId) { "Invalid activation marker key" }
        require(SHA256.matches(record.candidateSha256)) { "Invalid candidate digest" }
        require(record.previousSha256 == null || SHA256.matches(record.previousSha256)) { "Invalid previous digest" }
        return Entry(path, record)
    }

    private fun requireRootFile(
        path: Path,
        role: String,
        mustExist: Boolean = true,
    ): Path {
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.parent == root) { "$role extension artifact must be directly inside the extension root" }
        validateFileName(normalized.fileName.toString())
        if (mustExist) require(isRegularFile(normalized)) { "$role extension artifact does not exist" }
        return normalized
    }

    private fun artifact(fileName: String): Path {
        validateFileName(fileName)
        return root.resolve(fileName)
    }

    private fun validateFileName(fileName: String) {
        require(fileName.isNotBlank() && Path.of(fileName).fileName.toString() == fileName) { "Invalid artifact filename" }
        require(fileName != JOURNAL_DIRECTORY) { "Artifact filename collides with journal directory" }
    }

    private fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private fun sha256(path: Path): String {
        require(isRegularFile(path)) { "Artifact is not a regular file: $path" }
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel
            .open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            .use { channel ->
                val buffer = java.nio.ByteBuffer.allocate(32 * 1024)
                while (channel.read(buffer) != -1) {
                    buffer.flip()
                    digest.update(buffer)
                    buffer.clear()
                }
            }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun forceFile(path: Path) {
        FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun forceDirectory(path: Path) {
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun moveReplacing(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            error("Extension activation requires atomic rename support")
        }
    }

    data class Entry(
        val path: Path,
        val record: Record,
    )

    data class Record(
        val operationId: String,
        val packageName: String,
        val databaseIdentity: String,
        val markerKey: String,
        val state: State,
        val stagedName: String,
        val activeName: String,
        val previousName: String?,
        val rollbackName: String?,
        val candidateSha256: String,
        val previousSha256: String?,
        val storeTrustBinding: AuthenticatedStoreBinding?,
    ) {
        fun toProperties() =
            Properties().apply {
                setProperty("formatVersion", CURRENT_FORMAT_VERSION)
                setProperty("operationId", operationId)
                setProperty("packageName", packageName)
                setProperty("databaseIdentity", databaseIdentity)
                setProperty("markerKey", markerKey)
                setProperty("state", state.name)
                setProperty("stagedName", stagedName)
                setProperty("activeName", activeName)
                previousName?.let { setProperty("previousName", it) }
                rollbackName?.let { setProperty("rollbackName", it) }
                setProperty("candidateSha256", candidateSha256)
                previousSha256?.let { setProperty("previousSha256", it) }
                if (storeTrustBinding == null) {
                    setProperty("trustMode", TRUST_MODE_LEGACY)
                } else {
                    setProperty("trustMode", TRUST_MODE_AUTHENTICATED_STORE)
                    setProperty("ledgerEntryId", storeTrustBinding.ledgerEntryID.toString())
                    setProperty("storeSnapshotToken", storeTrustBinding.storeSnapshotToken)
                    setProperty("storeIndexUrl", storeTrustBinding.storeIndexUrl)
                    setProperty("storeSigningKey", storeTrustBinding.storeSigningKey)
                    setProperty("candidateSize", storeTrustBinding.candidateSize.toString())
                    setProperty("sourceIds", storeTrustBinding.sourceIDs.joinToString(","))
                }
            }

        companion object {
            fun fromProperties(properties: Properties): Record {
                fun required(key: String): String =
                    properties.getProperty(key)?.takeIf(String::isNotBlank)
                        ?: error("Missing activation journal property: $key")

                val formatVersion = required("formatVersion")
                require(formatVersion in SUPPORTED_FORMAT_VERSIONS) { "Unsupported activation journal version" }
                val packageName = required("packageName")
                val candidateSha256 = required("candidateSha256")
                val storeTrustBinding =
                    when (formatVersion) {
                        LEGACY_FORMAT_VERSION -> {
                            require(TRUST_PROPERTY_KEYS.none(properties::containsKey)) {
                                "Version-1 activation journal contains version-2 trust properties"
                            }
                            null
                        }

                        CURRENT_FORMAT_VERSION -> {
                            when (required("trustMode")) {
                                TRUST_MODE_LEGACY -> {
                                    require(AUTHENTICATED_TRUST_PROPERTY_KEYS.none(properties::containsKey)) {
                                        "Legacy activation journal contains authenticated trust properties"
                                    }
                                    null
                                }

                                TRUST_MODE_AUTHENTICATED_STORE -> {
                                    AuthenticatedStoreBinding(
                                        packageName = packageName,
                                        ledgerEntryID = required("ledgerEntryId").toLong(),
                                        storeSnapshotToken = required("storeSnapshotToken"),
                                        storeIndexUrl = required("storeIndexUrl"),
                                        storeSigningKey = required("storeSigningKey"),
                                        candidateSize = required("candidateSize").toLong(),
                                        candidateSha256 = candidateSha256,
                                        sourceIDs =
                                            required("sourceIds")
                                                .split(',')
                                                .map { it.toLong() },
                                    )
                                }

                                else -> {
                                    error("Unsupported activation journal trust mode")
                                }
                            }
                        }

                        else -> {
                            error("Unsupported activation journal version")
                        }
                    }
                return Record(
                    operationId = required("operationId"),
                    packageName = packageName,
                    databaseIdentity = required("databaseIdentity"),
                    markerKey = required("markerKey"),
                    state = State.valueOf(required("state")),
                    stagedName = required("stagedName"),
                    activeName = required("activeName"),
                    previousName = properties.getProperty("previousName")?.takeIf(String::isNotBlank),
                    rollbackName = properties.getProperty("rollbackName")?.takeIf(String::isNotBlank),
                    candidateSha256 = candidateSha256,
                    previousSha256 = properties.getProperty("previousSha256")?.takeIf(String::isNotBlank),
                    storeTrustBinding = storeTrustBinding,
                )
            }
        }
    }

    /** Immutable provenance required before a store-delivered JAR may enter the activation journal. */
    data class AuthenticatedStoreBinding(
        val packageName: String,
        val ledgerEntryID: Long,
        val storeSnapshotToken: String,
        val storeIndexUrl: String,
        val storeSigningKey: String,
        val candidateSize: Long,
        val candidateSha256: String,
        val sourceIDs: List<Long>,
    ) {
        init {
            ExtensionArtifactInputPolicy.requirePackageName(packageName)
            require(ledgerEntryID > 0) { "Invalid extension-store ledger entry ID" }
            require(runCatching { UUID.fromString(storeSnapshotToken).toString() }.getOrNull() == storeSnapshotToken) {
                "Invalid extension-store snapshot token"
            }
            ExtensionArtifactInputPolicy.requireHttpsUrl(storeIndexUrl)
            require(SHA256.matches(storeSigningKey)) { "Invalid extension-store signing key" }
            require(candidateSize in 1..StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES) {
                "Invalid authenticated extension artifact size"
            }
            require(SHA256.matches(candidateSha256)) { "Invalid authenticated extension artifact digest" }
            require(sourceIDs.isNotEmpty() && sourceIDs.size <= MAX_SOURCE_IDS) {
                "Invalid authenticated extension source-ID count"
            }
            require(sourceIDs.all { it > 0 } && sourceIDs == sourceIDs.distinct().sorted()) {
                "Authenticated extension source IDs must be positive, unique, and sorted"
            }
        }
    }

    enum class State {
        PREPARED,
        FILE_SWAP_PENDING,
        FILE_VISIBLE,
        DB_COMMITTED,
    }

    data class RecoveryReport(
        val referencedMarkerKeys: Set<String>,
        val rolledBack: Int,
        val finalized: Int,
    )

    private data class PackageLease(
        val packageName: String,
        val operationId: String,
    )

    companion object {
        const val MARKER_PREFIX = "komu.extension.activation."
        private const val LEGACY_FORMAT_VERSION = "1"
        private const val CURRENT_FORMAT_VERSION = "2"
        private val SUPPORTED_FORMAT_VERSIONS = setOf(LEGACY_FORMAT_VERSION, CURRENT_FORMAT_VERSION)
        private const val JOURNAL_DIRECTORY = ".activation-journal"
        private const val LEASE_PREFIX = "lease-"
        private const val LEASE_SUFFIX = ".lock"
        private const val TRUST_MODE_LEGACY = "legacy"
        private const val TRUST_MODE_AUTHENTICATED_STORE = "authenticated-store-jar"
        private const val MAX_SOURCE_IDS = 10_000
        private val AUTHENTICATED_TRUST_PROPERTY_KEYS =
            setOf("ledgerEntryId", "storeSnapshotToken", "storeIndexUrl", "storeSigningKey", "candidateSize", "sourceIds")
        private val TRUST_PROPERTY_KEYS = AUTHENTICATED_TRUST_PROPERTY_KEYS + "trustMode"
        private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal class PendingExtensionActivationException(
    packageName: String,
) : IllegalStateException("Another extension activation is pending for package $packageName")
