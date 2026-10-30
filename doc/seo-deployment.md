# SEO deployment checks

The preferred origin is `https://cozadzban.pl`. The application emits canonical
links, and the Compose nginx redirects `www.cozadzban.pl` to this origin while
preserving the path and query string.

## Fix the www TLS failure before checking canonicalization again

On 2026-10-30, the public endpoints returned:

- `http://cozadzban.pl/`: 301 to `https://cozadzban.pl/`.
- `https://cozadzban.pl/`: 200 with a canonical link and H1/H2 headings.
- `https://www.cozadzban.pl/`: Cloudflare 525 (origin TLS handshake failure).

An HTTP redirect in Compose cannot fix a TLS handshake that fails before the
request reaches Compose. Choose one of these infrastructure fixes:

1. If Cloudflare proxies `www`, create a Single Redirect rule for hostname
   `www.cozadzban.pl`, returning 301 to
   `concat("https://cozadzban.pl", http.request.uri.path)` with **Preserve query
   string** enabled. Keep the main domain's existing proxy/cache settings.
   Confirm that the Cloudflare edge certificate covers `www.cozadzban.pl`.
2. Alternatively, configure the origin host nginx to accept HTTPS for `www` with
   a valid certificate covering `www.cozadzban.pl`, and return
   `301 https://cozadzban.pl$request_uri` from that HTTPS server. The certificate
   and private key paths must refer to the actual installation; do not copy
   guessed paths or disable TLS verification to bypass the failure.

Validate host nginx with `nginx -t` before reloading it. Check all four combinations
of HTTP/HTTPS and www/non-www, including a story path and query parameters. All
variants should reach the HTTPS non-www URL, with no certificate errors or loops.
Do not run Gradle tests on the deployment server.

## Other scan findings

- Initial homepage HTML includes headings, summaries, links and JSON-LD. The
  homepage preloads the interactive UI theme without blocking the initial story
  list. A `<noscript>` stylesheet supports readers without JavaScript.
- Unknown public page requests return a helpful HTML page with HTTP 404.
  Protected routes and API authentication retain their existing behavior.
- Story pages also return the helpful page for missing stories and describe the
  visible content in JSON-LD, without creator/account data or invented dates.
- Google Analytics already loads after cookie consent when configured. A scanner
  that never accepts consent may not detect the external script.
- A CDN is an infrastructure choice. The www response already goes through
  Cloudflare; that alone does not establish whether the main domain's assets are
  cached. Inspect actual cache headers and timings before changing providers or
  proxy settings. Never cache session-specific HTML or authenticated API responses.
- The news feed contains changing topics. Keep its title and description accurate
  rather than stuffing them with whichever words appear most often in one scan.

After deploying the application and fixing www HTTPS, run a fresh scan. Existing
free-report snapshots can continue to show findings from older HTML.
