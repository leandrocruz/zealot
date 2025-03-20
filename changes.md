# Zealot

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
