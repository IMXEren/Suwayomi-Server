package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.impl.util.source.GetSource
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapItemDataClass
import suwayomi.tachidesk.manga.model.dataclass.ArchiveBootstrapItemState
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ArchiveBootstrapItemTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import java.time.Instant

/** What processing one series of a bootstrap produced. */
sealed interface ArchiveBootstrapProcessOutcome {
    /** the series was refreshed and its initial candidates were recorded */
    data class Completed(
        val candidateCount: Int,
    ) : ArchiveBootstrapProcessOutcome

    /** the source of the series is not installed right now */
    data object UnresolvedSource : ArchiveBootstrapProcessOutcome

    /** the series no longer qualifies for bootstrapping, for a durable reason */
    data class Skipped(
        val reason: String,
    ) : ArchiveBootstrapProcessOutcome
}

/**
 * Processes one series of a bootstrap session.
 *
 * The seams are injectable so the orchestration can be tested without a live source, while the
 * production defaults use the real source registry, the real acquisition policy column and the real
 * [Manga.updateMangaAndChapters] refresh path. Nothing here downloads a chapter: it only makes the
 * series' chapters discoverable to the revision queue.
 */
class ArchiveBootstrapProcessor(
    private val loadManga: (Int) -> ResultRow? = { mangaId ->
        transaction { MangaTable.selectAll().where { MangaTable.id eq mangaId }.firstOrNull() }
    },
    private val resolveSource: suspend (Long) -> Source? = { sourceId -> GetSource.getSourceOrNull(sourceId) },
    private val loadItemState: (Int) -> ArchiveBootstrapItemState? = { itemId ->
        transaction {
            ArchiveBootstrapItemTable
                .selectAll()
                .where { ArchiveBootstrapItemTable.id eq itemId }
                .firstOrNull()
                ?.let { ArchiveBootstrapItemState.valueOf(it[ArchiveBootstrapItemTable.state]) }
        }
    },
    private val applyAcquisitionPolicy: (Int, MangaAcquisitionPolicy, Long) -> Unit = { mangaId, policy, now ->
        transaction {
            MangaTable.update({ MangaTable.id eq mangaId }) {
                it[acquisitionPolicy] = policy.name
                it[lastModifiedAt] = now
            }
        }
    },
    private val refreshManga: suspend (Int) -> Unit = { mangaId ->
        // the explicit one-time refresh a bootstrap owes every series; it is independent of the
        // scheduler, so an ONLY_FETCH_ONCE series still gets exactly this refresh and nothing more
        Manga.updateMangaAndChapters(mangaId, updateManga = true)
    },
    private val createInitialCandidates: (Int, Long) -> Int = { mangaId, now ->
        transaction {
            val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.firstOrNull()
            if (mangaEntry == null) {
                0
            } else {
                // one query for the chapters of this single series; the whole library is never loaded
                val chapters =
                    ChapterTable
                        .selectAll()
                        .where { ChapterTable.manga eq mangaId }
                        .map { ChapterTable.toDataClass(it) }

                ChapterRevision.createCandidatesForBootstrap(mangaEntry, chapters, now).size
            }
        }
    },
) {
    suspend fun process(
        item: ArchiveBootstrapItemDataClass,
        now: Long = Instant.now().epochSecond,
    ): ArchiveBootstrapProcessOutcome {
        val mangaId = item.mangaId ?: return ArchiveBootstrapProcessOutcome.Skipped("no series snapshot")

        // re-read instead of trusting the snapshot: the series may have left the library, and only
        // library series are archived
        val mangaEntry = loadManga(mangaId)
        if (mangaEntry == null || !mangaEntry[MangaTable.inLibrary]) {
            return ArchiveBootstrapProcessOutcome.Skipped("series $mangaId is no longer in the library")
        }

        // a cancel can land between the claim and this point, so the claim is only honoured while the
        // series is still PROCESSING; a cancelled run must neither refresh the source nor record
        // candidates for a series it no longer owns
        if (loadItemState(item.id) != ArchiveBootstrapItemState.PROCESSING) {
            return ArchiveBootstrapProcessOutcome.Skipped("series $mangaId was cancelled before it was refreshed")
        }

        applyAcquisitionPolicy(mangaId, item.policy, now)

        val sourceId = mangaEntry[MangaTable.sourceReference]
        if (resolveSource(sourceId) == null) {
            return ArchiveBootstrapProcessOutcome.UnresolvedSource
        }

        refreshManga(mangaId)

        return ArchiveBootstrapProcessOutcome.Completed(createInitialCandidates(mangaId, now))
    }
}

/**
 * Single, global archive bootstrap worker.
 *
 * Concurrency is one on purpose: the whole point of a bootstrap is to stagger thousands of source
 * refreshes, and the persisted session delay - not this process - is what paces them. The loop itself
 * is the shared one, so wake ups cannot be lost and a failed series cannot end the run.
 */
class ArchiveBootstrapLoop(
    processor: ArchiveBootstrapProcessor = ArchiveBootstrapProcessor(),
    claim: (Long) -> ArchiveBootstrap.Claim? = { now -> ArchiveBootstrap.claimNextDueItem(now) },
    complete: (ArchiveBootstrap.Claim, Int, Long) -> Unit = { claim, count, now ->
        ArchiveBootstrap.completeItem(claim, count, now)
    },
    fail: (ArchiveBootstrap.Claim, Throwable, Long) -> Unit = { claim, error, now ->
        ArchiveBootstrap.failItem(claim, error, now)
    },
    resolveSource: (ArchiveBootstrap.Claim, Long) -> Unit = { claim, now ->
        ArchiveBootstrap.markItemUnresolvedSource(claim, now)
    },
    skip: (ArchiveBootstrap.Claim, String, Long) -> Unit = { claim, reason, now ->
        ArchiveBootstrap.markItemSkipped(claim, reason, now)
    },
    recoverInterrupted: (Long) -> Unit = { now -> ArchiveBootstrap.recoverInterruptedItems(now) },
    nextDueAt: (Long) -> Long? = { now -> ArchiveBootstrap.nextDueAt(now) },
    now: () -> Long = { Instant.now().epochSecond },
) {
    private val loop =
        ChapterRevisionWorkerLoop(
            workerName = "ArchiveBootstrapWorker",
            // a shutdown can leave a series claimed but unprocessed; the run resumes where it stopped
            beforeFirstDrain = { recoverInterrupted(now()) },
            // a running session that is only waiting out its own delay still has a due time, so the
            // wait is bounded without polling; with no running session it sleeps until woken
            idleTimeoutMillis = {
                val current = now()
                nextDueAt(current)?.let { dueAt -> waitMillisUntil(dueAt, current, MINIMUM_IDLE_MILLIS) }
            },
            drainOnce = {
                val current = now()
                val claimed = claim(current)
                if (claimed == null) {
                    false
                } else {
                    processClaimed(processor, claimed, complete, fail, resolveSource, skip, now)
                    true
                }
            },
        )

    fun start() = loop.start()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    fun stop() = loop.stop()

    private suspend fun processClaimed(
        processor: ArchiveBootstrapProcessor,
        claimed: ArchiveBootstrap.Claim,
        complete: (ArchiveBootstrap.Claim, Int, Long) -> Unit,
        fail: (ArchiveBootstrap.Claim, Throwable, Long) -> Unit,
        resolveSource: (ArchiveBootstrap.Claim, Long) -> Unit,
        skip: (ArchiveBootstrap.Claim, String, Long) -> Unit,
        now: () -> Long,
    ) {
        try {
            when (val outcome = processor.process(claimed.item, now())) {
                is ArchiveBootstrapProcessOutcome.Completed -> complete(claimed, outcome.candidateCount, now())
                ArchiveBootstrapProcessOutcome.UnresolvedSource -> resolveSource(claimed, now())
                is ArchiveBootstrapProcessOutcome.Skipped -> skip(claimed, outcome.reason, now())
            }
        } catch (e: CancellationException) {
            // the claimed series stays PROCESSING and is returned to the queue on the next start,
            // which keeps its attempts intact instead of recording a shutdown as a failure
            throw e
        } catch (e: Throwable) {
            // one broken series must never stop the run
            fail(claimed, e, now())
        }
    }
}

/**
 * Floor for the idle wait.
 *
 * A persisted due time in the past must not turn an idle wait into a busy loop.
 */
private const val MINIMUM_IDLE_MILLIS = 1_000L

object ArchiveBootstrapExecutor {
    private val loop by lazy { ArchiveBootstrapLoop() }

    fun start() = loop.start()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()
}
