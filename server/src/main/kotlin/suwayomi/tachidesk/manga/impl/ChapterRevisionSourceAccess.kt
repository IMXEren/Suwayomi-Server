package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import suwayomi.tachidesk.manga.impl.util.source.GetSource.getSourceOrNull
import suwayomi.tachidesk.manga.model.dataclass.ChapterRevisionDataClass
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

/** Raised when a candidate revision can not be retrieved from its source. */
class ChapterRevisionSourceException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Retrieves the page list and the page bytes of a candidate revision from its source.
 *
 * Retrieval always goes through the source itself and never through the download helpers, so an
 * older legacy download of the same chapter can not be mistaken for the current source content.
 * Kept as a narrow interface so acquisition can be exercised without a real extension.
 */
interface ChapterRevisionSourceAccess {
    suspend fun getPageList(revision: ChapterRevisionDataClass): List<Page>

    suspend fun openPage(
        revision: ChapterRevisionDataClass,
        page: Page,
    ): InputStream
}

object DefaultChapterRevisionSourceAccess : ChapterRevisionSourceAccess {
    override suspend fun getPageList(revision: ChapterRevisionDataClass): List<Page> {
        val source = sourceFor(revision)
        return try {
            source.getPageList(toSChapter(revision))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ChapterRevisionSourceException("Failed to fetch the page list of ${revision.sourceChapterUrl}", e)
        }
    }

    override suspend fun openPage(
        revision: ChapterRevisionDataClass,
        page: Page,
    ): InputStream {
        val source = sourceFor(revision)
        return try {
            when (source) {
                is LocalSource -> openLocalPage(revision, page)
                is HttpSource -> openHttpPage(source, page)
                else -> throw ChapterRevisionSourceException("Source ${source.id} does not serve pages")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChapterRevisionSourceException) {
            throw e
        } catch (e: Exception) {
            throw ChapterRevisionSourceException("Failed to fetch page ${page.index} of ${revision.sourceChapterUrl}", e)
        }
    }

    private suspend fun sourceFor(revision: ChapterRevisionDataClass): Source =
        revision.sourceId?.let { getSourceOrNull(it) }
            ?: throw ChapterRevisionSourceException("Source ${revision.sourceId} is not available")

    private fun toSChapter(revision: ChapterRevisionDataClass): SChapter =
        SChapter.create().apply {
            url = revision.sourceChapterUrl
            name = revision.name
            scanlator = revision.scanlator
            date_upload = revision.uploadDate
            chapter_number = revision.chapterNumber
            memo = revision.memo
        }

    private suspend fun openHttpPage(
        source: HttpSource,
        page: Page,
    ): InputStream {
        // extensions may only expose the real image url through a separate request
        if (page.imageUrl.isNullOrBlank()) {
            page.imageUrl = source.getImageUrl(page)
        }

        val response = source.getImage(page)
        // the page stream is only valid while the response is open, so closing it closes both
        return object : FilterInputStream(response.body.byteStream()) {
            override fun close() {
                super.close()
                response.close()
            }
        }
    }

    private fun openLocalPage(
        revision: ChapterRevisionDataClass,
        page: Page,
    ): InputStream {
        // archive backed local chapters materialize their pages into the local source page cache
        LocalSource.pageCache[revision.sourceChapterUrl]
            ?.getOrNull(page.index)
            ?.let { return it() }

        page.imageUrl?.takeIf { it.isNotBlank() }?.let { return FileInputStream(File(it)) }

        throw ChapterRevisionSourceException("Local page ${page.index} of ${revision.sourceChapterUrl} is not available")
    }
}
