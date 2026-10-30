package suwayomi.tachidesk.manga.model.table

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import suwayomi.tachidesk.manga.model.dataclass.ChapterPublicationEventDataClass

/**
 * Durable outbox of post-publication events.
 *
 * Publication must not be rolled back by a listener failure, and a listener that was down must still
 * see the event afterwards, so an event is recorded in the same commit that publishes the revision
 * and only then handed to listeners. [deliveredAt] stays null until every listener accepted it, which
 * makes an undelivered event observable and retryable instead of silently lost.
 */
object ChapterPublicationEventTable : IntIdTable() {
    /** the revision that was published; SET NULL so history outlives deleted source rows */
    val revision = optReference("chapter_revision", ChapterRevisionTable, ReferenceOption.SET_NULL)

    /** immutable chapter identity and discovery key, kept readable after the revision row is gone */
    val chapterKey = varchar("chapter_key", 64)
    val candidateKey = varchar("candidate_key", 64)
    val mangaId = integer("manga_id")

    val eventType = varchar("event_type", 64)
    val payload = text("payload")

    val occurredAt = long("occurred_at")
    val attempts = integer("attempts").default(0)
    val lastAttemptAt = long("last_attempt_at").nullable()
    val lastError = varchar("last_error", 4096).nullable()
    val deliveredAt = long("delivered_at").nullable()

    init {
        // the delivery loop only ever looks for events that have not been delivered yet
        index("chapter_publication_event_backlog_idx", false, deliveredAt, id)
    }
}

fun ChapterPublicationEventTable.toDataClass(eventEntry: ResultRow) =
    ChapterPublicationEventDataClass(
        id = eventEntry[id].value,
        revisionId = eventEntry[revision]?.value,
        chapterKey = eventEntry[chapterKey],
        candidateKey = eventEntry[candidateKey],
        mangaId = eventEntry[mangaId],
        eventType = eventEntry[eventType],
        payload = eventEntry[payload],
        occurredAt = eventEntry[occurredAt],
        attempts = eventEntry[attempts],
        lastAttemptAt = eventEntry[lastAttemptAt],
        lastError = eventEntry[lastError],
        deliveredAt = eventEntry[deliveredAt],
    )
