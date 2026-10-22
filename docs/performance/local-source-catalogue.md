# Local source catalogue performance

The catalogue previously enumerated root siblings for every cover lookup and parsed chapters for every title before returning an unpaginated response. Even cached covers incurred quadratic directory work. Empty iOS browse also waited for JVM readiness and filters independently of that server cost.

Resolve validated leaf names directly; list/sort directory metadata once per request; enrich only the requested 64-title page. This is pagination, not a catalogue limit. Preserve EPUB metadata and cached covers, stable title/date ordering, existing database IDs, and wire order after database materialization. Cancellation propagates. A malformed EPUB no longer turns the whole catalogue into HTTP 500; unreadable metadata leaves its title visible for individual diagnosis. No database migration or local path/identity change is involved. Desktop callers use the existing page/hasNextPage contract.

The iOS client separately proves empty private storage without opening the JVM. It falls back to the authoritative server for legacy storage, links, or any filesystem uncertainty. Nonempty entries continue to receive server-owned IDs. This avoids a competing native identity/index implementation.

Focused tests cover complete pagination, overflow, edited sort selection, cached-cover work bounds, EPUB metadata, malformed EPUB isolation, validated direct lookup, empty/invalid pages, and preservation of database identity and wire order. Host benchmarks use disposable synthetic folders and Java 17; they are not phone UI latency measurements. Final artifact and runtime evidence belongs in the app qualification document.

Maintenance: keep this optimization at the local catalogue/filesystem boundary. Do not replace archive readers or bypass repository/runtime trust checks. A future persistent index needs explicit import, external filesystem, and restore invalidation before replacing directory enumeration.
