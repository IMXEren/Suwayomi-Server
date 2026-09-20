package suwayomi.tachidesk.graphql.mutations

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.json.Json
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.types.CanonicalIdentityExportType
import suwayomi.tachidesk.graphql.types.CanonicalIdentityImportType
import suwayomi.tachidesk.graphql.types.CanonicalSourceBindingType
import suwayomi.tachidesk.graphql.types.CanonicalWorkType
import suwayomi.tachidesk.manga.impl.CanonicalIdentity
import suwayomi.tachidesk.manga.impl.CanonicalIdentityImportException
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingRole
import suwayomi.tachidesk.manga.model.dataclass.CanonicalDuplicateStrategy
import suwayomi.tachidesk.manga.model.dataclass.CanonicalIdentityExportDocument
import suwayomi.tachidesk.manga.model.dataclass.CanonicalSourceBindingDataClass
import suwayomi.tachidesk.manga.model.dataclass.CanonicalWriteOutcome

/**
 * Write side of the canonical identity control plane.
 *
 * Every mutation answers with an [CanonicalWriteOutcome] instead of throwing, because a conflict here
 * is usually a legitimate answer - "that manga already belongs to a work", "that priority is taken",
 * "the work still has bindings" - and a caller has to be able to tell those apart from a real failure.
 * Nothing in this class changes, deletes or suppresses archived content: it only records which sources
 * describe the same series and which one discovery is made from.
 */
class CanonicalIdentityMutation {
    data class CreateCanonicalWorkInput(
        val clientMutationId: String? = null,
        val title: String,
        val duplicateStrategy: CanonicalDuplicateStrategy = CanonicalDuplicateStrategy.KEEP_ALL,
        val preferredScanlator: String? = null,
    )

    data class CreateCanonicalWorkPayload(
        val clientMutationId: String?,
        val outcome: CanonicalWriteOutcome,
        val work: CanonicalWorkType?,
    )

    /** Creates an empty work; bindings are attached afterwards, one source at a time. */
    @RequireAuth
    fun createCanonicalWork(input: CreateCanonicalWorkInput): CreateCanonicalWorkPayload {
        val (clientMutationId, title, duplicateStrategy, preferredScanlator) = input

        val result = CanonicalIdentity.createWork(title, duplicateStrategy, preferredScanlator)

        return CreateCanonicalWorkPayload(clientMutationId, result.outcome, result.work?.let { CanonicalWorkType(it) })
    }

    data class UpdateCanonicalWorkInput(
        val clientMutationId: String? = null,
        val workKey: String,
        val title: String? = null,
        val duplicateStrategy: CanonicalDuplicateStrategy? = null,
        val preferredScanlator: String? = null,
        /**
         * Removes the recorded scanlator.
         *
         * A separate flag because "leave it alone" and "remove it" cannot both be a null argument.
         */
        val clearPreferredScanlator: Boolean = false,
    )

    data class UpdateCanonicalWorkPayload(
        val clientMutationId: String?,
        val outcome: CanonicalWriteOutcome,
        val work: CanonicalWorkType?,
    )

    @RequireAuth
    fun updateCanonicalWork(input: UpdateCanonicalWorkInput): UpdateCanonicalWorkPayload {
        val (clientMutationId, workKey, title, duplicateStrategy, preferredScanlator, clearPreferredScanlator) = input

        val result =
            CanonicalIdentity.updateWork(
                workKey = workKey,
                title = title,
                duplicateStrategy = duplicateStrategy,
                preferredScanlator = preferredScanlator,
                clearPreferredScanlator = clearPreferredScanlator,
            )

        return UpdateCanonicalWorkPayload(clientMutationId, result.outcome, result.work?.let { CanonicalWorkType(it) })
    }

    data class DeleteCanonicalWorkInput(
        val clientMutationId: String? = null,
        val workKey: String,
    )

    data class DeleteCanonicalWorkPayload(
        val clientMutationId: String?,
        val outcome: CanonicalWriteOutcome,
    )

    /** Deletes a work, but only while it has no bindings; detach them first. */
    @RequireAuth
    fun deleteCanonicalWork(input: DeleteCanonicalWorkInput): DeleteCanonicalWorkPayload {
        val (clientMutationId, workKey) = input

        return DeleteCanonicalWorkPayload(clientMutationId, CanonicalIdentity.deleteEmptyWork(workKey))
    }

    data class AttachMangaToCanonicalWorkInput(
        val clientMutationId: String? = null,
        val workKey: String,
        val mangaId: Int,
        val role: CanonicalBindingRole = CanonicalBindingRole.ACTIVE,
        /** omitted means "after every existing binding"; an explicit taken value is a conflict */
        val priority: Int? = null,
        val isPrimary: Boolean = false,
    )

    data class AttachMangaToCanonicalWorkPayload(
        val clientMutationId: String?,
        val outcome: CanonicalWriteOutcome,
        val binding: CanonicalSourceBindingType?,
    )

    @RequireAuth
    fun attachMangaToCanonicalWork(input: AttachMangaToCanonicalWorkInput): AttachMangaToCanonicalWorkPayload {
        val (clientMutationId, workKey, mangaId, role, priority, isPrimary) = input

        val result = CanonicalIdentity.attachManga(workKey, mangaId, role, priority, isPrimary)

        return AttachMangaToCanonicalWorkPayload(clientMutationId, result.outcome, result.binding.toType())
    }

    data class DetachCanonicalBindingInput(
        val clientMutationId: String? = null,
        val bindingId: Int,
    )

    data class DetachCanonicalBindingPayload(
        val clientMutationId: String?,
        val outcome: CanonicalWriteOutcome,
        val binding: CanonicalSourceBindingType?,
    )

    /** Removes a binding. The work, the manga, every revision and everything archived stay put. */
    @RequireAuth
    fun detachCanonicalBinding(input: DetachCanonicalBindingInput): DetachCanonicalBindingPayload {
        val (clientMutationId, bindingId) = input

        val result = CanonicalIdentity.detachBinding(bindingId)

        return DetachCanonicalBindingPayload(clientMutationId, result.outcome, result.binding.toType())
    }

    data class ChangeCanonicalBindingInput(
        val clientMutationId: String? = null,
        val bindingId: Int,
        val role: CanonicalBindingRole? = null,
        val priority: Int? = null,
    )

    data class ChangeCanonicalBindingPayload(
        val clientMutationId: String?,
        val outcome: CanonicalWriteOutcome,
        val binding: CanonicalSourceBindingType?,
    )

    /** Changes a binding's role and/or its ordering place. `DISABLED` also clears the primary marker. */
    @RequireAuth
    fun changeCanonicalBinding(input: ChangeCanonicalBindingInput): ChangeCanonicalBindingPayload {
        val (clientMutationId, bindingId, role, priority) = input

        val result = CanonicalIdentity.changeBinding(bindingId, role, priority)

        return ChangeCanonicalBindingPayload(clientMutationId, result.outcome, result.binding.toType())
    }

    data class PromoteCanonicalBindingInput(
        val clientMutationId: String? = null,
        val bindingId: Int,
    )

    data class PromoteCanonicalBindingPayload(
        val clientMutationId: String?,
        val outcome: CanonicalWriteOutcome,
        val binding: CanonicalSourceBindingType?,
    )

    /** Makes one binding the work's preferred source without disabling the others. */
    @RequireAuth
    fun promoteCanonicalBinding(input: PromoteCanonicalBindingInput): PromoteCanonicalBindingPayload {
        val (clientMutationId, bindingId) = input

        val result = CanonicalIdentity.promoteBinding(bindingId)

        return PromoteCanonicalBindingPayload(clientMutationId, result.outcome, result.binding.toType())
    }

    data class FailoverCanonicalWorkInput(
        val clientMutationId: String? = null,
        val workKey: String,
        val bindingId: Int,
    )

    data class FailoverCanonicalWorkPayload(
        val clientMutationId: String?,
        val outcome: CanonicalWriteOutcome,
        val binding: CanonicalSourceBindingType?,
    )

    /**
     * Moves a work onto another bound source: the chosen binding becomes primary and `ACTIVE` and the
     * abandoned primary is demoted to `FALLBACK`, so it stops feeding discovery but is kept, audited
     * and can be promoted back.
     */
    @RequireAuth
    fun failoverCanonicalWork(input: FailoverCanonicalWorkInput): FailoverCanonicalWorkPayload {
        val (clientMutationId, workKey, bindingId) = input

        val result = CanonicalIdentity.failover(workKey, bindingId)

        return FailoverCanonicalWorkPayload(clientMutationId, result.outcome, result.binding.toType())
    }

    data class ExportCanonicalIdentityPayload(
        val clientMutationId: String? = null,
        val export: CanonicalIdentityExportType,
    )

    /**
     * Exports the control plane as a durable JSON document.
     *
     * It is a document rather than more protobuf backup fields on purpose: a work is a series-level
     * object with many bindings, and a chunked, resumable restore can restore the manga of a backup in
     * any order, so folding the work into the per-manga record would make a work depend on whichever
     * manga happened to be restored first. Keeping it separate leaves the existing backup wire - which
     * other servers also read - untouched.
     */
    @RequireAuth
    fun exportCanonicalIdentity(clientMutationId: String? = null): ExportCanonicalIdentityPayload {
        val document = CanonicalIdentity.exportDocument()

        return ExportCanonicalIdentityPayload(
            clientMutationId,
            CanonicalIdentityExportType(
                payload = EXPORT_JSON.encodeToString(document),
                schemaVersion = document.schemaVersion,
                workCount = document.works.size,
                bindingCount = document.works.sumOf { it.bindings.size },
            ),
        )
    }

    data class ImportCanonicalIdentityInput(
        val clientMutationId: String? = null,
        val payload: String,
    )

    data class ImportCanonicalIdentityPayload(
        val clientMutationId: String?,
        val import: CanonicalIdentityImportType,
    )

    /**
     * Applies an exported document.
     *
     * Merge rather than replace: works absent from the document are left alone. A binding whose manga
     * is not present is imported detached with its snapshot, so an unresolved source stays visible
     * instead of disappearing.
     */
    @RequireAuth
    fun importCanonicalIdentity(input: ImportCanonicalIdentityInput): ImportCanonicalIdentityPayload {
        val (clientMutationId, payload) = input

        val document =
            try {
                EXPORT_JSON.decodeFromString<CanonicalIdentityExportDocument>(payload)
            } catch (e: Exception) {
                // the payload is echoed nowhere and the reason is static: it is user supplied input and
                // may carry anything, so neither the parser message nor a fragment of it is surfaced
                throw CanonicalIdentityImportException("The canonical identity export could not be read")
            }

        val result = CanonicalIdentity.importDocument(document)

        return ImportCanonicalIdentityPayload(
            clientMutationId,
            CanonicalIdentityImportType(
                worksCreated = result.worksCreated,
                worksUpdated = result.worksUpdated,
                bindingsBound = result.bindingsBound,
                bindingsUnresolved = result.bindingsUnresolved,
                bindingsRebound = result.bindingsRebound,
            ),
        )
    }

    private companion object {
        /** Canonical serialization of the export document, so the same control plane always round trips. */
        val EXPORT_JSON =
            Json {
                encodeDefaults = true
                prettyPrint = false
            }
    }
}

private fun CanonicalSourceBindingDataClass?.toType(): CanonicalSourceBindingType? = this?.let { CanonicalSourceBindingType(it) }
