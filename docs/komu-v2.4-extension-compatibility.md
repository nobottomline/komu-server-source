# Komu v2.4 extension compatibility

## Baseline and scope

The reconciliation starts at qualified Komu `0f92fc8b` and incorporates stable
Suwayomi `v2.4.2366` (`40bc5da5`). The upstream development branch has a separate
user-account and database redesign; that contract is not part of this upgrade.
Java 17/OpenJ9, loopback capability authentication, signed direct JARs, manual
APK import, and crash-consistent activation remain the embedded contract.

## Current repository failure

Keiyoushi's August 29 compiler change `c0064f7454f9211d46da075c25e026065a0fd782`
moved generated source factories from the extension package into the fixed
`keiyoushi.source.Generated` class. The qualified August server still downloaded
the complete protobuf index but rejected current MangaPill 1.6.0 with
`Extension source class escapes its package namespace`.

The class resolver now admits that exact compiler entry point only for API
1.6. Inspector, preflight, and shadow validation share the same rule. Its own
class entry must be authenticated by the artifact signature; package identity,
store signing identity, source IDs, source ownership, and immutable artifact
checks remain enforced. Other foreign namespaces remain rejected.

## Preserved Komu seams

Komu's transactional activation, journal recovery, installed metadata retention,
custom pinning, and runtime registry supersede upstream's separate loader/install
locking changes. The upstream dex2jar `computeFrames(true)` fix is retained
independently: manual APK conversion must produce verifiable JVM bytecode.

Configuration migration moved to upstream's server-config module but still
uses explicit user values and preserves an explicitly configured replacement.
Startup must return the migrated document to the writer and recover activation
journals only after database startup completes.

Shadow 9 must merge all service providers while keeping one ZIP entry per
class/resource. Duplicate service inputs are included for the merger, all other
duplicates are excluded, and duplicate entries in the final archive fail the
build. Java 17 bytecode verification remains mandatory before bundling.

## Migration and recovery

See [the migration ledger](komu-upstream-migration-ledger.md) for the immutable
61-65 line and appended 66-70 operations. A server rollback requires the exact
old artifact and its pre-upgrade data snapshot together; never reopen an upgraded
database with an older server as an informal rollback.

The app repository owns exact artifact hashes, Simulator and live-source
qualification, and the audited bundle-copy decision. Build output alone is not
an app release or physical-device rollout.


## APK metadata and platform compatibility

Manual APK manifests contain typed binary values. apk-parser's XML renderer
turns a float API version into a diagnostic string such as `{4:1068708659}`.
The existing AXML parser now reads only primitive metadata directly under the
application element, with a 1 MiB manifest bound, normalizes numbers and
booleans, and ignores nested component metadata and resource references.
This preserves API 1.4 and content-warning values during manual import.

The Darwin JVM port reports `Mac OS X` on iOS. The new upstream platform-info
payload must use JVM properties in the embedded profile instead of executing
`sw_vers`, which an iOS application cannot spawn. Desktop discovery remains
unchanged.

## Repository outage behavior

A refresh with no successful stores is distinct from a successfully downloaded
empty store. Failed stores retain their last authenticated artifact ledger,
available catalogue, and installed update metadata. Only successfully refreshed
stores and explicitly removed stores authorize catalogue removals. Legacy
callers without ledger input retain their compatibility behavior.

## Source verification

The complete server suite executes 267 tests with zero failures/errors and 10
environment/fixture-dependent skips. It covers generated entry authentication,
shadow source ownership, activation recovery, complete-store outages, typed APK
metadata, configuration precedence, and released 57/65 through schema 70.
Java 17 archive verification passes for every target-visible class. Live
Java 17 and embedded app qualification are recorded in the app repository.
