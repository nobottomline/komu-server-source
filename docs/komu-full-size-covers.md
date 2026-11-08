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
| `manga/controller/MangaController.kt` `thumbnail` | `X-Komu-Cover: original/thumbnail/pending`; `Cache-Control: no-store` while pending |
| `server/JavalinSetup.kt` | registers `GET/PUT /api/v1/komu/covers/preferences` |

Everything else lives in `app.komu.covers` and `app.komu.bridge.NativeCoverImage`.
With the preference off the thumbnail path is byte-for-byte upstream.

## Contract

- Serving never waits for the network: a request is answered from the cache
  or the thumbnail fetch as before, and an open decision schedules one
  background check (2 at a time, 1 per source, ≥1 s apart, each GET through
  the source's own client and headers).
- `CoverOriginalRules` is an explicit per-host rule set (version 1). A rule
  never leaves the source's own host and never fetches a page.
- `CoverIdentity` accepts an original only when it fills the thumbnail's
  frame with ≥1.15× the linear resolution, its aspect is within the rule's
  tolerance, and a 63-bit DCT hash of a 64×64 luma grid matches (≤10 bits,
  whole frame or centre crop).
- Verdicts are durable per thumbnail URL (`<data root>/komu-covers/verdicts`):
  rejections are rechecked after 30 days, transient failures back off
  15 min → 16 h and settle as a rejection after 4 attempts.
- An accepted original replaces every cached cover file of the manga by an
  atomic move; a refreshed cover URL in the meantime cancels the install.
- Decoding: javax.imageio on desktop; on iOS the host's ImageIO through JNI
  (`NativeCoverImage`, ABI 1), because OpenJ9 on iOS has no AWT natives. No
  decoder means no substitution.
- The preference (`<data root>/komu-covers/preferences.properties`, default
  on) is Komu-owned state; no database schema change.

Tests: `server/src/test/kotlin/app/komu/covers/`.
