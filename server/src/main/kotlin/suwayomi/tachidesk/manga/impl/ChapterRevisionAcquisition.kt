package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.server.serverConfig
import uy.kohesive.injekt.injectLazy
import java.io.File

/** Raised when the downloaded pages are not a usable chapter. */
class ChapterRevisionIntegrityException(
    message: String,
) : Exception(message)

/**
 * Acquires one candidate revision at a time: download to local staging, then validate.
 *
 * Acquisition never marks a chapter as downloaded and never writes into the archive; it only
 * produces a validated local copy that a later archival phase can commit.
 */
class ChapterRevisionAcquisitionProcessor(
    private val sourceAccess: ChapterRevisionSourceAccess,
    private val stagingRoot: () -> File,
    private val onAcquired: (ChapterRevisionDataClass) -> Unit = {},
    /**
     * Records that the completed candidate owes a page comparison.
     *
     * The archive is deliberately not woken in that case: the new revision's whole-chapter digest
     * proved it differs from what is archived, and the archive gate keeps it out of the commit queue
     * until the page comparison that explains the difference has run.
     */
    private val onVisualAnalysisDue: () -> Unit = {},
) {
    private val logger = KotlinLogging.logger {}

    suspend fun process(claimed: ChapterRevisionDataClass) {
        val root = stagingRoot()
        val directory = ChapterRevisionStaging.directory(root, claimed.candidateKey)
        val relativeDirectory = ChapterRevisionStaging.relativeDirectory(claimed.candidateKey)

        try {
            // a finalized directory survived a crash, so it is re-validated without contacting the
            // source again instead of downloading the pages a second time
            val expectedPageCount =
                if (directory.isDirectory) {
                    null
                } else {
                    downloadPages(claimed, root)
                }

            ChapterRevision.markDownloadedLocal(claimed.id, relativeDirectory)
            ChapterRevision.markValidating(claimed.id)

            when (val validation = ChapterRevisionStaging.validate(directory, expectedPageCount)) {
                is ChapterRevisionValidation.Invalid -> {
                    ChapterRevision.markValidationFailed(claimed.id, validation.reason)
                }

                is ChapterRevisionValidation.Valid -> {
                    // Completion and classification are one fenced transaction, so a completed revision
                    // can never be missing its comparison and the archive worker can never claim a
                    // revision whose duplication check has not run yet.
                    val completion =
                        ChapterRevision.markCompleteAndClassify(
                            claimed.id,
                            validation.pageCount,
                            validation.contentHash,
                            relativeDirectory,
                        )
                    if (completion != null) {
                        when (completion.outcome) {
                            ChapterRevision.ChapterRevisionClassificationOutcome.CONTINUE -> {
                                // the content is new, or there was nothing to compare against. When the
                                // whole-chapter digest already proved it differs, the page comparison is
                                // owed first and only that worker is woken.
                                if (completion.revision.visualAnalysisState == ChapterVisualAnalysisState.QUEUED) {
                                    onVisualAnalysisDue()
                                } else {
                                    onAcquired(claimed)
                                }
                            }

                            ChapterRevision.ChapterRevisionClassificationOutcome.UNCHANGED -> {
                                // byte-identical to what is already the active revision: it is terminal
                                // and must never be archived, so only its local copy is removed
                                discardUnchangedCandidate(completion.revision)
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChapterRevisionSourceException) {
            logger.debug(e) { "Chapter revision ${claimed.id} could not be retrieved" }
            ChapterRevision.markDownloadFailed(claimed.id, e.message ?: "source failure")
        } catch (e: ChapterRevisionIntegrityException) {
            logger.debug(e) { "Chapter revision ${claimed.id} is not a usable chapter" }
            ChapterRevision.markValidationFailed(claimed.id, e.message ?: "integrity failure")
        } catch (e: Exception) {
            logger.warn(e) { "Chapter revision ${claimed.id} failed to download" }
            ChapterRevision.markDownloadFailed(claimed.id, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Downloads every page into the partial directory and publishes it with one atomic rename.
     * Pages that were already downloaded are kept, so an interrupted attempt resumes instead of
     * restarting, and validation always sees a directory that is complete or absent.
     */
    private suspend fun downloadPages(
        revision: ChapterRevisionDataClass,
        stagingRoot: File,
    ): Int {
        val pages = sourceAccess.getPageList(revision)
        if (pages.isEmpty()) {
            throw ChapterRevisionIntegrityException("chapter does not have any pages")
        }

        val partialDirectory = ChapterRevisionStaging.partialDirectory(stagingRoot, revision.candidateKey)
        partialDirectory.mkdirs()
        ChapterRevisionStaging.clearTemporaryPages(partialDirectory)

        pages.forEachIndexed { index, page ->
            if (ChapterRevisionStaging.findPageFile(partialDirectory, index) != null) {
                return@forEachIndexed
            }
            sourceAccess.openPage(revision, page).use { content ->
                ChapterRevisionStaging.writePage(partialDirectory, index, content)
            }
        }

        ChapterRevisionStaging.publishCandidate(stagingRoot, revision.candidateKey)

        return pages.size
    }

    /**
     * Removes the staged pages of a revision whose content is already the active revision's.
     *
     * The classification has already committed by the time this runs, so the files are provably dead
     * weight. The revision keeps the staged path as its pending-cleanup marker until the directory is
     * really gone, which is what makes the removal resumable after a crash instead of leaking it; a
     * removal that fails right now is deferred to a persisted retry instant so it cannot block the
     * revisions behind it.
     */
    private fun discardUnchangedCandidate(revision: ChapterRevisionDataClass) {
        val root = stagingRoot()
        val discarded =
            try {
                ChapterRevisionStaging.deleteCandidate(root, revision.candidateKey)
                ChapterRevision.markUnchangedStagingCleaned(revision.id)
            } catch (e: CancellationException) {
                // a shutdown is not a failed removal, so it must not be recorded as one
                throw e
            } catch (e: Exception) {
                // the staging path and the filesystem message are dropped: this root is where the
                // operator's own archive lives, so neither belongs in a shared log line
                logger.debug { "staged pages of unchanged revision ${revision.id} could not be removed" }
                false
            }

        if (!discarded) {
            ChapterRevision.deferUnchangedStagingCleanup(
                revision.id,
                serverConfig.chapterRevisionSweepRetrySeconds.value.toLong(),
            )
            // The sweep worker owns the removal backlog, and it may be sleeping until the next monthly
            // run. The retry is persisted, so waking it now is what makes the retry observable instead
            // of waiting for a wake that a long idle period would postpone indefinitely.
            ChapterRevisionSweepExecutor.notifyWorkAvailable()
        }
    }
}

/**
 * Sequential acquisition loop over the queued revision backlog.
 *
 * Concurrency is deliberately one, which is the global candidate download rate limit the archival
 * design relies on. Kept as a class so tests can drive it with a fake source.
 */
class ChapterRevisionAcquisitionLoop(
    private val processor: ChapterRevisionAcquisitionProcessor,
    /**
     * Removes the staged pages of revisions whose classification proved them to be unchanged.
     *
     * It runs when the queue is empty, so it can never delay an acquisition, and it is the
     * restart-safe half of that cleanup: a revision is only unmarked once its files are really gone.
     */
    private val cleanupStaging: () -> Unit = { sweepCleanupUnchangedStaging() },
) {
    private val logger = KotlinLogging.logger {}

    private val worker =
        ChapterRevisionWorkerLoop(
            workerName = "chapter revision acquisition",
            beforeFirstDrain = {
                ChapterRevision.recoverInterrupted()
                // a shutdown can leave an unchanged revision whose staged pages were never removed
                cleanupStaging()
            },
            drainOnce = { drainOnce() },
        )

    /** Starts the loop. Repeated calls are ignored while it is already running. */
    fun start() = worker.start()

    fun stop() = worker.stop()

    /**
     * Records that queued work may exist. Safe to call from any thread and before [start]; the
     * signal is conflated so a wake up can never be lost.
     */
    fun notifyWorkAvailable() = worker.notifyWorkAvailable()

    /** Claims and processes one queued revision; returns false when the queue is empty. */
    internal suspend fun drainOnce(): Boolean {
        val claimed = ChapterRevision.claimNextQueued()
        if (claimed == null) {
            // the queue is empty, which is the only moment where discarding staged pages cannot delay
            // an acquisition
            cleanupStaging()
            return false
        }

        try {
            processor.process(claimed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // one broken candidate must never end the loop; its state is left mid flight so the
            // startup recovery picks it up again
            logger.error(e) { "Failed to acquire chapter revision ${claimed.id}" }
        }
        return true
    }
}

/**
 * Single, global chapter revision acquisition worker.
 *
 * The queue is independent of the legacy download manager and of `autoDownloadNewChapters`.
 */
object ChapterRevisionAcquisitionExecutor {
    private val applicationDirs: ApplicationDirs by injectLazy()

    private val loop by lazy {
        ChapterRevisionAcquisitionLoop(
            ChapterRevisionAcquisitionProcessor(
                sourceAccess = DefaultChapterRevisionSourceAccess,
                stagingRoot = { File(applicationDirs.archiveStagingRoot) },
                onAcquired = { ChapterRevisionArchiveExecutor.notifyWorkAvailable() },
                onVisualAnalysisDue = { ChapterRevisionVisualAnalysisExecutor.notifyWorkAvailable() },
            ),
            cleanupStaging = { sweepCleanupUnchangedStaging(stagingRoot = File(applicationDirs.archiveStagingRoot)) },
        )
    }

    fun start() = loop.start()

    fun stop() = loop.stop()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    /** Approves candidates, then wakes the worker only once the transition has been committed. */
    fun approve(ids: List<Int>): List<ChapterRevisionDataClass> {
        val approved = ChapterRevision.approve(ids)
        if (approved.isNotEmpty()) {
            notifyWorkAvailable()
        }
        return approved
    }

    /**
     * Re-queues failed candidates, preserving their attempt count.
     *
     * The staged content of the failed attempt is discarded so the next attempt downloads the pages
     * again instead of re-validating content that already failed.
     */
    fun retry(ids: List<Int>): List<ChapterRevisionDataClass> {
        val stagingRoot = File(applicationDirs.archiveStagingRoot)
        val retried =
            ChapterRevision.retry(ids) { revisions ->
                // Delete while the failed rows are locked and before QUEUED becomes visible. Otherwise
                // the worker could claim a retried row while its staging directory is being removed.
                revisions.forEach { ChapterRevisionStaging.deleteCandidate(stagingRoot, it.candidateKey) }
            }
        if (retried.isEmpty()) {
            return emptyList()
        }

        notifyWorkAvailable()
        return retried
    }
}
