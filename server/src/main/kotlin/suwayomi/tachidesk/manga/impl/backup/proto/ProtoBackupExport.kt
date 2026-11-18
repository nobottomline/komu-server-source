package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import android.app.Application
import android.content.Context
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import okio.Buffer
import okio.Sink
import okio.buffer
import okio.gzip
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupCategoryHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupGlobalMetaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupMangaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupSettingsHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupSourceHandler
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import suwayomi.tachidesk.util.HAScheduler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * Komu: publishes the new automated archive before pruning old ones, so a
 * failed or interrupted backup never leaves fewer archives than before.
 */
internal fun publishThenPrune(
    create: () -> File,
    prune: (keep: File) -> Unit,
) {
    val published = create()
    prune(published)
}

/**
 * Komu (review 5): publishes an archive so that its name survives a system
 * crash before anything relies on it. The bytes go to a temporary sibling,
 * which is synced and renamed into place; then the directory is synced, so
 * the rename itself is on disk. Any failure throws, and the caller (see
 * [publishThenPrune]) prunes nothing.
 */
internal fun publishArchiveDurably(
    target: File,
    write: (OutputStream) -> Unit,
    syncDirectory: (Path) -> Unit = ::syncDirectory,
): File {
    val directory = target.absoluteFile.parentFile
    val temporary = File(directory, ".${target.name}.${UUID.randomUUID()}.tmp")
    try {
        FileOutputStream(temporary).use { output ->
            write(output)
            output.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } finally {
        temporary.delete()
    }
    syncDirectory(directory.toPath())
    return target
}

/** `fsync` of a directory (opening one for reading is allowed on Darwin and Linux). */
internal fun syncDirectory(directory: Path) {
    FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
}

object ProtoBackupExport : ProtoBackupBase() {
    private val logger = KotlinLogging.logger { }
    private val applicationDirs: ApplicationDirs by injectLazy()
    private var backupSchedulerJobId: String = ""
    private const val LAST_AUTOMATED_BACKUP_KEY = "lastAutomatedBackup"
    private val preferences = Injekt.get<Application>().getSharedPreferences("server_util", Context.MODE_PRIVATE)
    private const val AUTO_BACKUP_FILENAME = "auto"

    init {
        serverConfig.subscribeTo(
            combine(serverConfig.backupInterval, serverConfig.backupTime) { interval, timeOfDay ->
                Pair(
                    interval,
                    timeOfDay,
                )
            },
            ::scheduleAutomatedBackupTask,
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun scheduleAutomatedBackupTask() {
        HAScheduler.descheduleCron(backupSchedulerJobId)

        val areAutomatedBackupsDisabled = serverConfig.backupInterval.value == 0
        if (areAutomatedBackupsDisabled) {
            return
        }

        val task = {
            try {
                publishThenPrune(::createAutomatedBackup, ::cleanupAutomatedBackups)
                preferences.edit().putLong(LAST_AUTOMATED_BACKUP_KEY, System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                logger.error(e) { "scheduleAutomatedBackupTask: failed due to" }
            }
        }

        val (backupHour, backupMinute) =
            serverConfig.backupTime.value
                .split(":")
                .map { it.toInt() }
        val backupInterval = serverConfig.backupInterval.value.days

        // trigger last backup in case the server wasn't running on the scheduled time
        val lastAutomatedBackup = preferences.getLong(LAST_AUTOMATED_BACKUP_KEY, 0)
        val wasPreviousBackupTriggered =
            (System.currentTimeMillis() - lastAutomatedBackup) < backupInterval.inWholeMilliseconds
        if (!wasPreviousBackupTriggered) {
            GlobalScope.launch(Dispatchers.IO) {
                task()
            }
        }

        backupSchedulerJobId = HAScheduler.scheduleCron(task, "$backupMinute $backupHour */${backupInterval.inWholeDays} * *", "backup")
    }

    /**
     * Writes the archive under a temporary name, syncs it, renames it into
     * place and syncs the directory (Komu, [publishArchiveDurably]), so the
     * name of an automated backup always denotes a complete, durable
     * archive. Returns the published file.
     */
    private fun createAutomatedBackup(): File {
        logger.info { "Creating automated backup..." }

        createBackup(BackupFlags.fromServerConfig()).use { input ->
            val automatedBackupDir = File(applicationDirs.automatedBackupRoot)
            automatedBackupDir.mkdirs()

            val backupFile = File(applicationDirs.automatedBackupRoot, Backup.getFilename(AUTO_BACKUP_FILENAME))
            return publishArchiveDurably(backupFile, write = { output -> input.copyTo(output) })
        }
    }

    private fun cleanupAutomatedBackups(keep: File) {
        logger.debug { "Cleanup automated backups (ttl= ${serverConfig.backupTTL.value})" }

        val isCleanupDisabled = serverConfig.backupTTL.value == 0
        if (isCleanupDisabled) {
            return
        }

        val automatedBackupDir = File(applicationDirs.automatedBackupRoot)
        if (!automatedBackupDir.isDirectory) {
            return
        }

        automatedBackupDir
            .listFiles { file ->
                file.name.startsWith(Backup.getBasename(AUTO_BACKUP_FILENAME)) && file != keep
            }?.forEach { file ->
            try {
                cleanupAutomatedBackupFile(file)
            } catch (_: Exception) {
                // ignore, will be retried on next cleanup
            }
        }
    }

    private fun cleanupAutomatedBackupFile(file: File) {
        if (!file.isFile) {
            return
        }

        val lastAccessTime = file.lastModified()
        val isTTLReached =
            System.currentTimeMillis() - lastAccessTime >=
                serverConfig.backupTTL.value.days
                    .coerceAtLeast(1.days)
                    .inWholeMilliseconds
        if (isTTLReached) {
            file.delete()
        }
    }

    fun createBackup(flags: BackupFlags): InputStream {
        // Create root object
        val backup: Backup =
            transaction {
                val backupMangas = BackupMangaHandler.backup(flags)
                Backup(
                    backupMangas,
                    BackupCategoryHandler.backup(flags),
                    BackupSourceHandler.backup(backupMangas, flags),
                    BackupGlobalMetaHandler.backup(flags),
                    BackupSettingsHandler.backup(flags),
                )
            }

        val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)

        val byteStream = Buffer()
        (byteStream as Sink)
            .gzip()
            .buffer()
            .use { it.write(byteArray) }

        return byteStream.inputStream()
    }
}
