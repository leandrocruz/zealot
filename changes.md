# Zealot

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
