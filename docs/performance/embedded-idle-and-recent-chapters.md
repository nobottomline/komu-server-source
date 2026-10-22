# Embedded idle work and recent chapters

## Decision and invariants

Keep Java 17, OpenJ9, the retained JVM, existing listener stop/start entry points,
queue persistence, extension trust and database ownership. Idle energy work must
not replace lifecycle correctness with JVM teardown. No heap, GC, interpreter,
Jetty concurrency or H2 pool-size tuning is justified by the current measurements.

The four lifetime `Flow.sample(1.seconds)` consumers now use event-armed windows.
A conflated channel preserves signals arriving before subscription and during a
callback. A window starts only when a value is pending, emits the latest value
within one second under continuous progress, and then returns to a timer-free
receive. Finite upstream completion delivers its last value; cancellation drops
pending work. This is throttling rather than debounce, which can starve progress.
Only signals/state snapshots are conflated; download/update deltas remain in the
owners' pending collections. Immediate REST download events also arm a subscription
flush, including terminal events. A synchronized per-chapter map atomically snapshots
and clears download deltas without losing concurrent terminal updates.

A pending capacity signal cannot restart downloads after Pause: requested state
and starting downloaders share a lock, and Pause snapshots active owners under that
lock before cancelling them. Resume explicitly arms scheduling again. Completion
callbacks can enqueue another capacity signal while the current one is delivered.

## Recent chapter query and data compatibility

Numbered pages and page size 50 remain compatible. Only the recent-chapter response
adds `supportsCursorPagination: true`; ordinary pagination responses are untouched.
Optional `beforeFetchedAt` (raw stored Long) and `beforeId` (positive Int) query
parameters form an exclusive cursor. Both must be present or both absent; malformed
pairs receive HTTP 400. With a cursor the numbered path is accepted but OFFSET is
ignored. The next cursor is the last returned chapter's raw `fetchedAt` and `id`.
Canonical fetchedAt values are epoch seconds, unlike uploadDate milliseconds;
clients must not normalize the cursor to another unit.

Ordering is now total: `(fetched_at DESC, id DESC)`. Selection uses an indexed,
ordered chapter-ID subquery with a correlated library-membership predicate; only
51 joined rows are hydrated and at most 50 DTOs are mapped. The extra row establishes
`hasNextPage` without a full count. Selection and hydration share one statement so
a concurrently removed manga cannot disappear between two separate queries.

A direct joined `LIMIT 51` was rejected after H2 EXPLAIN still selected a manga-first
plan and sorted the qualifying chapter set. The chosen subquery uses
`IDX_CHAPTER_RECENT` with `index sorted` on the 100,000-row H2 fixture. Migration 71
only adds that index, is safe to replay after H2 commits DDL before the ledger, and
does not change chapter identities, dates, progress or library membership.

Legacy numbered OFFSET pages are not a snapshot across separate requests:
concurrent insertion/deletion can shift later pages, as in the previous API. The
cursor lane splits the exclusive comparison into two disjoint, independently
limited indexed scans: equal timestamp with a lower ID, and timestamps no greater
than `beforeFetchedAt - 1`. The inclusive integer bound also avoids H2 scanning an
entire equal-timestamp group to evaluate a strict leading-column inequality.
UNION ALL supplies at most 102 candidate IDs and the outer query hydrates at most
51 rows. H2's row-value comparison was rejected after EXPLAIN ANALYZE showed it
seeking only on the first column and scanning 99,952 equal-timestamp rows. The
split plan seeks directly to the deep ID bound and scans at most 52 rows there on the equal-timestamp fixture. Remaining membership
filtering is still required; this is not a fixed scan bound for sparse libraries.
Inserting chapters ahead of the cursor does not shift the remaining traversal;
a deleted chapter is simply omitted. New items ahead of the cursor appear in the
next fresh sweep. This is not a multi-request transaction snapshot, and clients
still reconcile by stable chapter IDs. Existing callers without cursor parameters
retain numbered behavior; deep OFFSET is not claimed to be constant-cost.

## Embedded startup and maintenance

Main uses the same `supportsClasspathScanning()` gate as route registration before
initializing the GraphQL schema. Desktop GraphQL remains available. The existing
iOS route policy remains unchanged; this does not introduce a backup API.

On iOS with file-backed H2, Hikari keepalive and connection-lifetime recycling are
disabled. Per-checkout leak detection is opt-in with
`-Dkomu.database.diagnostics=true`. Min idle 2/max pool 6 and idle eviction remain
unchanged, keeping H2 open and preserving current contention behavior. Hikari's
supported housekeeping remains; this is not a claim of zero JVM timers. Desktop
and PostgreSQL configuration keep their existing policies.

HAScheduler creates its hibernation checker only when a real task is registered,
and cancels/purges that checker when its last owner is removed. Scheduled jobs
retain the existing suspension/catch-up semantics. The upstream cron engine is not
synchronously stopped on removal: its `stop()` waits active executors and can
interrupt work or deadlock callers that hold scheduling locks. Redesigning active
cron lifecycle is a separate measured change.

## Verification and rollout

- Virtual time: zero scheduled idle time before/after a burst, continuous progress,
  final delivery, cancellation, upstream failure and reentrant capacity signals.
- Real DownloadManager fixture: pause before its pending window leaves a queued
  chapter untouched; resume delivers a terminal missing-source failure.
- H2: complete parity across 1,027 chapters with equal discovery times and old
  upload dates; first/middle/final windows at 1k, 10k and 100k rows; overflow and
  empty requests; deterministic retries; EXPLAIN index selection; complete keyset
  traversal of 100k equal timestamps and insertion/deletion between cursor pages.
- Migration fixtures: fresh and released 57/65 data reach ledger 71, retained
  library/category/extension/reader state, interrupted DDL before the ledger,
  repeated migration and idempotent index creation.
- Profile tests retain desktop/PostgreSQL policies and H2 pool bounds.

Qualify the exact committed fat JAR with `:server:test :server:shadowJar
:server:verifyEmbeddedJava17Artifact`, a fixed `SOURCE_DATE_EPOCH`, two byte-identical
builds, host upgrade/restart on disposable data, and OpenJ9 Simulator lifecycle tests.
Artifact hash, source commit and final app/runtime evidence belong in Komu's
qualification record. Building this worktree does not replace the bundled JAR.
Keep the exact prior JAR and its matching pre-upgrade database snapshot together.
Physical-device energy improvement remains a separate Release measurement.
