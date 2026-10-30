package suwayomi.tachidesk.manga.model.dataclass

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

/**
 * One durable record in the post-publication outbox.
 *
 * [revisionId] is nullable because the event must survive the disappearance of the revision row it
 * was emitted for; [chapterKey] and [candidateKey] keep it intelligible afterwards.
 */
data class ChapterPublicationEventDataClass(
    val id: Int,
    val revisionId: Int?,
    val chapterKey: String,
    val candidateKey: String,
    val mangaId: Int,
    val eventType: String,
    val payload: String,
    val occurredAt: Long,
    val attempts: Int,
    val lastAttemptAt: Long?,
    val lastError: String?,
    val deliveredAt: Long?,
)
