# Publisher RSS and Apify fallback for failed article enrichment

The URL enrichment path first fetches the article. If a Financial Times or Euronews URL
returns HTTP 401, 403, or 429, or the fetch cannot reach the target, it tries the
publisher's RSS feed. `ft.trib.al` and `l.euronews.com` short links are resolved using
HTTPS redirects. The fallback accepts an item only when its publisher URL matches the
resolved article URL exactly after removing query parameters, fragments, and a trailing
slash. The item must have a nonempty title and summary. An unmatched, unavailable, or
incomplete feed leaves enrichment to the remaining methods.

If all normal enrichment methods fail, the server makes one final request to Apify
Web Fetch (`apify/web-fetch`) using `APP_FACEBOOK_IMPORT_APIFY_API_TOKEN`. This
applies to HTTPS article URLs when the token is configured. The request uses the
Authorization header and asks for the original HTML. The response is accepted only
when the target returned HTTP 2xx HTML, its final URL and canonical URL remain on
the same article and publisher, and the page has a usable title and summary. Known
short links for FT, Euronews, and Reuters may resolve to their publisher domains.
Errors and block pages still fail enrichment without a database write. Each request
can incur Apify Web Fetch charges; check current pricing on Apify before bulk retrying.

Feeds used:

- FT: `https://www.ft.com/news-feed?format=rss`
- Euronews: `https://www.euronews.com/rss?format=mrss`

These feeds contain recent items, so older failed proposals may remain unrecoverable.
Retry one failed proposal for each publisher after deployment and check the
`Publisher RSS fallback` log entries before bulk retrying. A successful feed match is
not evidence that the publisher's normal article endpoint is available again.

## Deferred options

- **TVN24:** Five failed proposals received HTTP 403 from this server. The current
  reader fallback returned no usable article. Check a different authorized egress
  route with one article before adding a publisher-specific fetch path. Validate
  that the result is an article, not TVN24's 403 placeholder.
- **Reuters:** Two failed proposals received HTTP 401 bot-protection pages. The
  mobile-client fallback did not recover them. Reuters Connect offers a licensed API;
  evaluate access and cost before integrating it.
- **Other Apify access methods:** Direct Unblocker proxy or residential proxy could
  be tested if Web Fetch still cannot retrieve a publisher. Both need separate proxy
  credentials and can incur additional charges.
