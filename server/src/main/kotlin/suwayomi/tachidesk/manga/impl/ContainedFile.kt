package suwayomi.tachidesk.manga.impl

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

import java.io.File

/**
 * Resolves a location beneath [root], or null when it would leave it.
 *
 * Every stored location that addresses an archive or staging artifact is checked through this, so a
 * relative path - however the column it came from was filled in - can only ever name a file inside the
 * root it belongs to. The check is on canonical paths, so a symbolic link or a redundant segment is
 * resolved before it is compared, and the root itself is never accepted as a target.
 */
internal fun containedFile(
    root: File,
    relativePath: String,
): File? {
    if (relativePath.isBlank()) return null
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
    val target = runCatching { File(canonicalRoot, relativePath).canonicalFile }.getOrNull() ?: return null
    val prefix = canonicalRoot.path + File.separator
    return target.takeIf { it.path.startsWith(prefix) }
}
