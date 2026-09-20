package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import suwayomi.tachidesk.graphql.types.ChapterRevisionType
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionIntegrityState
import suwayomi.tachidesk.manga.model.table.ChapterRevisionTable
import suwayomi.tachidesk.server.ApplicationDirs
import suwayomi.tachidesk.test.ApplicationTest
import suwayomi.tachidesk.test.clearTables
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The lifetime the tests ask a backend for, and the clock the signed locations are built against. */
private const val TEST_LIFETIME_SECONDS = 300L

private val TEST_NOW: Long = Instant.parse("2026-01-01T00:00:00Z").epochSecond

private val BASIC_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

private fun basicDate(epochSecond: Long): String = BASIC_DATE_TIME.format(Instant.ofEpochSecond(epochSecond).atOffset(ZoneOffset.UTC))

/** One absolute HTTPS location carrying exactly the query parameters it is given. */
private fun signedLocation(
    vararg parameters: Pair<String, String>,
    host: String = "bucket.example",
): String = "https://$host/object?" + parameters.joinToString("&") { (name, value) -> "$name=$value" }

/** An AWS/R2/S3-style signed location whose query states its own bounded expiry. */
private fun awsSignedLocation(
    issuedAtEpochSecond: Long = TEST_NOW,
    lifetimeSeconds: Long = TEST_LIFETIME_SECONDS,
): String =
    signedLocation(
        "X-Amz-Date" to basicDate(issuedAtEpochSecond),
        "X-Amz-Expires" to lifetimeSeconds.toString(),
        "X-Amz-Signature" to "abc",
    )

/**
 * Delivery of an archived revision's CBZ: who may be served at all, which command is run to serve it
 * directly, and what the mounted copy may and may not be used for.
 *
 * No test needs a live remote. The command runner is injectable, so what is asserted is the argument
 * list that would be run, the shape of the answer that is accepted, and that no process is started at
 * all where none may be.
 */
class ChapterRevisionDeliveryTest : ApplicationTest() {
    private val archiveRoot: File get() = File(Injekt.get<ApplicationDirs>().archiveRoot)

    /** Records what it was asked to run and answers with one canned result, so no process is spawned. */
    private class FakeRunner(
        private val result: ArchiveRemoteCommandResult,
    ) : ArchiveRemoteCommandRunner {
        val commands = mutableListOf<List<String>>()
        var calls = 0

        override suspend fun run(
            command: List<String>,
            timeout: Duration,
        ): ArchiveRemoteCommandResult {
            calls++
            commands += command
            return result
        }
    }

    private fun newKey(): String = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Suppress("LongParameterList")
    private fun insertRevision(
        candidateKey: String = newKey(),
        chapterKey: String = newKey(),
        disposition: ChapterRevisionDisposition = ChapterRevisionDisposition.ACCEPTED,
        archiveState: ChapterArchiveState = ChapterArchiveState.REMOTE_CONFIRMED,
        retentionState: ChapterRetentionState = ChapterRetentionState.RETAINED,
        integrityState: ChapterRevisionIntegrityState = ChapterRevisionIntegrityState.NEVER_AUDITED,
        deletedAt: Long? = null,
        archiveCbzPath: String? = ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey),
        archiveCbzHash: String? = null,
        archiveCbzSize: Long? = null,
    ): Int =
        transaction {
            ChapterRevisionTable.insert {
                it[ChapterRevisionTable.candidateKey] = candidateKey
                it[ChapterRevisionTable.chapterKey] = chapterKey
                it[sourceChapterUrl] = "https://example.invalid/$candidateKey"
                it[name] = "chapter"
                it[discoveredAt] = 1
                it[updatedAt] = 1
                it[ChapterRevisionTable.disposition] = disposition.name
                it[ChapterRevisionTable.archiveState] = archiveState.name
                it[ChapterRevisionTable.retentionState] = retentionState.name
                it[ChapterRevisionTable.integrityState] = integrityState.name
                it[ChapterRevisionTable.deletedAt] = deletedAt
                it[ChapterRevisionTable.archiveCbzPath] = archiveCbzPath
                it[ChapterRevisionTable.archiveCbzHash] = archiveCbzHash
                it[ChapterRevisionTable.archiveCbzSize] = archiveCbzSize
            }

            ChapterRevisionTable
                .selectAll()
                .where { ChapterRevisionTable.candidateKey eq candidateKey }
                .first()[ChapterRevisionTable.id]
                .value
        }

    /** One accepted revision whose archived CBZ really is on the mounted archive. */
    private data class Archived(
        val revisionId: Int,
        val candidateKey: String,
        val file: File,
        val content: ByteArray,
    )

    private fun archivedRevision(content: ByteArray = "cbz-bytes".toByteArray()): Archived {
        val candidateKey = newKey()
        val file = File(archiveRoot, ChapterRevisionArchiveArtifacts.relativeCbzPath(candidateKey))
        file.parentFile.mkdirs()
        file.writeBytes(content)

        val revisionId =
            insertRevision(
                candidateKey = candidateKey,
                archiveCbzHash = sha256Of(content),
                archiveCbzSize = content.size.toLong(),
            )

        return Archived(revisionId, candidateKey, file, content)
    }

    private fun revision(id: Int): ChapterRevisionDataClass = ChapterRevision.getRevision(id)!!

    /** The file of a resolution that was served from the mounted archive. */
    private fun localFile(resolution: ChapterRevisionDeliveryResolution): File =
        (resolution as ChapterRevisionDeliveryResolution.Local).file

    private fun resolver(
        runner: ArchiveRemoteCommandRunner = FakeRunner(ArchiveRemoteCommandResult(exitCode = 0)),
        directEnabled: Boolean = false,
        remote: String = "remote:bucket/library",
        executable: String = "rclone",
        fallback: Boolean = true,
        requireExpiryEvidence: Boolean = true,
        now: Long = TEST_NOW,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ChapterRevisionDeliveryResolver {
        val linker =
            RcloneArchiveLinkGenerator(
                executable = { executable },
                remoteRoot = { remote },
                expirySeconds = { TEST_LIFETIME_SECONDS },
                timeout = { 30.seconds },
                requireExpiryEvidence = { requireExpiryEvidence },
                now = { now },
                runner = runner,
            )

        return ChapterRevisionDeliveryResolver(
            archiveRoot = { archiveRoot },
            directDeliveryEnabled = { directEnabled },
            fallbackToLocal = { fallback },
            linker = linker,
            ioDispatcher = ioDispatcher,
        )
    }

    @Test
    fun `only an accepted revision of a chapter is deliverable`() {
        // a candidate, a rejected and an unchanged revision are not editions of a chapter anybody asked
        // to keep, so there is nothing to download for them
        listOf(
            ChapterRevisionDisposition.CANDIDATE,
            ChapterRevisionDisposition.REJECTED,
            ChapterRevisionDisposition.UNCHANGED,
        ).forEach { disposition ->
            val id = insertRevision(disposition = disposition, archiveCbzHash = "a".repeat(64), archiveCbzSize = 10)
            assertEquals(ChapterRevisionDeliverability.MISSING, revision(id).deliverability(), "$disposition")
        }

        listOf(ChapterRevisionDisposition.ACCEPTED, ChapterRevisionDisposition.SUPERSEDED).forEach { disposition ->
            val id = insertRevision(disposition = disposition, archiveCbzHash = "a".repeat(64), archiveCbzSize = 10)
            assertEquals(ChapterRevisionDeliverability.DELIVERABLE, revision(id).deliverability(), "$disposition")
        }
    }

    @Test
    fun `a revision whose payload is not durably there is unusable`() {
        val notCommitted =
            insertRevision(archiveState = ChapterArchiveState.NOT_COMMITTED, archiveCbzHash = "a".repeat(64), archiveCbzSize = 10)
        val pending =
            insertRevision(archiveState = ChapterArchiveState.REMOTE_PENDING, archiveCbzHash = "a".repeat(64), archiveCbzSize = 10)
        assertEquals(ChapterRevisionDeliverability.UNUSABLE, revision(notCommitted).deliverability())
        assertEquals(ChapterRevisionDeliverability.UNUSABLE, revision(pending).deliverability())

        listOf(
            ChapterRetentionState.DELETING,
            ChapterRetentionState.REMOTE_DELETE_PENDING,
            ChapterRetentionState.PRUNED,
        ).forEach { retention ->
            val id = insertRevision(retentionState = retention, archiveCbzHash = "a".repeat(64), archiveCbzSize = 10)
            assertEquals(ChapterRevisionDeliverability.UNUSABLE, revision(id).deliverability(), "$retention")
        }

        val deleted =
            insertRevision(deletedAt = 5, archiveCbzHash = "a".repeat(64), archiveCbzSize = 10)
        assertEquals(ChapterRevisionDeliverability.UNUSABLE, revision(deleted).deliverability())

        listOf(
            ChapterRevisionIntegrityState.MISSING,
            ChapterRevisionIntegrityState.CORRUPT,
        ).forEach { integrity ->
            val id = insertRevision(integrityState = integrity, archiveCbzHash = "a".repeat(64), archiveCbzSize = 10)
            assertEquals(ChapterRevisionDeliverability.UNUSABLE, revision(id).deliverability(), "$integrity")
        }

        // an inconclusive check is not a finding: it says nothing about where the payload is
        listOf(
            ChapterRevisionIntegrityState.NEVER_AUDITED,
            ChapterRevisionIntegrityState.VERIFIED,
            ChapterRevisionIntegrityState.AUDIT_FAILED,
        ).forEach { integrity ->
            val id = insertRevision(integrityState = integrity, archiveCbzHash = "a".repeat(64), archiveCbzSize = 10)
            assertEquals(ChapterRevisionDeliverability.DELIVERABLE, revision(id).deliverability(), "$integrity")
        }
    }

    @Test
    fun `a revision whose artifacts were not recorded cannot be delivered`() {
        val noPath = insertRevision(archiveCbzPath = null, archiveCbzHash = "a".repeat(64), archiveCbzSize = 10)
        val noHash = insertRevision(archiveCbzHash = null, archiveCbzSize = 10)
        val noSize = insertRevision(archiveCbzHash = "a".repeat(64), archiveCbzSize = null)

        listOf(noPath, noHash, noSize).forEach { id ->
            assertEquals(ChapterRevisionDeliverability.UNUSABLE, revision(id).deliverability())
        }
    }

    @Test
    fun `the direct command is one argument list that a shell can never split`() {
        val path = ChapterRevisionArchiveArtifacts.relativeCbzPath("a".repeat(64))

        assertEquals(
            listOf("rclone", "link", "remote:bucket/library/$path", "--expire", "300s"),
            RcloneArchiveLinkCommand.build("rclone", "remote:bucket/library", path, 300),
        )
        // a trailing slash on the configured root must not become a double separator in the spec
        assertEquals(
            listOf("rclone", "link", "remote:bucket/library/$path", "--expire", "60s"),
            RcloneArchiveLinkCommand.build("rclone", "remote:bucket/library/", path, 60),
        )

        val suspicious = RcloneArchiveLinkCommand.build("rclone", "remote:bucket; rm -rf /", path, 300)!!
        assertEquals(5, suspicious.size)
        assertTrue(suspicious[2].endsWith("/$path"))
        assertTrue(suspicious[3] == "--expire")
    }

    @Test
    fun `a command is never built for a target that cannot produce a location`() {
        val path = ChapterRevisionArchiveArtifacts.relativeCbzPath("a".repeat(64))

        listOf(
            Triple(" ", "remote:bucket", path),
            Triple("rclone", "  ", path),
            Triple("rclone", "remote:bucket", " "),
            // a local path would generate a location from the mounted copy this endpoint distrusts, and
            // a value starting with '-' would be read by rclone as one of its own options
            Triple("rclone", "/mnt/archive", path),
            Triple("rclone", "C:/mount", path),
            Triple("rclone", "--config=/tmp/rclone.conf", path),
            Triple("rclone", "not a remote at all", path),
        ).forEach { (executable, remote, cbzPath) ->
            assertNull(RcloneArchiveLinkCommand.build(executable, remote, cbzPath, 300), "$remote")
        }
    }

    @Test
    fun `only one absolute https location is accepted`() {
        val signed = "https://bucket.example/revisions/a/b.cbz?X-Amz-Signature=abc&X-Amz-Expires=300"
        assertEquals(signed, signedArchiveLocation(signed))
        assertEquals(signed, signedArchiveLocation("$signed\n"))

        listOf(
            "",
            "   ",
            "not a url",
            "http://bucket.example/object",
            "https://",
            "file:///etc/passwd",
            "/revisions/a.cbz",
            "https://user:secret@bucket.example/object",
            "https://bucket.example/object#fragment",
            "https://bucket.example/a b",
            "https://bucket.example/one\nhttps://bucket.example/two",
            "https://bucket.example/${"a".repeat(9000)}",
        ).forEach { answer ->
            assertNull(signedArchiveLocation(answer), answer.take(60))
        }
    }

    @Test
    fun `a link that could not be produced never carries a location`() {
        val leaked = "https://bucket.example/object?X-Amz-Signature=should-not-be-visible"
        val archived = archivedRevision()

        val failures =
            listOf(
                ArchiveRemoteCommandResult(startFailure = "rclone is missing"),
                ArchiveRemoteCommandResult(timedOut = true),
                ArchiveRemoteCommandResult(outputExceeded = true),
                ArchiveRemoteCommandResult(exitCode = 9, standardError = leaked),
                ArchiveRemoteCommandResult(exitCode = 0, standardOutput = "not a url"),
            )

        failures.forEachIndexed { index, result ->
            // a failure degrades to the mounted copy rather than reporting the answer rclone gave
            val outcome =
                runBlocking {
                    resolver(FakeRunner(result), directEnabled = true).resolve(archived.revisionId, directRequested = true)
                }

            assertTrue(outcome is ChapterRevisionDeliveryResolution.Local, "$index: $outcome")
            assertFalse(outcome.toString().contains("should-not-be-visible"))
        }

        // a location is accepted only when it really is one, and is then handed over as a redirect
        val location = awsSignedLocation()
        val answered = FakeRunner(ArchiveRemoteCommandResult(exitCode = 0, standardOutput = location))
        val direct =
            runBlocking { resolver(answered, directEnabled = true).resolve(archived.revisionId, directRequested = true) }

        assertEquals(ChapterRevisionDeliveryResolution.Direct(location), direct)
        assertEquals(1, answered.calls)
    }

    @Test
    fun `a location whose expiry cannot be proven is never handed to a client`() {
        val archived = archivedRevision()
        // a bare signature says nothing about how long the location stays valid, which is exactly what a
        // backend without expiry support answers when rclone asks it for --expire
        val unproven =
            signedLocation("X-Amz-Signature" to "abc")
        val runner = FakeRunner(ArchiveRemoteCommandResult(exitCode = 0, standardOutput = unproven))

        val fellBack = runBlocking { resolver(runner, directEnabled = true, fallback = true).resolve(archived.revisionId, true) }
        assertTrue(fellBack is ChapterRevisionDeliveryResolution.Local)
        assertEquals(1, runner.calls)

        // with no mounted copy permitted there is nothing left to serve, and a never-expiring credential
        // is still not served
        val refused = runBlocking { resolver(runner, directEnabled = true, fallback = false).resolve(archived.revisionId, true) }
        assertEquals(ChapterRevisionDeliveryResolution.Unavailable, refused)
    }

    @Test
    fun `an operator may accept a backend whose expiry parameters cannot be read here`() {
        // the opt-out is the operator's own statement about their backend, so the strict answer shape is
        // the only thing that still applies
        val location = signedLocation("X-Amz-Signature" to "abc")
        val runner = FakeRunner(ArchiveRemoteCommandResult(exitCode = 0, standardOutput = location))
        val archived = archivedRevision()

        val outcome =
            runBlocking {
                resolver(runner, directEnabled = true, requireExpiryEvidence = false).resolve(archived.revisionId, true)
            }

        assertEquals(ChapterRevisionDeliveryResolution.Direct(location), outcome)
    }

    @Test
    fun `only a signed query that proves a bounded expiry within the requested lifetime is accepted`() {
        val validity = { location: String -> ArchiveSignedUrlExpiry.provesBoundedExpiry(location, TEST_LIFETIME_SECONDS, TEST_NOW) }

        // AWS/R2/S3: the date is when the backend signed, the lifetime is what it granted
        assertTrue(validity(awsSignedLocation()), "a freshly signed aws location")
        assertTrue(validity(awsSignedLocation(issuedAtEpochSecond = TEST_NOW - 10, lifetimeSeconds = 290)), "signed moments ago")
        // a backend may round the requested duration up, and the tolerance is what "rounding" may be
        assertTrue(validity(awsSignedLocation(lifetimeSeconds = TEST_LIFETIME_SECONDS + 60)), "rounded up")
        assertFalse(validity(awsSignedLocation(lifetimeSeconds = TEST_LIFETIME_SECONDS + 61)), "longer than asked for")
        assertFalse(validity(awsSignedLocation(lifetimeSeconds = 86_400)), "a day instead of five minutes")
        assertFalse(validity(awsSignedLocation(issuedAtEpochSecond = TEST_NOW - 1_000)), "already expired")
        assertFalse(validity(awsSignedLocation(issuedAtEpochSecond = TEST_NOW + 3_600)), "signed in the future")
        assertFalse(validity(awsSignedLocation(issuedAtEpochSecond = 0)), "a date before any plausible signature")
        assertFalse(
            validity(signedLocation("X-Amz-Date" to basicDate(TEST_NOW), "X-Amz-Expires" to "300")),
            "a signature is missing",
        )
        assertFalse(
            validity(signedLocation("X-Amz-Date" to basicDate(TEST_NOW), "X-Amz-Signature" to "abc")),
            "an expiry is missing",
        )
        assertFalse(
            validity(signedLocation("X-Amz-Expires" to "300", "X-Amz-Signature" to "abc")),
            "a signature time is missing",
        )
        assertFalse(validity("https://bucket.example/object"), "no query at all")
        assertFalse(
            validity(signedLocation("X-Amz-Date" to "2026-01-01T00:00:00Z", "X-Amz-Expires" to "300", "X-Amz-Signature" to "abc")),
            "a date this family does not spell that way",
        )

        // a lifetime has to be a whole positive number of seconds, never a value that is read differently
        listOf("0", "-300", "+300", "300.5", " 300", "3e2", "").forEach { value ->
            assertFalse(
                validity(signedLocation("X-Amz-Date" to basicDate(TEST_NOW), "X-Amz-Expires" to value, "X-Amz-Signature" to "abc")),
                "expires=$value",
            )
        }

        // a parameter this check interprets must appear exactly once, in exactly one casing
        assertFalse(
            validity(
                signedLocation(
                    "X-Amz-Date" to basicDate(TEST_NOW),
                    "X-Amz-Expires" to "300",
                    "X-Amz-Signature" to "abc",
                    "x-amz-expires" to "300",
                ),
            ),
            "the same expiry stated twice",
        )
        assertFalse(
            validity(
                signedLocation(
                    "X-Amz-Date" to basicDate(TEST_NOW),
                    "X-Amz-Expires" to "300",
                    "X-Amz-Signature" to "abc",
                    "X-Goog-Signature" to "abc",
                ),
            ),
            "two signing families at once",
        )

        // Google spells the same three parameters under its own prefix
        val googleValid =
            signedLocation(
                "X-Goog-Date" to basicDate(TEST_NOW),
                "X-Goog-Expires" to "300",
                "X-Goog-Signature" to "abc",
            )
        assertEquals(true, validity(googleValid), "a freshly signed google location")
        assertFalse(
            validity(
                signedLocation(
                    "X-Goog-Date" to basicDate(TEST_NOW - 10_000),
                    "X-Goog-Expires" to "300",
                    "X-Goog-Signature" to "abc",
                ),
            ),
            "an expired google location",
        )

        // Azure states one absolute expiry instead of a lifetime
        val azureValid =
            signedLocation(
                "se" to Instant.ofEpochSecond(TEST_NOW + TEST_LIFETIME_SECONDS).toString(),
                "sig" to "abc",
            )
        assertEquals(true, validity(azureValid), "a bounded azure sas")
        assertFalse(
            validity(signedLocation("se" to Instant.ofEpochSecond(TEST_NOW + 86_400).toString(), "sig" to "abc")),
            "an azure sas that outlives what was asked for",
        )
        assertFalse(
            validity(signedLocation("se" to Instant.ofEpochSecond(TEST_NOW - 3_600).toString(), "sig" to "abc")),
            "an expired azure sas",
        )
        assertFalse(
            validity(signedLocation("se" to "not-a-time", "sig" to "abc")),
            "an azure expiry that is not a time",
        )
        // the signature is as much a part of the proof as the expiry is
        assertFalse(
            validity(signedLocation("se" to Instant.ofEpochSecond(TEST_NOW + 300).toString())),
            "an azure sas without a signature",
        )
        assertFalse(validity(signedLocation("sig" to "abc")), "an azure signature without an expiry")
    }

    @Test
    fun `a requested lifetime is what bounds what a backend may answer`() {
        // the same location is boundedly short for one lifetime and unbounded for another
        assertEquals(true, ArchiveSignedUrlExpiry.provesBoundedExpiry(awsSignedLocation(), 300, TEST_NOW))
        assertEquals(false, ArchiveSignedUrlExpiry.provesBoundedExpiry(awsSignedLocation(), 60, TEST_NOW))
    }

    @Test
    fun `a cancelled link request is carried on rather than answered as a failure`() {
        val cancelling =
            object : ArchiveRemoteCommandRunner {
                override suspend fun run(
                    command: List<String>,
                    timeout: Duration,
                ): ArchiveRemoteCommandResult = throw CancellationException("stopped")
            }

        val generator =
            RcloneArchiveLinkGenerator(
                executable = { "rclone" },
                remoteRoot = { "remote:bucket/library" },
                expirySeconds = { TEST_LIFETIME_SECONDS },
                timeout = { 30.seconds },
                runner = cancelling,
            )

        val propagated =
            runBlocking {
                runCatching { generator.link(listOf("rclone", "link", "remote:bucket/x", "--expire", "300s")) }
                    .exceptionOrNull()
            }

        // a request or a server that is being stopped must not be turned into a served fallback copy
        assertTrue(propagated is CancellationException, "$propagated")
    }

    @Test
    fun `the mounted copy is verified off the resolving thread`() {
        val archived = archivedRevision()
        val offloaded = AtomicBoolean(false)
        val dispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) {
                    offloaded.set(true)
                    Dispatchers.IO.dispatch(context, block)
                }
            }

        val outcome = runBlocking { resolver(ioDispatcher = dispatcher).resolve(archived.revisionId, true) }

        // reading and hashing a whole artifact is blocking work, so it may not run where the request was
        // resolved; the check itself is unchanged by having moved
        assertTrue(offloaded.get(), "the artifact was verified without being offloaded")
        assertEquals(archived.file.canonicalPath, localFile(outcome).canonicalPath)
        assertEquals(archived.content.size.toLong(), (outcome as ChapterRevisionDeliveryResolution.Local).size)
    }

    @Test
    fun `a GET is redirected to the generated location`() {
        val location = awsSignedLocation()
        val runner = FakeRunner(ArchiveRemoteCommandResult(exitCode = 0, standardOutput = location))
        val archived = archivedRevision()

        val outcome =
            runBlocking { resolver(runner, directEnabled = true).resolve(archived.revisionId, directRequested = true) }

        assertEquals(ChapterRevisionDeliveryResolution.Direct(location), outcome)
        assertEquals(1, runner.calls)
        assertEquals(
            listOf(
                "rclone",
                "link",
                "remote:bucket/library/${ChapterRevisionArchiveArtifacts.relativeCbzPath(archived.candidateKey)}",
                "--expire",
                "300s",
            ),
            runner.commands.single(),
        )
    }

    @Test
    fun `direct delivery that is disabled or failing falls back to the mounted copy`() {
        val archived = archivedRevision()
        val disabled = FakeRunner(ArchiveRemoteCommandResult(exitCode = 0, standardOutput = "https://bucket.example/x"))

        val local =
            runBlocking { resolver(disabled, directEnabled = false).resolve(archived.revisionId, true) }
        assertEquals(archived.file.canonicalPath, localFile(local).canonicalPath)
        assertEquals(archived.content.size.toLong(), (local as ChapterRevisionDeliveryResolution.Local).size)
        // a disabled feature must not start a process at all
        assertEquals(0, disabled.calls)

        val failing = FakeRunner(ArchiveRemoteCommandResult(exitCode = 9))
        val fellBack =
            runBlocking { resolver(failing, directEnabled = true, fallback = true).resolve(archived.revisionId, true) }
        assertTrue(fellBack is ChapterRevisionDeliveryResolution.Local)
        assertEquals(1, failing.calls)

        val refused =
            runBlocking { resolver(failing, directEnabled = true, fallback = false).resolve(archived.revisionId, true) }
        assertEquals(ChapterRevisionDeliveryResolution.Unavailable, refused)
    }

    @Test
    fun `a HEAD is never redirected, and is refused when only a redirect could serve it`() {
        val runner = FakeRunner(ArchiveRemoteCommandResult(exitCode = 0, standardOutput = "https://bucket.example/x"))
        val archived = archivedRevision()

        // a generated location is a credential for a GET, so a HEAD cannot follow it
        val methodNotAllowed =
            runBlocking { resolver(runner, directEnabled = true, fallback = false).resolve(archived.revisionId, false) }
        assertEquals(ChapterRevisionDeliveryResolution.MethodNotAllowed, methodNotAllowed)
        assertEquals(0, runner.calls)

        // with the mounted copy permitted, a HEAD is answered from it instead
        val local = runBlocking { resolver(runner, directEnabled = true, fallback = true).resolve(archived.revisionId, false) }
        assertTrue(local is ChapterRevisionDeliveryResolution.Local)
        assertEquals(0, runner.calls)
    }

    @Test
    fun `a mounted copy that is not the recorded artifact is refused`() {
        val archived = archivedRevision()

        // the bytes are not the ones the archive recorded
        archived.file.writeBytes("tampered".toByteArray())
        assertEquals(
            ChapterRevisionDeliveryResolution.Unusable,
            runBlocking { resolver().resolve(archived.revisionId, true) },
        )

        // the recorded length does not match the file
        archived.file.writeBytes(archived.content)
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq archived.revisionId }) {
                it[archiveCbzSize] = archived.content.size.toLong() + 1
            }
        }
        assertEquals(
            ChapterRevisionDeliveryResolution.Unusable,
            runBlocking { resolver().resolve(archived.revisionId, true) },
        )

        // and a stored location that is not the canonical one for this revision is never followed
        transaction {
            ChapterRevisionTable.update({ ChapterRevisionTable.id eq archived.revisionId }) {
                it[archiveCbzPath] = "revisions/../../escape.cbz"
                it[archiveCbzSize] = archived.content.size.toLong()
            }
        }
        assertEquals(
            ChapterRevisionDeliveryResolution.Unusable,
            runBlocking { resolver().resolve(archived.revisionId, true) },
        )
    }

    @Test
    fun `the mounted copy is refused when it has been removed`() {
        val archived = archivedRevision()
        assertTrue(archived.file.delete())

        assertEquals(
            ChapterRevisionDeliveryResolution.Unusable,
            runBlocking { resolver().resolve(archived.revisionId, true) },
        )
    }

    @Test
    fun `an unknown revision is not found and a candidate is never delivered`() {
        assertEquals(
            ChapterRevisionDeliveryResolution.NotFound,
            runBlocking { resolver().resolve(Int.MAX_VALUE, true) },
        )

        val candidate = insertRevision(disposition = ChapterRevisionDisposition.CANDIDATE)
        assertEquals(
            ChapterRevisionDeliveryResolution.NotFound,
            runBlocking { resolver().resolve(candidate, true) },
        )
    }

    @Test
    fun `the download URL is this server's own route and never the stored location`() {
        val archived = archivedRevision()
        val url = exposedType(archived.revisionId).downloadUrl

        assertEquals("/api/v1/archive/revisions/${archived.revisionId}/download", url)
        // nothing that describes storage may reach a client: not a path, not a key, not a credential
        assertTrue(url!!.startsWith("/api/v1/"))
        assertFalse(url.contains("?"))
        assertFalse(url.contains(archived.candidateKey))
        // the advertised address is never an absolute location, so no signed URL can reach a client
        // through it and nothing it holds can be stored or shared
        assertFalse(url.contains("http"))

        val candidate = insertRevision(disposition = ChapterRevisionDisposition.CANDIDATE)
        // no URL may be advertised for a revision the download route itself would refuse
        assertNull(exposedType(candidate).downloadUrl)

        val pending =
            insertRevision(
                archiveState = ChapterArchiveState.REMOTE_PENDING,
                archiveCbzHash = "a".repeat(64),
                archiveCbzSize = 1,
            )
        assertNull(exposedType(pending).downloadUrl)
    }

    /** One revision as the GraphQL layer exposes it, built from the row the way the schema does. */
    private fun exposedType(revisionId: Int): ChapterRevisionType =
        transaction {
            ChapterRevisionType(
                ChapterRevisionTable.selectAll().where { ChapterRevisionTable.id eq revisionId }.first(),
            )
        }

    @Test
    fun `a data class and the type it is exposed through agree on deliverability`() {
        val archived = archivedRevision()
        val dataClass = revision(archived.revisionId)

        assertEquals(ChapterRevisionDeliverability.DELIVERABLE, dataClass.deliverability())
        assertEquals(
            ChapterRevisionDeliverability.DELIVERABLE,
            chapterRevisionDeliverability(
                disposition = dataClass.disposition,
                archiveState = dataClass.archiveState,
                archiveCbzPath = dataClass.archiveCbzPath,
                archiveCbzHash = dataClass.archiveCbzHash,
                archiveCbzSize = dataClass.archiveCbzSize,
                retentionState = dataClass.retentionState,
                deletedAt = dataClass.deletedAt,
                integrityState = dataClass.integrityState,
            ),
        )

        // the two entry points are one rule, so they can never disagree
        val pruned = insertRevision(retentionState = ChapterRetentionState.PRUNED, archiveCbzHash = "a".repeat(64), archiveCbzSize = 1)
        val prunedData = revision(pruned)
        assertEquals(ChapterRevisionDeliverability.UNUSABLE, prunedData.deliverability())
        assertNotEquals(ChapterRevisionDeliverability.DELIVERABLE, prunedData.deliverability())
    }

    @AfterEach
    fun cleanup() {
        clearTables(ChapterRevisionTable)
    }
}
