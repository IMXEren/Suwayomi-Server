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
import org.jetbrains.exposed.v1.core.and
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
import suwayomi.tachidesk.manga.impl.backup.proto.handlers.BackupUserSettingsHandler
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobDataClass
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreJobState
import suwayomi.tachidesk.manga.model.dataclass.BackupRestorePhase
import suwayomi.tachidesk.manga.model.table.CategoryTable
import suwayomi.tachidesk.manga.model.table.ChapterUserTable
import suwayomi.tachidesk.manga.model.table.MangaUserTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.user.ARCHIVE_OWNER_ID
import suwayomi.tachidesk.server.user.UserPermission
import suwayomi.tachidesk.server.user.hasPermission
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream
import java.util.Date
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
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

        data class RestoringUserSettings(
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
    private val restoreOwner = ConcurrentHashMap<String, Int>()

    val notifyFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST)

    /**
     * The restore status a client polls.
     *
     * The in-memory map is only a fast path for a sync restore, which never has a job row. A durable
     * restore is read back from the database instead, so its status survives a restart and is not lost
     * when the in-memory cache is cleaned up.
     */
    fun getRestoreState(
        id: String,
        userId: Int,
    ): BackupRestoreState? =
        if (restoreOwner[id] == userId) {
            backupRestoreIdToState[id]
        } else if (userId == ARCHIVE_OWNER_ID && restoreOwner[id] == null) {
            BackupRestoreJob.get(id)?.toLegacyRestoreState()
        } else {
            null
        }

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
                    backupRestoreIdToState.remove(id)
                    restoreOwner.remove(id)
                }
            },
            delay,
        )
    }

    fun restore(
        userId: Int,
        sourceStream: InputStream,
        flags: BackupFlags,
        syncMode: SyncRestoreMode = SyncRestoreMode.NONE,
        options: BackupRestoreJob.Request? = null,
    ): String {
        require(userId == ARCHIVE_OWNER_ID || options?.handoffRequested != true) {
            "Only the built-in admin can start an archive bootstrap"
        }
        // Existing admin imports remain durable; other users retain upstream's per-user restore.
        if (userId == ARCHIVE_OWNER_ID && syncMode == SyncRestoreMode.NONE) {
            return restoreDurably(sourceStream, options?.copy(flags = flags) ?: BackupRestoreJob.Request(flags))
        }

        // The upload stream closes with the HTTP request, before an asynchronous restore completes.
        val uploaded = if (syncMode == SyncRestoreMode.NONE) decode(sourceStream, syncMode) else null
        return queueRestore(userId, flags, syncMode) { uploaded ?: decode(sourceStream, syncMode) }
    }

    fun restore(
        userId: Int,
        backup: Backup,
        flags: BackupFlags,
        syncMode: SyncRestoreMode = SyncRestoreMode.NONE,
    ): String = queueRestore(userId, flags, syncMode) { backup }

    @OptIn(DelicateCoroutinesApi::class)
    private fun queueRestore(
        userId: Int,
        flags: BackupFlags,
        syncMode: SyncRestoreMode,
        load: () -> Backup,
    ): String {
        val restoreId = UUID.randomUUID().toString()
        logger.info { "restore($restoreId): queued" }
        restoreOwner[restoreId] = userId
        updateRestoreState(restoreId, BackupRestoreState.Idle)
        GlobalScope.launch {
            runRestore(userId, restoreId, flags, syncMode, load)
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
        userId: Int,
        sourceStream: InputStream,
        restoreId: String = "legacy",
        flags: BackupFlags = BackupFlags.DEFAULT,
        syncMode: SyncRestoreMode = SyncRestoreMode.NONE,
    ): ValidationResult {
        restoreOwner[restoreId] = userId
        return runRestore(userId, restoreId, flags, syncMode) { decode(sourceStream, syncMode) }
    }

    private suspend fun runRestore(
        userId: Int,
        restoreId: String,
        flags: BackupFlags,
        syncMode: SyncRestoreMode,
        load: () -> Backup,
    ): ValidationResult =
        backupMutex.withLock {
            try {
                logger.info { "restore($restoreId): restoring..." }
                performRestore(userId, restoreId, load(), flags, syncMode)
            } catch (e: Exception) {
                logger.error(e) { "restore($restoreId): failed due to" }

                updateRestoreState(restoreId, BackupRestoreState.Failure)
                ValidationResult(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                )
            } catch (e: OutOfMemoryError) {
                logger.error { "restore($restoreId): out of memory" }

                updateRestoreState(restoreId, BackupRestoreState.Failure)
                ValidationResult(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                )
            } finally {
                if (syncMode.isSync) {
                    clearSyncingFlags(userId)
                }
                logger.info { "restore($restoreId): finished with state ${getRestoreState(restoreId, userId)?.toStatus()?.state}" }
                cleanupRestoreState(restoreId)
            }
        }

    private fun clearSyncingFlags(userId: Int) {
        transaction {
            MangaUserTable.update(
                {
                    MangaUserTable.isSyncing eq true and (MangaUserTable.user eq userId)
                },
            ) {
                it[MangaUserTable.isSyncing] = false
            }
            ChapterUserTable.update(
                {
                    ChapterUserTable.isSyncing eq true and (ChapterUserTable.user eq userId)
                },
            ) {
                it[ChapterUserTable.isSyncing] = false
            }
            CategoryTable.update(
                {
                    CategoryTable.isSyncing eq true and (CategoryTable.user eq userId)
                },
            ) {
                it[CategoryTable.isSyncing] = false
            }
        }
    }

    private fun decode(
        sourceStream: InputStream,
        syncMode: SyncRestoreMode,
    ): Backup {
        val bytes =
            sourceStream
                .source()
                .run {
                    if (!syncMode.isSync) gzip() else this
                }.buffer()
                .use { it.readByteArray() }
        return parser.decodeFromByteArray(Backup.serializer(), bytes)
    }

    private fun performRestore(
        userId: Int,
        id: String,
        backup: Backup,
        flags: BackupFlags,
        syncMode: SyncRestoreMode,
    ): ValidationResult {
        val validationResult = validate(userId, backup)

        // only users with the MANAGE_SETTINGS permission can change the global server settings
        val canManageSettings = hasPermission(userId, UserPermission.MANAGE_SETTINGS)

        val restoreCategories = if (flags.includeCategories) 1 else 0
        val restoreMeta = if (flags.includeClientData) 1 else 0
        val restoreSettings = if (flags.includeServerSettings && canManageSettings) 1 else 0
        val restoreUserSettings = if (flags.includeUserSettings) 1 else 0
        val getRestoreAmount = { size: Int -> size + restoreCategories + restoreMeta + restoreSettings + restoreUserSettings }
        val restoreAmount = getRestoreAmount(if (flags.includeManga) backup.backupManga.size else 0)

        if (flags.includeServerSettings && canManageSettings) {
            updateRestoreState(
                id,
                BackupRestoreState.RestoringSettings(restoreSettings, restoreAmount),
            )

            BackupSettingsHandler.restore(backup.serverSettings, userId)
        }

        if (flags.includeUserSettings) {
            updateRestoreState(
                id,
                BackupRestoreState.RestoringUserSettings(restoreSettings, restoreAmount),
            )

            BackupUserSettingsHandler.restore(
                userId,
                backup.userSettings,
                backup.serverSettings,
            )
        }

        val categoryMapping =
            if (flags.includeCategories) {
                updateRestoreState(id, BackupRestoreState.RestoringCategories(restoreSettings + restoreCategories, restoreAmount))
                BackupCategoryHandler.restore(userId, backup.backupCategories, syncMode)
            } else {
                emptyMap()
            }

        if (flags.includeClientData) {
            updateRestoreState(id, BackupRestoreState.RestoringMeta(restoreSettings + restoreCategories + restoreMeta, restoreAmount))

            BackupGlobalMetaHandler.restore(userId, backup.meta)

            BackupSourceHandler.restore(userId, backup.backupSources)
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
                    userId,
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
