package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.BackupRestoreJobType
import suwayomi.tachidesk.manga.impl.backup.proto.BackupRestoreExecutor
import suwayomi.tachidesk.manga.impl.backup.proto.BackupRestoreJob

/**
 * Control over durable `.tachibk` restores.
 *
 * Every operation is explicit because every one of them changes a library: a restore is queued by an
 * upload, and from then on it is retried, cancelled or cleaned up on purpose - never implicitly.
 */
class BackupRestoreMutation {
    data class BackupRestoreJobInput(
        val clientMutationId: String? = null,
        val restoreId: String,
    )

    data class BackupRestoreJobPayload(
        val clientMutationId: String?,
        val job: BackupRestoreJobType?,
        val error: String?,
    )

    /**
     * Returns a failed or cancelled restore to the queue.
     *
     * The series the previous attempt already applied are kept: the retry continues from the recorded
     * position instead of importing the library again.
     */
    @RequireAuth
    fun retryBackupRestore(input: BackupRestoreJobInput): BackupRestoreJobPayload {
        val job =
            BackupRestoreJob.get(input.restoreId)
                ?: return failure(input, "no backup restore with id ${input.restoreId}")

        if (!job.stagedFileRetained) {
            return failure(input, "the staged backup of this restore is no longer available")
        }

        if (!BackupRestoreJob.retry(input.restoreId)) {
            return failure(input, "backup restore ${input.restoreId} is not retryable in state ${job.state}")
        }

        BackupRestoreExecutor.notifyWorkAvailable()
        return success(input, input.restoreId)
    }

    /** Abandons a restore. A running one stops at its next series boundary. */
    @RequireAuth
    fun cancelBackupRestore(input: BackupRestoreJobInput): BackupRestoreJobPayload {
        val job =
            BackupRestoreJob.get(input.restoreId)
                ?: return failure(input, "no backup restore with id ${input.restoreId}")

        if (!BackupRestoreJob.cancel(input.restoreId)) {
            return failure(input, "backup restore ${input.restoreId} is not cancellable in state ${job.state}")
        }

        return success(input, input.restoreId)
    }

    /**
     * Deletes the staged payload of a finished restore.
     *
     * Refused while the restore still needs it: a queued or running import, an import that failed or
     * was cancelled, and a handoff that is blocked are all cases where the payload is the only way to
     * continue.
     */
    @RequireAuth
    fun cleanupBackupRestore(input: BackupRestoreJobInput): BackupRestoreJobPayload {
        val job =
            BackupRestoreJob.get(input.restoreId)
                ?: return failure(input, "no backup restore with id ${input.restoreId}")

        if (!job.stagedFileRetained) {
            return success(input, input.restoreId)
        }

        if (!BackupRestoreExecutor.cleanupStaged(input.restoreId)) {
            return failure(
                input,
                "the staged backup of restore ${input.restoreId} is still needed in state ${job.state} " +
                    "and handoff ${job.handoffState}",
            )
        }

        return success(input, input.restoreId)
    }

    /**
     * Starts the archive bootstrap of an import that succeeded but could not hand over.
     *
     * The import is never repeated: this only retries the bootstrap that the previous attempt could not
     * start because another run was active.
     */
    @RequireAuth
    fun retryBackupRestoreHandoff(input: BackupRestoreJobInput): BackupRestoreJobPayload {
        val job =
            BackupRestoreJob.get(input.restoreId)
                ?: return failure(input, "no backup restore with id ${input.restoreId}")

        if (!BackupRestoreExecutor.retryHandoff(input.restoreId)) {
            return failure(
                input,
                "the handoff of restore ${input.restoreId} is not retryable in state ${job.state} " +
                    "and handoff ${job.handoffState}",
            )
        }

        return success(input, input.restoreId)
    }

    private fun success(
        input: BackupRestoreJobInput,
        restoreId: String,
    ): BackupRestoreJobPayload =
        BackupRestoreJobPayload(
            clientMutationId = input.clientMutationId,
            job = BackupRestoreJob.get(restoreId)?.let { BackupRestoreJobType(it) },
            error = null,
        )

    private fun failure(
        input: BackupRestoreJobInput,
        error: String,
    ): BackupRestoreJobPayload =
        BackupRestoreJobPayload(
            clientMutationId = input.clientMutationId,
            job = BackupRestoreJob.get(input.restoreId)?.let { BackupRestoreJobType(it) },
            error = error,
        )
}
