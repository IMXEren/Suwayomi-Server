package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.buffer
import okio.gzip
import okio.source
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.graphql.types.toStatus
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupValidator.ValidationResult
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupValidator.validate
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupCategoryHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupGlobalMetaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupMangaHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupSettingsHandler
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupSourceHandler
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobDataClass
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestorePhase
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap

object ProtoBackupImport : ProtoBackupBase() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val applicationDirs: ApplicationDirs by injectLazy()

    private val logger = KotlinLogging.logger {}

    private val backupMutex = Mutex()

    sealed class BackupRestoreState {
        data object Idle : BackupRestoreState()

        data object Success : BackupRestoreState()

        data object Failure : BackupRestoreState()

        data class RestoringCategories(
            val current: Int,
            val totalManga: Int,
        ) : BackupRestoreState()

        data class RestoringMeta(
            val current: Int,
            val totalManga: Int,
        ) : BackupRestoreState()

        data class RestoringSettings(
            val current: Int,
            val totalManga: Int,
        ) : BackupRestoreState()

        data class RestoringManga(
            val current: Int,
            val totalManga: Int,
            val title: String,
        ) : BackupRestoreState()
    }

    private val backupRestoreIdToState = ConcurrentHashMap<String, BackupRestoreState>()

    val notifyFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST)

    /**
     * The restore status a client polls.
     *
     * The in-memory map is only a fast path for a sync restore, which never has a job row. A durable
     * restore is read back from the database instead, so its status survives a restart and is not lost
     * when the in-memory cache is cleaned up.
     */
    fun getRestoreState(id: String): BackupRestoreState? = backupRestoreIdToState[id] ?: BackupRestoreJob.get(id)?.toLegacyRestoreState()

    /** Records that a durable restore moved, so a client that polls the flow sees the progress. */
    internal fun notifyRestoreStateChanged() {
        scope.launch {
            notifyFlow.emit(Unit)
        }
    }

    private fun updateRestoreState(
        id: String,
        state: BackupRestoreState,
    ) {
        backupRestoreIdToState[id] = state

        scope.launch {
            notifyFlow.emit(Unit)
        }
    }

    private fun cleanupRestoreState(id: String) {
        val timer = Timer()
        val delay = 1000L * 60 // 60 seconds

        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    logger.debug { "cleanupRestoreState: $id (${getRestoreState(id)?.toStatus()?.state})" }
                    backupRestoreIdToState.remove(id)
                }
            },
            delay,
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun restore(
        sourceStream: InputStream,
        flags: BackupFlags,
        syncMode: SyncRestoreMode = SyncRestoreMode.NONE,
        options: BackupRestoreJob.Request? = null,
    ): String {
        // A plain restore is durable: the payload is staged once and a job row owns the progress, so a
        // restart resumes a long import instead of losing it. A sync restore keeps the in-memory path,
        // because the sync protocol needs the validation result in the same call and its payload is
        // derived from the server, not uploaded.
        if (syncMode == SyncRestoreMode.NONE) {
            return restoreDurably(sourceStream, options?.copy(flags = flags) ?: BackupRestoreJob.Request(flags))
        }

        val restoreId = System.currentTimeMillis().toString()

        logger.info { "restore($restoreId): queued" }

        updateRestoreState(restoreId, BackupRestoreState.Idle)

        GlobalScope.launch {
            restoreLegacy(sourceStream, restoreId, flags, syncMode)
        }

        return restoreId
    }

    /** Stages the uploaded payload and hands the restore to the durable worker. */
    private fun restoreDurably(
        sourceStream: InputStream,
        request: BackupRestoreJob.Request,
    ): String {
        val job = BackupRestoreJob.create(sourceStream, request, File(applicationDirs.archiveStagingRoot))

        logger.info { "restore(${job.restoreId}): staged ${job.stagedSize} bytes and queued" }

        BackupRestoreExecutor.notifyWorkAvailable()
        notifyRestoreStateChanged()

        return job.restoreId
    }

    suspend fun restoreLegacy(
        sourceStream: InputStream,
        restoreId: String = "legacy",
        flags: BackupFlags = BackupFlags.DEFAULT,
        syncMode: SyncRestoreMode = SyncRestoreMode.NONE,
    ): ValidationResult =
        backupMutex.withLock {
            try {
                logger.info { "restore($restoreId): restoring..." }
                performRestore(restoreId, sourceStream, flags, syncMode)
            } catch (e: Exception) {
                logger.error(e) { "restore($restoreId): failed due to" }

                updateRestoreState(restoreId, BackupRestoreState.Failure)
                ValidationResult(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                )
            } finally {
                if (syncMode.isSync) {
                    clearSyncingFlags()
                }
                logger.info { "restore($restoreId): finished with state ${getRestoreState(restoreId)?.toStatus()?.state}" }
                cleanupRestoreState(restoreId)
            }
        }

    private fun clearSyncingFlags() {
        transaction {
            MangaTable.update({ MangaTable.isSyncing eq true }) {
                it[isSyncing] = false
            }
            ChapterTable.update({ ChapterTable.isSyncing eq true }) {
                it[isSyncing] = false
            }
            CategoryTable.update({ CategoryTable.isSyncing eq true }) {
                it[isSyncing] = false
            }
        }
    }

    private fun performRestore(
        id: String,
        sourceStream: InputStream,
        flags: BackupFlags,
        syncMode: SyncRestoreMode,
    ): ValidationResult {
        val backupString =
            sourceStream
                .source()
                .run {
                    if (!syncMode.isSync) gzip() else this
                }.buffer()
                .use { it.readByteArray() }
        val backup = parser.decodeFromByteArray(Backup.serializer(), backupString)

        val validationResult = validate(backup)

        val restoreCategories = if (flags.includeCategories) 1 else 0
        val restoreMeta = if (flags.includeClientData) 1 else 0
        val restoreSettings = if (flags.includeServerSettings) 1 else 0
        val getRestoreAmount = { size: Int -> size + restoreCategories + restoreMeta + restoreSettings }
        val restoreAmount = getRestoreAmount(if (flags.includeManga) backup.backupManga.size else 0)

        if (flags.includeServerSettings) {
            updateRestoreState(
                id,
                BackupRestoreState.RestoringSettings(restoreSettings, restoreAmount),
            )

            BackupSettingsHandler.restore(backup.serverSettings)
        }

        val categoryMapping =
            if (flags.includeCategories) {
                updateRestoreState(id, BackupRestoreState.RestoringCategories(restoreSettings + restoreCategories, restoreAmount))
                BackupCategoryHandler.restore(backup.backupCategories, syncMode)
            } else {
                emptyMap()
            }

        if (flags.includeClientData) {
            updateRestoreState(id, BackupRestoreState.RestoringMeta(restoreSettings + restoreCategories + restoreMeta, restoreAmount))

            BackupGlobalMetaHandler.restore(backup.meta)

            BackupSourceHandler.restore(backup.backupSources)
        }

        // Store source mapping for error messages
        val sourceMapping = backup.getSourceMap()

        val errors = mutableListOf<Pair<Date, String>>()

        // Restore individual manga
        if (flags.includeManga) {
            backup.backupManga.forEachIndexed { index, manga ->
                updateRestoreState(
                    id,
                    BackupRestoreState.RestoringManga(
                        current = getRestoreAmount(index + 1),
                        totalManga = restoreAmount,
                        title = manga.title,
                    ),
                )

                BackupMangaHandler.restore(
                    backupManga = manga,
                    categoryMapping = categoryMapping,
                    sourceMapping = sourceMapping,
                    errors = errors,
                    flags = flags,
                    syncMode = syncMode,
                )
            }
        }

        logger.info {
            """
            Restore Errors:
            ${errors.joinToString("\n") { "${it.first} - ${it.second}" }}
            Restore Summary:
            - Missing Sources:
                ${validationResult.missingSources.joinToString("\n                    ")}
            - Titles missing Sources:
                ${validationResult.mangasMissingSources.joinToString("\n                    ")}
            - Missing Trackers:
                ${validationResult.missingTrackers.joinToString("\n                    ")}
            """.trimIndent()
        }

        updateRestoreState(id, BackupRestoreState.Success)

        return validationResult
    }
}

/**
 * Projects a durable job onto the restore status the existing clients already speak.
 *
 * The legacy status distinguishes "queued" from "working on this phase"; a durable job distinguishes
 * the same things through its state and phase, so the projection is total and no client has to learn
 * a second status vocabulary. The series counter of the series phase is the count the job has actually
 * applied, which is what a resume continues from.
 */
private fun BackupRestoreJobDataClass.toLegacyRestoreState(): ProtoBackupImport.BackupRestoreState {
    val total = mangaCount

    return when (state) {
        BackupRestoreJobState.QUEUED -> {
            ProtoBackupImport.BackupRestoreState.Idle
        }

        BackupRestoreJobState.SUCCESS -> {
            ProtoBackupImport.BackupRestoreState.Success
        }

        BackupRestoreJobState.FAILURE -> {
            ProtoBackupImport.BackupRestoreState.Failure
        }

        // a cancelled restore is reported as a failure: it did not reach the library it was applying
        BackupRestoreJobState.CANCELLED -> {
            ProtoBackupImport.BackupRestoreState.Failure
        }

        BackupRestoreJobState.RUNNING -> {
            when (phase) {
                BackupRestorePhase.PENDING -> {
                    ProtoBackupImport.BackupRestoreState.RestoringSettings(0, total)
                }

                BackupRestorePhase.SETTINGS -> {
                    ProtoBackupImport.BackupRestoreState.RestoringSettings(mangaIndex, total)
                }

                BackupRestorePhase.CATEGORIES -> {
                    ProtoBackupImport.BackupRestoreState.RestoringCategories(mangaIndex, total)
                }

                BackupRestorePhase.META -> {
                    ProtoBackupImport.BackupRestoreState.RestoringMeta(mangaIndex, total)
                }

                BackupRestorePhase.MANGA,
                BackupRestorePhase.COMPLETED,
                -> {
                    ProtoBackupImport.BackupRestoreState.RestoringManga(
                        current = mangaIndex,
                        totalManga = total,
                        // the legacy status carries the series title for a progress label; the durable
                        // job deliberately does not persist one, so the label falls back to the counter
                        title = "",
                    )
                }
            }
        }
    }
}
