# iOS WebView provider (Komu, November 2026)

Decision record: Komu ADR-0086 (`docs/architecture/server/0086-ios-webview-provider.md`
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
| JNI surface | `app.komu.bridge.JsExecutor` (ABI 3) | Komu; native side `Modules/Sources/JNIBridge/JsExecutor.m` in the app |
| AndroidX WebKit glue | `org.chromium.support_lib_*` | upstream #2357 (`f9fa6628`), plus Komu boundary interfaces and the provider hand-off in `SupportLibWebViewChromiumFactory` |
| `evaluateJavascript` as JSON | KCEF | upstream #2428 (`f54d716d`), #2517 (`9017f68f`) |
| CEF marked unavailable on iOS | `ServerSetup` | Komu |
| In-process contract diagnostic | `suwayomi.tachidesk.server.util.WebViewContractDiagnostic` | Komu; debug app builds only |

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
- Not supported: touch input into the page (`dispatchTouchEvent`), interactive
  verification (Turnstile), `WebMessagePort`s.

## Desktop

Unchanged: without `-Dkomu.platform=ios` the KCEF provider is registered and the
glue advertises upstream's feature list only.
