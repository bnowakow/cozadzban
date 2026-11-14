# Publisher RSS and Apify fallback for failed article enrichment

The URL enrichment path first fetches the article. If a Financial Times or Euronews URL
returns HTTP 401, 403, or 429, or the fetch cannot reach the target, it tries the
publisher's RSS feed. `ft.trib.al` and `l.euronews.com` short links are resolved using
HTTPS redirects. The fallback accepts an item only when its publisher URL matches the
resolved article URL exactly after removing query parameters, fragments, and a trailing
slash. The item must have a nonempty title and summary. An unmatched, unavailable, or
incomplete feed leaves enrichment to the remaining methods.

If all normal enrichment methods fail, the server tries these Apify methods in
increasing expected cost order, using `APP_FACEBOOK_IMPORT_APIFY_API_TOKEN`:

1. Web Fetch (`apify/web-fetch`) for the original HTML.
2. Web Fetch with `wait=2000`, which forces browser rendering.
3. Direct Apify `groups-UNBLOCKER` proxy, if the account has external proxy access.
   The server reads the current proxy password from `GET /v2/users/me` and keeps it
   in memory briefly. This requires a paid Apify plan for external proxy access.
4. Website Content Crawler (`apify/website-content-crawler`) with Firefox and the
   UNBLOCKER proxy group. It is limited to one URL, one page, and a $0.05 maximum
   charge per run. This browser Actor is the last and usually most expensive step.

Each step is skipped when the previous one returns a usable article. The server
requires an HTTPS URL, an article on the expected publisher, a usable title and
summary, and no error or challenge page. Known FT, Euronews, and Reuters short
links may resolve to their publisher domains. Errors still fail enrichment without
a database write. Web Fetch and proxy requests can incur charges independently;
the Actor charge cap applies only to the last step. Current prices and account
access are on [Apify's pricing](https://apify.com/pricing) and
[Proxy](https://docs.apify.com/proxy) pages. This is an expected cost order;
the actual bill depends on account plan, redirects, retries, and browser runtime.

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
- **Residential proxy:** May provide a different route for remaining FT articles,
  but needs separate usage controls and content validation before automatic use.
