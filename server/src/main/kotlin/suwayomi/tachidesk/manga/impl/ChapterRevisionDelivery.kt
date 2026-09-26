package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import suwayomi.tachidesk.manga.model.dataclass.ChapterArchiveState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRetentionState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDisposition
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionIntegrityState
import java.io.File
import java.time.Instant
import kotlin.time.Duration

/**
 * Whether the immutable archived CBZ of a revision may be delivered, and why it may not be.
 *
 * Two different facts are kept apart on purpose. [MISSING] says there is nothing here to deliver at
 * all - a candidate, a rejected or an unchanged revision is not a readable edition of a chapter - while
 * [UNUSABLE] says the revision is one the archive accepted, but the bytes it would serve are gone,
 * being removed, or were found wrong by the last integrity check.
 */
enum class ChapterRevisionDeliverability {
    DELIVERABLE,
    MISSING,
    UNUSABLE,
}

/**
 * The delivery classification of one revision, decided from the recorded fields it depends on.
 *
 * The data class and the GraphQL type both carry these fields and neither is built from the other, so the
 * rule is spelled once here and both call it: a download URL is therefore never advertised for a revision
 * that the download route itself would refuse.
 */
fun chapterRevisionDeliverability(
    disposition: ChapterRevisionDisposition,
    archiveState: ChapterArchiveState,
    archiveCbzPath: String?,
    archiveCbzHash: String?,
    archiveCbzSize: Long?,
    retentionState: ChapterRetentionState,
    deletedAt: Long?,
    integrityState: ChapterRevisionIntegrityState,
): ChapterRevisionDeliverability =
    when {
        disposition != ChapterRevisionDisposition.ACCEPTED &&
            disposition != ChapterRevisionDisposition.SUPERSEDED -> {
            ChapterRevisionDeliverability.MISSING
        }

        archiveState != ChapterArchiveState.REMOTE_CONFIRMED -> {
            ChapterRevisionDeliverability.UNUSABLE
        }

        archiveCbzPath == null || archiveCbzHash == null || archiveCbzSize == null -> {
            ChapterRevisionDeliverability.UNUSABLE
        }

        // a payload whose deletion started is meant to disappear, so it is not a readable copy any more
        deletedAt != null || retentionState in retentionPayloadGoneStates -> {
            ChapterRevisionDeliverability.UNUSABLE
        }

        integrityState.isFinding -> {
            ChapterRevisionDeliverability.UNUSABLE
        }

        else -> {
            ChapterRevisionDeliverability.DELIVERABLE
        }
    }

/**
 * Classifies one revision's archived CBZ for delivery.
 *
 * Everything it looks at is the archive's own recorded state, so it is answerable without touching
 * storage. See [chapterRevisionDeliverability] for the rule itself.
 */
fun ChapterRevisionDataClass.deliverability(): ChapterRevisionDeliverability =
    chapterRevisionDeliverability(
        disposition = disposition,
        archiveState = archiveState,
        archiveCbzPath = archiveCbzPath,
        archiveCbzHash = archiveCbzHash,
        archiveCbzSize = archiveCbzSize,
        retentionState = retentionState,
        deletedAt = deletedAt,
        integrityState = integrityState,
    )

/**
 * The API address of one revision's archived CBZ.
 *
 * The address is this server's own route and never a location the bytes are kept at: a direct remote
 * location is a short-lived bearer credential for the payload, so it is only ever produced by the
 * request that follows this address, and never handed out as part of a URL that a client may store,
 * log or share.
 */
object ChapterRevisionDeliveryRoutes {
    private const val BASE = "/api/v1/archive/revisions"

    fun downloadPath(revisionId: Int): String = "$BASE/$revisionId/download"
}

/**
 * What a request for one revision's archived CBZ resolved to.
 *
 * Failure is a value rather than a message on purpose: what is stored, where it is stored and why a
 * location could not be produced are all facts about this server's storage, and none of them belongs in
 * a response. A client is told only which of these four things happened.
 */
sealed interface ChapterRevisionDeliveryResolution {
    /**
     * A GET-only location the client is redirected to. Never logged or persisted.
     *
     * The location is bounded in time only when the remote that produced it honours the requested
     * expiry: the signed query is required to state that expiry before this outcome is reached, unless
     * the operator turned that requirement off for a backend known to bound links on its own.
     */
    data class Direct(
        val location: String,
    ) : ChapterRevisionDeliveryResolution

    /** The immutable mounted archive copy, already verified against its recorded size and digest. */
    data class Local(
        val file: File,
        val size: Long,
    ) : ChapterRevisionDeliveryResolution

    /** nothing may be delivered at that address */
    data object NotFound : ChapterRevisionDeliveryResolution

    /** the revision exists, but the payload it would serve is gone, wrong or being removed */
    data object Unusable : ChapterRevisionDeliveryResolution

    /** no direct location could be produced and the mounted copy may not be used */
    data object Unavailable : ChapterRevisionDeliveryResolution

    /**
     * Only a redirect could serve this request, and a redirect cannot carry the request's method.
     *
     * A generated location is a credential for a GET, so a HEAD cannot be answered by following it.
     */
    data object MethodNotAllowed : ChapterRevisionDeliveryResolution
}

/** The outcome of asking the configured remote for a link to one archived CBZ. */
sealed interface ArchiveDirectLink {
    /**
     * One absolute HTTPS location, and - unless the operator opted out - one whose signed query states
     * its own bounded validity. Whether the backend honoured the requested expiry is therefore a fact
     * this value carries, not an assumption about the flag that was passed to rclone.
     */
    data class Generated(
        val location: String,
    ) : ArchiveDirectLink

    /**
     * No link was produced: the command failed, timed out, was stopped, or did not answer with exactly
     * one absolute HTTPS location. The reason is deliberately not carried anywhere near a response.
     */
    data object Failed : ArchiveDirectLink
}

/**
 * The exact `rclone link` invocation for one archived CBZ, or null when no command may be run.
 *
 * The command is an argument list, so `ProcessBuilder` can never hand any part of it to a shell and the
 * remote spec and the path can never be reinterpreted as options or as a second command. A blank or
 * malformed remote and a blank executable produce no command at all rather than a command that fails,
 * so a misconfiguration can never start a process.
 *
 * `link` is given the direct remote spec of the CBZ itself and `--expire`, whose value is a Go
 * duration: a number of seconds therefore carries the `s` unit, because a bare number is not a
 * duration rclone accepts. The official command therefore stays `rclone link remote:path --expire 300s`.
 *
 * The flag is a request only. rclone's own documentation states that a backend which does not support
 * expiry ignores `--expire` and returns a link that never expires, which is why what comes back out of
 * this command is verified by [ArchiveSignedUrlExpiry] before it is ever handed to a client.
 */
internal object RcloneArchiveLinkCommand {
    fun build(
        executable: String,
        remoteRoot: String,
        cbzRelativePath: String,
        expirySeconds: Long,
    ): List<String>? {
        val executableValue = executable.trim().takeIf { it.isNotEmpty() } ?: return null
        val remoteValue = remoteRoot.trim().takeIf { it.isNotEmpty() } ?: return null
        if (cbzRelativePath.isBlank()) return null

        return runCatching {
            listOf(
                requireSafeExecutable(executableValue),
                "link",
                "${requireSafeRemoteSpec(remoteValue)}/$cbzRelativePath",
                "--expire",
                "${expirySeconds.coerceAtLeast(1)}s",
            )
        }.getOrNull()
    }
}

/** Longest location that is still plausibly one signed URL rather than a wall of output. */
private const val MAX_SIGNED_LOCATION_LENGTH = 8192

/**
 * The single absolute HTTPS location [output] holds, or null when it holds anything else.
 *
 * rclone prints the link and nothing else, so an answer that is not exactly one such URL is refused
 * rather than trimmed into shape: a wrapped, repeated or decorated line would otherwise be published to
 * a client as a location this server never meant to hand out. A relative or non-HTTPS value, a value
 * naming credentials in front of its host, and a value carrying a fragment are all refused too - the
 * first two because they are not the remote's own address, the last because a client that does not
 * encode it would truncate the signed query it belongs to.
 */
internal fun signedArchiveLocation(output: String): String? {
    val candidate = output.trim()
    if (candidate.isEmpty() || candidate.length > MAX_SIGNED_LOCATION_LENGTH) return null
    if (candidate.any { it.isWhitespace() || it.isISOControl() }) return null
    if (candidate.contains('#')) return null

    val url = candidate.toHttpUrlOrNull() ?: return null
    if (!url.isHttps) return null
    if (url.host.isBlank()) return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    if (url.fragment != null) return null

    return candidate
}

/**
 * Asks the configured rclone remote for a link to one archived CBZ.
 *
 * The remote is addressed directly, exactly as the durability verifier addresses it, so a location is
 * never generated from the mounted copy this endpoint exists to distrust. Nothing about the answer is
 * persisted or logged, not even on failure: the location is a bearer credential for the payload until
 * it expires, so only the fact that a location could or could not be produced is ever recorded, and
 * diagnostics are static strings that cannot carry rclone's own output.
 *
 * A location is only accepted when its own signed query proves a bounded validity within the lifetime
 * this server asked for, because the `--expire` flag is a request a backend is free to ignore. A remote
 * that cannot prove it degrades to the mounted copy or to an unavailable answer, never to a
 * never-expiring credential. [requireExpiryEvidence] is the operator's explicit opt-out for a backend
 * whose links are known to be bounded even though their parameters cannot be read here.
 */
class RcloneArchiveLinkGenerator(
    private val executable: () -> String,
    private val remoteRoot: () -> String,
    private val expirySeconds: () -> Long,
    private val timeout: () -> Duration,
    private val requireExpiryEvidence: () -> Boolean = { true },
    private val now: () -> Long = { Instant.now().epochSecond },
    private val runner: ArchiveRemoteCommandRunner = ProcessArchiveRemoteCommandRunner,
) {
    private val logger = KotlinLogging.logger {}

    /** The command that would be run for [cbzRelativePath], or null when none may be run. */
    fun commandFor(cbzRelativePath: String): List<String>? =
        RcloneArchiveLinkCommand.build(executable(), remoteRoot(), cbzRelativePath, expirySeconds())

    suspend fun link(command: List<String>): ArchiveDirectLink {
        val result =
            try {
                runner.run(command, timeout())
            } catch (e: CancellationException) {
                // the request or the server is being stopped: that is the caller's own outcome to
                // observe, so it is carried on rather than turned into a failed link
                throw e
            } catch (e: Exception) {
                // A runner that throws has still not produced a location; the exception itself could
                // describe the configured remote, so only its class is recorded
                logger.warn { "A direct download location could not be requested (${e::class.java.simpleName})" }
                return ArchiveDirectLink.Failed
            }

        when {
            result.startFailure != null -> {
                logger.warn { "A direct download location could not be requested: rclone could not be started" }
                return ArchiveDirectLink.Failed
            }

            result.timedOut -> {
                logger.warn { "A direct download location could not be requested: rclone did not answer in time" }
                return ArchiveDirectLink.Failed
            }

            result.outputExceeded -> {
                logger.warn { "A direct download location could not be requested: rclone wrote too much output" }
                return ArchiveDirectLink.Failed
            }

            result.exitCode != 0 -> {
                logger.warn { "A direct download location could not be requested: rclone exited ${result.exitCode}" }
                return ArchiveDirectLink.Failed
            }
        }

        val location = signedArchiveLocation(result.standardOutput)
        if (location == null) {
            logger.warn { "A direct download location could not be requested: rclone did not answer with one URL" }
            return ArchiveDirectLink.Failed
        }

        if (requireExpiryEvidence() &&
            !ArchiveSignedUrlExpiry.provesBoundedExpiry(
                location = location,
                requestedLifetimeSeconds = expirySeconds(),
                now = now(),
            )
        ) {
            // the backend ignored --expire, or cannot state an expiry this server can read: handing this
            // to a client would hand out a credential that never becomes invalid
            logger.warn { "A direct download location could not be requested: its expiry could not be verified" }
            return ArchiveDirectLink.Failed
        }

        return ArchiveDirectLink.Generated(location)
    }
}

/**
 * Resolves one request for the archived CBZ of a revision.
 *
 * Direct delivery and local delivery are alternatives for the same bytes, and which one answers depends
 * on three things read at request time: whether direct delivery is enabled, whether a location could
 * actually be produced, and whether the mounted copy may be used instead. A configuration that cannot
 * produce a command is treated as direct delivery being unavailable rather than as a failure, so a blank
 * remote never starts a process and never turns into an error either.
 *
 * The mounted copy is never trusted on its own. Its location is rebuilt from the immutable candidate key
 * and required to equal the recorded value, it is resolved as a canonical path inside the archive root,
 * and its exact recorded size and SHA-256 are verified on every request before a byte is served. A copy
 * that does not match what the archive recorded is refused rather than streamed, because a mismatch
 * means the file is not the revision this revision row describes.
 *
 * That verification reads and hashes a whole CBZ, which is blocking filesystem work: it therefore runs
 * on [ioDispatcher] rather than on whichever thread the request was resolved on, so hashing a large
 * artifact cannot hold up the event loop that is answering other clients.
 */
class ChapterRevisionDeliveryResolver(
    private val archiveRoot: () -> File,
    private val directDeliveryEnabled: () -> Boolean,
    private val fallbackToLocal: () -> Boolean,
    private val linker: RcloneArchiveLinkGenerator,
    private val revisionOf: (Int) -> ChapterRevisionDataClass? = ChapterRevision::getRevision,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun resolve(
        revisionId: Int,
        /** true for a request whose method a redirect can carry, i.e. a GET */
        directRequested: Boolean,
    ): ChapterRevisionDeliveryResolution {
        val revision = revisionOf(revisionId) ?: return ChapterRevisionDeliveryResolution.NotFound

        when (revision.deliverability()) {
            ChapterRevisionDeliverability.MISSING -> return ChapterRevisionDeliveryResolution.NotFound
            ChapterRevisionDeliverability.UNUSABLE -> return ChapterRevisionDeliveryResolution.Unusable
            ChapterRevisionDeliverability.DELIVERABLE -> Unit
        }

        val cbzRelativePath = canonicalCbzPathOf(revision) ?: return ChapterRevisionDeliveryResolution.Unusable
        val command = if (directDeliveryEnabled()) linker.commandFor(cbzRelativePath) else null

        if (directRequested) {
            if (command == null) {
                return if (fallbackToLocal()) local(revision, cbzRelativePath) else ChapterRevisionDeliveryResolution.Unavailable
            }

            return when (val link = linker.link(command)) {
                is ArchiveDirectLink.Generated -> {
                    ChapterRevisionDeliveryResolution.Direct(link.location)
                }

                ArchiveDirectLink.Failed -> {
                    if (fallbackToLocal()) local(revision, cbzRelativePath) else ChapterRevisionDeliveryResolution.Unavailable
                }
            }
        }

        return when {
            fallbackToLocal() -> local(revision, cbzRelativePath)
            command != null -> ChapterRevisionDeliveryResolution.MethodNotAllowed
            else -> ChapterRevisionDeliveryResolution.Unavailable
        }
    }

    /**
     * The portable location of the archived CBZ, or null when the recorded one is not the canonical one.
     *
     * The path is derived from the immutable candidate key and compared with the stored value, so a
     * stored path can only ever address a CBZ of exactly this revision, however the column was filled in.
     */
    private fun canonicalCbzPathOf(revision: ChapterRevisionDataClass): String? {
        val recorded = revision.archiveCbzPath ?: return null
        val expected =
            runCatching { ChapterRevisionArchiveArtifacts.relativeCbzPath(revision.candidateKey) }.getOrNull()
                ?: return null

        return expected.takeIf { it == recorded }
    }

    /**
     * The verified mounted copy, or the reason it may not be served.
     *
     * Reading and hashing the artifact is blocking work, so it is moved off the resolving thread before
     * anything is opened.
     */
    private suspend fun local(
        revision: ChapterRevisionDataClass,
        cbzRelativePath: String,
    ): ChapterRevisionDeliveryResolution =
        withContext(ioDispatcher) {
            verifyLocalCopy(revision, cbzRelativePath)
        }

    /** The mounted copy once its recorded size and digest have been checked against what is on disk. */
    private fun verifyLocalCopy(
        revision: ChapterRevisionDataClass,
        cbzRelativePath: String,
    ): ChapterRevisionDeliveryResolution {
        val file = containedFile(archiveRoot(), cbzRelativePath) ?: return ChapterRevisionDeliveryResolution.Unusable
        if (!file.isFile) return ChapterRevisionDeliveryResolution.Unusable

        val recordedSize = revision.archiveCbzSize ?: return ChapterRevisionDeliveryResolution.Unusable
        if (file.length() != recordedSize) return ChapterRevisionDeliveryResolution.Unusable

        val recordedHash = revision.archiveCbzHash ?: return ChapterRevisionDeliveryResolution.Unusable
        val digest =
            runCatching { ChapterRevisionArchiveArtifacts.digestOf(file) }.getOrNull()
                ?: return ChapterRevisionDeliveryResolution.Unusable
        if (!digest.sha256.equals(recordedHash, ignoreCase = true)) return ChapterRevisionDeliveryResolution.Unusable

        return ChapterRevisionDeliveryResolution.Local(file, digest.size)
    }
}
