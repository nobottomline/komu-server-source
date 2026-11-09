# Full-size covers (Komu fork seam)

Decision record: Komu `docs/architecture/covers/0084-full-size-covers.md`.

Many extensions hand the server a downsized thumbnail URL although the
source hosts a larger original (WordPress `-175x238`, Baozi `?w=285&h=375`,
MangaGeko `/avatar/288x412/`). With the preference on, the server checks the
original in the background and keeps it only when it is verifiably the same
picture, larger.

## Seams in upstream code

| File | Change |
|---|---|
| `manga/impl/Manga.kt` `fetchHttpSourceMangaThumbnail` | a cover whose original was already accepted refills a cleared cache with that original (`FullSizeCovers.originalResponse`) |
| `manga/controller/MangaController.kt` `thumbnail` | serves through `FullSizeCovers.serve`: the cover is opened first and `X-Komu-Cover: original/thumbnail/pending` describes exactly those bytes; `Cache-Control: no-store` while pending |
| `manga/impl/Manga.kt` `clearThumbnail`, `ThumbnailFileProvider.delete`, `graphql/mutations/ImageMutation.kt` | every cover-cache clear runs under `CoverPublicationGate` (`FullSizeCovers.invalidate`/`invalidateAll`); inside a transaction the clear runs again after commit |
| `manga/impl/Manga.kt` `fetchMangaThumbnail`, `ThumbnailFileProvider.downloadImpl` | the thumbnail fill, its refill with an accepted original and the library download are written by `CoverCacheWriter` at the generation captured before the fetch (a fetch a refresh overtook answers its request and caches nothing) |
| `server/JavalinSetup.kt` | registers `GET/PUT /api/v1/komu/covers/preferences` |

Everything else lives in `app.komu.covers` and `app.komu.bridge.NativeCoverImage`.
With the preference off the thumbnail path is byte-for-byte upstream.

## Contract

- Serving never waits for the network: a request is answered from the cache
  or the thumbnail fetch as before, and an open decision schedules one
  background check (2 at a time, 1 per source, ≥1 s apart, each GET through
  the source's own client and headers).
- `CoverOriginalRules` is an explicit per-host rule set (version 1). A rule
  never leaves the source's own host and never fetches a page; candidates are
  https only and carry the hosts a fetch may reach.
- `CoverEgress` (every candidate, reference and refill GET): OkHttp never
  follows redirects; each hop (at most 3 redirects) must be https, on an
  allowed host, and connect only to a public unicast address (resolver
  answers checked per hop, the connected peer checked again; loopback,
  RFC 1918, shared, link-local, ULA, multicast, documentation and their
  IPv4-mapped/compatible/NAT64/6to4 forms refused). Routes are direct only:
  the egress client has `Proxy.NO_PROXY`, and a source whose client sets a
  proxy is refused. A refusal is the final verdict `REFUSED` with its
  reason. Non-2xx answers are read as statuses (Suwayomi's `await` would
  have turned a 404 into a network error); 403 and an HTML page (an
  unsolved challenge) are retried with backoff.
- The cache keeps one name per cover and 2418 types a file by its
  extension, so an original is accepted only in the cached format
  (`FORMAT_MISMATCH` otherwise), and every thumbnail answer is typed from
  its bytes.
- `CoverIdentity` accepts an original only when it fills the thumbnail's
  frame with ≥1.15× the linear resolution, its aspect is within the rule's
  tolerance, and a 63-bit DCT hash of a 64×64 luma grid matches (≤10 bits,
  whole frame or centre crop).
- Verdicts are durable per thumbnail URL (`<data root>/komu-covers/verdicts`):
  a due recheck is evaluated before final/exhausted, so rejections are
  rechecked after 30 days through ordinary traffic; consecutive retryable
  failures (any kind) back off 15 min → 16 h and settle on the thumbnail
  after 4 attempts; any final outcome resets the count.
- An accepted original replaces every cached cover file of the manga in
  place (same file name, staged and renamed atomically), so a reader that
  found the name always opens one complete image. Publication holds the
  manga's `CoverPublicationGate` stripe and succeeds only while the
  generation captured before the verification read anything is unchanged;
  every cache clear bumps it under the same lock.
- Decoding: javax.imageio on desktop; on iOS the host's ImageIO through JNI
  (`NativeCoverImage`, ABI 1), because OpenJ9 on iOS has no AWT natives. No
  decoder means no substitution.
- The preference (`<data root>/komu-covers/preferences.properties`, default
  on) is Komu-owned state; no database schema change.

- `CoverQualification.run` lives in the `qualification` source set, never in
  the server JAR; `:server:qualificationJar` packages it, and a DEBUG iOS
  host puts that JAR on the class path and calls it through JNI to qualify
  the native decoder inside OpenJ9.
- `CoverCacheWriterArchitectureTest` fails on any other write into the
  cover-cache directories.

Tests: `server/src/test/kotlin/app/komu/covers/`.
