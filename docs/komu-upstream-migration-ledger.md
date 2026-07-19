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
