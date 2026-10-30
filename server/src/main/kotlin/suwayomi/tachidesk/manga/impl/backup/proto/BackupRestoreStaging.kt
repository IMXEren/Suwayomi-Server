package suwayomi.tachidesk.manga.impl.backup.proto

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** The staged payload of one restore together with the facts that make a resume honest. */
data class StagedBackup(
    val relativePath: String,
    val size: Long,
    val sha256: String,
)

/**
 * On-disk location of an uploaded backup while its restore runs.
 *
 * The payload lives outside the database - it is the one thing that must never be copied into a row -
 * and it lives under local staging rather than under the archive, because a partially restored backup
 * is not archive content.
 *
 * The file name is derived only from the restore id, which is generated as 32 hex characters, so a
 * stored relative path can never be produced from user input. [stagedFile] still re-checks that a
 * path read back from the database resolves inside the staging root: a hand-edited row must not be
 * able to point a restore at an arbitrary file.
 */
object BackupRestoreStaging {
    /** sub-directory of the staging root holding every staged backup */
    const val ROOT_DIR_NAME = "backup-imports"

    const val SUFFIX = ".tachibk"

    /**
     * Hard cap on a single staged upload.
     *
     * The baseline library backup is a few tens of megabytes, so this is far above anything legitimate
     * while still bounding what one request can write to local disk.
     */
    const val MAX_STAGED_BYTES = 512L * 1024 * 1024

    private const val TEMP_SUFFIX = ".partial"
    private const val BUFFER_SIZE = 64 * 1024

    private val RESTORE_ID_PATTERN = Regex("[0-9a-f]{32}")

    /** Portable, host independent location of a staged backup inside the staging root. */
    fun relativePath(restoreId: String): String {
        require(RESTORE_ID_PATTERN.matches(restoreId)) { "Invalid backup restore id: $restoreId" }
        return "$ROOT_DIR_NAME/$restoreId$SUFFIX"
    }

    /** Resolves a stored relative path under [stagingRoot], refusing anything that escapes it. */
    fun stagedFile(
        stagingRoot: File,
        relativePath: String,
    ): File {
        val root = stagingRoot.absoluteFile.normalize().toPath()
        val resolved = File(stagingRoot, relativePath).absoluteFile.normalize().toPath()
        if (resolved == root || !resolved.startsWith(root)) {
            throw IOException("the staged backup path escapes the staging root")
        }

        return resolved.toFile()
    }

    /**
     * Streams [source] into a temporary sibling and publishes it with one atomic move.
     *
     * The move happens last so an interrupted upload can never be mistaken for a complete payload, and
     * the digest is computed while streaming so a resume can prove the bytes it continues from are
     * exactly the bytes the job was created from.
     */
    fun stage(
        source: InputStream,
        stagingRoot: File,
        restoreId: String,
    ): StagedBackup {
        val target = stagedFile(stagingRoot, relativePath(restoreId))
        target.parentFile?.mkdirs()

        val temporary = File(target.parentFile, "${target.name}$TEMP_SUFFIX")
        temporary.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L

        try {
            source.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_STAGED_BYTES) {
                            throw IOException("the uploaded backup exceeds the supported size of $MAX_STAGED_BYTES bytes")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }

            if (total == 0L) {
                throw IOException("the uploaded backup is empty")
            }

            moveAtomically(temporary, target)
        } catch (e: Throwable) {
            temporary.delete()
            throw e
        }

        return StagedBackup(relativePath(restoreId), total, digest.digest().toHex())
    }

    /** True when [file] still matches the size and digest the job was created from. */
    fun matches(
        file: File,
        expectedSize: Long,
        expectedSha256: String,
    ): Boolean {
        if (!file.isFile || file.length() != expectedSize) {
            return false
        }

        return digestOf(file).equals(expectedSha256, ignoreCase = true)
    }

    /** SHA-256 of an existing file, read in bounded chunks. */
    fun digestOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }

        return digest.digest().toHex()
    }

    /** Deletes a staged payload; a payload that is already gone is not an error. */
    fun delete(file: File) {
        if (file.exists() && !file.delete() && file.exists()) {
            throw IOException("Failed to remove the staged backup: ${file.name}")
        }
    }

    private fun moveAtomically(
        source: File,
        target: File,
    ) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // the temporary file is a sibling of the target, so this can only happen on an unusual
            // filesystem; a plain replace is still better than refusing the upload
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
