package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Outcome of running one external archive verification command. */
data class ArchiveRemoteCommandResult(
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    /** Set when the command could not even be started, e.g. because the executable is missing. */
    val startFailure: String? = null,
    /** Set when the command was stopped because it wrote more output than the runner allows. */
    val outputExceeded: Boolean = false,
    val standardOutput: String = "",
    val standardError: String = "",
)

/** Runs an external archival verification command; isolated so the verifier is testable. */
fun interface ArchiveRemoteCommandRunner {
    suspend fun run(
        command: List<String>,
        timeout: Duration,
    ): ArchiveRemoteCommandResult
}

/**
 * Runs the command through `ProcessBuilder`, so an argument can never be reinterpreted by a shell.
 *
 * Output is redirected to temporary files instead of pipes. A pipe would have to be drained by a
 * reader coroutine, and a killed child can leave a grandchild holding the inherited pipe handle, so
 * that reader could never observe end-of-stream and would block the caller forever. Files have no
 * such coupling: the command is polled in a cancellable way, forcibly terminated at the timeout or
 * on cancellation, and only then is the bounded output read back.
 */
object ProcessArchiveRemoteCommandRunner : ArchiveRemoteCommandRunner {
    private const val MAX_DIAGNOSTIC_CHARS = 64 * 1024
    private const val POLL_INTERVAL_MILLIS = 50L
    private const val TERMINATE_GRACE_MILLIS = 10_000L

    /** Longest a stopped command is given to die; a verification claim lease must cover it. */
    const val TERMINATE_GRACE_SECONDS = TERMINATE_GRACE_MILLIS / 1_000L

    /**
     * Hard cap on the redirected output of one command.
     *
     * The poll loop stops the command as soon as the combined streams pass this, and the size is
     * checked again after exit so oversized fast output can never be accepted as a valid listing.
     */
    const val MAX_REDIRECTED_OUTPUT_BYTES = 8L * 1024 * 1024

    override suspend fun run(
        command: List<String>,
        timeout: Duration,
    ): ArchiveRemoteCommandResult {
        val outputFile =
            try {
                File.createTempFile("suwayomi-archive-verify-", ".out")
            } catch (e: IOException) {
                return ArchiveRemoteCommandResult(startFailure = e.message ?: e.javaClass.simpleName)
            }
        val errorFile = File("${outputFile.absolutePath}.err")

        try {
            val process =
                try {
                    ProcessBuilder(command)
                        .redirectOutput(outputFile)
                        .redirectError(errorFile)
                        .start()
                        .also { started ->
                            // An encrypted or misconfigured rclone stops at an interactive prompt. End
                            // its standard input immediately so it can never wait for an answer that
                            // this process will not give.
                            runCatching { started.outputStream.close() }
                        }
                } catch (e: IOException) {
                    return ArchiveRemoteCommandResult(startFailure = e.message ?: e.javaClass.simpleName)
                }

            try {
                var outputExceeded = false
                // `delay` is cancellable, so a cancelled caller stops waiting immediately and the
                // finally block below still kills the command
                val exited =
                    withTimeoutOrNull(timeout) {
                        while (process.isAlive) {
                            if (redirectedOutputTooLarge(outputFile, errorFile)) {
                                outputExceeded = true
                                return@withTimeoutOrNull true
                            }
                            delay(POLL_INTERVAL_MILLIS)
                        }
                    }
                outputExceeded = outputExceeded || redirectedOutputTooLarge(outputFile, errorFile)
                val timedOut = !outputExceeded && exited == null
                if (timedOut || outputExceeded) {
                    terminate(process)
                }

                return ArchiveRemoteCommandResult(
                    exitCode = if (timedOut || outputExceeded) null else process.exitValue(),
                    timedOut = timedOut,
                    outputExceeded = outputExceeded,
                    standardOutput = outputFile.readBounded(),
                    standardError = errorFile.readBounded(),
                )
            } finally {
                // cancellation must never leave a verification command running
                withContext(NonCancellable + Dispatchers.IO) { terminate(process) }
            }
        } finally {
            outputFile.delete()
            errorFile.delete()
        }
    }

    private fun redirectedOutputTooLarge(vararg files: File): Boolean {
        var total = 0L
        files.forEach { file ->
            total += file.length().coerceAtMost(MAX_REDIRECTED_OUTPUT_BYTES + 1)
            if (total > MAX_REDIRECTED_OUTPUT_BYTES) {
                return true
            }
        }
        return false
    }

    /** Forcibly stops the command without ever waiting on it unbounded. */
    private fun terminate(process: Process) {
        if (!process.isAlive) {
            return
        }

        process.destroyForcibly()
        process.waitFor(TERMINATE_GRACE_MILLIS, TimeUnit.MILLISECONDS)
    }

    private fun File.readBounded(): String {
        if (!isFile) {
            return ""
        }

        return inputStream().use { input ->
            val buffer = ByteArray(MAX_DIAGNOSTIC_CHARS)
            var read = 0
            while (read < buffer.size) {
                val count = input.read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
            String(buffer, 0, read, Charsets.UTF_8)
        }
    }
}

/** One entry of the `rclone lsjson` output. */
@Serializable
private data class RcloneEntry(
    @SerialName("Path") val path: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Size") val size: Long = -1,
    @SerialName("Hashes") val hashes: Map<String, String>? = null,
)

/**
 * Confirms archival durability by listing the configured rclone *remote*, never the mounted path.
 *
 * A mounted object store can report a write as complete while the object only exists in the upload
 * cache, so the remote is queried directly through the rclone CLI. An object that is not visible
 * there yet is [ArchiveVerification.Pending] and therefore retryable, while a size or hash mismatch
 * is a real [ArchiveVerification.Unconfirmed] failure.
 *
 * Matching sizes are sufficient for confirmation, as the archival design defines it; a SHA-256 digest
 * is used as stronger evidence only when the backend reports a comparable one.
 */
class RcloneArchiveCommitVerifier(
    executable: String,
    remoteRoot: String,
    private val timeout: Duration,
    private val runner: ArchiveRemoteCommandRunner = ProcessArchiveRemoteCommandRunner,
) : ArchiveCommitVerifier {
    private val executable = requireSafeExecutable(executable)
    private val remoteRoot = requireSafeRemoteSpec(remoteRoot)

    /** Bare remote name, redacted from diagnostics as well as the full spec. */
    private val remoteName = remoteRoot.substringBefore(':')

    /**
     * Whether a configured executable has to be redacted from a persisted reason.
     *
     * The default `rclone` name is deliberately left alone: it is not a secret, and replacing that
     * bare word would mangle otherwise useful diagnostics. Any other configured value may embed a
     * local path or user name, so it is replaced - as long as it is long enough to be specific.
     */
    private val redactExecutable = executable != DEFAULT_EXECUTABLE_NAME && executable.length >= MIN_REDACTABLE_REMOTE_NAME

    override suspend fun verify(artifact: ChapterRevisionArchiveArtifact): ArchiveVerification = classify(listing(artifact), artifact)

    /**
     * Re-checks that both archived objects are still the objects the archive recorded.
     *
     * Unlike [verify], which confirms a freshly committed artifact, this is a check of content that
     * was confirmed long ago, so absence is evidence rather than a pending state: a backend that
     * answers "directory not found" or "object not found", or lists nothing at all, positively says
     * the payload is gone. A path that merely is not listed *yet* while other objects of the same
     * revision are stays retryable, because reporting a propagation delay as missing content would be
     * a false integrity finding.
     */
    suspend fun verifyIntegrity(artifact: ChapterRevisionArchiveArtifact): ArchiveIntegrityVerification =
        classifyIntegrity(listing(artifact), artifact)

    /**
     * Confirms that the exact archived CBZ of [artifact] no longer exists on the remote.
     *
     * The sidecar manifest is expected to still be there - pruning keeps it on purpose - so its
     * presence is never treated as evidence that the deletion failed. Only the CBZ payload decides
     * the outcome.
     */
    suspend fun verifyAbsent(artifact: ChapterRevisionArchiveArtifact): ArchiveAbsenceVerification =
        classifyAbsence(listing(artifact), artifact)

    private suspend fun listing(artifact: ChapterRevisionArchiveArtifact): ArchiveRemoteCommandResult {
        val remoteDirectory = "$remoteRoot/${ChapterRevisionArchiveArtifacts.relativeDirectory(artifact.candidateKey)}"

        return runner.run(
            listOf(
                executable,
                "lsjson",
                remoteDirectory,
                "--files-only",
                "--hash",
                "--no-modtime",
                "--no-mimetype",
            ),
            timeout,
        )
    }

    private fun classifyAbsence(
        result: ArchiveRemoteCommandResult,
        artifact: ChapterRevisionArchiveArtifact,
    ): ArchiveAbsenceVerification {
        result.startFailure?.let { failure ->
            return ArchiveAbsenceVerification.Unconfirmed("could not run $executable: $failure".asReason())
        }

        if (result.timedOut) {
            return ArchiveAbsenceVerification.Pending(
                "rclone did not answer within ${timeout.inWholeSeconds}s".asReason(),
            )
        }

        if (result.outputExceeded) {
            return ArchiveAbsenceVerification.Unconfirmed("rclone wrote more output than a verification allows".asReason())
        }

        val exitCode = result.exitCode
        if (exitCode == null) {
            return ArchiveAbsenceVerification.Unconfirmed("rclone did not report an exit code".asReason())
        }

        if (exitCode in ABSENT_EXIT_CODES) {
            // documented as "directory not found" / "object not found": the payload is really gone
            return ArchiveAbsenceVerification.ConfirmedAbsent
        }

        if (exitCode in PENDING_EXIT_CODES) {
            return ArchiveAbsenceVerification.Pending("rclone exit code $exitCode: ${diagnostic(result)}".asReason())
        }

        if (exitCode != 0) {
            return ArchiveAbsenceVerification.Unconfirmed("rclone exit code $exitCode: ${diagnostic(result)}".asReason())
        }

        val entries =
            parseEntries(result.standardOutput).getOrElse { e ->
                return ArchiveAbsenceVerification.Unconfirmed(
                    "could not read the rclone listing: ${e.message ?: e.javaClass.simpleName}".asReason(),
                )
            }

        val cbzName = artifact.relativeCbzPath.substringAfterLast('/')
        val stillPresent = entries.any { it.name == cbzName }

        return if (stillPresent) {
            ArchiveAbsenceVerification.StillPresent("$cbzName is still visible on the remote".asReason())
        } else {
            ArchiveAbsenceVerification.ConfirmedAbsent
        }
    }

    private fun classify(
        result: ArchiveRemoteCommandResult,
        artifact: ChapterRevisionArchiveArtifact,
    ): ArchiveVerification {
        result.startFailure?.let { failure ->
            return ArchiveVerification.Unconfirmed("could not run $executable: $failure".asReason())
        }

        if (result.timedOut) {
            return ArchiveVerification.Pending("rclone did not answer within ${timeout.inWholeSeconds}s".asReason())
        }

        if (result.outputExceeded) {
            return ArchiveVerification.Unconfirmed("rclone wrote more output than a verification allows".asReason())
        }

        val exitCode = result.exitCode
        if (exitCode == null) {
            return ArchiveVerification.Unconfirmed("rclone did not report an exit code".asReason())
        }

        if (exitCode in PENDING_EXIT_CODES) {
            return ArchiveVerification.Pending("rclone exit code $exitCode: ${diagnostic(result)}".asReason())
        }

        if (exitCode != 0) {
            return ArchiveVerification.Unconfirmed("rclone exit code $exitCode: ${diagnostic(result)}".asReason())
        }

        val entries =
            parseEntries(result.standardOutput).getOrElse { e ->
                return ArchiveVerification.Unconfirmed(
                    "could not read the rclone listing: ${e.message ?: e.javaClass.simpleName}".asReason(),
                )
            }

        val byName = entries.associateBy { it.name }
        val cbzName = artifact.relativeCbzPath.substringAfterLast('/')
        val manifestName = artifact.relativeManifestPath.substringAfterLast('/')

        val cbz =
            byName[cbzName]
                ?: return ArchiveVerification.Pending("$cbzName is not visible on the remote yet".asReason())
        val manifest =
            byName[manifestName]
                ?: return ArchiveVerification.Pending("$manifestName is not visible on the remote yet".asReason())

        if (cbz.size != artifact.cbzSize) {
            return ArchiveVerification.Unconfirmed(
                "remote $cbzName has size ${cbz.size}, expected ${artifact.cbzSize}".asReason(),
            )
        }
        if (manifest.size != artifact.manifestSize) {
            return ArchiveVerification.Unconfirmed(
                "remote $manifestName has size ${manifest.size}, expected ${artifact.manifestSize}".asReason(),
            )
        }

        hashMismatch(cbz, artifact.cbzSha256, cbzName)?.let { return it }
        hashMismatch(manifest, artifact.manifestSha256, manifestName)?.let { return it }

        return ArchiveVerification.Confirmed
    }

    /**
     * Classifies an integrity check, keeping absence, damage and an inconclusive check apart.
     *
     * The same command and the same remote boundary as [classify], but a different judgement: this is
     * a re-check of content that was confirmed before, so "not there" is a finding while a timeout or
     * an unparsable listing only says the check did not conclude anything.
     */
    private fun classifyIntegrity(
        result: ArchiveRemoteCommandResult,
        artifact: ChapterRevisionArchiveArtifact,
    ): ArchiveIntegrityVerification {
        result.startFailure?.let { failure ->
            return ArchiveIntegrityVerification.Failed("could not run $executable: $failure".asReason())
        }

        if (result.timedOut) {
            return ArchiveIntegrityVerification.Retryable(
                "rclone did not answer within ${timeout.inWholeSeconds}s".asReason(),
            )
        }

        if (result.outputExceeded) {
            return ArchiveIntegrityVerification.Failed("rclone wrote more output than a verification allows".asReason())
        }

        val exitCode = result.exitCode
        if (exitCode == null) {
            return ArchiveIntegrityVerification.Failed("rclone did not report an exit code".asReason())
        }

        if (exitCode in ABSENT_EXIT_CODES) {
            // documented as "directory not found" / "object not found": the payload is really gone
            return ArchiveIntegrityVerification.Missing("rclone exit code $exitCode: ${diagnostic(result)}".asReason())
        }

        if (exitCode in PENDING_EXIT_CODES) {
            return ArchiveIntegrityVerification.Retryable("rclone exit code $exitCode: ${diagnostic(result)}".asReason())
        }

        if (exitCode != 0) {
            return ArchiveIntegrityVerification.Failed("rclone exit code $exitCode: ${diagnostic(result)}".asReason())
        }

        val entries =
            parseEntries(result.standardOutput).getOrElse { e ->
                return ArchiveIntegrityVerification.Failed(
                    "could not read the rclone listing: ${e.message ?: e.javaClass.simpleName}".asReason(),
                )
            }

        val byName = entries.associateBy { it.name }
        val cbzName = artifact.relativeCbzPath.substringAfterLast('/')
        val manifestName = artifact.relativeManifestPath.substringAfterLast('/')

        val cbz = byName[cbzName]
        val manifest = byName[manifestName]

        if (cbz == null && manifest == null) {
            // an empty directory is the other shape a gone payload takes: the path exists, so the listing
            // succeeds, and it holds neither of the two objects the archive recorded
            return ArchiveIntegrityVerification.Missing(
                "neither $cbzName nor $manifestName is listed on the remote".asReason(),
            )
        }
        if (cbz == null) {
            return ArchiveIntegrityVerification.Missing("$cbzName is not listed on the remote".asReason())
        }
        if (manifest == null) {
            // The payload is there, but the manifest that is its immutable audit record is not. That is an
            // incomplete artifact set rather than a missing payload, and neither object can be trusted on
            // its own.
            return ArchiveIntegrityVerification.Corrupt("$manifestName is not listed beside $cbzName".asReason())
        }

        if (cbz.size != artifact.cbzSize) {
            return ArchiveIntegrityVerification.Corrupt(
                "remote $cbzName has size ${cbz.size}, expected ${artifact.cbzSize}".asReason(),
            )
        }
        if (manifest.size != artifact.manifestSize) {
            return ArchiveIntegrityVerification.Corrupt(
                "remote $manifestName has size ${manifest.size}, expected ${artifact.manifestSize}".asReason(),
            )
        }

        hashMismatchReason(cbz, artifact.cbzSha256, cbzName)?.let {
            return ArchiveIntegrityVerification.Corrupt(it.asReason())
        }
        hashMismatchReason(manifest, artifact.manifestSha256, manifestName)?.let {
            return ArchiveIntegrityVerification.Corrupt(it.asReason())
        }

        return ArchiveIntegrityVerification.Verified
    }

    private fun parseEntries(output: String): Result<List<RcloneEntry>> =
        runCatching { REMOTE_LISTING_JSON.decodeFromString(ListSerializer(RcloneEntry.serializer()), output) }

    /**
     * Compares the SHA-256 a backend reports with the locally computed one.
     *
     * A backend that reports a SHA-256 must report a valid 64-hex digest equal to the expected one;
     * a malformed digest is a failure rather than a licence to accept the file on its size. Only a
     * backend that reports no SHA-256 at all is confirmed by size alone.
     */
    private fun hashMismatch(
        entry: RcloneEntry,
        expected: String,
        name: String,
    ): ArchiveVerification? = hashMismatchReason(entry, expected, name)?.let { ArchiveVerification.Unconfirmed(it.asReason()) }

    /** The reason a reported digest does not match, or null when it matches or was not reported. */
    private fun hashMismatchReason(
        entry: RcloneEntry,
        expected: String,
        name: String,
    ): String? {
        val hashEntry =
            entry.hashes
                .orEmpty()
                .entries
                .firstOrNull { it.key.replace("-", "").equals(SHA256_HASH_KEY, ignoreCase = true) }
                ?: return null
        val reported = hashEntry.value.trim()

        if (!HEX_DIGEST.matches(reported)) {
            return "remote $name reported a malformed SHA-256: $reported"
        }

        return if (reported.equals(expected, ignoreCase = true)) {
            null
        } else {
            "remote $name has SHA-256 $reported, expected $expected"
        }
    }

    /**
     * Bounds a reason for the persisted error column and redacts configured values from it.
     *
     * rclone echoes the remote spec in its diagnostics and the requested remote may be sensitive, so
     * neither the configured remote nor the executable path is ever stored verbatim. Enough of the
     * bounded diagnostic text is kept to debug a real failure.
     */
    private fun String.asReason(): String {
        var redacted = this
        if (redactExecutable) {
            redacted = redacted.replace(executable, RCLONE_PLACEHOLDER)
        }
        redacted = redacted.replace(remoteRoot, REMOTE_PLACEHOLDER)
        if (remoteName.length >= MIN_REDACTABLE_REMOTE_NAME) {
            redacted = redacted.replace(remoteName, REMOTE_PLACEHOLDER)
        }

        return redacted.take(MAX_VERIFICATION_REASON_LENGTH)
    }

    /** Bounded, single-line diagnostic so rclone output never floods the persisted error column. */
    private fun diagnostic(result: ArchiveRemoteCommandResult): String =
        (result.standardError.ifBlank { result.standardOutput })
            .trim()
            .replace(WHITESPACE, " ")

    private companion object {
        /**
         * Documented rclone exit codes that mean "not visible yet" rather than a real failure:
         * 3 directory not found, 4 file not found, 5 temporary error (documented as retryable).
         * Authentication, configuration and syntax errors are deliberately not retryable.
         */
        val PENDING_EXIT_CODES = setOf(3, 4, 5)

        /**
         * Documented rclone exit codes that prove the requested path is not there at all:
         * 3 directory not found, 4 object not found. For an absence check these are a confirmation,
         * not a failure.
         */
        val ABSENT_EXIT_CODES = setOf(3, 4)

        /** Compared after removing '-' so both `SHA-256` and `SHA256` are recognised. */
        const val SHA256_HASH_KEY = "sha256"

        /** The configured executable name that needs no redaction. */
        const val DEFAULT_EXECUTABLE_NAME = "rclone"

        val HEX_DIGEST = Regex("[0-9a-fA-F]{64}")

        val WHITESPACE = Regex("\\s+")

        /** Shorter names are left alone so redaction can not mangle unrelated words. */
        const val MIN_REDACTABLE_REMOTE_NAME = 3

        val REMOTE_LISTING_JSON = Json { ignoreUnknownKeys = true }
    }
}

/** Bounds a verification reason to what the persisted archive error column can hold. */
private const val MAX_VERIFICATION_REASON_LENGTH = 400

/** Placeholder used when a diagnostic is persisted, so configuration is never leaked. */
private const val REMOTE_PLACEHOLDER = "<remote>"

private const val RCLONE_PLACEHOLDER = "<rclone>"

/** A named rclone remote: `name:path`, where the name is a config section name. */
private val NAMED_REMOTE = Regex("[A-Za-z0-9][A-Za-z0-9_. -]*:.*")

/** An on-the-fly rclone remote: `:backend,param=value:path`, the backend being the first token. */
private val ON_THE_FLY_REMOTE = Regex(":[A-Za-z0-9_][^:]*:.*")

/** A local path, including a Windows drive, which would verify the mounted copy instead. */
private val LOCAL_PATH = Regex("[A-Za-z]:[/\\\\].*")

/**
 * Rejects a configured rclone executable that no command may be started with at all.
 *
 * A blank value or one carrying control characters cannot name a program, and `ProcessBuilder` would
 * either fail on it or be handed something that is not a command; both are refused here instead.
 */
internal fun requireSafeExecutable(value: String): String {
    require(value.isNotBlank()) { "The rclone executable must not be blank" }
    require(value.none { it.isISOControl() }) { "The rclone executable must not contain control characters" }

    return value
}

/**
 * Rejects a remote spec that would make rclone read its own options or verify a local mount.
 *
 * `ProcessBuilder` already avoids shell interpretation, but a value such as `--config=/x` starts
 * with `-` and is rejected anyway, and a local path such as `/mnt/archive` or `C:/mount` would
 * verify exactly the mounted copy that this check exists to distrust. Only `/` is normalized.
 */
internal fun requireSafeRemoteSpec(value: String): String {
    val spec = value.trim()
    require(spec.isNotBlank()) { "The rclone remote must not be blank" }
    require(spec.none { it.isISOControl() }) { "The rclone remote must not contain control characters" }
    require(!spec.startsWith("-")) { "The rclone remote must not start with '-'" }
    require(!spec.contains('\\')) { "The rclone remote must use '/' as its path separator" }
    require(!spec.startsWith("/")) { "The rclone remote must name a remote, not a local path" }
    require(!LOCAL_PATH.matches(spec)) { "The rclone remote must name a remote, not a local path" }
    require(NAMED_REMOTE.matches(spec) || ON_THE_FLY_REMOTE.matches(spec)) {
        "The rclone remote must use the 'remote:path' or ':backend,param=value:path' form"
    }

    return spec.trimEnd('/')
}
