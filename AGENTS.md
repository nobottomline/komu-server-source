# Komu server fork engineering instructions

This repository is the Suwayomi Server fork used by Komu's embedded OpenJ9
runtime. It is also upstream-derived software. Preserve both contracts.
`CLAUDE.md` imports this file.

## Establish topology before editing

- Inspect `git status`, branch/worktree identity, `origin`, and `upstream` before
  making a change. Do not assume the primary checkout is the active integration
  line.
- The primary `master` checkout is the protected rollback/reference line.
  Upstream reconciliation and risky embedded-runtime work belong in the
  designated integration worktree documented by the Komu app repository's
  `PATHS.md`.
- Fetching upstream references is not permission to merge, rebase, or replace a
  qualified Komu line.
- A JAR in `build/` is only build evidence. It is not the JAR bundled by the iOS
  app until a separate audited copy step occurs in the Komu repository.

## Minimize the fork

- Find the upstream implementation and history before adding fork-only code.
- Reuse upstream services, migrations, extension loaders, and API contracts.
  Add the narrowest iOS/OpenJ9 seam that closes a proven gap.
- Every Komu-only patch needs a concrete embedded-runtime requirement, focused
  tests, an explicit desktop/upstream compatibility story, and a maintenance
  rationale.
- Do not delete desktop code as “unused by iOS.” Prefer runtime/build gates that
  leave upstream desktop behavior intact.
- Do not create large compatibility frameworks merely because one extension or
  old database fails. Reproduce the exact boundary and fix its owner.

## Embedded runtime contract

- Java 17 is Komu's currently qualified OpenJ9 contract. Do not raise bytecode,
  toolchain, or runtime requirements solely because upstream desktop uses a
  newer JDK.
- Keep the embedded listener loopback-only and the Web UI unavailable. Verify
  both IPv4 and IPv6 behavior and ensure user/config migration cannot reopen it.
- Preserve cold-start, restart, background/foreground, and forced-termination
  behavior. Health checks prove liveness only; exercise the affected API and
  extension path.
- Keep iOS-specific behavior explicit and default-off for ordinary desktop
  execution.

## Extensions and artifact trust

- Preserve authenticated direct-JAR installation and APK/manual-import
  compatibility until an explicit migration retires a lane.
- Treat repository signing identity, manifest bytes, downloaded artifact bytes,
  package/source identity, activation journals, leases, and database state as a
  single trust boundary.
- Never bypass signature or immutable-byte validation to make an artifact load.
- Installation, update, switch, rollback, and crash recovery must be idempotent
  and operation-owned.
- Repository removal or refresh must not erase installed extension metadata,
  enabled-language state, source identity, or icons.

## Database and rollout safety

- Never run migration experiments on the user's only database. Use an immutable
  copy and retain an exact rollback hash.
- Test interruption before/during/after migration or artifact publish, partial
  old/new configuration, repeat execution, and cold restart.
- Prefer explicit new values during migration while preserving compatible
  unknown state. A version marker alone does not prove data was migrated.
- A production upgrade requires evidence for library/category counts,
  extension/source records, reader-critical paths, and rollback—not merely a
  successful server launch.

## Verification ladder

Run the smallest set that proves the boundary, then escalate as risk requires:

1. focused Kotlin tests and migration fixtures;
2. fat-JAR structure, classpath, Java-version, and signature validation;
3. host-JVM integration;
4. OpenJ9 iOS Simulator launch/restart;
5. real repository and representative API 1.4/API 1.6 extensions;
6. catalogue/search/detail/chapter/page E2E;
7. signed install-over-existing-data rehearsal when rollout requires it.

Do not weaken or skip a failing gate to describe a candidate as qualified.
Record exact commits, artifact hashes, exclusions, and environments in the Komu
integration documentation.

## Git and delivery

- Preserve unrelated changes. Commit app-side and server-side work separately.
- Use concise professional Conventional Commit messages without AI branding,
  generated essays, or `Co-authored-by` trailers.
- Do not force-push, rewrite the protected reference line, publish a release, or
  replace Komu's bundled JAR unless the task explicitly authorizes that step.
