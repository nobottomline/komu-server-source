# iOS WebView provider (Komu, November 2026)

Decision record: Komu ADR-0087 (`docs/architecture/server/0087-ios-webview-provider.md`
in the app repository). Research: `docs/problems/tachimanga-server-comparison-2026-11.md`.

## What it closes

74 of Keiyoushi's 1,554 extensions reference `android.webkit.WebView`; 43 of the 44
source modules behind them use Keiyoushi's `runWebView` helpers. On the iOS host
(`-Dkomu.platform=ios`) CEF never starts, so upstream's `KcefWebViewProvider.init`
waited for it forever on the main looper (reproduced on 2.4.2449).

## Pieces

| Piece | Where | Origin |
|---|---|---|
| Provider selection, typed failure | `xyz.nulldev.androidcompat.webkit.WebViewProviders`, `WebViewUnavailableException` | Komu |
| iOS provider | `xyz.nulldev.androidcompat.webkit.ios.WKWebViewProvider` (+ `WKWebViewSessions`, `WebEngine`) | Komu; restores the archived provider of `archive/pre-v2.3-master-20260814` on a new engine protocol |
| JNI surface | `app.komu.bridge.JsExecutor` (ABI 4) | Komu; native side `Modules/Sources/JNIBridge/JsExecutor.m` in the app |
| AndroidX WebKit glue | `org.chromium.support_lib_*` | upstream #2357 (`f9fa6628`), plus Komu boundary interfaces and the provider hand-off in `SupportLibWebViewChromiumFactory` |
| `evaluateJavascript` as JSON | KCEF | upstream #2428 (`f54d716d`), #2517 (`9017f68f`) |
| CEF marked unavailable on iOS | `ServerSetup` | Komu |
| Cookie bridge | `xyz.nulldev.androidcompat.webkit.ios.WebCookies` (`WebCookie`, `WebViewCookieJar`, `CookieSync`), `eu.kanade.tachiyomi.network.KomuWebViewCookieJar` | Komu (Review 1) |
| Server jar RFC 6265 fixes | `PersistentCookieStore` (`get` matching, identity, host-only without Domain), `CookieManagerImpl` (explicit Domain) | Komu fixes of upstream code (Review 1) |
| In-process contract diagnostic | `app.komu.webview.WebViewContractQualification`, `qualification` source set, `:server:webviewQualificationJar` | Komu; never in the server JAR (Review 1) |

`WebResourceError` replaces the android.jar stub (its constructor threw), as the
fork already does for other `android.webkit` classes.

## Contract (what extensions get)

- Page events with URLs, `evaluateJavascript` results and web-message listeners
  on the WebView's looper; interception and `@JavascriptInterface` calls on
  background threads (interface calls in order).
- `evaluateJavascript`: the completion value of any script as JSON, `"null"` for
  `undefined` or a thrown error; object keys sorted.
- `loadUrl` (headers, POST), `loadData`/`loadDataWithBaseURL`; one User-Agent per
  session (`settings.userAgentString`, default the server's `http.agent`) on every load.
- Only `@JavascriptInterface` methods reach the page; they return `undefined`.
- Glue: `DOCUMENT_START_SCRIPT:1`, `WEB_MESSAGE_LISTENER`, `USER_AGENT_METADATA`
  (stored only). Not offered: `JS_INJECTION_IN_FRAME_AND_WORLD`, `COOKIE_INTERCEPT`,
  `MUTE_AUDIO` (media never autoplays), `WEB_MESSAGE_ARRAY_BUFFER`.
- Interception observes main-frame, sub-frame, fetch, XHR and resource-timing
  requests; a non-null response blocks a held sub-frame navigation only (WebKit
  cannot replace http(s) responses without private API).
- `postVisualStateCallback` completes after a round trip through the page.
- Cookies (Review 1): before each load the jar's cookies for the load's host
  reach the session's own store as RFC 6265 records (host-only stays host-only;
  Domain, Path, Secure, HttpOnly, SameSite, Expires kept). The engine reports the
  session's full cookie set with its admitted main-frame hosts whenever either
  changes, before its next event; `CookieSync` reconciles additions, changes and
  deletions (expiry included) into the jar for those hosts only. Sub-frame hosts
  never become targets. WebKit stores SameSite Lax and Strict only; None reads
  back as unspecified and is kept in the jar.
- Page messages (Review 1): credit-bounded per session (256 messages or 4 MiB in
  the JVM's hands; one message always fits). Each bridge call and web message
  carries its cost and returns it with `OP_ACK` once handled; refused messages
  reach the client as `onReceivedError(ERROR_TOO_MANY_REQUESTS)`; 512 refusals end
  the session (`onRenderProcessGone`).
- Egress (Review 1, Komu ADR-0063 §6): a session waiting for one of the three
  slots declares each load at once (`OP_BIND`); if the engine ends it while it
  waits, it never activates and the slot goes on.
- Not supported: touch input into the page (`dispatchTouchEvent`), interactive
  verification (Turnstile), `WebMessagePort`s.

## Server jar (Review 1)

`PersistentCookieStore.get(url)` now applies RFC 6265 §5.4 like Android's
CookieManager does (host-only to its own host, domain-match rather than a string
suffix, path, Secure, expiry) — before, a host-only cookie of `example.com` was
sent to `ads.example.com` and to `badexample.com` by OkHttp too. Cookies are keyed
by name, domain and path. `HttpCookie`s without a Domain are stored host-only, and
`CookieManager.setCookie` with an explicit Domain stores a domain cookie.

## Desktop

Unchanged: without `-Dkomu.platform=ios` the KCEF provider is registered and the
glue advertises upstream's feature list only.
