@file:Suppress("RedundantNullableReturnType", "unused")

package suwayomi.tachidesk.graphql.mutations

import com.expediagroup.graphql.generator.annotations.GraphQLDeprecated
import io.javalin.http.UploadedFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import suwayomi.tachidesk.graphql.directives.RequireAuth
import suwayomi.tachidesk.graphql.server.TemporaryFileStorage
import suwayomi.tachidesk.graphql.types.BackupRestoreStatus
import suwayomi.tachidesk.graphql.types.PartialBackupFlags
import suwayomi.tachidesk.graphql.types.toStatus
import suwayomi.tachidesk.manga.impl.backup.BackupFlags
import suwayomi.tachidesk.manga.impl.backup.proto.BackupRestoreJob
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupExport
import suwayomi.tachidesk.manga.impl.backup.proto.ProtoBackupImport
import suwayomi.tachidesk.manga.impl.backup.proto.models.Backup
import suwayomi.tachidesk.manga.model.dataclass.BackupRestoreCategoryPolicy
import suwayomi.tachidesk.manga.model.dataclass.MangaAcquisitionPolicy
import suwayomi.tachidesk.server.JavalinSetup.future
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class BackupMutation {
    data class RestoreBackupInput(
        val clientMutationId: String? = null,
        val backup: UploadedFile,
        val flags: PartialBackupFlags? = null,
        /**
         * Optional: start an archive bootstrap for the imported series once the import succeeded.
         *
         * It is expressed here rather than as a separate call because the categories the overrides name
         * are the ones this import restores, so the handoff has to be decided before it runs.
         */
        val bootstrapDefaultPolicy: MangaAcquisitionPolicy? = null,
        /** Ordered: the first override whose category matches a series wins. */
        val bootstrapCategoryOverrides: List<BootstrapCategoryPolicyInput>? = null,
    ) {
        data class BootstrapCategoryPolicyInput(
            val categoryName: String,
            val policy: MangaAcquisitionPolicy,
        )
    }

    data class RestoreBackupPayload(
        val clientMutationId: String?,
        val id: String,
        val status: BackupRestoreStatus?,
    )

    @RequireAuth
    fun restoreBackup(input: RestoreBackupInput): CompletableFuture<RestoreBackupPayload> {
        val (clientMutationId, backup, flags) = input

        return future {
            val effectiveFlags = BackupFlags.fromPartial(flags)

            val restoreId =
                ProtoBackupImport.restore(
                    backup.content(),
                    effectiveFlags,
                    options =
                        input.bootstrapDefaultPolicy?.let { policy ->
                            BackupRestoreJob.Request(
                                flags = effectiveFlags,
                                handoffDefaultPolicy = policy,
                                handoffCategoryOverrides =
                                    input.bootstrapCategoryOverrides.orEmpty().map {
                                        BackupRestoreCategoryPolicy(it.categoryName, it.policy)
                                    },
                            )
                        },
                )

            // a durable restore already reports its queued state synchronously; only a restore that has
            // not published a state yet has to wait to be woken
            if (ProtoBackupImport.getRestoreState(restoreId) == null) {
                withTimeout(10.seconds) {
                    ProtoBackupImport.notifyFlow.first {
                        ProtoBackupImport.getRestoreState(restoreId) != null
                    }
                }
            }

            RestoreBackupPayload(clientMutationId, restoreId, ProtoBackupImport.getRestoreState(restoreId)?.toStatus())
        }
    }

    data class CreateBackupInput(
        val clientMutationId: String? = null,
        val flags: PartialBackupFlags? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeChapters: Boolean? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeCategories: Boolean? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeTracking: Boolean? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeHistory: Boolean? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeClientData: Boolean? = null,
        @GraphQLDeprecated("Will get removed", replaceWith = ReplaceWith("flags"))
        val includeServerSettings: Boolean? = null,
    )

    data class CreateBackupPayload(
        val clientMutationId: String?,
        val url: String,
    )

    @RequireAuth
    fun createBackup(input: CreateBackupInput? = null): CreateBackupPayload {
        val filename = Backup.getFilename()

        val backup =
            ProtoBackupExport.createBackup(
                if (input?.flags != null) {
                    BackupFlags.fromPartial(input.flags)
                } else {
                    BackupFlags(
                        includeManga = BackupFlags.DEFAULT.includeManga,
                        includeCategories = input?.includeCategories ?: BackupFlags.DEFAULT.includeCategories,
                        includeChapters = input?.includeChapters ?: BackupFlags.DEFAULT.includeChapters,
                        includeTracking = input?.includeTracking ?: BackupFlags.DEFAULT.includeTracking,
                        includeHistory = input?.includeHistory ?: BackupFlags.DEFAULT.includeHistory,
                        includeClientData = input?.includeClientData ?: BackupFlags.DEFAULT.includeClientData,
                        includeServerSettings = input?.includeServerSettings ?: BackupFlags.DEFAULT.includeServerSettings,
                    )
                },
            )

        TemporaryFileStorage.saveFile(filename, backup)

        return CreateBackupPayload(
            clientMutationId = input?.clientMutationId,
            url = "/api/graphql/files/backup/$filename",
        )
    }
}
