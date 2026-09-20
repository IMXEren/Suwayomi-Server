# Personal Manga/Manhwa Archival and Update System

## 1. Goal

The aim is to build a personal manga/manhwa/comic library that behaves more like a self-hosted media automation system than a normal manga reader.

The system should allow a series to be queued once, acquire its existing chapters, archive those chapters in inexpensive remote object storage, continue tracking the original source for future updates, and expose the resulting personal library through Mihon-compatible readers.

The important distinction is that the system is not simply meant to be a remotely hosted Suwayomi installation.

It should provide:

* permanent archival;
* automated or manually approved updates;
* detection of revised/reuploaded chapters;
* visual comparison of chapter revisions;
* good library organization through Komga;
* Mihon-compatible reading;
* minimal physical storage requirements;
* inexpensive scalable storage such as Cloudflare R2 or Backblaze B2;
* resilience against a source disappearing later.

The desired mental model is approximately:

```text
Sonarr/Radarr-style acquisition
        +
Suwayomi/Mihon source ecosystem
        +
Komga library management
        +
object-storage archive
        +
Mihon-compatible reader
```

---

# 2. Overall architecture

The recommended separation of responsibilities is:

```text
                   Manga sources
                        │
                        ▼
                   Suwayomi fork
                acquisition / updates
                        │
                        ▼
                  local staging
                        │
                        ▼
                 accepted chapter
                        │
                        ▼
              mounted object storage
                   R2 / B2 / S3
                        │
                        ▼
                      Komga
               canonical user library
                        │
                        ▼
               Komga Mihon extension
                        │
                        ▼
                Mihon-compatible app
```

Each component should have a narrowly defined responsibility.

### Suwayomi

Suwayomi should be responsible for:

* running Mihon/Tachiyomi-compatible extensions;
* searching sources;
* adding source manga;
* retrieving chapter lists;
* detecting new chapters;
* detecting possible reuploads/revisions;
* applying per-series update policies;
* maintaining acquisition queues;
* downloading chapter candidates;
* coordinating manual approval.

It should not become the authoritative archival library.

### Object storage

R2/B2/S3 should be responsible for:

* permanent chapter storage;
* large-scale inexpensive capacity;
* preserving accepted releases independently of the original source;
* making local disk capacity mostly irrelevant.

### Komga

Komga should be responsible for:

* library indexing;
* collections;
* reading lists;
* metadata presentation/editing;
* reading progress;
* user-facing organization;
* exposing the archive to Mihon-compatible clients.

Komga therefore becomes the canonical **library interface**, while object storage remains the canonical **file archive**.

### Mihon-compatible application

The Android reader remains a client rather than the owner of the library.

It should read the archived library through Komga's Mihon-compatible source integration and optionally maintain a small device-side cache/offline selection.

---

# 3. Why Suwayomi rather than Mangarr or Tranga

Mangarr and Tranga solve similar acquisition problems, but Suwayomi is particularly attractive because the user already relies heavily on Mihon-compatible applications and sources.

Suwayomi provides the most useful component to avoid rebuilding:

```text
Mihon-compatible extension ecosystem
                ↓
source adapters
                ↓
search / metadata / chapter lists / page acquisition
```

Building another downloader would mean independently maintaining site-specific connectors, anti-bot behavior, authentication differences, and source changes.

Suwayomi therefore makes the best acquisition foundation.

Mangarr and Tranga remain useful reference implementations for concepts such as monitoring, queues, automatic acquisition and media-library rescans.

---

# 4. Why Suwayomi should be forked

Stock Suwayomi's update model is useful, but the desired update semantics are richer.

The normal idea is approximately:

```text
library manga
     ↓
check source
     ↓
discover chapters
     ↓
optionally auto-download
```

The required model is closer to:

```text
tracked source manga
        ↓
discover source state
        ↓
classify change
        ↓
apply acquisition policy
        ↓
possibly stage candidate
        ↓
possibly visually compare revision
        ↓
manual/automatic decision
        ↓
commit accepted chapter
        ↓
archive + Komga
```

The fork should therefore concentrate on orchestration and revision management while preserving Suwayomi's existing extension and download infrastructure as much as possible.

Deep changes to extension handling should be avoided.

---

# 5. Per-series update policies

Update behavior should be configured individually for each series.

A useful initial policy model is:

```text
AUTO
MANUAL
NOTIFY_ONLY
PAUSED
```

## AUTO

New chapters are automatically queued and acquired.

A safe revision policy can still require review if an already archived chapter changes materially.

Example:

```text
new chapter
    ↓
download
    ↓
archive automatically

existing chapter changed
    ↓
candidate download
    ↓
image diff
    ↓
meaningful changes?
    ├─ no  → ignore
    └─ yes → manual review
```

## MANUAL

Suwayomi checks for new chapters, creates persistent actionable entries in an approval queue, and does not acquire them until approved.

Example:

```text
Chapter 104 available
Chapter 105 available

[Archive 104]
[Archive 105]
[Archive all]
[Ignore]
```

## NOTIFY_ONLY

`NOTIFY_ONLY` was an illustrative policy sketch and is **not implemented**. The confirmed v1 policy set is exactly `AUTO`, `MANUAL` and `PAUSED`.

It is kept here as a description of a possible later policy: source updates emit notifications or activity records, but do not create approval-queue entries or download candidates, and an explicit archive request first promotes an update into the normal acquisition flow. A future implementation would need its own notification/activity record and its own persistence in backup, SyncYomi and the WebUI; none of that exists today.

## PAUSED

No automatic update checking or acquisition for that series.

Additional policies can eventually be introduced, such as:

```text
NOTIFY_ONLY
ARCHIVE_EXISTING_ONLY
ON_DEMAND
AUTO_NEW_MANUAL_REVISIONS
```

but these should not complicate the first implementation unnecessarily.

---

# 6. Required acquisition state machine

A proper state model is preferable to simple downloaded/not-downloaded booleans, but acquisition, archival durability and library publication should not be represented by one linear enum. They can fail and retry independently.

Use three related state dimensions.

### Acquisition state

```text
DISCOVERED
    ↓
PENDING_APPROVAL
    ↓
APPROVED
    ↓
QUEUED
    ↓
DOWNLOADING
    ↓
DOWNLOADED_LOCAL
    ↓
VALIDATING
    ↓
COMPLETE
```

Automatic policies can skip `PENDING_APPROVAL` and `APPROVED`.

### Archive state

```text
NOT_COMMITTED
    ↓
COMMITTING
    ↓
REMOTE_PENDING
    ↓
REMOTE_CONFIRMED
```

### Publication state

```text
NOT_PUBLISHED
    ↓
PUBLISHING
    ↓
PUBLISHED
```

Publication here means the archived revision's bytes are in place as the active copy on the mounted archive. Komga indexing is **not** part of this dimension: it is tracked by its own durable scan intent.

### Library-indexing state

```text
PENDING
    ↓
RUNNING
    ↓
COMPLETE
```

### Retention state

```text
RETAINED
    ↓
PRUNE_QUEUED
    ↓
DELETING
    ↓
REMOTE_DELETE_PENDING
    ↓
PRUNED
```

### Integrity state

```text
NEVER_AUDITED
    ↓
VERIFIED
```

Failures remain explicit within their own dimension:

```text
acquisition: DOWNLOAD_FAILED / VALIDATION_FAILED
archive:     COMMIT_FAILED / ARCHIVE_UNCONFIRMED
publication: PUBLICATION_FAILED
indexing:    FAILED
retention:   PRUNE_FAILED (only an explicit retry requeues it)
integrity:   MISSING / CORRUPT / AUDIT_FAILED
```

A chapter can therefore be acquisition-complete and remotely confirmed while its Komga rescan is still failing. Retrying publication must not redownload or recommit the chapter, and retrying a scan must not republish it.

---

# 7. Staging versus archive storage

Suwayomi should not directly work on unfinished downloads inside the permanent archive.

Use two areas:

```text
/staging
/archive
```

`/staging` should preferably be ordinary local storage.

`/archive` can be an object-storage-backed filesystem mount.

Example:

```text
Suwayomi
   │
   ▼
/staging/Series/Chapter-42/
   │
   │ download completed
   ▼
build/finalize CBZ
   │
   ▼
/archive/Series/Chapter-42.cbz
   │
   ▼
rclone VFS
   │
   ▼
R2/B2
```

This prevents Komga from observing:

* partially downloaded chapters;
* half-created CBZ files;
* revision candidates that have not been accepted;
* temporary files.

---

# 8. Mounted object storage

Because the object store can be mounted through something such as rclone, Suwayomi does not need native S3/R2 integration.

That is preferable to adding code such as:

```text
R2StorageBackend
B2StorageBackend
S3StorageBackend
```

The operating system can instead present:

```text
/archive
```

as a filesystem.

The application then remains storage-provider-independent.

That means the same code works with:

```text
local filesystem
NAS
NFS
SMB
rclone + R2
rclone + B2
rclone + S3
future storage backend
```

A dedicated "upload to S3" stage is therefore unnecessary.

---

# 9. Caveat: an object-storage mount is not a normal filesystem

This abstraction is convenient, but the underlying semantics remain different.

For example:

```text
mv /staging/ch.cbz /archive/series/ch.cbz
```

looks like a normal move.

If `/staging` and `/archive` reside on different filesystems, however, the operation effectively becomes:

```text
read local file
    ↓
write into rclone mount/cache
    ↓
upload object
    ↓
delete staging copy
```

The write may return before the remote object has necessarily reached durable storage depending on mount/cache configuration. The staging copy must therefore be retained after the archive-path write closes.

A concrete rclone-backed commit is:

```text
compute local CBZ size + SHA-256
        ↓
copy into archive mount
        ↓
close file
        ↓
mark REMOTE_PENDING
        ↓
wait until rclone no longer reports the object as queued/uploading
        ↓
query the configured remote through rclone, bypassing the mount cache
        ↓
confirm object exists and size matches
        ↓
mark REMOTE_CONFIRMED
        ↓
delete staging copy
```

The exact queue/status operation should be encapsulated by an `ArchiveCommitVerifier`; Suwayomi should not contain provider-specific R2/B2 code. A local/NFS implementation can use filesystem durability operations, while an rclone implementation can use rclone's command or remote-control interface.

Remote size confirmation detects incomplete uploads but is not a full integrity proof. Preserve the local SHA-256 in the archive manifest and run a later integrity audit using a remote checksum when the backend exposes a trustworthy one, or a full read-back when it does not. A stronger optional `REMOTE_VERIFIED` state can record that audit.

---

# 10. rclone VFS cache

A local VFS cache is strongly recommended.

Its purposes differ depending on direction.

For writes:

```text
Suwayomi
   ↓
rclone VFS write cache
   ↓
R2/B2
```

For reads:

```text
Komga
   ↓
rclone VFS
   ↓
local cache hit?
   ├─ yes → local disk
   └─ no  → object storage
```

Even tens of gigabytes of cache can hold many recently accessed chapters without requiring the machine to store the whole archive.

---

# 11. Deployment topology and read-path caveat

The design must not require Suwayomi, rclone and Komga to run on one Proxmox host. They may initially be colocated, moved together to a VPS, or split later.

The required relationships are:

```text
Suwayomi host
├── local staging
├── archive committer/verifier
└── access to archive object storage

Komga host
├── independent access to the active archive view
└── network-reachable API for rescan requests
```

When the components are colocated, both applications can use the same rclone mount and VFS cache. When split, each host can mount the same object store independently, or the active library can be exposed to Komga through another filesystem service. A `PostCommitHook` should invoke Komga through its configured HTTP API rather than assuming localhost or direct process access.

Wherever Komga runs, it remains in the critical reading path:

```text
Mihon
   ↓
Komga host
   ↓
rclone cache/mount
   ↓
R2/B2
```

The Komga host's network connection, especially upload capacity toward the reader, can therefore limit throughput. A local rclone cache reduces repeated object-store reads but does **not** remove the need for the client to retrieve pages through Komga.

Initially this is an acceptable architectural compromise because it avoids custom client development. If it later becomes a real bottleneck, a future optimization could preserve Komga as the metadata/control plane while allowing image/chapter payloads to be delivered directly from object storage using signed URLs.

That optimization should not be part of v1.

---

# 12. Komga as the personal library layer

Komga adds substantial value beyond Suwayomi.

Suwayomi answers:

> Where can I acquire this manga and are there new chapters?

Komga answers:

> What does my personal library contain and how do I organize and read it?

Komga should own user-facing concepts such as:

```text
Libraries
Collections
Reading lists
Metadata
Read progress
Library organization
```

Possible examples:

```text
Libraries
├── Manga
├── Manhwa
├── Manhua
└── Finished

Collections
├── Favorites
├── Currently Publishing
├── Read Later
└── Re-read
```

The archive files should be sufficiently independent that replacing Komga later does not destroy the collection.

---

# 13. Komf

Komf can be added optionally for richer metadata.

The logical relationship is:

```text
AniList / MAL / MangaUpdates / etc.
              ↓
             Komf
              ↓
             Komga
```

Komf should not participate in acquisition.

Its role is metadata enrichment after a work has entered the library.

It can remain optional because it is not essential to the archival/update system itself.

---

# 14. Komga integration with the Suwayomi fork

Komga is not hard-coded deeply into Suwayomi. A generic post-commit/event mechanism carries it:

```text
ChapterCommitted
      ↓
listeners
  ├─ Komga refresh
  ├─ webhook
  ├─ notification
  └─ logging
```

Implemented state: publication writes a durable outbox event (`ChapterRevisionPublicationEvents`). The Komga listener is the only listener built today; it consumes those events into one coalesced, generation-fenced scan intent and sends an authenticated `POST` to the configured library scan endpoint, so a burst of publications debounces to a single restart-safe scan. The outbox itself is generic, so a webhook, notification or logging listener could be added without touching publication.

**Not validated live:** the endpoint path, the header form and Komga's response handling are exercised against an injected HTTP client, not against a running Komga instance.

---

# 15. Chapter revisions

Revision awareness is the largest behavioral difference from stock Suwayomi.

The system should not assume:

```text
one logical chapter = one permanent immutable source item
```

Instead:

```text
Chapter 42
├── revision 1
├── revision 2
└── revision 3
```

Only one revision normally needs to be active in Komga.

Previous accepted revisions can remain stored separately.

---

# 16. Do not rewrite Suwayomi's entire chapter model initially

A full transformation from:

```text
Manga
  └── Chapter
```

into:

```text
Manga
  └── Chapter
       └── Revision[]
```

would potentially affect many existing APIs and behaviors.

A safer design is a parallel revision table.

Conceptually:

```text
Chapter
   │
   │ currently active version
   ▼
ChapterRevision
├── old accepted revision
├── superseded revision
└── pending candidate
```

Possible fields:

```text
id
chapterId
sourceUrl
discoveredAt
pageCount
scanlator
candidatePath
contentHash
status
diffResult
```

Revision status might be:

```text
CANDIDATE
ACCEPTED
REJECTED
SUPERSEDED
```

This allows most existing Suwayomi code to continue seeing a normal active Chapter.

---

# 17. Revision detection

Source metadata alone cannot reliably tell whether the page content changed.

The Keiyoushi extension API confirms this limitation. Its standard chapter object exposes only:

```text
url
name
chapter_number
scanlator
date_upload
memo
```

and its standard page object exposes only:

```text
index
url
imageUrl
```

There is no standardized chapter revision ID, update timestamp, content digest or ETag. `memo` can carry source-specific data, but no common revision key is currently required.

Representative extensions expose different amounts of useful information:

* MangaDex receives `pages` and `updatedAt` in its chapter DTO, but currently maps `publishAt` to `date_upload` and does not copy `updatedAt` into the returned chapter. Its page-list response contains a MangaDex content hash, but that is source-specific.
* Comick returns the chapter's creation timestamp.
* Webtoons returns its exposure timestamp.
* Asura Scans returns its creation timestamp.
* Some other sources map an API `updatedAt` value into `date_upload`, making that field a useful change hint, but this behavior is not universal.

Current Suwayomi reconciliation also matches existing chapters by `url`. For the same URL, refreshed metadata is overwritten in place; a changed URL is normally treated as a removed chapter plus a new chapter. The fork must compare the old and fetched snapshots **before** this overwrite/delete logic and preserve the old snapshot in revision history.

A chapter may change because:

* its source URL changed;
* title changed;
* scanlator changed;
* upload or update timestamp changed;
* page count changed;
* pages were replaced at unchanged URLs;
* translation corrections were uploaded;
* credit pages changed;
* images were recompressed;
* the source deleted/recreated the chapter.

Revision discovery should therefore use three evidence levels:

```text
METADATA_HINT
    chapter URL/name/number/scanlator/date/memo changed

MANIFEST_HINT
    getPageList changed page count or returned a different page manifest

CONTENT_PROOF
    downloaded page-byte hashes or perceptual fingerprints changed
```

Metadata and page-manifest differences create a **candidate revision**; they do not prove that the visible content differs. Page URLs may contain rotating hosts, signatures or expiry tokens, while a source may replace bytes at an unchanged URL.

Only `CONTENT_PROOF` is source-independent. Consequently, guaranteeing detection of every revision requires periodically downloading existing chapters again. This should be an explicit per-series revision-sweep policy rather than hidden behavior:

```text
SIGNALS_ONLY
RECENT_CHAPTER_SWEEP
SCHEDULED_FULL_SWEEP
DISABLED
```

The initial default should be:

```text
normal source update     → inspect metadata signals
suspicious signal        → probe manifest, then download candidate
recent revision sweep    → newest 10 chapters every 30 days
full historical sweep    → disabled; manual request only
```

Recent sweeps should apply to actively monitored series and run through a staggered, globally rate-limited queue. They must not enqueue `series count × 10` downloads as one burst. If the configured monthly budget is exhausted, remaining checks roll over fairly into the next scheduling window.

An optional `RevisionSignalProvider` capability can later expose authoritative source-specific tokens without changing the base extension contract:

```text
probe(chapter)
    → AUTHORITATIVE_TOKEN(value)
    → HINT(value)
    → UNSUPPORTED
```

Priority extensions could eventually place a namespaced token in `SChapter.memo`. Until then, source-specific signals such as MangaDex's content hash are optimizations only; the generic content-download fallback remains necessary.

---

# 18. Candidate revision downloads

Revision checking requires downloading the new version before deciding whether it should replace the archived one.

These downloads should not enter the normal archive immediately.

Use a separate staging location such as:

```text
/revision-staging/<chapter>/<candidate>/
```

or logically distinguish download purpose:

```text
NORMAL_DOWNLOAD
REVISION_CHECK
```

The flow becomes:

```text
existing chapter metadata changed
             ↓
      candidate detected
             ↓
download candidate to staging
             ↓
      compare with archive
             ↓
        diff result
```

---

# 19. Image-based chapter diff

The diff should operate on images, not CBZ file bytes.

Comparing archive hashes directly is inadequate because these can make CBZs binary-different while visually identical:

* JPEG recompression;
* WebP conversion;
* metadata changes;
* ZIP timestamps;
* filename changes;
* compression level changes.

Each page should ideally have:

```text
exact content hash
perceptual fingerprint
dimensions
possibly normalized thumbnail
```

The comparison can classify pages as:

```text
UNCHANGED
MODIFIED
ADDED
REMOVED
MOVED
```

---

# 20. Page alignment

Pages must not simply be compared by ordinal index.

Suppose a credit page is inserted:

```text
OLD        NEW

1          1
2          new credit
3          2
4          3
5          4
```

Naive positional comparison would report almost every page as changed.

Instead, perform sequence alignment using page fingerprints:

```text
old 1  = new 1
         + new 2
old 2  = new 3
old 3  = new 4
old 4  = new 5
```

This is analogous to line alignment in a text diff.

---

# 21. Exact versus perceptual equality

Two levels of comparison are useful.

### Exact equality

A cryptographic/content hash identifies literally identical image bytes.

### Visual/perceptual equality

A perceptual hash or other similarity metric can identify images that look effectively the same despite:

* resizing;
* recompression;
* format conversion;
* minor encoding differences.

This avoids surfacing pointless revision reviews.

---

# 22. Manual visual diff UI

For manually reviewed revisions, the interface should summarize the chapter before showing individual pages.

Example:

```text
Chapter 42

Archived revision:
37 pages

Candidate:
39 pages

Summary:
33 unchanged
2 modified
3 added
1 removed
```

Then:

```text
Page 1      unchanged
Page 2      modified       [review]
Page 3      unchanged
             + added       [review]
...
Page 31     modified       [review]
```

Useful page comparison modes include:

### Side-by-side

```text
┌──────────────┬──────────────┐
│ ARCHIVED     │ CANDIDATE    │
│              │              │
│ old page     │ new page     │
│              │              │
└──────────────┴──────────────┘
```

### Before/after slider

```text
OLD  ◄────────●────────► NEW
```

### Optional difference overlay

A pixel/region overlay can be useful as an advanced mode.

It should not be the default because image recompression can produce visually noisy pixel differences.

---

# 23. Revision approval actions

Manual review supports:

```text
ACCEPT CANDIDATE
KEEP CURRENT
KEEP BOTH
REJECT CANDIDATE
```

`KEEP BOTH` is useful when two scanlations or genuinely different releases are both desirable.

Implemented state: all four actions exist. `KEEP BOTH` archives the candidate without replacing the active revision and pins it so retention does not prune it, which is the archival treatment this section anticipated. Komga still sees one served revision per chapter.

---

# 24. Revision automation rules

Not every revision needs human review. The delivered behavior is deliberately narrower than the sketch below, because a rule that dismisses content is not reversible:

```text
exactly identical (page-by-page content hash match)
    → classified UNCHANGED, duplicate bytes are never archived

visually equivalent after recompression
    → review, unless chapterRevisionAutoDismissVisuallyEquivalent is turned on
      (off by default)

only known credit page changed
    → review; no credit-page heuristic is implemented

page count changed
    → review

meaningful page modification
    → review

brand-new chapter
    → follows the normal series acquisition policy
```

The intent is preserved - manual review stays focused on meaningful differences - but only exact equality auto-dismisses out of the box, and nothing silently discards a payload that differs.

---

# 25. Revision history storage

Previous accepted revisions should ideally not be destroyed immediately.

Possible storage:

```text
/archive-active/
    Series/
        Chapter 42.cbz

/revisions/
    Series/
        Chapter 42/
            rev-1.cbz
            rev-2.cbz
```

or equivalent object naming.

Only the active version needs to be visible to Komga.

Historical versions can remain an internal archival concern. Retention should be configurable per series, with a global default of `3`:

```text
acceptedRevisionRetention:
    3 (global default)
    UNLIMITED
    or
    <another non-negative historical revision count>
```

The count means previous accepted revisions retained **in addition to** the active revision. `0` keeps only the active revision; `3` keeps the active revision plus the three newest superseded accepted revisions. Pruning happens only after the replacement CBZ and manifest are remotely confirmed, and never deletes the active revision. A revision marked `KEEP_BOTH` is pinned and is not counted against the window.

**Not implemented:** a separate configurable retention duration for *rejected* candidates. Rejecting a candidate does not itself delete anything; only accepted-revision retention prunes payloads, and a rejected candidate that was never archived has no payload to prune. The paragraph that follows describes an intended later refinement, not current behavior.

---

# 26. Chapter diff implementation should remain isolated

Image-processing logic should not be spread throughout Suwayomi.

A dedicated conceptual service is preferable:

```text
ChapterDiffService
```

Input:

```text
archived chapter
candidate chapter
```

Output:

```text
DiffManifest
```

Example:

```text
oldPages: 37
newPages: 39
unchanged: 33
modified: 2
added: 3
removed: 1
```

The Suwayomi update system should consume the result without knowing much about image-processing implementation details.

---

# 27. Source independence and canonical manga identity

An ideal long-term archive would model:

```text
Canonical Work
├── Source binding A
├── Source binding B
└── Source binding C
```

This would allow a series to migrate between providers while remaining one archival entity.

For example:

```text
2026
Source A
  ↓
archive Chapters 1–100

Source A disappears

2027
Source B
  ↓
continue with Chapter 101
```

The archive would still regard this as one series.

However, this is **not recommended for the first version**.

Suwayomi naturally associates a manga with a source.

Breaking that relationship would affect substantially more of its architecture than the update-policy/revision features.

For v1:

```text
one Suwayomi manga
    =
one source manga
```

Canonical cross-source merging can be handled later at the archive/Komga layer.

Implemented state (Phase 4): the archive/Komga layer does now model canonical works with multiple source bindings, with `ACTIVE`/`FALLBACK`/`DISABLED` roles, so the binding of a series can be promoted to another source and a series can survive a source going away. This is **reversible and advisory**: the two sources' chapters are never asserted to be the same chapters, and nothing is merged or deleted automatically. The Suwayomi manga-to-source relationship itself is unchanged, so `one Suwayomi manga = one source manga` still holds.

---

# 28. Archive file format

CBZ is an appropriate archival unit.

Recommended layout:

```text
/library/
  series/
    chapter-001.cbz
    chapter-002.cbz
    chapter-003.cbz
```

CBZ gives:

* simple portability;
* compatibility with existing comic software;
* one logical object per chapter;
* relatively straightforward archival;
* easy migration away from the current stack later.

Internal IDs should be kept separately rather than relying entirely on display filenames.

---

# 29. Metadata

The archive should retain enough metadata to survive source changes.

At minimum:

```text
series ID
chapter ID
chapter number
chapter title
source
source URL/identifier
scanlator
discovery timestamp
archive timestamp
revision ID
page count
content fingerprints
```

Do not treat mutable source URLs or human-readable chapter titles as permanent identity.

This information must not live only in Suwayomi's database. Every immutable archived revision should have a durable JSON manifest stored beside it or in a dedicated manifest prefix, for example:

```text
/revisions/Series/Chapter-42/rev-2.cbz
/revisions/Series/Chapter-42/rev-2.archive.json
```

The manifest should include the CBZ SHA-256, ordered page fingerprints, schema version and all source/revision identity fields. `ComicInfo.xml` may also be embedded in the CBZ for portable reader metadata, but it is not a replacement for the archival manifest.

Write the immutable CBZ and manifest before changing the active Komga-visible copy. This allows the database and active library view to be reconstructed from object storage if Suwayomi or Komga is replaced.

---

# 30. Object storage choice

The two most attractive managed options are R2 and B2.

### Cloudflare R2

Advantages:

* no Internet egress charges;
* simple S3-compatible API;
* predictable behavior for frequent reading.

### Backblaze B2

Advantages:

* cheaper raw storage;
* good option once the archive becomes large.

For this project, exact provider choice matters less because the mounted filesystem abstraction keeps the application mostly provider-independent.

---

# 31. Why MinIO is unnecessary here

MinIO is primarily an S3-compatible storage server that runs on storage you already own.

It does not provide extra physical capacity.

Running:

```text
MinIO
   ↓
100 GB local disk
```

still leaves approximately 100 GB of usable underlying storage.

Therefore it does not address the main requirement:

> maintain a potentially large archive without owning enough physical disk.

A caching proxy was initially what was imagined, but MinIO is not necessary for that role.

rclone VFS caching or application/device-side caching is more relevant.

---

# 32. Reading-path caching

There can be several layers of cache:

```text
R2/B2
   ↓
Proxmox rclone VFS cache
   ↓
Komga
   ↓
Mihon cache/offline chapters
```

The most important cache from a user-experience perspective may actually be the client cache.

Manga access patterns are highly predictable:

```text
read page 8
prefetch page 9
prefetch page 10
...
```

Mihon can also explicitly download selected chapters locally when offline access is required.

---

# 33. Direct-object reading

If Proxmox eventually proves to be a serious reading bottleneck, the bytes can be served straight from the object store:

```text
Mihon
   │
   │ metadata/auth
   ▼
library service
   │
   └── signed object URL
              │
              ▼
             R2
```

The image/chapter bytes then go directly:

```text
R2 → phone
```

instead of:

```text
R2 → Proxmox → phone
```

## Implemented state

Direct delivery of an archived revision's CBZ **is implemented** as an opt-in server capability. It is not used for ordinary reading, which still goes through Komga.

What is implemented:

```text
archiveDirectDeliveryEnabled (default off)
archiveDirectDeliveryExpirySeconds (default 300)
archiveDirectDeliveryRequireExpiryEvidence (default on)
archiveDirectDeliveryFallbackToLocal (default on)

GET  <server>/api/v1/archive/revisions/<revisionId>/download          full payload
HEAD <server>/api/v1/archive/revisions/<revisionId>/download          local-fallback metadata
GET  <server>/api/v1/archive/revisions/<revisionId>/download  Range: bytes=…   local single range
```

Behavior:

- the route is authenticated like every other API route and identifies the revision by opaque server-side id;
- when direct delivery is enabled it asks the rclone remote for a short-lived signed link (`rclone link`) and answers with a redirect;
- by default a link is only used when the command reported an expiry the server could read, and that expiry is compared against the configured maximum; a backend that silently ignores the expiry request and answers with a permanent public link is refused rather than exposed;
- when direct delivery is disabled, or the link is refused, or the signed-link request fails, the server falls back to streaming the verified local copy (staged artifact or mounted archive file) when the fallback setting is on;
- direct redirects are GET-only; HEAD and RFC 7233 single-range responses are provided by the verified local fallback, while a direct-only HEAD request is refused rather than minting a GET credential;
- eligibility is fenced on the revision being durably archived, not pruned, and not the subject of a missing/corrupt integrity finding;
- no signed address is ever stored in the database or exposed through GraphQL.

## Retained caveats

- Live validation of signed links against a real rclone remote and a real R2/B2/S3 bucket has **not** been performed. The signing path is exercised against injected/recorded command output, not against a live backend.
- Expiry reporting is backend-dependent. The requirement is deliberately conservative: a backend whose `rclone link` output carries no readable expiry is treated as unable to prove link lifetime.
- Serving bytes directly to clients bypasses the mounted library path, so it does not inherit whatever caching Komga or the mount provided.
- This remains an optimization for reading, not a requirement, and it does not change what the archive holds.

---

# 34. Recommended Suwayomi fork scope

The fork introduces only the concepts needed for the update model. What actually exists, next to the abstraction that was sketched here:

```text
MangaAcquisitionPolicy        MangaTable.acquisitionPolicy (AUTO / MANUAL / PAUSED)

ChapterRevision               ChapterRevisionTable, with independent acquisition, archive,
                              publication, retention and integrity state dimensions

RevisionDiff                  ChapterRevisionVisualComparison + persisted alignments and thumbnails

RevisionApproval              explicit accept / keep-current / keep-both / reject actions

StagingArea                   ChapterRevisionStaging / ChapterRevisionArchive under the staging root

LibraryCommitter              ChapterRevisionPublication, fenced and restart-safe

ArchiveCommitVerifier         ChapterRevisionArchiveVerification and
                              ChapterRevisionRemoteVerification

ArchiveManifestWriter          ChapterRevisionArchiveManifestCodec, schema-versioned sidecar JSON

PostCommitHook                ChapterRevisionPublicationEvents, a durable outbox

RevisionSignalProvider        not built as a pluggable interface; metadata reconciliation
                              (ChapterRevision reconciliation of name/scanlator/date/number/memo)
                              is the only signal source, and it records a confidence level rather
                              than pretending to be a generic provider

UpdateCandidate               not built as a separate entity; candidates are ChapterRevision rows
                              in the approval backlog
```

Policy:

```text
MangaAcquisitionPolicy
├── AUTO
├── MANUAL
└── PAUSED
```

`NOTIFY_ONLY` from the original sketch is not implemented (section 5).

Revision model:

```text
Chapter
   ↓
ChapterRevision[]
```

without forcing the rest of Suwayomi to immediately understand all revisions.

---

# 35. What should remain upstream-compatible

Avoid modifying these areas unless necessary:

```text
extension loading
source API compatibility
source search
source authentication
page retrieval
basic download machinery
```

These are precisely the components upstream Suwayomi is valuable for maintaining.

The fork should mainly modify:

```text
chapter refresh reconciliation
update-policy decisions
download orchestration
candidate revision handling
database state
GraphQL/API surfaces
WebUI
post-download/commit events
```

This substantially reduces future merge/rebase pain.

---

# 36. Relative implementation difficulty

### Relatively straightforward

```text
per-series AUTO/MANUAL/PAUSED policy
configurable staging path
configurable archive path
CBZ archival
mounted R2/B2 library
post-commit hooks
Komga refresh trigger
```

### Moderate

```text
pending chapter queue
manual approval UI
candidate state
API/GraphQL additions
revision metadata
archive confirmation state
```

### Significant but isolated

```text
candidate chapter downloads
image fingerprinting
page sequence alignment
visual diff generation
revision history
manual diff UI
```

### Deferred by design

```text
transparent direct-R2 Mihon delivery as the reading path
rewriting Suwayomi's entire chapter model
```

### Delivered, but deliberately narrow

```text
canonical source-independent manga identity (canonical works + source bindings)
multiple source bindings per Suwayomi manga
source failover between bindings
archive integrity audits
rollback to previous chapter revisions
direct object-store delivery of an archived CBZ
```

These were originally listed as "avoid initially". They are now implemented, but each one is bounded on purpose and must not be read as a general capability:

- canonical identity is **reversible and advisory**: a canonical work groups source bindings with `ACTIVE`/`FALLBACK`/`DISABLED` roles so a series can survive a source going away. It does **not** infer that chapters from two different sources are the same chapter, and it does **not** delete or merge anything automatically. Duplicate scanlation handling is left to a human; the system only surfaces a duplicate *policy* hint, never an automatic action.
- integrity audits only report what the remote copy looks like. They never delete content and never downgrade a revision's `REMOTE_CONFIRMED` archive state.
- rollback is fenced: it refuses a target that is not an accepted/superseded, durably archived revision whose payload was not pruned and not found missing/corrupt, and it republishes the archived bytes through the ordinary publication worker rather than writing files itself.
- direct delivery is an opt-in download path for an archived CBZ (section 33), not the reading path.

---

# 37. Confirmed implementation phases

All five confirmed phases below are implemented in this fork. The mechanism-by-mechanism mapping, the migration list, the deployment checklist and the validations that have **not** been run live are in section 37a.

## Phase 0 — fresh archive bootstrap from backup

The archive starts fresh, and existing library state is imported through Suwayomi's existing `.tachibk` restore implementation rather than through a second custom parser.

The current backup baseline contains:

```text
3,435 manga entries
46,699 known chapter records
5 categories
72 distinct source IDs
22 source IDs with numeric-only saved names that require resolution during import
```

The backup carries manga/source bindings, chapter metadata, categories, progress and history; it does not contain the chapter image archive. Import therefore restores tracking state, then source refresh and archival acquisition fetch the actual content.

Bootstrap flow:

```text
install/resolve required extensions
        ↓
run existing ProtoBackupImport
        ↓
report unresolved source IDs without aborting the whole import
        ↓
refresh imported manga in bounded batches
        ↓
apply selected acquisition policy
        ↓
queue initial archive downloads resumably
```

An imported manga with the `ONLY_FETCH_ONCE` update strategy archives the initial known content and then stops periodic source updates unless the user changes its policy. The import UI offers one default archival policy plus per-category overrides.

Because this is a large import, every step is resumable and globally rate/concurrency limited: one durable bootstrap session plus per-manga items, one explicit refresh per imported manga at global concurrency 1, and a persisted inter-item delay. A server restart or one broken extension does not restart the migration from zero.

The bootstrap is reached from the WebUI restore flow, which can also hand only the manga subset it actually imported to bootstrap, and the bootstrap has its own progress, item list, unresolved-source report and pause/resume/cancel/retry controls.

**Not validated live:** a physical `.tachibk` import against the current 3,435-manga backup has not been run, so the resolution of the 22 numeric-only saved source names is still a deployment step.

---

## Phase 1 — v1 server and archive foundation

Delivered:

```text
per-series AUTO / MANUAL / PAUSED policy
independent acquisition/archive/publication states
persistent approval and download queues
local staging and validation
CBZ creation
remote-confirmed archive commit
durable revision manifest
Komga post-commit rescan
metadata-triggered revision candidates
accepted-revision retention count or UNLIMITED
```

Revision handling remains conservative: metadata changes create candidates, but v1 does not claim source-independent visual proof. The proof level a candidate carries (`METADATA_HINT` before acquisition, `CONTENT_PROOF` once its bytes are hashed) is recorded on the candidate and in the archive manifest, and confidence is never upgraded by inference.

---

## Phase 2 — v1 WebUI

The WebUI fork is maintained at:

```text
../Suwayomi-WebUI
https://github.com/IMXEren/Suwayomi-WebUI
```

Delivered controls, under an `/archive` route with its own navigation entry:

```text
approve/reject/archive all
pause series/change policy
inspect queue and independent failure states
retry failed acquisition/archive/publication steps
configure revision retention
monitor backup-import/bootstrap progress
```

The dashboard is organized into overview, approval queue, queue inspector, revision review, sweep management, integrity audits and canonical works, plus a per-series archival settings dialog reached from the manga toolbar and restore options in the backup screen.

---

## Phase 3 — v2 content revision detection and review

Delivered:

```text
candidate chapter downloads
exact page hashes
perceptual hashes
page sequence alignment
changed-page detection
thumbnail generation
staggered recent-chapter revision sweeps
manual full-sweep action
```

Delivered review UI: side-by-side, before/after slider, and a difference overlay. Delivered automation rules:

```text
exactly or visually identical → dismiss (visually equivalent dismissal is off by default)
minor/configured changes      → review
meaningful changes            → review
```

---

## Phase 4 — advanced archival features

Delivered, each one bounded as described in section 36:

```text
canonical series identity (canonical works)
multiple simultaneous source bindings with ACTIVE/FALLBACK/DISABLED roles
source promotion/failover between bindings
advisory duplicate-scanlation policy (no automatic merge or delete)
archive integrity audits against the direct remote
rollback to a previous accepted chapter revision
direct object-store delivery of an archived CBZ (opt-in)
```

Cross-source migration in the sense of *moving a library from one source to another and treating the two sources' chapters as the same chapters* is **not** implemented. Canonical identity makes the switch possible to record and to fail over, but it never asserts chapter equivalence across sources.

---

# 37a. Implementation status

Status of the confirmed phases, as implemented in this fork. This section is deliberately **untimed**: it records what the code does, not when it changed, and it is not a changelog. No live integration has been exercised end to end; the deployment validations that remain are listed at the end of this section.

## What is implemented, by mechanism

```text
per-series acquisition policy (AUTO / MANUAL / PAUSED)
    MangaTable.acquisitionPolicy, MangaType, backup + SyncYomi triggers, WebUI control

chapter revision candidates
    ChapterRevisionTable + ChapterRevisionDataClass, deterministic content-hash candidate keys,
    nullable SET_NULL references to the source chapter row, independent state dimensions

candidate acquisition
    restart-safe single-concurrency executor, per-candidate staging directory, SHA-256 of the
    downloaded page set, interrupted in-flight work recovers to QUEUED without resetting attempts

archive artifacts
    deterministic CBZ + schema-versioned JSON sidecar manifest built under the staging root,
    atomically copied to the archive root, sizes and hashes of both objects persisted

remote durability verification
    separate persistent verifier that runs `rclone lsjson` against the configured direct remote
    (not the VFS mount), requires both objects and any reported SHA-256, retries under a persisted
    claim lease, deletes local staging only after REMOTE_CONFIRMED

accepted-revision lifecycle
    DB-enforced single active revision per chapter identity, explicit accept / keep-current /
    keep-both / reject actions, durable post-publication outbox events

retention pruning
    global default of 3 historical accepted revisions plus the active one, per-series override,
    UNLIMITED option, deletion of the superseded CBZ only after direct-remote absence verification,
    manifests preserved

Komga integration
    durable publication events coalesced into one generation-fenced scan intent, authenticated
    POST to the configured library scan endpoint, bursts debounce to one restart-safe scan

metadata-triggered discovery
    idempotent METADATA_CHANGE candidates for in-library manga on normalized name, scanlator,
    upload date, chapter number or recursively canonicalized memo change

backup bootstrap
    one durable session plus per-manga items, category-resolved policy snapshots, explicit one-time
    refresh of each imported manga, idempotent BOOTSTRAP_IMPORT candidates, pause/resume/cancel/retry

resumable backup restore
    uploaded bytes staged and integrity-checked atomically, existing restore handlers resume by
    persisted phase and manga index, unresolved sources and per-manga failures audited, optional
    handoff of only the imported subset into bootstrap

revision sweeps
    durable scheduled/manual sessions and per-chapter items at global concurrency 1, scheduled
    default of newest 10 chapters every 30 days, full-history sweep manual, exact matches classified
    UNCHANGED so duplicate bytes are never archived

visual revision analysis
    bounded exact and perceptual page fingerprints, insertion/deletion-aware sequence alignment,
    dual-sided deterministic thumbnails, schema-v4 manifest audit, baseline-fenced and restart-safe;
    perceptual-only auto-dismiss is off by default

comparison media
    authenticated opaque routes for baseline/candidate thumbnails and full pages, traversal-safe
    CBZ streaming with a decompressed-byte cap, staging-to-archive fallback, integrity validation

canonical identity
    canonical works plus source bindings with ACTIVE/FALLBACK/DISABLED roles, atomic promotion,
    identity snapshots on candidates and manifests, advisory duplicate policy, import/export

integrity audits
    durable direct-remote audits with missing/corrupt/transient classification, manual and scheduled
    runs, per-revision integrity status, retry/backoff, pause/cancel/retry

rollback
    fenced activation of a previously accepted revision, republished through the ordinary
    publication worker, recorded in an append-only rollback table

direct delivery
    opt-in authenticated CBZ delivery with `rclone link`, required signed-expiry evidence by
    default, verified local fallback, HEAD and single-range support
```

## Migrations

The archival work spans migrations `M0065` through `M0079`. Each is paired with a test that runs the migration and asserts the resulting schema.

```text
M0065  MangaAcquisitionPolicy            per-series acquisition policy
M0066  MangaAcquisitionPolicySyncYomi    SyncYomi delta triggers for the new column
M0067  ChapterRevision                   candidate table and independent state dimensions
M0068  ChapterRevisionArchive            staging/archive artifact hashes and sizes
M0069  ChapterRevisionArchiveVerification remote verification claim lease and state
M0070  ChapterRevisionPublication        active-revision publication and outbox events
M0071  ChapterRevisionRetention          retention state and pruning bookkeeping
M0072  KomgaScanIntent                   coalesced, generation-fenced scan intent
M0073  ChapterRevisionDiscoveryAudit     discovery/confidence audit fields
M0074  ArchiveBootstrap                 bootstrap session and per-manga items
M0075  BackupRestoreJob                 durable restore phase and progress
M0076  ChapterRevisionSweep             sweep sessions, items and schedule
M0077  ChapterRevisionVisualAnalysis    analysis jobs, alignments and thumbnails
M0078  CanonicalIdentity                canonical works and source bindings
M0079  ChapterRevisionIntegrityAudit    audit sessions, items, findings and schedule
```

Every migration is written for both H2 and PostgreSQL. Migrations perform only the bounded compatibility backfills needed for new invariants or workers—for example acquisition-policy defaults, chapter identity keys, and making already-pending verification rows immediately due. They deliberately do **not** backfill the existing chapter catalog into revision candidates; only newly reconciled chapters of in-library manga create candidates.

Migration tests are named after their migration (`M0065`-`M0079` in `server/src/test/kotlin/suwayomi/tachidesk/server/database`), with one exception: `M0074` is asserted by `ArchiveBootstrapMigrationTest`, which lives beside the bootstrap tests in `server/src/test/kotlin/suwayomi/tachidesk/manga/impl/ArchiveBootstrapTest.kt`.

## What the archive still guarantees

- A filesystem write is never treated as proof of archival. Only an explicit remote confirmation moves a revision to `REMOTE_CONFIRMED`.
- An integrity finding describes the remote copy. It never deletes a payload and never downgrades `REMOTE_CONFIRMED`.
- A rollback never writes archive files itself; it changes which revision is active and lets the publication worker republish.
- Canonical identity never asserts chapter equivalence across sources and never merges or deletes revisions.
- No signed remote address is persisted or exposed through GraphQL.

## Deployment checklist

The settings below live in the `Archival` settings group. No credential is listed here; the Komga API key is a write-only secret, excluded from generated config backups and never returned by the settings queries.

```text
Storage
    archiveStagingPath                     local staging root (empty = unconfigured)
    archivePath                            mounted archive root (empty = unconfigured)

Remote durability verification
    archiveRcloneRemote                    direct remote spec, e.g. `remote:bucket/path`
                                           (empty = verification disabled, revisions stay REMOTE_PENDING)
    archiveRcloneExecutable                default `rclone`
    archiveVerificationRetrySeconds        default 300
    archiveVerificationTimeoutSeconds      default 30

Komga
    komgaBaseUrl                           base URL of the Komga instance
    komgaApiKey                            write-only API key
    komgaLibraryId                         library to rescan
    komgaRescanDebounceSeconds             default 15
    komgaRescanRetrySeconds                default 300
    komgaRequestTimeoutSeconds             default 30

Retention
    acceptedRevisionRetention              default 3 historical revisions plus the active one
                                           (per-series override and UNLIMITED also available)

Bootstrap (Phase 0)
    archiveBootstrapInterItemDelaySeconds  default 2
    archiveBootstrapRetrySeconds           default 300
    archiveBootstrapMaxAttempts            default 3

Revision sweeps (Phase 3)
    chapterRevisionSweepEnabled            default on
    chapterRevisionSweepIntervalDays       default 30
    chapterRevisionSweepNewestChapters     default 10
    chapterRevisionSweepItemDelaySeconds
    chapterRevisionSweepRetrySeconds
    chapterRevisionSweepMaxAttempts
    chapterRevisionVisualHashThreshold             default 2
    chapterRevisionAutoDismissVisuallyEquivalent   default off
    chapterRevisionVisualAnalysisRetrySeconds
    chapterRevisionVisualAnalysisMaxAttempts
    chapterRevisionThumbnailMaxDimension

Integrity audits (Phase 4)
    chapterIntegrityAuditEnabled           default off
    chapterIntegrityAuditIntervalDays      default 90
    chapterIntegrityAuditRecentRevisions   default 10
    chapterIntegrityAuditItemDelaySeconds
    chapterIntegrityAuditRetrySeconds
    chapterIntegrityAuditMaxAttempts

Direct delivery (Phase 4, opt-in)
    archiveDirectDeliveryEnabled               default off
    archiveDirectDeliveryExpirySeconds         default 300
    archiveDirectDeliveryFallbackToLocal       default on
    archiveDirectDeliveryRequireExpiryEvidence default on
```

## Remaining deployment validations

These have **not** been run, and nothing in this document should be read as claiming they passed:

```text
live rclone signed-link retrieval against a real remote and bucket
live remote durability verification (`rclone lsjson`) against R2/B2/S3
live Komga endpoint and header behavior against a running Komga instance
PostgreSQL migration and concurrency behavior under a real PostgreSQL server
a physical Mihon `.tachibk` import, including unresolved-source resolution for the
    22 numeric-only saved source names in the current backup
```

Subprocess, HTTP and filesystem behavior are covered by tests that inject the command runner, the HTTP client and the filesystem roots; that is not a substitute for the live checks above.

# 38. Intended end-user workflow

Adding a series could look like:

```text
Add series

Source:
MangaDex

Series:
Example Manga

Update mode:
AUTO

Revision handling:
MANUAL IF CONTENT CHANGES

Archive existing chapters:
YES
```

The system then performs:

```text
resolve manga
    ↓
fetch chapter list
    ↓
queue existing chapters
    ↓
download
    ↓
validate
    ↓
archive
    ↓
Komga indexes
    ↓
series appears in Mihon
```

Later:

```text
source releases Chapter 72
         ↓
Suwayomi discovers it
         ↓
AUTO policy
         ↓
download + archive
         ↓
Komga refresh
         ↓
Mihon sees Chapter 72
```

If Chapter 41 gets replaced:

```text
source metadata indicates possible revision
         ↓
download candidate
         ↓
visual comparison
         ↓
3 pages changed
1 page added
         ↓
manual review
         ↓
[Accept]
[Reject]
[Keep both]
```

On acceptance:

```text
current revision
      ↓
superseded/history

candidate
      ↓
active archive version
      ↓
Komga refresh
```

---

# 39. Core architectural principle

The most important separation is:

```text
SOURCE STATE
     ≠
ARCHIVE STATE
     ≠
LIBRARY PRESENTATION
```

Suwayomi represents the outside world.

R2/B2 represents what has actually been preserved.

Komga represents how the preserved collection is organized and consumed.

That separation protects the archive from source disappearance, extension failures, software replacements and changing acquisition policies.

---

# 40. Final target

The finished system should effectively behave like a personal automated manga preservation platform:

```text
                    ┌───────────────┐
                    │ Manga sources │
                    └───────┬───────┘
                            │
                            ▼
                 ┌────────────────────┐
                 │   Suwayomi fork    │
                 │                    │
                 │ discovery          │
                 │ update policies    │
                 │ queues             │
                 │ revision detection │
                 │ manual approval    │
                 └─────────┬──────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │ Local staging   │
                  └────────┬────────┘
                           │
                     candidate diff
                           │
                     accept/reject
                           │
                           ▼
                  ┌─────────────────┐
                  │ Mounted archive │
                  │   R2/B2/S3      │
                  └────────┬────────┘
                           │
                           ▼
                       ┌───────┐
                       │ Komga │
                       └───┬───┘
                           │
                     Mihon extension
                           │
                           ▼
                    ┌───────────────┐
                    │ Mihon clients │
                    └───────────────┘
```

The main implementation focus should therefore be **the update/revision orchestration layer around Suwayomi**, rather than storage code, a new manga downloader, or a new reader.

That gives the project substantial functionality while still allowing upstream Suwayomi to maintain the difficult source-extension ecosystem.
