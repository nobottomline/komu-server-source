# Komu ↔ Suwayomi database migration ledger

Migration numbers are persistent release data, not implementation details.
Komu shipped its own versions 55–57 before adopting the newer Suwayomi line,
so those meanings remain immutable and all colliding upstream operations move
to new Komu numbers.

| Komu version | Class | Origin | Contract |
|---:|---|---|---|
| 55 | `M0055_ExtensionBaseUrl` | Komu | released; immutable |
| 56 | `M0056_ExtensionSourceLangs` | Komu | released; immutable |
| 57 | `M0057_ExtensionCustomPinned` | Komu | released; immutable |
| 58 | `M0058_RenameMetaKeys` | upstream `M0055` (`03a95e66`) | semantic port |
| 59 | `M0059_SyncYomi` | upstream `M0056` (`811e1516`) | semantic port retained because current upstream code uses this schema |
| 60 | `M0060_AddNewExtensionApiFields` | upstream `M0057` (`2d535b44`) | expand-only semantic port |
| 61 | `M0061_AddExtensionStore` | upstream `M0058` (`2d535b44`) | semantic port |
| 62 | `M0062_AddJarUrlToExtensionTable` | upstream `M0059` (`d34a486d`) | semantic port |
| 63 | `M0063_AddExtensionActivationMarker` | Komu | released activation journal |
| 64 | `M0064_AddExtensionStoreArtifactLedger` | Komu | released artifact custody ledger |
| 65 | `M0065_MakeExtensionPkgNameUnique` | upstream `M0060` | released package identity constraint |
| 66 | `M0066_CreateForeignKeyIndexes` | upstream `M0061` (`fba61e1a`) | resumable index creation |
| 67 | `M0067_PreventDuplicatedCategoryManga` | upstream `M0062` (`7dbf2ed4`) | deduplicate memberships, then enforce uniqueness |
| 68 | `M0068_FixSyncYomiTriggers` | upstream `M0063` (`9eb779d1`) | sync trigger repair |
| 69 | `M0069_AddSyncFlags` | upstream `M0064` (`ee547c03`) | preserve opaque reader/category flags |
| 70 | `M0070_FixSecondsFallbackUploadDate` | upstream `M0065` (`dba836b2`) | repair seconds stored in a millisecond column |
| 71 | `M0071_IndexRecentChapters` | Komu | additive ordered discovery index; no row changes |

Rules:

1. Never rename, reorder or change the meaning of a released Komu migration.
2. Never import an upstream class under a version already present in this
   ledger; port the operation and record its origin.
3. Do not assume a permanent numeric offset. Allocate the next free Komu
   version for each future upstream or local migration.
4. Every reconciliation must test both a fresh database and an authentic
   upgrade boundary carrying the released migration names and data.
5. Destructive schema contraction is separate from expansion. Version 60
   retains `EXTENSION.REPO`, `EXTENSION.IS_NSFW`, `SOURCE.IS_NSFW`,
   `EXTENSION.BASE_URL`, `EXTENSION.SOURCE_LANGS` and
   `EXTENSION.IS_CUSTOM_PINNED` until all old/new server surfaces and released
   iOS clients have completed dual-read/dual-write migration.
6. A feature may receive a tombstone only after its code, table mappings,
   triggers and public API have been removed together. SyncYomi remains in the
   current upstream runtime, so its migration must remain executable.
7. No database produced by this reconciliation branch is considered
   production-compatible until the H2 upgrade fixture, fresh startup and
   embedded OpenJ9/iOS test all pass.


## v2.4 reconciliation and interrupted H2 upgrades

The August release already recorded Komu versions 61 through 65. The stable
Suwayomi v2.4.2366 operations therefore append as 66 through 70; importing
upstream class names unchanged would create duplicate migration numbers.

H2 commits DDL before the migration ledger transaction completes. New indexes,
columns, and the membership constraint permit replay after a process dies in
that window. PostgreSQL retains transactional constraint creation. Existing
published migrations retain their identities; upstream's interrupted SyncYomi
creation repair remains at Komu version 59.

`DatabaseMigrationLineTest` exercises fresh, released 57, and released 65
ledgers through 71. It preserves installed/custom extension and source records,
library/category state, chapter read/bookmark/page/memo values, and the old
ledger; checks deduplication and one-time timestamp repair; and replays partial
DDL at each new schema boundary before its ledger entry exists.
