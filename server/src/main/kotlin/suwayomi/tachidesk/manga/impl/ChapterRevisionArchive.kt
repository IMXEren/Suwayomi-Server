package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import suwayomi.tachidesk.manga.model.dataclass.CanonicalBindingRole
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionComparisonState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDiscoveryReason
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionMetadataField
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionPageAlignmentState
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionSignalConfidence
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisFailure
import suwayomi.tachidesk.manga.model.dataclass.ChapterVisualAnalysisState
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Ordered per-page content fingerprint persisted in the archive manifest. */
@Serializable
data class ChapterRevisionPageFingerprint(
    val name: String,
    val size: Long,
    val sha256: String,
)

/**
 * The visual answer to "how does this revision differ from the one it replaces".
 *
 * The whole-chapter digest only says that the bytes differ. This is what says whether one page was
 * rewritten, one credit page was added or the whole chapter was re-encoded - which is the question a
 * human reviewer actually has to answer.
 */
@Serializable
data class ChapterRevisionArchiveManifestVisualComparison(
    val algorithmVersion: String,
    val hammingThreshold: Int,
    val baselineRevisionId: Int? = null,
    val baselinePageCount: Int,
    val candidatePageCount: Int,
    val exactCount: Int,
    val visuallyEquivalentCount: Int,
    val modifiedCount: Int,
    val addedCount: Int,
    val removedCount: Int,
    val allPagesVisuallyEquivalent: Boolean,
    val hasLimitations: Boolean,
    val limitations: String? = null,
    val pages: List<ChapterRevisionArchiveManifestVisualPage> = emptyList(),
)

/**
 * One aligned row of the visual comparison, as recorded beside the CBZ.
 *
 * Local thumbnail locations are deliberately absent: they describe the staging root of the machine
 * that produced the archive, which is exactly what a portable manifest must not contain.
 */
@Serializable
data class ChapterRevisionArchiveManifestVisualPage(
    val ordinal: Int,
    val baselinePageIndex: Int? = null,
    val candidatePageIndex: Int? = null,
    val state: ChapterRevisionPageAlignmentState,
    val baselinePerceptualHash: String? = null,
    val candidatePerceptualHash: String? = null,
    val hammingDistance: Int? = null,
)

/**
 * The terminal visual-analysis audit of one revision.
 *
 * Added in schema version 4 beside [ChapterRevisionArchiveManifestVisualComparison], and recorded even
 * when that comparison is absent: a revision whose analysis failed is not the same fact as one that
 * never owed an analysis, and an archive re-read years later must still be able to tell them apart.
 * [failureCategory] is the name of a category, never a message, so no local path can reach the
 * archive.
 */
@Serializable
data class ChapterRevisionArchiveManifestVisualAnalysis(
    val state: ChapterVisualAnalysisState,
    val attempts: Int,
    val completedAt: Long? = null,
    val failureCategory: String? = null,
)

/**
 * The canonical work and source binding snapshot recorded beside an archived revision.
 *
 * Added in schema version 5. [bindingRole] is the name of a [CanonicalBindingRole] and [bindingPrimary]
 * says whether the binding was the work's preferred source when this revision was discovered, so the
 * audit stays readable without the control plane's own tables.
 */
@Serializable
data class ChapterRevisionArchiveManifestCanonicalIdentity(
    val workKey: String,
    val bindingRole: CanonicalBindingRole,
    val bindingPriority: Int,
    val bindingPrimary: Boolean,
    val bindingSourceId: Long? = null,
    val bindingMangaUrl: String? = null,
)

/**
 * Durable, source-independent description of one archived chapter revision.
 *
 * Written beside the CBZ so the archive can be reconstructed without Suwayomi's database. Mutable
 * source URLs and display titles are recorded as metadata, never as identity: [candidateKey] is the
 * immutable identity of the revision.
 */
@Serializable
data class ChapterRevisionArchiveManifest(
    val schemaVersion: Int,
    val revisionId: Int,
    val candidateKey: String,
    /**
     * Source coordinates of the archived revision.
     *
     * Optional rather than required so a manifest written by an older schema version - one that did
     * not record them, or recorded them under a different shape - still decodes instead of failing.
     * The archive is meant to outlive the server that wrote it, and a required field turns every
     * later addition into a break.
     */
    val seriesId: Int? = null,
    val chapterId: Int? = null,
    val sourceId: Long? = null,
    val sourceMangaUrl: String? = null,
    val sourceChapterUrl: String,
    val chapterNumber: Float,
    val chapterTitle: String,
    val scanlator: String? = null,
    val memo: JsonObject,
    val discoveryReason: ChapterRevisionDiscoveryReason = ChapterRevisionDiscoveryReason.NEW_CHAPTER,
    val signalConfidence: ChapterRevisionSignalConfidence = ChapterRevisionSignalConfidence.METADATA_HINT,
    val changedMetadataFields: List<ChapterRevisionMetadataField> = emptyList(),
    /**
     * The sweep item that discovered this revision, or null when ordinary reconciliation did.
     *
     * Recorded so the archive can be re-read later without Suwayomi's database: "this revision was
     * re-checked by a sweep" is the provenance of a re-release, and the item id is also the binding
     * that makes an item own exactly one revision.
     */
    val discoverySweepItemId: Int? = null,
    /**
     * What the content comparison found when this revision finished acquiring.
     *
     * A revision that is not archived because its bytes already were lives only here and in the
     * database, so the audit is what proves the archive is not missing anything.
     */
    val comparisonState: ChapterRevisionComparisonState = ChapterRevisionComparisonState.PENDING,
    val comparisonBaselineRevisionId: Int? = null,
    val comparedAt: Long? = null,
    /**
     * The page-by-page comparison that ran for this revision, or null when none was needed.
     *
     * Added in schema version 4. A version 2 or 3 manifest simply has no such field, and decoding one
     * leaves it null, so an older archive stays readable exactly as it was written.
     */
    val visualComparison: ChapterRevisionArchiveManifestVisualComparison? = null,
    /**
     * How the visual analysis of this revision ended, whatever it ended in.
     *
     * Added in schema version 4. A version 2 or 3 manifest simply has no such field, and decoding one
     * leaves it null, so an older archive stays readable exactly as it was written.
     */
    val visualAnalysis: ChapterRevisionArchiveManifestVisualAnalysis? = null,
    /**
     * The canonical work and source binding this revision was discovered under, or null while its
     * manga was unbound.
     *
     * Added in schema version 5. It is a *snapshot*, not a reference: a version 2, 3 or 4 manifest has
     * no such field and decodes to null, and a revision whose work was later deleted still describes
     * the binding it was discovered under. [bindingRole] and [bindingPrimary] are recorded together
     * with [bindingPriority] because the ordering of a work's bindings is what makes "the next
     * fallback" unambiguous years later.
     *
     * There is deliberately no cross-source chapter identity in it. [candidateKey] and the archived
     * CBZ remain the identity of one *source* chapter revision: whether chapter 1 of two bound sources
     * is the same chapter is an unresolved, manual decision, and a manifest that claimed otherwise
     * would have to be unwound later.
     */
    val canonicalIdentity: ChapterRevisionArchiveManifestCanonicalIdentity? = null,
    val discoveredAt: Long,
    val archivedAt: Long,
    val pageCount: Int,
    val pages: List<ChapterRevisionPageFingerprint>,
    val acquisitionContentHash: String? = null,
    val archiveContentHash: String,
    val archiveSize: Long,
)

/** Size and digest of a published archive artifact. */
data class ChapterRevisionArtifactDigest(
    val size: Long,
    val sha256: String,
)

/** Raised when an immutable archive artifact already exists with different content. */
class ChapterRevisionArchiveConflictException(
    message: String,
) : IOException(message)

/**
 * Traversal-safe location of the immutable archive artifacts of one revision.
 *
 * The layout is derived only from the immutable 64-hex [ChapterRevision.candidateKey], so it never
 * depends on mutable display titles or source URLs.
 */
object ChapterRevisionArchiveArtifacts {
    /** sub-directory of the archive root holding every archived revision */
    const val ROOT_DIR_NAME = "revisions"

    const val MANIFEST_SUFFIX = ".archive.json"

    /**
     * Sub-directory of the local staging root holding the finished immutable artifacts before they
     * are copied into the archive root.
     *
     * It is deliberately a sibling of - never inside - the validated page directory, so page
     * validation keeps rejecting any extra entry, and an object-storage mount never receives a
     * half-written temporary file.
     */
    const val LOCAL_ROOT_DIR_NAME = "revision-artifacts"

    private val candidateKeyPattern = Regex("[0-9a-f]{64}")

    /** Portable, host independent location of a revision inside the archive root. */
    fun relativeDirectory(candidateKey: String): String {
        require(candidateKeyPattern.matches(candidateKey)) { "Invalid chapter revision candidate key: $candidateKey" }
        return "$ROOT_DIR_NAME/$candidateKey"
    }

    fun directory(
        archiveRoot: File,
        candidateKey: String,
    ): File = File(archiveRoot, relativeDirectory(candidateKey))

    fun relativeCbzPath(candidateKey: String): String = "${relativeDirectory(candidateKey)}/$candidateKey.cbz"

    fun relativeManifestPath(candidateKey: String): String = "${relativeDirectory(candidateKey)}/$candidateKey$MANIFEST_SUFFIX"

    fun cbzFile(
        archiveRoot: File,
        candidateKey: String,
    ): File = File(archiveRoot, relativeCbzPath(candidateKey))

    fun manifestFile(
        archiveRoot: File,
        candidateKey: String,
    ): File = File(archiveRoot, relativeManifestPath(candidateKey))

    /** Portable, host independent location of a revision inside the local staging root. */
    fun relativeLocalDirectory(candidateKey: String): String {
        require(candidateKeyPattern.matches(candidateKey)) { "Invalid chapter revision candidate key: $candidateKey" }
        return "$LOCAL_ROOT_DIR_NAME/$candidateKey"
    }

    fun localDirectory(
        stagingRoot: File,
        candidateKey: String,
    ): File = File(stagingRoot, relativeLocalDirectory(candidateKey))

    fun localCbzFile(
        stagingRoot: File,
        candidateKey: String,
    ): File = File(localDirectory(stagingRoot, candidateKey), "$candidateKey.cbz")

    fun localManifestFile(
        stagingRoot: File,
        candidateKey: String,
    ): File = File(localDirectory(stagingRoot, candidateKey), "$candidateKey$MANIFEST_SUFFIX")

    /** SHA-256 and byte size of any published artifact, local or archived. */
    fun digestOf(file: File): ChapterRevisionArtifactDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return ChapterRevisionArtifactDigest(file.length(), digest.digest().toHex())
    }

    /**
     * Removes only the archived CBZ payload of a revision and keeps its sidecar manifest.
     *
     * The manifest is the immutable audit record of a revision - it is the only durable description
     * of what the pruned CBZ contained - so pruning must never delete it. Idempotent: a payload that
     * is already gone is not an error, which is what lets a retried deletion be safe.
     */
    fun deleteArchivedCbz(
        archiveRoot: File,
        candidateKey: String,
    ) {
        val cbz = cbzFile(archiveRoot, candidateKey)
        if (cbz.exists() && !cbz.delete() && cbz.exists()) {
            throw IOException("Failed to remove the archived CBZ payload: ${cbz.path}")
        }
    }

    /** True while the archived CBZ payload of a revision still exists on the mounted archive. */
    fun archivedCbzExists(
        archiveRoot: File,
        candidateKey: String,
    ): Boolean = cbzFile(archiveRoot, candidateKey).exists()

    /** Removes the published artifacts of a revision so a retry can rebuild them from staging. */
    fun deleteRevision(
        archiveRoot: File,
        candidateKey: String,
    ) {
        deleteDirectory(directory(archiveRoot, candidateKey))
    }

    /** Removes the locally built artifacts of a revision; the downloaded pages are left untouched. */
    fun deleteLocalRevision(
        stagingRoot: File,
        candidateKey: String,
    ) {
        deleteDirectory(localDirectory(stagingRoot, candidateKey))
    }

    /**
     * Removes both the locally built and the archived artifacts of a revision.
     *
     * The downloaded pages under [ChapterRevisionStaging] are deliberately not touched, so a retry
     * rebuilds the artifacts without downloading the chapter again.
     */
    fun deleteArtifacts(
        stagingRoot: File,
        archiveRoot: File,
        candidateKey: String,
    ) {
        deleteRevision(archiveRoot, candidateKey)
        deleteLocalRevision(stagingRoot, candidateKey)
    }

    private fun deleteDirectory(directory: File) {
        if (directory.exists() && !directory.deleteRecursively() && directory.exists()) {
            throw IOException("Failed to remove chapter revision artifacts: $directory")
        }
    }
}

/**
 * Layout of the active library view inside the archive root.
 *
 * Only the active revision of a chapter is ever copied here, so a reader-facing library (Komga,
 * Kavita, a plain reader) sees exactly one file per chapter. The path is derived from internal ids
 * only - the manga row id and the immutable chapter identity key - so renaming a series or editing a
 * chapter title can never move or orphan an already published file, and a replacement always lands
 * on the same destination. Display names carry no identity and are therefore never used here.
 */
object ChapterRevisionLibrary {
    /** sub-directory of the archive root that holds the active, reader-visible copies */
    const val ROOT_DIR_NAME = "library"

    private val chapterKeyPattern = Regex("[0-9a-f]{64}")

    /** Portable, host independent location of a chapter inside the active library view. */
    fun relativeDirectory(mangaId: Int): String {
        require(mangaId > 0) { "Invalid manga id for an active library path: $mangaId" }
        return "$ROOT_DIR_NAME/manga-$mangaId"
    }

    fun relativeCbzPath(
        mangaId: Int,
        chapterKey: String,
    ): String {
        require(chapterKeyPattern.matches(chapterKey)) { "Invalid chapter identity key: $chapterKey" }
        return "${relativeDirectory(mangaId)}/$chapterKey.cbz"
    }

    fun cbzFile(
        archiveRoot: File,
        mangaId: Int,
        chapterKey: String,
    ): File = File(archiveRoot, relativeCbzPath(mangaId, chapterKey))
}

/**
 * Deterministic CBZ writer.
 *
 * Page order is the staged file name order, entries carry a fixed timestamp and are stored
 * uncompressed, so the same ordered pages always produce byte-identical output. That lets the
 * archive treat an existing artifact as idempotent by comparing its digest.
 */
object ChapterRevisionCbz {
    private const val FIXED_TIME_YEAR = 1980
    private const val FIXED_TIME_MONTH = 1
    private const val FIXED_TIME_DAY = 1

    /** The DOS epoch, stored timezone independently via [ZipEntry.setTimeLocal]. */
    private val FIXED_TIME: LocalDateTime = LocalDateTime.of(FIXED_TIME_YEAR, FIXED_TIME_MONTH, FIXED_TIME_DAY, 0, 0, 0)

    /** Writes [pages] into [output] and returns the resulting size and SHA-256. */
    fun write(
        pages: List<File>,
        output: File,
    ): ChapterRevisionArtifactDigest {
        val digest = MessageDigest.getInstance("SHA-256")

        output.outputStream().use { fileOutput ->
            val digestOutput = java.security.DigestOutputStream(fileOutput, digest)

            ZipOutputStream(digestOutput).use { zip ->
                // images are already compressed, so storing them keeps the output deterministic
                zip.setLevel(Deflater.NO_COMPRESSION)

                pages.forEach { page ->
                    val entry = ZipEntry(page.name)
                    entry.method = ZipEntry.STORED
                    entry.size = page.length()
                    entry.compressedSize = page.length()
                    entry.crc = crc32Of(page)
                    entry.setTimeLocal(FIXED_TIME)

                    zip.putNextEntry(entry)
                    page.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

        return ChapterRevisionArtifactDigest(output.length(), digest.digest().toHex())
    }

    /** SHA-256 and size of an existing artifact. */
    fun digestOf(file: File): ChapterRevisionArtifactDigest = ChapterRevisionArchiveArtifacts.digestOf(file)

    /** Ordered page fingerprint as recorded in the archive manifest. */
    fun fingerprintOf(page: File): ChapterRevisionPageFingerprint =
        ChapterRevisionPageFingerprint(page.name, page.length(), digestOf(page).sha256)

    private fun crc32Of(file: File): Long {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * Canonical serialization of the sidecar manifest.
 *
 * Field order is the declaration order of [ChapterRevisionArchiveManifest] and defaults are always
 * encoded, so the same revision always serializes to the same bytes.
 */
object ChapterRevisionArchiveManifestCodec {
    const val SCHEMA_VERSION = 5

    private val json =
        Json {
            encodeDefaults = true
            prettyPrint = true
        }

    fun encode(manifest: ChapterRevisionArchiveManifest): ByteArray = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): ChapterRevisionArchiveManifest = json.decodeFromString(bytes.toString(Charsets.UTF_8))
}

/**
 * Reads the visual comparison of a revision for the sidecar manifest.
 *
 * Lives here rather than in the comparison store because the manifest is the only caller that needs
 * this exact shape, and a comparison that was never produced has to be absent from the manifest
 * rather than present-but-empty.
 */
object ChapterRevisionArchiveVisuals {
    fun auditOf(revisionId: Int): ChapterRevisionArchiveManifestVisualComparison? {
        val summary = ChapterRevisionComparisonStore.getComparison(revisionId) ?: return null
        val pages = ChapterRevisionComparisonStore.getPages(revisionId)

        return ChapterRevisionArchiveManifestVisualComparison(
            algorithmVersion = summary.algorithmVersion,
            hammingThreshold = summary.hammingThreshold,
            baselineRevisionId = summary.baselineRevisionId,
            baselinePageCount = summary.baselinePageCount,
            candidatePageCount = summary.candidatePageCount,
            exactCount = summary.exactCount,
            visuallyEquivalentCount = summary.visuallyEquivalentCount,
            modifiedCount = summary.modifiedCount,
            addedCount = summary.addedCount,
            removedCount = summary.removedCount,
            allPagesVisuallyEquivalent = summary.allPagesVisuallyEquivalent,
            hasLimitations = summary.hasLimitations,
            limitations = summary.limitations,
            pages =
                pages.map { page ->
                    ChapterRevisionArchiveManifestVisualPage(
                        ordinal = page.ordinal,
                        baselinePageIndex = page.baselinePageIndex,
                        candidatePageIndex = page.candidatePageIndex,
                        state = page.state,
                        baselinePerceptualHash = page.baselinePerceptualHash,
                        candidatePerceptualHash = page.candidatePerceptualHash,
                        hammingDistance = page.hammingDistance,
                    )
                },
        )
    }

    /**
     * The terminal state of the visual analysis of one revision, whatever it turned out to be.
     *
     * Recorded even when no comparison exists, because "the archive holds no comparison of this
     * revision" is itself the audit: a reader has to be able to tell a revision that owed nothing
     * apart from one whose comparison failed, and a failed one apart from a limited one. The failure is
     * a category rather than a message, so nothing local - a path, a URL, a source error - can end up
     * inside a portable manifest.
     */
    fun analysisOf(
        state: ChapterVisualAnalysisState,
        attempts: Int,
        completedAt: Long?,
        failure: ChapterVisualAnalysisFailure?,
    ): ChapterRevisionArchiveManifestVisualAnalysis =
        ChapterRevisionArchiveManifestVisualAnalysis(
            state = state,
            attempts = attempts,
            completedAt = completedAt,
            failureCategory = failure?.name,
        )
}

/**
 * Identity of a just-published archive artifact, handed to a [ArchiveCommitVerifier].
 */
data class ChapterRevisionArchiveArtifact(
    val candidateKey: String,
    val relativeCbzPath: String,
    val relativeManifestPath: String,
    val cbzSha256: String,
    val cbzSize: Long,
    val manifestSha256: String,
    val manifestSize: Long,
)

/** Outcome of verifying that a published archive artifact reached durable remote storage. */
sealed interface ArchiveVerification {
    /** No verifier is configured, so the artifact legitimately stays REMOTE_PENDING. */
    data object NotAttempted : ArchiveVerification

    data object Confirmed : ArchiveVerification

    /**
     * The artifact is not visible on the remote yet. This is a retryable outcome: the revision stays
     * REMOTE_PENDING and is checked again later instead of being reported as a failure.
     */
    data class Pending(
        val reason: String,
    ) : ArchiveVerification

    data class Unconfirmed(
        val reason: String,
    ) : ArchiveVerification
}

/**
 * Confirms that a published archive artifact actually reached durable remote storage.
 *
 * A successful write through an object-storage mount is not proof of remote durability, so this
 * boundary has to be implemented by a provider-specific verifier (for example an rclone remote
 * check). The default implementation never confirms anything.
 */
fun interface ArchiveCommitVerifier {
    suspend fun verify(artifact: ChapterRevisionArchiveArtifact): ArchiveVerification

    companion object {
        val NOT_CONFIGURED = ArchiveCommitVerifier { ArchiveVerification.NotAttempted }
    }
}

/**
 * Outcome of checking that an archived artifact is really gone from remote storage.
 *
 * The mirror image of [ArchiveVerification]: deleting through an object-storage mount returns before
 * the object has necessarily left the remote, so absence is only ever proven by asking the remote
 * directly.
 */
sealed interface ArchiveAbsenceVerification {
    /** No verifier is configured, so nothing about remote storage may be inferred. */
    data object NotAttempted : ArchiveAbsenceVerification

    /** The exact object is no longer listed on the remote. */
    data object ConfirmedAbsent : ArchiveAbsenceVerification

    /** The exact object is still listed, so the deletion has not propagated yet. Retryable. */
    data class StillPresent(
        val reason: String,
    ) : ArchiveAbsenceVerification

    /** The check itself could not be completed yet (timeout, temporary remote error). Retryable. */
    data class Pending(
        val reason: String,
    ) : ArchiveAbsenceVerification

    data class Unconfirmed(
        val reason: String,
    ) : ArchiveAbsenceVerification
}

/**
 * Confirms that an archived artifact no longer exists on remote storage.
 *
 * Symmetric to [ArchiveCommitVerifier]: the default implementation never claims anything, so a
 * deployment without a configured remote simply keeps such revisions at REMOTE_DELETE_PENDING.
 */
fun interface ArchiveDeletionVerifier {
    suspend fun verifyAbsent(artifact: ChapterRevisionArchiveArtifact): ArchiveAbsenceVerification

    companion object {
        val NOT_CONFIGURED = ArchiveDeletionVerifier { ArchiveAbsenceVerification.NotAttempted }
    }
}
