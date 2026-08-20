# Zealot

## Release v1.3.2
LTS: 20/08/2026

 - Fixed an infinite redirect loop introduced in v1.3.1. `fixRelativeUrl` started routing every non-absolute `Location` through `HttpUtils.sanitize`, which re-encodes whole query values via `URLEncoder` and so turned an already-encoded `?acao=SSO%2Flogin` into `?acao=SSO%252Flogin`. eProc TJRJ (`eproc1g.tjrj.jus.br`) routes on `acao`, did not recognize the mangled value, and answered with the same redirect indefinitely. Relative locations now go through `HttpUtils.escapeIllegal`, which escapes only characters that are illegal in a URI and leaves a valid `%XX` triplet alone (a bare `%` is still escaped to `%25`). Escaping uses ISO-8859-1 for parity with the `URLEncoder` call it replaces. Both v1.3.1 behaviors are kept: bare-relative locations are still resolved against the request URL, and raw locations with spaces/accents (eProc TJMS) still parse.
 - `escapeIllegal` also fixes two latent defects of `sanitize` on the redirect path: `split('=')` dropped base64 padding from a query value (`token=...sig==` became `token=...sig`) and threw `MatchError` on a value carrying its own `=`. `sanitize` itself is unchanged, since it is still used to parse the `Location` when detecting known eProc site errors.

## Release v1.3.1
LTS: 20/08/2026

 - Fixed `BotError(HttpError, "Error extraindo domínio de ...")` crash when a redirect `Location` carries unencoded characters (spaces, accents, quotes, parentheses). `DefaultHttpSession.domainGiven` now reads the host via the lenient `new URL(url).getHost` instead of `new URL(url).toURI`, whose strict RFC 2396 validation threw `URISyntaxException` on the raw query string. Domain extraction only needs the host, so the stricter parse was never required. Seen on eProc TJMS (`eproc1g.tjms.jus.br`), whose `?acao=principal&msg=Não foi localizado usuário com este CPF (...)` redirect crashed cookie handling.
 - `DefaultHttpRequest.fixRelativeUrl` now resolves and sanitizes **any** non-absolute redirect location, not just those starting with `..` or `/`. Bare-relative locations like `externo_controlador.php?...` previously bypassed both `URI.resolve` and `HttpUtils.sanitize` and were passed through raw, producing malformed URLs (e.g. `baseUrl + location` concatenated `/eproc` + `externo…` without a separator). Any location not starting with `http://`/`https://` is now treated as relative and resolved against the request URL.

## Release v1.3.0
LTS: 17/07/2026

 - Added `using HttpSession` to `HttpInterceptor` methods

## Release v1.2.0
LTS: 26/06/2026

 - Added TLS version pinning via `HttpRequest.tls(Tls1_2 | Tls1_3)` — renders `--tlsv1.x --tls-max 1.x`. Default is unchanged (no flag) when not set. Needed for gov.br certificate login (`certificado.sso.acesso.gov.br`): the F5 BigIP downgrades TLS 1.3→1.2 mid-handshake while requesting the client cert, which OpenSSL 3.0 aborts with `curl (35) data between ccs and finished`. Pinning TLS 1.2 from the ClientHello avoids the downgrade.
 - Reverted the native curl cookie jar introduced in v1.0.0 (`CookieJar`/`CurlCookieJar`, `HttpSession.options`, `ResponseCookie.hostOnly`, removal of `HttpSession.rebase`). v1.0.0 stopped persisting `Set-Cookie` in `DefaultHttpSession.update` and delegated cookie state to curl's jar, which broke bot logins. Cookie handling is back to the v0.8.2 behavior (explicit `--cookie` per request, cookies persisted in-session).
 - Kept the PUT HTTP Method from v1.1.0 (Sirea TRF1), including the fix for the swapped `Put`/`Head` curl verbs.

## Release v1.1.0
LTS: 10/06/2026

 - Added PUT HTTP Method (Sirea TRF1)

## Release v1.0.0
LTS: 28/04/2026

 - Using the native curl cookie jar (via `CookieJar`)
 - Added `HttpSession.options`
 - Added `ResponseCookie.hostOnly` wich is required to determine the cookie "url" in other contexts, like google chrome extensions
 - Removed legacy code `HttpSession.rebase`

## Release v0.8.2
LTS: 10/04/2026

 - Fixed double-decompression in `HttpResponse.bodyAsString` when `Compression.All`/`Only` was in effect. `curl --compressed` decompresses the body transparently but leaves `Content-Encoding` in the response headers, so the old code tried to gunzip already-plain bytes and failed with `ZipException: Not in GZIP format`. Decompression is now driven by the magic bytes of the body file (`1f 8b` for gzip, zlib FCHECK rule for deflate) instead of the `Content-Encoding` header.
 - Added a raw-deflate fallback in `HttpResponse.bodyAsString`. When magic bytes don't match gzip or zlib but `Content-Encoding: deflate` is present, the body is now inflated with `Inflater(nowrap = true)`, handling servers that send raw deflate without the zlib wrapper (non-compliant with RFC 7230 but common in the wild).
 - Cleaned up Scala 3 compiler warnings: replaced `tail: _*` with `tail*` in `CurlHttpEngine`, replaced `DefaultHttp.apply _` with `() => DefaultHttp()` in `Http.layer`, and passed `StandardCharsets.UTF_8` explicitly to `URLDecoder.decode` in `CurlHttpEngine.printRequest` (the single-arg overload is deprecated).

## Release v0.8.1
LTS: 26/03/2026

 - Fixed infinite loop at `zealot.http.DefaultHttpSession.domainGiven`

## Release v0.8.0
LTS: 19/03/2026

 - Added `DataUrlEncodeCharset`
 
## Release v0.7.0
LTS: 19/03/2026

 - Added `Compression` enum (`Off`, `All`, `Only(algorithms*)`) for controlling HTTP response compression
 - Added `HttpRequest.compressed(compression)` to control compression per request
 - Added `HttpSession.compressed` to set compression as a session default
 - Request-level compression takes precedence over session-level
 - CurlHttpEngine renders `--compressed` and optionally overrides `Accept-Encoding` for `Compression.Only`
 - `HttpResponse.bodyAsString` now auto-decompresses `gzip` and `deflate` responses based on `Content-Encoding` header
 - `HttpResponse.bodyAsString` now validates `Content-Type` and fails for binary content types
 - `HttpResponse.bodyAsString` uses `ZIO.attemptBlocking` and `ZIO.acquireReleaseWith` for safe blocking I/O with guaranteed resource cleanup

## Release v0.6.1
LTS: 17/03/2026

 - Added `HttpRequest.headers(values)`
 - Added `HttpRequest.fields (values)`

## Release v0.6.0
LTS: 11/03/2026

 - Added `HttpProxy.skipCertificateValidation` to be able to add the `--proxy-insecure` option to the curl command line

## Release v0.5.0
LTS: 13/01/2026

 - Using scala 3.7.2
 - Added HttpOptions
 - Customizing the curl binary via CurlOptions

## Release v0.4.0
LTS: 05/11/2025

 - Added `HttpRequest.suppressUserAgent`

## Release v0.3.1
LTS: 01/10/2025

 - Added `HttpRequest.maxRedirects` to configure the maximum number of redirects allowed
 - Added infinite redirect loop protection (defaults to max 10 redirects)

## Release v0.3.0
LTS: 29/09/2025

 - Using the result of `HttpInterceptor.onFollow` when performing redirect requests 

## Release v0.2.9
LTS: 25/04/2025

 - Added `HttpProxy.secure` to handle https proxies

## Release v0.2.8
LTS: 19/03/2025

 - Calling `curl` via `zio-process` so that `zio.timeout(duration)` works

## Release v0.2.7
LTS: 19/03/2025

 - Using `ExecutableHttpRequest` on `HttpInterceptor` 

## Release v0.2.6
LTS: 14/03/2025

- Added `HtmlElement.parent`

## Release v0.2.5
LTS: 13/03/2025

 - Updating zio dependencies

## Release v0.2.4
LTS: 07/02/2025

 - Allowing responses to set cookies for parent domains of the current request 

## Release v0.2.3
LTS: 30/01/2025

 - Second attempt at "header parsing problem with multiple responses"

## Release v0.2.2
LTS: 22/01/2025

 - Fixed header parsing when header file contains multiple lines with http responses
 - Refactored response parser code into a new object called ResponseParser
 - Added CurlTest

## Release v0.2.1
LTS: 17/01/2025
 
 - Fixed date parsing for cookies for dates with only 2 numbers to represent years (like Sun, 19-Jan-25 15:00:18 GMT)

## Release v0.2.0
LTS: 12/12/2024

 - Creating new sessions with a collection of predefined headers 
 - Added HttpRequest.removeHeader

## Release v0.1.5
LTS: 11/12/2024

 - Fixed cookie parsing from values like `name=value==`

## Release v0.1.4
LTS: 06/12/2024

 - Handling redirects for "relative" locations

## Release v0.1.3
LTS: 04/12/2024

 - Follow redirects when response code is between 300 and 400

## Release v0.1.2
LTS: 03/12/2024

 - Ignoring '.' at Cookies.from
 - Added Cookies.all

## Release v0.1.1
LTS: 25/11/2024
 
 - Keep HttpVersion when following http redirects 

## Release v0.1.0
LTS: 22/11/2024

 - Fixed bug when reading headers/response code with multiple http responses (when using --proxy)
 - Added HttpContext.logger (HttpLogger)
 - Added Outcome values
   * TwoFactorAuthError
   * CertificateError
   * ExpectedAttributeNotFound
   * ExpectedElementNotFound
 - Removed Outcome values
   * AuthIsNotWorkingPleaseRetry
   * SiteHasChanged

## Release v0.0.2
LTS: 22/10/2024

 - Added support for PKCS12 certificates

## Release v0.0.1
LTS: 18/10/2024
