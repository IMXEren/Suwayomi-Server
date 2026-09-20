package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationEventDataClass
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import suwayomi.tachidesk.manga.model.table.ChapterPublicationEventTable
import suwayomi.tachidesk.manga.model.table.toDataClass
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

/** Kinds of post-publication event. Only one exists so far, so consumers can switch exhaustively. */
enum class ChapterPublicationEventType {
    CHAPTER_REVISION_PUBLISHED,
}

/**
 * What a published revision looks like to a consumer.
 *
 * The identity fields are the durable ones - the chapter identity key and the record ids - while the
 * display fields are carried along purely as convenience for a reader-facing library. A consumer must
 * not treat the mutable display fields as identity.
 */
@Serializable
data class ChapterRevisionPublishedPayload(
    val schemaVersion: Int,
    val revisionId: Int,
    val chapterKey: String,
    val candidateKey: String,
    val mangaId: Int,
    val chapterId: Int?,
    val sourceId: Long?,
    val sourceMangaUrl: String?,
    val sourceChapterUrl: String,
    val chapterNumber: Float,
    val chapterTitle: String,
    val scanlator: String?,
    val activeCbzPath: String,
    val activeCbzHash: String,
    val activeCbzSize: Long,
    /** the active copy this publication replaced, when there was one */
    val replacedActiveCbzPath: String?,
    val publishedAt: Long,
)

/** One durable post-publication event, as handed to listeners. */
data class ChapterPublicationEvent(
    val id: Int,
    val type: ChapterPublicationEventType,
    val occurredAt: Long,
    val payload: ChapterRevisionPublishedPayload,
)

/**
 * A consumer of post-publication events, for example a library layer that has to rescan.
 *
 * Delivery is at-least-once and only ever happens after the publication transaction committed, so a
 * listener must be idempotent: it can see the same event twice. A listener that throws fails only its
 * own delivery attempt; the publication itself stays committed, and the event stays undelivered so it
 * is retried instead of being lost.
 */
fun interface ChapterRevisionPublicationListener {
    suspend fun onPublished(event: ChapterPublicationEvent)
}

/** Registered [ChapterRevisionPublicationListener]s. Deliberately provider agnostic. */
object ChapterRevisionPublicationListenerRegistry {
    private val listeners = CopyOnWriteArrayList<ChapterRevisionPublicationListener>()

    fun register(listener: ChapterRevisionPublicationListener) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: ChapterRevisionPublicationListener) {
        listeners.remove(listener)
    }

    internal fun snapshot(): List<ChapterRevisionPublicationListener> = listeners.toList()
}

/**
 * Durable post-publication outbox.
 *
 * An event is recorded in the same commit that publishes the revision, so a published revision can
 * never exist without its event. Delivery then happens after that commit and is retried until every
 * registered listener accepted the event, which keeps a broken or absent listener from either rolling
 * back a publication or silently dropping the notification.
 */
object ChapterRevisionPublicationEvents {
    private const val SCHEMA_VERSION = 1
    private const val EVENT_TYPE_PUBLISHED = "CHAPTER_REVISION_PUBLISHED"
    private const val MAX_ERROR_LENGTH = 4096

    private val json = Json { encodeDefaults = true }

    /** Records the publication event inside the caller's publication transaction. */
    fun recordPublished(
        revision: ChapterRevisionDataClass,
        replacedActiveCbzPath: String?,
        now: Long = Instant.now().epochSecond,
    ): Int {
        val activeCbzPath = revision.activeCbzPath ?: throw IllegalStateException("revision ${revision.id} has no active copy")
        val activeCbzHash = revision.activeCbzHash ?: throw IllegalStateException("revision ${revision.id} has no active copy hash")
        val activeCbzSize = revision.activeCbzSize ?: throw IllegalStateException("revision ${revision.id} has no active copy size")
        val mangaId = revision.mangaId ?: throw IllegalStateException("revision ${revision.id} has no manga")

        val payload =
            ChapterRevisionPublishedPayload(
                schemaVersion = SCHEMA_VERSION,
                revisionId = revision.id,
                chapterKey = revision.chapterKey,
                candidateKey = revision.candidateKey,
                mangaId = mangaId,
                chapterId = revision.chapterId,
                sourceId = revision.sourceId,
                sourceMangaUrl = revision.sourceMangaUrl,
                sourceChapterUrl = revision.sourceChapterUrl,
                chapterNumber = revision.chapterNumber,
                chapterTitle = revision.name,
                scanlator = revision.scanlator,
                activeCbzPath = activeCbzPath,
                activeCbzHash = activeCbzHash,
                activeCbzSize = activeCbzSize,
                replacedActiveCbzPath = replacedActiveCbzPath,
                publishedAt = revision.publishedAt ?: now,
            )

        return transaction {
            ChapterPublicationEventTable
                .insertAndGetId {
                    it[ChapterPublicationEventTable.revision] = revision.id
                    it[chapterKey] = revision.chapterKey
                    it[candidateKey] = revision.candidateKey
                    it[ChapterPublicationEventTable.mangaId] = mangaId
                    it[eventType] = EVENT_TYPE_PUBLISHED
                    it[ChapterPublicationEventTable.payload] = json.encodeToString(payload)
                    it[occurredAt] = now
                }.value
        }
    }

    /** True while at least one event has not been delivered yet. */
    fun hasPending(): Boolean =
        transaction {
            ChapterPublicationEventTable
                .selectAll()
                .where { ChapterPublicationEventTable.deliveredAt.isNull() }
                .limit(1)
                .firstOrNull() != null
        }

    /**
     * Delivers the oldest undelivered event to every registered listener.
     *
     * Returns true only when the event really was delivered, so the caller can distinguish "nothing
     * left to do" from "a listener failed and this has to be retried later".
     */
    suspend fun deliverPending(now: Long = Instant.now().epochSecond): Boolean {
        val pending = nextPending() ?: return false

        val event =
            decode(pending) ?: run {
                // an event that cannot be read back can never be delivered; keep it observable
                markDeliveryFailed(pending.id, "the recorded event payload could not be read back", now)
                return false
            }

        val listeners = ChapterRevisionPublicationListenerRegistry.snapshot()
        if (listeners.isEmpty()) {
            // nothing is listening, so there is nothing to deliver to
            markDelivered(pending.id, now)
            return true
        }

        listeners.forEach { listener ->
            try {
                listener.onPublished(event)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "A chapter publication listener failed for event ${pending.id}" }
                markDeliveryFailed(pending.id, e.message ?: e.javaClass.simpleName, now)
                return false
            }
        }

        markDelivered(pending.id, now)
        return true
    }

    private fun nextPending(): ChapterPublicationEventDataClass? =
        transaction {
            ChapterPublicationEventTable
                .selectAll()
                .where { ChapterPublicationEventTable.deliveredAt.isNull() }
                .orderBy(ChapterPublicationEventTable.id to SortOrder.ASC)
                .limit(1)
                .firstOrNull()
                ?.let { ChapterPublicationEventTable.toDataClass(it) }
        }

    private fun markDelivered(
        id: Int,
        now: Long,
    ): Boolean =
        transaction {
            val attempts = currentAttempts(id)

            ChapterPublicationEventTable.update({
                (ChapterPublicationEventTable.id eq id) and (ChapterPublicationEventTable.deliveredAt.isNull())
            }) {
                it[ChapterPublicationEventTable.deliveredAt] = now
                it[ChapterPublicationEventTable.attempts] = attempts + 1
                it[ChapterPublicationEventTable.lastAttemptAt] = now
                it[ChapterPublicationEventTable.lastError] = null
            } > 0
        }

    private fun markDeliveryFailed(
        id: Int,
        error: String,
        now: Long,
    ): Boolean =
        transaction {
            val attempts = currentAttempts(id)

            ChapterPublicationEventTable.update({ ChapterPublicationEventTable.id eq id }) {
                it[ChapterPublicationEventTable.attempts] = attempts + 1
                it[ChapterPublicationEventTable.lastAttemptAt] = now
                it[ChapterPublicationEventTable.lastError] = error.take(MAX_ERROR_LENGTH)
            } > 0
        }

    private fun currentAttempts(id: Int): Int =
        ChapterPublicationEventTable
            .selectAll()
            .where { ChapterPublicationEventTable.id eq id }
            .first()[ChapterPublicationEventTable.attempts]

    private fun decode(row: ChapterPublicationEventDataClass): ChapterPublicationEvent? {
        val type =
            ChapterPublicationEventType.entries.firstOrNull { it.name == row.eventType }
                ?: return null
        val payload =
            runCatching { json.decodeFromString<ChapterRevisionPublishedPayload>(row.payload) }
                .getOrNull()
                ?: return null

        return ChapterPublicationEvent(row.id, type, row.occurredAt, payload)
    }
}
