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
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.time.Instant

/**
 * Builds the immutable CBZ and its sidecar manifest for one acquired revision.
 *
 * Both artifacts are built completely on ordinary local staging and hashed there; only then are
 * they copied into the archive root, which may be an object-storage mount. The revision's staging
 * pages are never modified: this step only reads them, so a failed or unverified archive commit can
 * be retried without downloading the chapter again.
 *
 * Publication stops at [suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState.REMOTE_PENDING].
 * A successful write through an object-storage mount is not proof of remote durability, so
 * [REMOTE_CONFIRMED] is only reached by the separate, explicitly configured remote verifier: this
 * step never waits on an upload.
 */
class ChapterRevisionArchiveProcessor(
    private val stagingRoot: () -> File,
    private val archiveRoot: () -> File,
    private val onVerificationDue: () -> Unit = {},
) {
    private val logger = KotlinLogging.logger {}

    suspend fun process(claimed: ChapterRevisionDataClass) {
        try {
            val candidateKey = claimed.candidateKey
            val relativeCbzPath = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey)
            val relativeManifestPath = ChapterRevisionArchiveArtifacts.relativeManifestPath(candidateKey)

            val staged = ChapterRevisionStaging.directory(stagingRoot(), candidateKey)
            val stagedContentHash =
                when (val validation = ChapterRevisionStaging.validate(staged, claimed.pageCount)) {
                    is ChapterRevisionValidation.Invalid -> {
                        throw ChapterRevisionArchiveConflictException("staged chapter revision is not usable: ${validation.reason}")
                    }

                    is ChapterRevisionValidation.Valid -> {
                        validation.contentHash
                    }
                }

            if (claimed.contentHash != null && stagedContentHash != claimed.contentHash) {
                throw ChapterRevisionArchiveConflictException(
                    "staged pages no longer match the acquired content hash of revision ${claimed.id}",
                )
            }

            val pages = ChapterRevisionStaging.pageFiles(staged)
            val fingerprints = pages.map { ChapterRevisionCbz.fingerprintOf(it) }

            // 1. build the immutable artifacts completely on ordinary local storage and hash them, so
            //    an object-storage mount never sees a half-written temporary file
            val localDirectory = ChapterRevisionArchiveArtifacts.localDirectory(stagingRoot(), candidateKey)
            localDirectory.mkdirs()

            val localCbz = ChapterRevisionArchiveArtifacts.localCbzFile(stagingRoot(), candidateKey)
            val cbzDigest = publishLocalCbz(localCbz, pages)

            val localManifest = ChapterRevisionArchiveArtifacts.localManifestFile(stagingRoot(), candidateKey)
            val manifest = publishLocalManifest(localManifest, claimed, fingerprints, cbzDigest)

            // 2. copy the finished immutable objects into the archive root, verifying the bytes that
            //    actually landed there
            copyIntoArchive(
                source = localCbz,
                target = ChapterRevisionArchiveArtifacts.cbzFile(archiveRoot(), candidateKey),
                expected = cbzDigest,
                description = "CBZ",
            )
            copyIntoArchive(
                source = localManifest,
                target = ChapterRevisionArchiveArtifacts.manifestFile(archiveRoot(), candidateKey),
                expected = manifest.digest,
                description = "manifest",
            )

            val published =
                ChapterRevision.markRemotePending(
                    id = claimed.id,
                    cbzPath = relativeCbzPath,
                    manifestPath = relativeManifestPath,
                    cbzHash = cbzDigest.sha256,
                    cbzSize = cbzDigest.size,
                    manifestHash = manifest.digest.sha256,
                    manifestSize = manifest.digest.size,
                    archivedAt = manifest.value.archivedAt,
                )

            if (published) {
                // the transition has committed, so the verification worker may now safely pick it up
                onVerificationDue()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to archive chapter revision ${claimed.id}" }
            ChapterRevision.markArchiveCommitFailed(claimed.id, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Builds the deterministic CBZ into the local artifact directory and publishes it with one
     * atomic move.
     *
     * An artifact left behind by an interrupted attempt is reused only when its digest matches the
     * one just built from the staged pages; a mismatching artifact is immutable and is never
     * overwritten.
     */
    private fun publishLocalCbz(
        target: File,
        pages: List<File>,
    ): ChapterRevisionArtifactDigest {
        val temporary = temporarySiblingOf(target)
        temporary.delete()
        val built = ChapterRevisionCbz.write(pages, temporary)

        if (target.exists()) {
            val existing = ChapterRevisionArchiveArtifacts.digestOf(target)
            temporary.delete()
            if (existing != built) {
                throw ChapterRevisionArchiveConflictException(
                    "a locally staged CBZ already exists with different content: ${target.path}",
                )
            }
            return existing
        }

        ChapterRevisionStaging.moveAtomicallyInto(temporary, target)
        return built
    }

    /**
     * Builds the sidecar manifest locally and publishes it with one atomic move.
     *
     * A manifest left behind by an interrupted attempt contributes only its original `archivedAt`
     * audit timestamp; every other field still has to describe exactly this revision and these
     * ordered page fingerprints. The digest of the exact bytes on disk is returned so the archived
     * copy can be compared against it.
     */
    private fun publishLocalManifest(
        target: File,
        claimed: ChapterRevisionDataClass,
        pages: List<ChapterRevisionPageFingerprint>,
        cbzDigest: ChapterRevisionArtifactDigest,
    ): ChapterRevisionArtifactValue {
        val existing = target.takeIf { it.exists() }?.let { ChapterRevisionArchiveManifestCodec.decode(it.readBytes()) }

        val expected =
            manifestFor(
                claimed = claimed,
                archivedAt = existing?.archivedAt ?: Instant.now().epochSecond,
                pages = pages,
                digest = cbzDigest,
            )

        if (existing != null && existing != expected) {
            throw ChapterRevisionArchiveConflictException(
                "a locally staged manifest already exists that describes different content: ${target.path}",
            )
        }

        if (existing == null) {
            val temporary = temporarySiblingOf(target)
            temporary.delete()
            temporary.writeBytes(ChapterRevisionArchiveManifestCodec.encode(expected))
            ChapterRevisionStaging.moveAtomicallyInto(temporary, target)
        }

        return ChapterRevisionArtifactValue(expected, ChapterRevisionArchiveArtifacts.digestOf(target))
    }

    /**
     * Copies a locally finished artifact into the archive root.
     *
     * The copy is written beside its target first and re-hashed, because an object-storage mount can
     * truncate or transform a write. An existing archive artifact is reused only when its bytes match
     * the local source exactly; anything else is immutable and fails the commit.
     */
    private fun copyIntoArchive(
        source: File,
        target: File,
        expected: ChapterRevisionArtifactDigest,
        description: String,
    ): ChapterRevisionArtifactDigest {
        if (target.exists()) {
            val existing = ChapterRevisionArchiveArtifacts.digestOf(target)
            if (existing != expected) {
                throw ChapterRevisionArchiveConflictException(
                    "an archived $description already exists with different content: ${target.path}",
                )
            }
            return existing
        }

        target.parentFile.mkdirs()
        val temporary = temporarySiblingOf(target)
        temporary.delete()
        source.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }

        val written = ChapterRevisionArchiveArtifacts.digestOf(temporary)
        if (written != expected) {
            temporary.delete()
            throw ChapterRevisionArchiveConflictException(
                "the archived $description does not match the locally built artifact: ${target.path}",
            )
        }

        ChapterRevisionStaging.moveAtomicallyInto(temporary, target)
        return written
    }

    private fun temporarySiblingOf(target: File): File = File(target.parentFile, ".${target.name}.tmp")

    /** A locally published artifact together with the digest of the exact bytes on disk. */
    private data class ChapterRevisionArtifactValue(
        val value: ChapterRevisionArchiveManifest,
        val digest: ChapterRevisionArtifactDigest,
    )

    private fun manifestFor(
        claimed: ChapterRevisionDataClass,
        archivedAt: Long,
        pages: List<ChapterRevisionPageFingerprint>,
        digest: ChapterRevisionArtifactDigest,
    ) = ChapterRevisionArchiveManifest(
        schemaVersion = ChapterRevisionArchiveManifestCodec.SCHEMA_VERSION,
        revisionId = claimed.id,
        candidateKey = claimed.candidateKey,
        seriesId = claimed.mangaId,
        chapterId = claimed.chapterId,
        sourceId = claimed.sourceId,
        sourceMangaUrl = claimed.sourceMangaUrl,
        sourceChapterUrl = claimed.sourceChapterUrl,
        chapterNumber = claimed.chapterNumber,
        chapterTitle = claimed.name,
        scanlator = claimed.scanlator,
        memo = ChapterRevision.canonicalMemoObject(claimed.memo),
        discoveryReason = claimed.discoveryReason,
        signalConfidence = claimed.signalConfidence,
        changedMetadataFields = claimed.changedMetadataFields,
        discoverySweepItemId = claimed.sweepItemId,
        comparisonState = claimed.comparisonState,
        comparisonBaselineRevisionId = claimed.comparisonBaselineRevisionId,
        comparedAt = claimed.comparedAt,
        // the page-by-page audit is read back from the durable comparison, so the manifest describes
        // exactly the analysis the archive gate was opened by
        visualComparison = ChapterRevisionArchiveVisuals.auditOf(claimed.id),
        // recorded even when no comparison exists, so a failed or unnecessary analysis stays legible
        visualAnalysis =
            ChapterRevisionArchiveVisuals.analysisOf(
                state = claimed.visualAnalysisState,
                attempts = claimed.visualAnalysisAttempts,
                completedAt = claimed.visualAnalysisCompletedAt,
                failure = claimed.visualAnalysisLastFailure,
            ),
        // schema version 5: the canonical binding the discovery was made under. It is a snapshot of
        // the candidate, not a lookup, so the audit survives a later detach or a deleted work.
        canonicalIdentity =
            claimed.canonicalBinding?.let {
                ChapterRevisionArchiveManifestCanonicalIdentity(
                    workKey = it.workKey,
                    bindingRole = it.bindingRole,
                    bindingPriority = it.bindingPriority,
                    bindingPrimary = it.bindingPrimary,
                    bindingSourceId = it.bindingSourceId,
                    bindingMangaUrl = it.bindingMangaUrl,
                )
            },
        discoveredAt = claimed.discoveredAt,
        archivedAt = archivedAt,
        pageCount = pages.size,
        pages = pages,
        acquisitionContentHash = claimed.contentHash,
        archiveContentHash = digest.sha256,
        archiveSize = digest.size,
    )
}

/**
 * Drains revisions whose acquisition completed into their archive commit.
 *
 * Independent from the acquisition state machine: only `COMPLETE` + `NOT_COMMITTED` revisions are
 * claimed, and a failed archive commit never changes how the chapter was acquired.
 */
class ChapterRevisionArchiveLoop(
    private val processor: ChapterRevisionArchiveProcessor,
) {
    private val logger = KotlinLogging.logger {}

    private val worker =
        ChapterRevisionWorkerLoop(
            workerName = "chapter revision archive",
            beforeFirstDrain = { ChapterRevision.recoverInterruptedArchives() },
            drainOnce = { drainOnce() },
        )

    fun start() = worker.start()

    fun stop() = worker.stop()

    fun notifyWorkAvailable() = worker.notifyWorkAvailable()

    /** Claims and archives one revision; returns false when there is nothing to commit. */
    internal suspend fun drainOnce(): Boolean {
        val claimed = ChapterRevision.claimNextArchiveCommit() ?: return false
        try {
            processor.process(claimed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // one broken revision must never end the loop; its state is left mid flight so the
            // startup recovery picks it up again
            logger.error(e) { "Failed to archive chapter revision ${claimed.id}" }
        }
        return true
    }
}

/**
 * Single, global chapter revision archive worker.
 *
 * It never confirms remote durability itself: revisions that reached REMOTE_PENDING are handed to
 * [ChapterRevisionArchiveVerificationExecutor]. The downloaded pages and the locally built artifacts
 * of a pending revision are retained until that separate worker confirms durability.
 */
object ChapterRevisionArchiveExecutor {
    private val applicationDirs: ApplicationDirs by injectLazy()

    private val loop by lazy {
        ChapterRevisionArchiveLoop(
            ChapterRevisionArchiveProcessor(
                stagingRoot = { File(applicationDirs.archiveStagingRoot) },
                archiveRoot = { File(applicationDirs.archiveRoot) },
                onVerificationDue = { ChapterRevisionArchiveVerificationExecutor.notifyWorkAvailable() },
            ),
        )
    }

    fun start() = loop.start()

    fun stop() = loop.stop()

    fun notifyWorkAvailable() = loop.notifyWorkAvailable()

    /**
     * Requeues revisions whose archive commit failed.
     *
     * Both the archived copies and the locally built artifacts of the failed attempt are removed
     * while the rows are locked and before `NOT_COMMITTED` becomes visible, so the worker can never
     * publish into a directory that is being removed. The downloaded pages are deliberately
     * preserved so a retry does not have to download the chapter again.
     */
    fun retry(ids: List<Int>): List<ChapterRevisionDataClass> {
        val stagingRoot = File(applicationDirs.archiveStagingRoot)
        val archiveRoot = File(applicationDirs.archiveRoot)
        val retried =
            ChapterRevision.retryArchive(ids) { revisions ->
                revisions.forEach { revision ->
                    ChapterRevisionArchiveArtifacts.deleteArtifacts(stagingRoot, archiveRoot, revision.candidateKey)
                }
            }
        if (retried.isEmpty()) {
            return emptyList()
        }

        notifyWorkAvailable()
        return retried
    }
}
