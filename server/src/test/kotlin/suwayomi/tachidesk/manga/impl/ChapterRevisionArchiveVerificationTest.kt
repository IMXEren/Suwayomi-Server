package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import suwayomi.tachidesk.manga.model.dataclass.ChapterAcquisitionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.manga.model.table.ChapterTable
import suwayomi.tachidesk.manga.model.table.MangaTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import suwayomi.tachidesk.test.createChapters
import suwayomi.tachidesk.test.createLibraryManga
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterRevisionArchiveVerificationTest : ApplicationTest() {
    private val stagingRoot: File = File("build/tmp/chapter-revision-verify-staging-${UUID.randomUUID()}")
    private val archiveRoot: File = File("build/tmp/chapter-revision-verify-archive-${UUID.randomUUID()}")

    private val remoteRoot = "testremote:bucket/library"

    /** A minimal PNG so the staged files pass the image type check. */
    private fun pngBytes(marker: Int): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24) { marker.toByte() }

    private class FakeSourceAccess(
        private val content: List<ByteArray>,
    ) : ChapterRevisionSourceAccess {
        override suspend fun getPageList(revision: ChapterRevisionDataClass): List<Page> = content.indices.map { Page(it) }

        override suspend fun openPage(
            revision: ChapterRevisionDataClass,
            page: Page,
        ): InputStream = ByteArrayInputStream(content[page.index])
    }

    /** Records every command and answers with a fixed result. */
    private class RecordingRunner(
        private val respond: (List<String>) -> ArchiveRemoteCommandResult = { ArchiveRemoteCommandResult() },
    ) : ArchiveRemoteCommandRunner {
        val commands = mutableListOf<List<String>>()
        var invocations = 0
            private set

        override suspend fun run(
            command: List<String>,
            timeout: Duration,
        ): ArchiveRemoteCommandResult {
            commands += command
            invocations++
            return respond(command)
        }
    }

    private fun entry(
        name: String,
        size: Long,
        sha256: String? = null,
    ): String =
        if (sha256 == null) {
            """{"Path":"$name","Name":"$name","Size":$size}"""
        } else {
            """{"Path":"$name","Name":"$name","Size":$size,"Hashes":{"SHA-256":"$sha256"}}"""
        }

    private fun listing(vararg entries: String): String = entries.joinToString(",", "[", "]")

    /** A remote listing that matches exactly what the archive recorded for [revision]. */
    private fun remoteListing(
        revision: ChapterRevisionDataClass,
        cbzSize: Long? = null,
        manifestSize: Long? = null,
        cbzHash: String? = null,
        manifestHash: String? = null,
    ): String =
        listing(
            entry(
                revision.archiveCbzPath!!.substringAfterLast('/'),
                cbzSize ?: revision.archiveCbzSize!!,
                cbzHash ?: revision.archiveCbzHash,
            ),
            entry(
                revision.archiveManifestPath!!.substringAfterLast('/'),
                manifestSize ?: revision.archiveManifestSize!!,
                manifestHash ?: revision.archiveManifestHash,
            ),
        )

    private fun verifier(runner: ArchiveRemoteCommandRunner) =
        RcloneArchiveCommitVerifier(
            executable = "rclone",
            remoteRoot = remoteRoot,
            timeout = 5.seconds,
            runner = runner,
        )

    private fun verificationProcessor(
        runner: ArchiveRemoteCommandRunner,
        deleteLocalArtifacts: ((File, ChapterRevisionDataClass) -> Unit)? = null,
    ): ChapterRevisionArchiveVerificationProcessor =
        if (deleteLocalArtifacts == null) {
            ChapterRevisionArchiveVerificationProcessor(
                stagingRoot = { stagingRoot },
                verifier = { verifier(runner) },
            )
        } else {
            ChapterRevisionArchiveVerificationProcessor(
                stagingRoot = { stagingRoot },
                verifier = { verifier(runner) },
                deleteLocalArtifacts = deleteLocalArtifacts,
            )
        }

    /** Acquires one revision and commits it into the archive, leaving it REMOTE_PENDING. */
    private fun pendingCandidate(title: String): ChapterRevisionDataClass {
        val mangaId = createLibraryManga(title).also { createChapters(it, 1, read = false) }

        val candidate =
            transaction {
                MangaTable.update({ MangaTable.id eq mangaId }) { it[acquisitionPolicy] = MangaAcquisitionPolicy.AUTO.name }

                val mangaEntry = MangaTable.selectAll().where { MangaTable.id eq mangaId }.first()
                val chapters =
                    ChapterTable
                        .selectAll()
                        .where { ChapterTable.manga eq mangaId }
                        .map { ChapterTable.toDataClass(it) }

                ChapterRevision.createCandidatesForNewChapters(mangaEntry, chapters, 1_000)

                ChapterRevisionTable
                    .selectAll()
                    .where { ChapterRevisionTable.manga eq mangaId }
                    .first()
                    .let { ChapterRevisionTable.toDataClass(it) }
            }

        runBlocking {
            ChapterRevisionAcquisitionProcessor(
                sourceAccess = FakeSourceAccess(listOf(pngBytes(1), pngBytes(2))),
                stagingRoot = { stagingRoot },
            ).process(ChapterRevision.claimNextQueued(now = 50)!!)
        }
        assertEquals(
            ChapterAcquisitionState.COMPLETE,
            ChapterRevision.getRevision(candidate.id)!!.acquisitionState,
        )

        runBlocking {
            ChapterRevisionArchiveProcessor(
                stagingRoot = { stagingRoot },
                archiveRoot = { archiveRoot },
            ).process(ChapterRevision.claimNextArchiveCommit(now = 100)!!)
        }

        val archived = ChapterRevision.getRevision(candidate.id)!!
        assertEquals(ChapterArchiveState.REMOTE_PENDING, archived.archiveState)
        return archived
    }

    private fun verifyOnce(
        revision: ChapterRevisionDataClass,
        runner: ArchiveRemoteCommandRunner,
        now: Long = 200,
        retryIntervalSeconds: Long = 300,
        deleteLocalArtifacts: ((File, ChapterRevisionDataClass) -> Unit)? = null,
    ): ChapterRevisionDataClass {
        // the lease is what the claim persists, so a test that wants the retry cadence uses the same
        // value here as the production lease would only ever be longer
        val claimed = ChapterRevision.claimNextDueVerification(now = now, leaseSeconds = retryIntervalSeconds)!!
        assertEquals(revision.id, claimed.id)
        runBlocking { verificationProcessor(runner, deleteLocalArtifacts).process(claimed) }
        return ChapterRevision.getRevision(revision.id)!!
    }

    /** Makes a pending revision due again, standing in for the scheduled time being reached. */
    private fun makeDueAgain(revision: ChapterRevisionDataClass) {
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq revision.id }) {
                it[archiveNextVerificationAt] = 0
            }
        }
    }

    /**
     * Puts a revision back into the state a fresh archive republication leaves behind.
     *
     * A revision that reached a terminal archive state is only verified again after an explicit
     * retry, so a test that wants another verification has to stand in for that republication.
     */
    private fun makePendingAgain(revision: ChapterRevisionDataClass) {
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq revision.id }) {
                it[archiveState] = ChapterArchiveState.REMOTE_PENDING.name
                it[archiveNextVerificationAt] = 0
            }
        }
    }

    @Test
    fun `the rclone command queries the configured remote with an argument list`() {
        val revision = pendingCandidate("VERIFY_COMMAND")
        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 0, standardOutput = remoteListing(revision)) }

        verifyOnce(revision, runner)

        assertEquals(1, runner.invocations)
        assertEquals(
            listOf(
                "rclone",
                "lsjson",
                "$remoteRoot/revisions/${revision.candidateKey}",
                "--files-only",
                "--hash",
                "--no-modtime",
                "--no-mimetype",
            ),
            runner.commands.single(),
            "the remote is queried directly through an argument list, never through a shell",
        )
    }

    @Test
    fun `matching sizes on the remote confirm durability`() {
        val revision = pendingCandidate("VERIFY_CONFIRMED")
        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 0, standardOutput = remoteListing(revision)) }

        val confirmed = verifyOnce(revision, runner)

        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, confirmed.archiveState)
        assertNull(confirmed.archiveLastError)
        assertEquals(1, confirmed.archiveVerificationAttempts)
        assertEquals(200L, confirmed.archiveLastVerificationAt)
        assertNull(confirmed.archiveNextVerificationAt, "a confirmed revision is no longer scheduled")
    }

    @Test
    fun `a reported sha-256 must match when it is comparable`() {
        val mismatched = pendingCandidate("VERIFY_HASH_BAD")
        val badRunner =
            RecordingRunner {
                ArchiveRemoteCommandResult(
                    exitCode = 0,
                    standardOutput = remoteListing(mismatched, cbzHash = "0".repeat(64)),
                )
            }

        val unconfirmed = verifyOnce(mismatched, badRunner)

        assertEquals(ChapterArchiveState.ARCHIVE_UNCONFIRMED, unconfirmed.archiveState)
        assertTrue(unconfirmed.archiveLastError!!.contains("SHA-256"), unconfirmed.archiveLastError!!)

        val matched = pendingCandidate("VERIFY_HASH_OK")
        val goodRunner =
            RecordingRunner {
                ArchiveRemoteCommandResult(
                    exitCode = 0,
                    standardOutput = remoteListing(matched, cbzHash = matched.archiveCbzHash!!.uppercase()),
                )
            }

        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, verifyOnce(matched, goodRunner).archiveState)
    }

    @Test
    fun `objects that are not visible on the remote yet stay pending and are rescheduled`() {
        val revision = pendingCandidate("VERIFY_PENDING")
        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 0, standardOutput = "[]") }

        val pending = verifyOnce(revision, runner, now = 200, retryIntervalSeconds = 300)

        assertEquals(ChapterArchiveState.REMOTE_PENDING, pending.archiveState)
        assertTrue(pending.archiveLastError!!.contains("not visible"), pending.archiveLastError!!)
        assertEquals(1, pending.archiveVerificationAttempts)
        assertEquals(500L, pending.archiveNextVerificationAt)
        assertNull(
            ChapterRevision.claimNextDueVerification(now = 499, leaseSeconds = 300),
            "the scheduled retry is in the future, so the revision is not claimed again",
        )
    }

    @Test
    fun `documented rclone not-found and temporary exit codes stay pending`() {
        val revision = pendingCandidate("VERIFY_EXIT_CODES")

        listOf(3, 4, 5).forEach { exitCode ->
            makeDueAgain(revision)
            val runner =
                RecordingRunner {
                    ArchiveRemoteCommandResult(exitCode = exitCode, standardError = "rclone: object not visible")
                }

            val pending = verifyOnce(revision, runner)
            assertEquals(
                ChapterArchiveState.REMOTE_PENDING,
                pending.archiveState,
                "rclone exit code $exitCode means not visible yet, not a failed archive",
            )
        }
    }

    @Test
    fun `a command timeout stays pending instead of failing the archive`() {
        val revision = pendingCandidate("VERIFY_TIMEOUT")
        val runner = RecordingRunner { ArchiveRemoteCommandResult(timedOut = true) }

        val pending = verifyOnce(revision, runner)

        assertEquals(ChapterArchiveState.REMOTE_PENDING, pending.archiveState)
        assertTrue(pending.archiveLastError!!.contains("did not answer"), pending.archiveLastError!!)
    }

    @Test
    fun `a fatal exit code or an unusable executable marks the revision unconfirmed`() {
        val fatal = pendingCandidate("VERIFY_FATAL")
        val fatalRunner =
            RecordingRunner {
                ArchiveRemoteCommandResult(exitCode = 1, standardError = "Failed to create file system for remote")
            }

        val unconfirmed = verifyOnce(fatal, fatalRunner)

        assertEquals(ChapterArchiveState.ARCHIVE_UNCONFIRMED, unconfirmed.archiveState)
        assertTrue(unconfirmed.archiveLastError!!.contains("exit code 1"), unconfirmed.archiveLastError!!)

        val missing = pendingCandidate("VERIFY_NO_EXECUTABLE")
        val missingRunner = RecordingRunner { ArchiveRemoteCommandResult(startFailure = "Cannot run program rclone") }

        val refused = verifyOnce(missing, missingRunner)

        assertEquals(ChapterArchiveState.ARCHIVE_UNCONFIRMED, refused.archiveState)
        assertTrue(refused.archiveLastError!!.contains("could not run rclone"), refused.archiveLastError!!)
    }

    @Test
    fun `malformed output after a successful exit is unconfirmed`() {
        val revision = pendingCandidate("VERIFY_MALFORMED")
        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 0, standardOutput = "not json at all") }

        val unconfirmed = verifyOnce(revision, runner)

        assertEquals(ChapterArchiveState.ARCHIVE_UNCONFIRMED, unconfirmed.archiveState)
        assertTrue(
            unconfirmed.archiveLastError!!.contains("could not read the rclone listing"),
            unconfirmed.archiveLastError!!,
        )
    }

    @Test
    fun `a remote size mismatch is unconfirmed`() {
        val revision = pendingCandidate("VERIFY_SIZE")
        val runner =
            RecordingRunner {
                ArchiveRemoteCommandResult(
                    exitCode = 0,
                    standardOutput = remoteListing(revision, cbzSize = revision.archiveCbzSize!! + 1),
                )
            }

        val unconfirmed = verifyOnce(revision, runner)

        assertEquals(ChapterArchiveState.ARCHIVE_UNCONFIRMED, unconfirmed.archiveState)
        assertTrue(unconfirmed.archiveLastError!!.contains("has size"), unconfirmed.archiveLastError!!)
    }

    @Test
    fun `invalid remote configuration is refused before any process runs`() {
        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 0, standardOutput = "[]") }

        listOf("", "   ", "/", "remote\n:path", "remote\u0000:path").forEach { remote ->
            assertTrue(
                runCatching { RcloneArchiveCommitVerifier("rclone", remote, 5.seconds, runner) }.isFailure,
                "remote '$remote' must be refused",
            )
        }
        assertTrue(
            runCatching { RcloneArchiveCommitVerifier("", remoteRoot, 5.seconds, runner) }.isFailure,
            "a blank executable must be refused",
        )
        assertEquals(0, runner.invocations, "a refused configuration must never spawn a process")
    }

    @Test
    fun `a disabled verifier claims nothing and spawns nothing`() {
        val revision = pendingCandidate("VERIFY_DISABLED")
        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 0, standardOutput = remoteListing(revision)) }
        val loop =
            ChapterRevisionArchiveVerificationLoop(
                processor = verificationProcessor(runner),
                enabled = { false },
                retryIntervalSeconds = { 300 },
            )

        assertTrue(!runBlocking { loop.drainOnceIfEnabled() }, "a disabled verifier claims no work")
        assertEquals(0, runner.invocations)

        val untouched = ChapterRevision.getRevision(revision.id)!!
        assertEquals(ChapterArchiveState.REMOTE_PENDING, untouched.archiveState)
        assertEquals(0L, untouched.archiveNextVerificationAt)
        assertEquals(0, untouched.archiveVerificationAttempts)
    }

    @Test
    fun `a pending verification is rescheduled instead of hot looping`() {
        val revision = pendingCandidate("VERIFY_RESCHEDULED")
        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 3, standardError = "not found") }
        val loop =
            ChapterRevisionArchiveVerificationLoop(
                processor = verificationProcessor(runner),
                enabled = { true },
                retryIntervalSeconds = { 300 },
                now = { 1_000 },
            )

        runBlocking {
            assertTrue(loop.drainOnceIfEnabled(), "the due revision is verified once")
            assertTrue(!loop.drainOnceIfEnabled(), "the scheduled retry is in the future, so nothing is claimed")
        }

        assertEquals(1, runner.invocations)
        assertEquals(1_300L, ChapterRevision.getRevision(revision.id)!!.archiveNextVerificationAt)
    }

    @Test
    fun `claiming a due verification counts the attempt and schedules the next one`() {
        val revision = pendingCandidate("VERIFY_CLAIM")
        assertEquals(0L, revision.archiveNextVerificationAt, "a fresh commit is due immediately")

        val first = ChapterRevision.claimNextDueVerification(now = 400, leaseSeconds = 60)!!
        assertEquals(revision.id, first.id)
        assertEquals(1, first.archiveVerificationAttempts)
        assertEquals(400L, first.archiveLastVerificationAt)
        assertEquals(460L, first.archiveNextVerificationAt)

        assertNull(ChapterRevision.claimNextDueVerification(now = 459, leaseSeconds = 60))
        assertEquals(
            revision.id,
            ChapterRevision.claimNextDueVerification(now = 460, leaseSeconds = 60)!!.id,
        )
    }

    @Test
    fun `the next due time follows the persisted schedule`() {
        val revision = pendingCandidate("VERIFY_DUE_AT")

        assertEquals(900L, ChapterRevision.nextVerificationDueAt(now = 900))
        ChapterRevision.claimNextDueVerification(now = 900, leaseSeconds = 100)
        assertEquals(1_000L, ChapterRevision.nextVerificationDueAt(now = 950))

        ChapterRevision.markArchiveUnconfirmed(revision.id, "test")
        assertNull(ChapterRevision.nextVerificationDueAt(now = 950), "nothing pending means nothing is scheduled")
    }

    @Test
    fun `a confirmed revision drops its local copies but never the archive`() {
        val revision = pendingCandidate("VERIFY_CLEANUP")
        assertTrue(ChapterRevisionStaging.directory(stagingRoot, revision.candidateKey).isDirectory)
        assertTrue(ChapterRevisionArchiveArtifacts.localCbzFile(stagingRoot, revision.candidateKey).isFile)
        val archivedCbz = File(archiveRoot, revision.archiveCbzPath!!)

        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 0, standardOutput = remoteListing(revision)) }
        val confirmed = verifyOnce(revision, runner)

        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, confirmed.archiveState)
        assertNull(confirmed.candidatePath, "the pending-cleanup marker is cleared last")
        assertTrue(
            !ChapterRevisionStaging.directory(stagingRoot, revision.candidateKey).exists(),
            "the downloaded pages are dropped only after confirmation",
        )
        assertTrue(!ChapterRevisionArchiveArtifacts.localDirectory(stagingRoot, revision.candidateKey).exists())
        assertTrue(archivedCbz.isFile, "the archived copy must never be deleted by confirmation")
        assertTrue(File(archiveRoot, confirmed.archiveManifestPath!!).isFile)
    }

    @Test
    fun `cleanup never runs before remote confirmation`() {
        val revision = pendingCandidate("VERIFY_NO_EARLY_CLEANUP")
        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 3, standardError = "not found") }

        val pending = verifyOnce(revision, runner)

        assertEquals(ChapterArchiveState.REMOTE_PENDING, pending.archiveState)
        assertTrue(ChapterRevisionStaging.directory(stagingRoot, revision.candidateKey).isDirectory)
        assertTrue(ChapterRevisionArchiveArtifacts.localCbzFile(stagingRoot, revision.candidateKey).isFile)
        assertTrue(ChapterRevision.revisionsAwaitingCleanup(5).isEmpty())
    }

    @Test
    fun `a failed cleanup stays recoverable and is retried later`() {
        val revision = pendingCandidate("VERIFY_CLEANUP_RETRY")
        var failDeletion = true
        val deleteLocalArtifacts: (File, ChapterRevisionDataClass) -> Unit = { root, pending ->
            if (failDeletion) {
                throw IOException("staged file is still in use")
            } else {
                deleteLocalRevisionArtifacts(root, pending)
            }
        }
        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 0, standardOutput = remoteListing(revision)) }

        val confirmed = verifyOnce(revision, runner, deleteLocalArtifacts = deleteLocalArtifacts)

        assertEquals(
            ChapterArchiveState.REMOTE_CONFIRMED,
            confirmed.archiveState,
            "a failed cleanup must never undo a durable confirmation",
        )
        assertEquals(
            revision.candidatePath,
            confirmed.candidatePath,
            "the cleanup marker survives a failed delete so it can be retried",
        )
        assertEquals(listOf(revision.id), ChapterRevision.revisionsAwaitingCleanup(5).map { it.id })

        failDeletion = false
        val processor = verificationProcessor(runner, deleteLocalArtifacts)
        assertTrue(processor.cleanupPendingLocalArtifacts(), "the lingering cleanup is retried")
        assertNull(ChapterRevision.getRevision(revision.id)!!.candidatePath)
        assertTrue(ChapterRevision.revisionsAwaitingCleanup(5).isEmpty())
        assertTrue(!processor.cleanupPendingLocalArtifacts(), "nothing is left to clean up")
    }

    @Test
    fun `cleanup markers are only cleared for confirmed revisions`() {
        val revision = pendingCandidate("VERIFY_MARKER_GUARD")

        assertTrue(!ChapterRevision.markCandidateCleanupComplete(revision.id))
        assertEquals(revision.candidatePath, ChapterRevision.getRevision(revision.id)!!.candidatePath)
    }

    @Test
    fun `an unconfirmed revision can be retried and rebuilt`() {
        val revision = pendingCandidate("VERIFY_UNCONFIRMED_RETRY")
        val rejecting = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 1, standardError = "auth failed") }
        assertEquals(ChapterArchiveState.ARCHIVE_UNCONFIRMED, verifyOnce(revision, rejecting).archiveState)

        var cleanedBeforeCommit = false
        val retried =
            ChapterRevision.retryArchive(listOf(revision.id), now = 400) { retriedRevisions ->
                retriedRevisions.forEach {
                    ChapterRevisionArchiveArtifacts.deleteArtifacts(stagingRoot, archiveRoot, it.candidateKey)
                }
                cleanedBeforeCommit = true
            }

        assertTrue(cleanedBeforeCommit, "artifact cleanup must run before the retry becomes visible")
        assertEquals(ChapterArchiveState.NOT_COMMITTED, retried.single().archiveState)
        assertNull(retried.single().archiveNextVerificationAt)

        // the commit rebuilds from the still-downloaded pages and is verified again afterwards
        runBlocking {
            ChapterRevisionArchiveProcessor(
                stagingRoot = { stagingRoot },
                archiveRoot = { archiveRoot },
            ).process(ChapterRevision.claimNextArchiveCommit(now = 500)!!)
        }

        val republished = ChapterRevision.getRevision(revision.id)!!
        assertEquals(ChapterArchiveState.REMOTE_PENDING, republished.archiveState)
        assertEquals(0L, republished.archiveNextVerificationAt)

        val accepting = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 0, standardOutput = remoteListing(republished)) }
        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, verifyOnce(republished, accepting, now = 600).archiveState)
    }

    @Test
    fun `the process runner captures output and terminates a stalled command`() {
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath

        val version = runBlocking { ProcessArchiveRemoteCommandRunner.run(listOf(java, "-version"), 60.seconds) }
        assertEquals(0, version.exitCode, version.standardError)
        assertTrue(!version.timedOut)
        assertTrue(
            (version.standardError + version.standardOutput).isNotBlank(),
            "the runner must capture what the command wrote",
        )

        // blocks independently of standard input, which this runner now closes immediately
        val blockingCommand =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                listOf("ping", "-n", "30", "127.0.0.1")
            } else {
                listOf("sleep", "30")
            }

        val startedAt = System.nanoTime()
        val stalled = runBlocking { ProcessArchiveRemoteCommandRunner.run(blockingCommand, 2.seconds) }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            stalled.timedOut,
            "a command that never exits must be terminated by the timeout, but it exited with " +
                "${stalled.exitCode}: ${stalled.standardError}",
        )
        assertNull(stalled.exitCode)
        assertTrue(
            elapsedMillis < 30_000,
            "a stalled command must be killed at the timeout instead of being awaited to completion",
        )
    }

    @Test
    fun `a command that would wait on standard input is not left hanging`() {
        val waitingForInput =
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                listOf("cmd", "/c", "pause")
            } else {
                listOf("cat")
            }

        val startedAt = System.nanoTime()
        val result = runBlocking { ProcessArchiveRemoteCommandRunner.run(waitingForInput, 15.seconds) }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertTrue(
            !result.timedOut,
            "closing standard input must let an interactive command finish instead of hanging for " +
                "the whole timeout: ${result.standardError}",
        )
        assertTrue(elapsedMillis < 15_000, "the command must not wait for the whole timeout")
    }

    @Test
    fun `a missing executable is reported instead of thrown`() {
        val result =
            runBlocking {
                ProcessArchiveRemoteCommandRunner.run(
                    listOf("definitely-not-a-real-rclone-binary-${UUID.randomUUID()}", "--version"),
                    10.seconds,
                )
            }

        assertNull(result.exitCode)
        assertTrue(result.startFailure != null, "a missing executable must be reported as a start failure")
        assertTrue(!result.timedOut)
    }

    @Test
    fun `a claim lease covers a command timeout that is longer than the retry cadence`() {
        // a 600s timeout with a 30s retry cadence: the persisted lease must outlast the command
        val lease = verificationLeaseSeconds(retryIntervalSeconds = 30, commandTimeoutSeconds = 600)
        assertTrue(lease > 600, "the lease must cover a command that runs for the whole timeout: $lease")

        val revision = pendingCandidate("VERIFY_LEASE")
        val claimed = ChapterRevision.claimNextDueVerification(now = 1_000, leaseSeconds = lease)!!
        assertEquals(revision.id, claimed.id)
        assertEquals(1_000 + lease, claimed.archiveNextVerificationAt)

        assertNull(
            ChapterRevision.claimNextDueVerification(now = 1_000 + 600, leaseSeconds = lease),
            "another instance must not claim a row whose command may still be running",
        )
        assertEquals(
            revision.id,
            ChapterRevision.claimNextDueVerification(now = 1_000 + lease, leaseSeconds = lease)!!.id,
        )
    }

    @Test
    fun `the production lease is never shorter than the retry cadence`() {
        assertEquals(
            300L,
            verificationLeaseSeconds(retryIntervalSeconds = 300, commandTimeoutSeconds = 30),
            "a short command must not shorten the configured retry cadence",
        )
    }

    @Test
    fun `a stale confirmation never overwrites a hard unconfirmed result`() {
        val revision = pendingCandidate("VERIFY_STALE_CONFIRM")
        ChapterRevision.markArchiveUnconfirmed(revision.id, "hard failure", now = 300)
        assertEquals(ChapterArchiveState.ARCHIVE_UNCONFIRMED, ChapterRevision.getRevision(revision.id)!!.archiveState)

        assertTrue(
            !ChapterRevision.markRemoteConfirmed(revision.id, now = 400),
            "only a pending revision may be confirmed",
        )
        val untouched = ChapterRevision.getRevision(revision.id)!!
        assertEquals(ChapterArchiveState.ARCHIVE_UNCONFIRMED, untouched.archiveState)
        assertEquals("hard failure", untouched.archiveLastError)
    }

    @Test
    fun `a confirmation is applied exactly once`() {
        val revision = pendingCandidate("VERIFY_CONFIRM_ONCE")

        assertTrue(ChapterRevision.markRemoteConfirmed(revision.id, now = 300))
        assertTrue(!ChapterRevision.markRemoteConfirmed(revision.id, now = 400))
        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, ChapterRevision.getRevision(revision.id)!!.archiveState)
    }

    @Test
    fun `a malformed remote sha-256 is a failure while an absent one falls back to size`() {
        val malformed = pendingCandidate("VERIFY_HASH_MALFORMED")
        listOf("", "not-a-digest", "abc123", "z".repeat(64), "0".repeat(63)).forEach { reported ->
            makePendingAgain(malformed)
            val runner =
                RecordingRunner {
                    ArchiveRemoteCommandResult(
                        exitCode = 0,
                        standardOutput = remoteListing(malformed, cbzHash = reported),
                    )
                }

            val unconfirmed = verifyOnce(malformed, runner)
            assertEquals(
                ChapterArchiveState.ARCHIVE_UNCONFIRMED,
                unconfirmed.archiveState,
                "a malformed SHA-256 '$reported' must not be silently ignored",
            )
            assertTrue(unconfirmed.archiveLastError!!.contains("malformed SHA-256"), unconfirmed.archiveLastError!!)
        }

        val absent = pendingCandidate("VERIFY_HASH_ABSENT")
        val cbzName = absent.archiveCbzPath!!.substringAfterLast('/')
        val manifestName = absent.archiveManifestPath!!.substringAfterLast('/')
        val noHashRunner =
            RecordingRunner {
                ArchiveRemoteCommandResult(
                    exitCode = 0,
                    standardOutput =
                        listing(
                            entry(cbzName, absent.archiveCbzSize!!),
                            entry(manifestName, absent.archiveManifestSize!!),
                        ),
                )
            }

        assertEquals(
            ChapterArchiveState.REMOTE_CONFIRMED,
            verifyOnce(absent, noHashRunner).archiveState,
            "a backend that reports no SHA-256 at all is confirmed by exact size",
        )
    }

    @Test
    fun `the sha-256 field is recognised with or without the dash`() {
        val revision = pendingCandidate("VERIFY_HASH_KEY")
        val cbzName = revision.archiveCbzPath!!.substringAfterLast('/')
        val manifestName = revision.archiveManifestPath!!.substringAfterLast('/')
        val runner =
            RecordingRunner {
                ArchiveRemoteCommandResult(
                    exitCode = 0,
                    standardOutput =
                        listing(
                            """{"Path":"$cbzName","Name":"$cbzName","Size":${revision.archiveCbzSize!!},"Hashes":{"SHA256":"${revision.archiveCbzHash!!}"}}""",
                            """{"Path":"$manifestName","Name":"$manifestName","Size":${revision.archiveManifestSize!!},"Hashes":{"sha256":"${revision.archiveManifestHash!!}"}}""",
                        ),
                )
            }

        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, verifyOnce(revision, runner).archiveState)
    }

    @Test
    fun `injected or local remote specs are refused while real remote forms are accepted`() {
        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 0, standardOutput = "[]") }

        listOf("--config=/etc/rclone.conf", "C:/mount", "/mount", "remote\\bucket", "remote:bucket\\path").forEach { remote ->
            assertTrue(
                runCatching { RcloneArchiveCommitVerifier("rclone", remote, 5.seconds, runner) }.isFailure,
                "remote '$remote' must be refused",
            )
        }

        // constructing an accepted spec must not throw, and must never spawn a process
        listOf("testremote:bucket/library", "testremote:", ":s3,provider=Other:path", "on-the-fly:s3:path").forEach { remote ->
            RcloneArchiveCommitVerifier("rclone", remote, 5.seconds, runner)
        }
        assertEquals(0, runner.invocations, "constructing a verifier must never spawn a process")
    }

    @Test
    fun `a configured remote and executable are never persisted verbatim`() {
        val revision = pendingCandidate("VERIFY_REDACTION")
        val secretRemote = "supersecretremote:bucket/private"
        val secretExecutable = "rclone-at-a-secret-location"
        val runner =
            RecordingRunner {
                ArchiveRemoteCommandResult(
                    exitCode = 1,
                    standardError =
                        "Failed to create file system for \"$secretRemote\": AccessDenied: " +
                            "the command $secretExecutable could not be used",
                )
            }
        val processor =
            ChapterRevisionArchiveVerificationProcessor(
                stagingRoot = { stagingRoot },
                verifier = { RcloneArchiveCommitVerifier(secretExecutable, secretRemote, 5.seconds, runner) },
            )

        val claimed = ChapterRevision.claimNextDueVerification(now = 200, leaseSeconds = 300)!!
        runBlocking { processor.process(claimed) }

        val unconfirmed = ChapterRevision.getRevision(revision.id)!!
        assertEquals(ChapterArchiveState.ARCHIVE_UNCONFIRMED, unconfirmed.archiveState)
        val reason = unconfirmed.archiveLastError!!
        assertTrue(!reason.contains(secretRemote), "the configured remote must not be persisted: $reason")
        assertTrue(!reason.contains("supersecretremote"), "the remote name must not be persisted: $reason")
        assertTrue(!reason.contains(secretExecutable), "the executable must not be persisted: $reason")
        assertTrue(reason.contains("<remote>"), "the redaction placeholder must remain readable: $reason")
        assertTrue(reason.contains("AccessDenied"), "the diagnostic must stay debuggable: $reason")
    }

    @Test
    fun `an oversized command output is unconfirmed rather than read back`() {
        val revision = pendingCandidate("VERIFY_OUTPUT_EXCEEDED")
        val runner =
            RecordingRunner {
                ArchiveRemoteCommandResult(outputExceeded = true, standardError = "rclone listed too much")
            }

        val unconfirmed = verifyOnce(revision, runner)

        assertEquals(ChapterArchiveState.ARCHIVE_UNCONFIRMED, unconfirmed.archiveState)
        assertTrue(unconfirmed.archiveLastError!!.contains("more output"), unconfirmed.archiveLastError!!)
    }

    @Test
    fun `a failed cleanup is deferred behind later confirmed revisions`() {
        val first = pendingCandidate("VERIFY_DEFER_FIRST")
        val second = pendingCandidate("VERIFY_DEFER_SECOND")
        ChapterRevision.markRemoteConfirmed(first.id, now = 300)
        ChapterRevision.markRemoteConfirmed(second.id, now = 300)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq first.id }) { it[updatedAt] = 300 }
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq second.id }) { it[updatedAt] = 400 }
        }
        assertEquals(listOf(first.id, second.id), ChapterRevision.revisionsAwaitingCleanup(5).map { it.id })

        assertTrue(
            ChapterRevision.deferCandidateCleanup(first.id, now = 350),
            "deferral must move behind the latest cleanup even when the wall-clock value is not newer",
        )

        assertEquals(
            listOf(second.id, first.id),
            ChapterRevision.revisionsAwaitingCleanup(5).map { it.id },
            "a deferred revision must no longer block the ones behind it",
        )
        val deferred = ChapterRevision.getRevision(first.id)!!
        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, deferred.archiveState)
        assertEquals(first.candidatePath, deferred.candidatePath)
    }

    @Test
    fun `cleanup drains successful batches and stops when a batch cannot make progress`() {
        val undeletable = pendingCandidate("VERIFY_CLEANUP_BLOCKED")
        val healthy = pendingCandidate("VERIFY_CLEANUP_HEALTHY")
        ChapterRevision.markRemoteConfirmed(undeletable.id, now = 300)
        ChapterRevision.markRemoteConfirmed(healthy.id, now = 300)
        transaction {
            // the undeletable revision is the oldest, so without deferral it would be retried first
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq undeletable.id }) { it[updatedAt] = 300 }
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq healthy.id }) { it[updatedAt] = 400 }
        }

        val failing: (File, ChapterRevisionDataClass) -> Unit = { root, revision ->
            if (revision.id == undeletable.id) {
                throw IOException("staged file is still in use")
            } else {
                deleteLocalRevisionArtifacts(root, revision)
            }
        }
        val processor = verificationProcessor(RecordingRunner { ArchiveRemoteCommandResult() }, failing)

        assertTrue(processor.cleanupPendingLocalArtifacts(), "the healthy revision behind it is cleaned up")
        assertTrue(!processor.cleanupPendingLocalArtifacts(), "a batch with no progress stops the drain")

        assertNull(ChapterRevision.getRevision(healthy.id)!!.candidatePath)
        val blocked = ChapterRevision.getRevision(undeletable.id)!!
        assertEquals(ChapterArchiveState.REMOTE_CONFIRMED, blocked.archiveState)
        assertEquals(undeletable.candidatePath, blocked.candidatePath)
        assertEquals(listOf(undeletable.id), ChapterRevision.revisionsAwaitingCleanup(5).map { it.id })
    }

    @Test
    fun `the verification loop waits for its schedule, reacts to a wake and stops cleanly`() {
        var clock = 1_000L
        val runner = RecordingRunner { ArchiveRemoteCommandResult(exitCode = 3, standardError = "not found") }
        val scheduled = pendingCandidate("VERIFY_LOOP_SCHEDULED")
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq scheduled.id }) {
                it[archiveNextVerificationAt] = clock + 3_600
            }
        }

        val loop =
            ChapterRevisionArchiveVerificationLoop(
                processor = verificationProcessor(runner),
                enabled = { true },
                retryIntervalSeconds = { 30 },
                leaseSeconds = { 60 },
                now = { clock },
            )

        loop.start()
        try {
            runBlocking { delay(300) }
            assertEquals(0, runner.invocations, "a revision scheduled in the future must not be verified early")
            assertEquals(
                0,
                ChapterRevision.getRevision(scheduled.id)!!.archiveVerificationAttempts,
                "the scheduled revision was not even claimed",
            )

            // a newly due revision is picked up as soon as the worker is woken
            val due = pendingCandidate("VERIFY_LOOP_DUE")
            assertEquals(0L, ChapterRevision.getRevision(due.id)!!.archiveNextVerificationAt)
            loop.notifyWorkAvailable()
            runBlocking {
                withTimeout(10_000) {
                    while (runner.invocations == 0) delay(10)
                }
            }

            assertEquals(1, ChapterRevision.getRevision(due.id)!!.archiveVerificationAttempts)
            assertTrue(
                ChapterRevision.getRevision(due.id)!!.archiveNextVerificationAt!! > clock,
                "the pending revision is rescheduled into the future instead of spinning",
            )
        } finally {
            loop.stop()
        }

        val afterStop = runner.invocations
        runBlocking { delay(400) }
        assertEquals(afterStop, runner.invocations, "stop() must cancel the worker cleanly")
    }

    @AfterEach
    internal fun tearDown() {
        stagingRoot.deleteRecursively()
        archiveRoot.deleteRecursively()
        clearTables(ChapterRevisionTable, ChapterTable, MangaTable)
    }
}
