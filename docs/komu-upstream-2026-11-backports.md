# Upstream reconciliation, November 2026 (stage 1)

Base: the full-size covers line (`feat/full-size-covers-20261108`), itself on
Suwayomi stable `v2.4.2366` (`40bc5da5`). Upstream `master` on 2026-11-09 is
`d5215280`, 124 commits past that release, with no newer release tag.

## Why not a merge

Upstream master carries two redesigns that this line cannot take implicitly:

- **User accounts** (`e02a74a9`, #623; 227 files): migration `M0066_AddUsers`
  moves library, read, bookmark, download and category state into per-user
  tables, drops the old columns and creates an admin account from the
  configured credentials. Every later fix in those areas is written against the
  new tables.
- **REST removal** (`97e0444d`, #2512): deletes the v1 controllers for manga,
  chapters, sources, extensions, categories, downloads, updates, tracking,
  backups and settings. The Komu iOS client reaches its catalogue, reader,
  library, extension and download features through those routes.

A trial merge produces 21 textual conflicts; the semantic cost is a port of
every Komu REST surface, the update-run, energy, covers and Local Source work
to the user-scoped schema, plus a data migration of real libraries. That is a
separate program (stage 2) to start from an upstream release that contains
accounts, not from an unreleased master.

## Extension compatibility evidence

All 1,554 extensions in Keiyoushi's live index (2026-11-09) are API 1.6 and
publish a direct JAR. A byte-level scan of every JAR's constant pool against
this server's class graph found no missing class or member except Mihon's
host-only `tachiyomi.decoder.ImageDecoder` (4 extensions; upstream lacks it
too), and against the Java 17 platform found no missing API. API 1.6, direct
JAR installation and protobuf/v2 repositories were reconciled in v2.4.2409.

## Backported upstream commits

Cherry-picked with `-x` (authorship kept); `CHANGELOG.md` hunks dropped.

| Upstream | Change | Why on iOS |
|---|---|---|
| `ed65c816` #2343, `f1356311` #2346, `8d7d4fc3` #2345 | SharedPreferences string-set cleanup, `deleteAll` persisted, listeners notified on clear | source preference correctness |
| `d63ea788` #2404 | Javalin handles Jakarta exceptions | embedded HTTP errors |
| `ddde30c5` #2484 | `android.net.Network*` | extension API surface |
| `f4791666` #2483 | missing CBZ reads as not downloaded | download state |
| `f01a4759` #2368 | Local Source ignores `@eaDir` | Local Source |
| `24895eb5` #2463 | AVIF image sequences recognised | page/cover typing |
| `c159c19f` #2465 | HSV conversion implemented (was a native stub) | extensions using `Color` HSV |
| `a95fc90f` #2466, `c59e5024` #2467, `9161ced2` #2470 | bitmap types, bold typeface, Paint size | image-processing extensions |
| `5bfc74ff` #2458 | server info does not fail with WebUI disabled | Komu always disables WebUI |
| `659184fe` #2515 | `Bitmap.createBitmap(int[], w, h, Config)` | extension API surface |
| `551cb073` #2535 | `RenderProcessGoneDetail` | extension API surface |

Already present in the fork in another form: #2477 (thread-safe class-loader
cache), #2480 (missing old JAR on update; staged activation owns it), #2421
(missing chapters are skipped by the existing `inList` enqueue).

Not taken: everything that depends on user accounts or REST removal, auth/CSRF
changes (Komu authenticates with its session capability), KCEF/WebView
lifecycle (no KCEF on iOS), OPDS/KOReader/SyncYomi features, desktop packaging,
dependency bumps, and #2461/#2391 (written against refactors this line lacks;
the covers work owns the thumbnail path).

## Schema

No migration. The ledger stays at 71, so 2.4.2418 and later 71-schema servers
open this line's data and vice versa.
