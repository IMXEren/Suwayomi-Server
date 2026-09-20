package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * What a direct check of an archived artifact found.
 *
 * This is a typed result rather than a message, because the audit has to tell four different facts
 * apart and each of them has its own durable consequence: the payload is exactly what the archive
 * recorded, the payload is confirmed absent, the payload is present but not what was recorded, or the
 * check itself could not conclude anything. Only [Missing] and [Corrupt] are findings; [Retryable]
 * and [Failed] say something about the *check*, never about the archive.
 *
 * [Corrupt] is reserved for evidence that the object itself is wrong - a size or digest mismatch, or
 * a digest a backend reported that is not a digest at all. Everything else that leaves the archive's
 * state unknown is [Failed], so a permission error never masquerades as a damaged payload.
 */
sealed interface ArchiveIntegrityVerification {
    /** No verifier is configured, so nothing about remote storage may be inferred. */
    data object Unavailable : ArchiveIntegrityVerification

    /** Both archived objects exist and match the sizes and digests the archive recorded. */
    data object Verified : ArchiveIntegrityVerification

    /**
     * The archived payload is confirmed absent from remote storage.
     *
     * Only a backend which positively reports "not there" produces this: a path that merely is not
     * listed yet is [Retryable], because reporting a missing payload that is still uploading would
     * turn a propagation delay into a false integrity finding.
     */
    data class Missing(
        val reason: String,
    ) : ArchiveIntegrityVerification

    /** The archived payload is there but is not the payload the archive recorded. */
    data class Corrupt(
        val reason: String,
    ) : ArchiveIntegrityVerification

    /** The check could not conclude anything yet, so it is worth another attempt later. */
    data class Retryable(
        val reason: String,
    ) : ArchiveIntegrityVerification

    /** The check failed in a way that another attempt of the same command would not fix. */
    data class Failed(
        val reason: String,
    ) : ArchiveIntegrityVerification
}

/**
 * Checks one archived revision against remote storage.
 *
 * Symmetric to [ArchiveCommitVerifier] and [ArchiveDeletionVerifier], and deliberately behind its own
 * boundary: the audit decides *when* and *in what order* revisions are checked, while the backend
 * decides *how* an object is looked up. The default implementation never claims anything, so a
 * deployment without a configured remote leaves every revision at NEVER_AUDITED instead of reporting
 * findings nobody verified.
 */
fun interface ArchiveIntegrityVerifier {
    suspend fun check(artifact: ChapterRevisionArchiveArtifact): ArchiveIntegrityVerification

    /**
     * Confirms whether the archived payload is really gone.
     *
     * Used for the one decision a check cannot make on its own: once the attempts a session allows are
     * exhausted on an inconclusive check, only a positive absence answer justifies recording a missing
     * payload - anything else stays a failed check. The default answers "nothing was attempted", so an
     * implementation that cannot tell absence apart never produces a finding.
     */
    suspend fun confirmAbsent(artifact: ChapterRevisionArchiveArtifact): ArchiveAbsenceVerification =
        ArchiveAbsenceVerification.NotAttempted

    companion object {
        val NOT_CONFIGURED = ArchiveIntegrityVerifier { ArchiveIntegrityVerification.Unavailable }
    }
}

/**
 * Checks integrity through the same direct-remote rclone boundary durability verification uses.
 *
 * Deliberately a thin adapter rather than a second rclone client: the command, its argument safety,
 * the output bound and the redaction of the configured remote all have to stay identical to the check
 * that confirmed the artifact in the first place, so the audit reuses them instead of restating them.
 */
class RcloneArchiveIntegrityVerifier(
    private val verifier: RcloneArchiveCommitVerifier,
) : ArchiveIntegrityVerifier {
    override suspend fun check(artifact: ChapterRevisionArchiveArtifact): ArchiveIntegrityVerification = verifier.verifyIntegrity(artifact)

    override suspend fun confirmAbsent(artifact: ChapterRevisionArchiveArtifact): ArchiveAbsenceVerification =
        verifier.verifyAbsent(artifact)
}
