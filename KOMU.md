# Komu's Suwayomi-Server fork

This repository is the fork of [Suwayomi-Server](https://github.com/Suwayomi/Suwayomi-Server)
that the Komu iOS manga reader embeds as its local server. It is distributed
under the Mozilla Public License 2.0, the same license as upstream; see
[`LICENSE`](LICENSE), which is kept unchanged from upstream.

## What the Komu changes cover

Komu runs this server inside the iOS app on an embedded OpenJ9 (Java 17) JVM,
listening on loopback only, with the Web UI excluded. The fork keeps upstream
behaviour for ordinary desktop builds and adds narrow, iOS-gated changes:

- **iOS/OpenJ9 embedding**: Java 17 bytecode, gated desktop-only services,
  a secure session lifecycle, and JNI bridges to the host app
  (`app.komu.bridge`) for native file transfer, cover images, WebView
  JavaScript execution and the library commit.
- **Extensions**: authenticated direct-JAR installation from signed
  repositories, with preflight, crash-safe activation journals and updates,
  next to the upstream APK and manual-import paths.
- **Library commit protocol**: the host app commits the library database
  durably (H2 `CHECKPOINT SYNC`) before shared background work runs; backups
  and `server.conf` are written atomically and durably.
- **WebView provider**: extension WebViews run on the app's off-screen
  `WKWebView` through upstream's `WebViewProvider` seam, with cookie
  reconciliation and per-session egress controls.
- **Covers, updates and performance**: full-size cover preference, library
  update run identity, Local Source and idle-energy work.
- **Reproducible builds**: stable archive order, stripped timestamps and a
  `SOURCE_DATE_EPOCH` build time, so a tagged commit can be rebuilt to the
  same JAR bytes with the same JDK.

Komu-specific notes live in `docs/komu-*.md` and `AGENTS.md`.

## Release tags and the app's bundled JAR

The server version is `2.4.<commit count>` (`git rev-list --count HEAD`,
see `buildSrc/src/main/kotlin/Constants.kt`), so it is fixed by the exact
source commit.

- A tag `komu-v<version>`, for example `komu-v2.4.2477`, points at the exact
  commit that the Komu app's bundled `komu-server.jar` of that version was
  built from. The tag is placed on the source commit itself, not on a later
  documentation commit, because any later commit changes the version number.
- The Komu app records the bundled JAR's version, source commit, source tag,
  byte size and SHA-256 in `Config/EmbeddedServerArtifact.json`, and its
  acknowledgements link to the matching tag in this repository.
- Earlier `komu-ios-server-v*` tags mark the GitHub releases published before
  this scheme.

To rebuild a bundled JAR, check out its tag with full history (the version
depends on the commit count) and run, with Java 17:

```sh
SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)" ./gradlew :server:shadowJar
```

The output is `server/build/Suwayomi-Server-v<version>.jar`; compare its
SHA-256 with the value recorded by the app.

## Upstream attribution

Suwayomi-Server (formerly Tachidesk) is developed by the
[Suwayomi contributors](https://github.com/Suwayomi/Suwayomi-Server/graphs/contributors)
and builds on Tachiyomi/Mihon extension APIs. All upstream copyright and
license notices are retained. Komu's fork is based on upstream `v2.4.2366`
with selected later upstream commits cherry-picked; their original authorship
is preserved in the history.
