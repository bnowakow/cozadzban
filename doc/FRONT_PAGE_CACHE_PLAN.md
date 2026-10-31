# Front page cache plan

Status: **planned, not implemented**. The design below was agreed on 31 October
2026. It is deferred because measurement showed that the first-load cost was
image size, not database queries. The image fixes shipped first (see
[Article image generation](ARTICLE_IMAGE_GENERATION_PLAN.md)).

## Measurements (31 October 2026)

Taken on the production host, plus seven days of Zipkin traces (2,000 traces).

| Step | Time |
| --- | --- |
| Front-page article query (404 articles, `LIMIT 50`) | 0.5 ms |
| Article count and language queries | 0.1–0.3 ms |
| Initial HTML for `/` | ~80 ms |
| Vaadin page-setup request (`?v-r=init`) | ~8 ms |
| `/generated-images/{id}.png` (313 requests) | median 33 s, 90th percentile 116 s, max 162 s |
| `/rss/image/{id}` (50 requests) | median 1.5 s, 90th percentile 9.6 s |

Generated PNGs were 1.6–2.0 MB each (133 files, 229 MB). Zipkin durations
include the download to the visitor. A first load showing about 20 cards
downloaded around 35 MB. The images were also re-encoded on every request and
cached for only ten minutes with no revalidation.

Fixes applied: a JPEG website copy about ten times smaller, year-long cacheable
versioned URLs, `Last-Modified` revalidation, and `loading="lazy"` on feed
images. Re-measure before implementing this cache. With the image fixes in
place, it may save only milliseconds.

## Agreed design

**Scope**
- Signed-out visitors only. Signed-in users and admins always read the database,
  so no per-user data can leak between visitors (compare BR-07).
- Front page, default filters only: the unfiltered feed, plus `?lang=pl` and
  `?lang=en`, each cached separately.
- The first N articles, with N set by `APP_FRONT_PAGE_CACHE_ARTICLE_COUNT`
  (default 20). Each entry also holds the total count and the language list.
  Requests that fit inside the cached range never touch the database; anything
  beyond it reads the database as today. Check the batch size Vaadin's
  `VirtualList` requests first.
- Rendered `/stories/{id}` HTML for those same N articles, for signed-out
  visitors. Story views are still counted on every cached response.
- Not cached for now: `/rss` and `/sitemap.xml`.

**Invalidation and rebuild.** Clear the cache, then rebuild it in the
background, so writes don't wait. A visitor in the next moment may still read
the database. Triggers:
- an article is created, edited, or deleted (UI, REST API, Facebook proposal
  approval);
- a Facebook post is published for an article (story pages link to it);
- a generated image finishes (feed and story pages show it);
- the favicon backfill updates an article;
- a daily scheduled rebuild at `APP_FRONT_PAGE_CACHE_REFRESH_CRON` (default
  `0 0 4 * * *`, UTC). This avoids the 02:00 database backup and the 03:15
  Codex check;
- an ADMIN menu item, **Rebuild front page cache**, at the bottom of the
  account menu. It asks for confirmation, then shows progress (cleared, front
  page variants rebuilt, story pages rebuilt) and a final summary.

Route triggers through `ArticleService` and the few repositories that update
`article` directly (`updateFavicon`, image job completion, publication queue),
so no write path is missed.

**Storage.** In-memory, one cache per application container (for example
Caffeine), behind a small interface so Redis can replace it later. During a
blue/green overlap, a rebuild only reaches the container that handled the
change or the admin click. The other container catches up at its next change
or the daily rebuild.

**Settings**

| Variable | Default |
| --- | --- |
| `APP_FRONT_PAGE_CACHE_ENABLED` | `true` |
| `APP_FRONT_PAGE_CACHE_ARTICLE_COUNT` | `20` |
| `APP_FRONT_PAGE_CACHE_STORY_PAGES_ENABLED` | `true` |
| `APP_FRONT_PAGE_CACHE_REFRESH_CRON` | `0 0 4 * * *` |

**Metrics.** Hit, miss, rebuild count, and rebuild duration, exposed through
Micrometer at `/actuator/metrics`.

**Tests.**
- Each invalidation trigger clears and rebuilds the cache.
- Signed-in requests bypass the cache.
- Cached story responses still count views.
- The cache-enabled and story-pages-enabled settings work.
- The admin action reports progress.
