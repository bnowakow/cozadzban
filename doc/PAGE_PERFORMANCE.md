# Page performance

This page records how fast the public site loaded before and after the October
2026 changes, what changed, and how to measure again in the same way.

## Summary

The first front-page load was slow because of image size, not the server or the
database. On 31 October 2026, a website copy of each generated illustration
about 11× smaller, plus better caching, cut the images on the first page from
25.2 MB to 2.2 MB. At 10 Mbit/s, that is 19.6 s → 1.1 s of image download.

## Timeline

| When (UTC) | Version / commit | Change |
|---|---|---|
| 2026-10-31 07:30–08:30 | 0.62.32 | Baseline measurements below |
| 2026-10-31 08:39:44 | 0.62.33, `560636d` | Generated images: JPEG website copy, versioned year-long URLs, `Last-Modified`/304 revalidation, no per-request PNG re-encoding, `loading="lazy"` in the feed |
| 2026-10-31 08:47:56 | nginx config, `49d506d` | `/VAADIN/build/*` served with `Cache-Control: public, max-age=31536000, immutable` instead of 1 hour |
| 2026-10-31 09:04 | nginx config, `a3ea1c6` | Compose nginx gzips text responses itself, instead of relying only on the host HTTPS proxy. Visitor sizes are unchanged (JavaScript 340 → 111 KB, HTML 63 → 20 KB, `aura.css` 69 → 12 KB, `/rss` 456 → 138 KB); compression no longer depends on the host proxy's configuration |

Use these times as `SINCE` and `UNTIL` boundaries when comparing Zipkin data.

## Baseline, before the changes

### What a first front-page load cost

Measured on the server, with 404 articles.

| Part | Cost |
|---|---|
| Front-page article query | 0.7–2.6 ms warm (the app's column list); up to ~47 ms with a cold cache |
| Count and language queries | 0.1–0.5 ms |
| Front page HTML `/` | ~80 ms |
| Vaadin page-setup request (`?v-r=init`) | ~8 ms |
| JavaScript bundle / `aura.css` / HTML, as visitors get them | 110 KB / 11 KB / 20 KB, gzipped by the host HTTPS proxy |
| Generated images on the first 20 cards (14 images) | **25.2 MB, 19.6 s at 10 Mbit/s** |

Generated images were 1200×630 PNGs of 1.6–2.0 MB each: 133 files, 229 MB in
total. The browser could cache them for only 10 minutes. After that it had no
way to revalidate, so it downloaded the full PNG again. Each request also decoded
and re-encoded the PNG.

### Real requests (Zipkin)

| Window | `/generated-images/{id}.png` requests | Median | 90th percentile | Slowest |
|---|---:|---:|---:|---:|
| 7 days to 31 Oct (2,000 traces) | 313 | 33.2 s | 116.4 s | 162.3 s |
| 2 hours before the deploy | 104 | 56.6 s | — | 94.3 s |

From the same 7 days, `/rss/image/{id}` had a median of 1.5 s and a 90th
percentile of 9.6 s, and `/stories/{id}` a median of 9 ms. Zipkin durations run
until the response is fully sent, so they include the download to the visitor.

## After the changes (31 October 2026, first measurements)

| Measurement | Before | After |
|---|---:|---:|
| Image data, first 20 cards | 25.2 MB | 2.2 MB |
| Image download at 10 Mbit/s | 19.6 s | 1.1 s |
| One example image (article 519) | 2.0 MB PNG | 186 KB JPEG |
| Revisit within a year (versioned image or Vaadin asset) | Downloaded again after 10 min / 1 h | Served from the browser cache |
| Unversioned image after its 10 minutes | Full download | 304 with no body |
| Zipkin, generated-image requests | median 56.6 s (2 h before) | median 143 ms (4 requests, first hour) |

The first post-deploy Zipkin numbers come from a handful of requests. Treat them
as a sanity check, not a result; see the comparison steps below.

The front-page cache that was designed alongside these changes is deferred. With
queries under 3 ms, it would save almost nothing. See
[FRONT_PAGE_CACHE_PLAN.md](FRONT_PAGE_CACHE_PLAN.md).

## How to measure again

Run on the deployment server:

```sh
make performance-report
```

The report is printed and saved as
`logs/performance-reports/<UTC time>Z.md`. It contains:

1. **Real requests (Zipkin):** count, median, 90th percentile and slowest
   duration for each endpoint.
2. **Database:** best-of-three execution time for the front-page queries.
3. **Server response times:** best of three for `/` and the newest story page.
4. **First-page images:** PNG vs JPEG total size, and download time at a fixed
   rate.
5. **Caching headers:** for images and Vaadin assets, locally and through the
   public site, plus the public gzip sizes.

Options are environment variables:

| Variable | Default | Use |
|---|---|---|
| `LOOKBACK_HOURS` | `168` | How far back Zipkin is queried |
| `SINCE` / `UNTIL` | unset | Only count traces in this UTC window, e.g. `SINCE=2026-10-31T08:40:00Z` |
| `RATE` | `1250k` | Download rate for the image test (bytes/s; `1250k` ≈ 10 Mbit/s, `250k` ≈ slow mobile) |
| `ARTICLES` | `20` | How many front-page articles to include |
| `BASE_URL` / `PUBLIC_URL` | local proxy / `https://cozadzban.pl` | Targets |

Example: everything since the image deploy, on a slow connection:

```sh
SINCE=2026-10-31T08:40:00Z RATE=250k make performance-report
```

Requesting each image's `.jpg` creates its website copy if it is missing, just
as the first visitor would.

### Comparing over time

- **Save reports regularly.** Zipkin keeps traces only in memory (256 MB
  limit). It drops them on restart and evicts old ones as new ones arrive, so a
  week-old window may already be gone. The saved reports are the long-term
  record. `logs/` is not in git, so copy reports you want to keep elsewhere.
- **Expect sampling.** Production traces 10% of requests
  (`MANAGEMENT_TRACING_SAMPLING_PROBABILITY`). Wait until an endpoint has at
  least ~100 sampled requests before trusting its median.
- **Compare like with like.** Use the same `LOOKBACK_HOURS` or `SINCE`/`UNTIL`
  length, and the same `RATE` and `ARTICLES`. Compare medians and 90th
  percentiles, not slowest requests, which are dominated by single slow
  visitors and crawlers.
- **The section 4 numbers are deterministic.** Image size and download time at
  a fixed rate don't depend on traffic, so they are directly comparable between
  any two reports.
- **The PNG endpoint should go quiet.** The site no longer links to
  `/generated-images/{id}.png`. After the change, remaining PNG traffic comes
  from Facebook, link previews (`og:image`), and old cached pages.
- **Real-browser check.** Open the front page in a private window with the
  developer tools Network tab. Feed images should be `.jpg?v=` URLs of about
  100–200 KB with `loading="lazy"`. Reload: images and `/VAADIN/build/` files
  should come from the browser cache.

### Suggested follow-up

About a week after 31 October 2026, run:

```sh
SINCE=2026-10-31T08:40:00Z make performance-report
```

Then compare the `get /generated-images/{id}.jpg` median and 90th percentile
with the baseline table above. If the front page still feels slow, look next at
`get /rss/image/{id}`, the source-thumbnail proxy. Its 90th percentile was
4.4–9.6 s.
