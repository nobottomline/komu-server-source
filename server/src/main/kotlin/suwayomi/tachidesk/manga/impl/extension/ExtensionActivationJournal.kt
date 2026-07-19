package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
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
    ): Entry {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid extension package name" }
        require(SHA256.matches(databaseIdentity)) { "Invalid database identity" }
        val staged = requireRootFile(stagedArtifact, "staged")
        val active = requireRootFile(activeArtifact, "active", mustExist = false)
        val previous = previousArtifact?.let { requireRootFile(it, "previous", mustExist = true) }
        require(staged != active) { "Staged and active extension artifacts must differ" }

        val operationId = UUID.randomUUID().toString()
        val replacesInPlace = previous == active
        val rollback =
            if (replacesInPlace) {
                root.resolve(".${active.fileName}.rollback-$operationId.jar").also { backup ->
                    Files.copy(active, backup, StandardCopyOption.COPY_ATTRIBUTES)
                    forceFile(backup)
                    forceDirectory(root)
                }
            } else {
                null
            }

        val journalPath = journalRoot.resolve("$operationId.properties")
        try {
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
                    candidateSha256 = sha256(staged),
                    previousSha256 = previous?.let(::sha256),
                )
            return Entry(journalPath, record).also(::persist)
        } catch (failure: Throwable) {
            // If the journal rename landed, recovery owns the backup. If it
            // did not, the active old artifact is still untouched and this
            // operation must not leak an unreferenced rollback copy.
            if (!Files.exists(journalPath)) {
                rollback?.let { runCatching { Files.deleteIfExists(it) } }
                runCatching { forceDirectory(root) }
            }
            throw failure
        }
    }

    fun transition(
        entry: Entry,
        state: State,
    ): Entry {
        require(state.ordinal == entry.record.state.ordinal + 1) {
            "Invalid activation transition ${entry.record.state} -> $state"
        }
        return entry.copy(record = entry.record.copy(state = state)).also(::persist)
    }

    fun publish(entry: Entry) {
        val staged = artifact(entry.record.stagedName)
        val active = artifact(entry.record.activeName)
        require(sha256(staged) == entry.record.candidateSha256) { "Staged extension artifact changed before activation" }
        moveReplacing(staged, active)
        forceDirectory(root)
        require(sha256(active) == entry.record.candidateSha256) { "Published extension artifact digest mismatch" }
    }

    fun rollback(entry: Entry) {
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
        isDatabaseCommitted: (markerKey: String) -> Boolean,
        removeDatabaseMarker: (markerKey: String) -> Unit,
    ): RecoveryReport {
        sweepTemporaryJournalFiles()
        val entries = loadAll()
        require(entries.all { it.record.databaseIdentity == databaseIdentity }) {
            "Pending extension activation belongs to a different database configuration"
        }
        var rolledBack = 0
        var finalized = 0
        entries.forEach { entry ->
            if (isDatabaseCommitted(entry.record.markerKey)) {
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

    fun deleteJournal(entry: Entry) {
        Files.deleteIfExists(entry.path)
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
    ) {
        fun toProperties() =
            Properties().apply {
                setProperty("formatVersion", FORMAT_VERSION)
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
            }

        companion object {
            fun fromProperties(properties: Properties): Record {
                fun required(key: String): String =
                    properties.getProperty(key)?.takeIf(String::isNotBlank)
                        ?: error("Missing activation journal property: $key")

                require(required("formatVersion") == FORMAT_VERSION) { "Unsupported activation journal version" }
                return Record(
                    operationId = required("operationId"),
                    packageName = required("packageName"),
                    databaseIdentity = required("databaseIdentity"),
                    markerKey = required("markerKey"),
                    state = State.valueOf(required("state")),
                    stagedName = required("stagedName"),
                    activeName = required("activeName"),
                    previousName = properties.getProperty("previousName")?.takeIf(String::isNotBlank),
                    rollbackName = properties.getProperty("rollbackName")?.takeIf(String::isNotBlank),
                    candidateSha256 = required("candidateSha256"),
                    previousSha256 = properties.getProperty("previousSha256")?.takeIf(String::isNotBlank),
                )
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

    companion object {
        const val MARKER_PREFIX = "komu.extension.activation."
        private const val FORMAT_VERSION = "1"
        private const val JOURNAL_DIRECTORY = ".activation-journal"
        private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
