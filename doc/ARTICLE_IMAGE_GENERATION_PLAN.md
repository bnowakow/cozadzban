# Article image generation — proposed feature

Status: experimental Codex CLI adapter, article image job, public display,
and basic ADMIN status/trigger page implemented. The production provider,
usage budget, and provider connection controls remain planned. New articles without a usable source thumbnail receive a
`MISSING_THUMBNAIL` image status. The source thumbnail remains the first choice;
the branded placeholder remains the failure fallback.

## Feasibility and provider choice

The Codex CLI can run in a headless Docker container. Its `codex login
--device-auth` flow lets an operator finish sign-in in a separate browser, and
`codex exec` supports unattended commands. A persistent `CODEX_HOME` volume
would retain its credential cache across container replacement. The cache can
contain plaintext access tokens, so it must stay out of the browser, logs,
source tree, and container images. The requested Java-container access means
the web app process can read the volume and must be treated as trusted. These are documented CLI
capabilities, **not** evidence that a hosted application's admin UI may reuse
Codex's ChatGPT login to power production image generation.

The CLI is installed in the Spring Boot image, with its credential cache in the
private `codex-auth` Docker volume. A bounded Java adapter can ask `$imagegen`
for an image from an article's title, lead, quote, summary, and cached
text. A scheduler checks each minute and claims at most one missing-image
article every ten minutes across application instances using a database pacing
row. It retries up to three times, then marks the job `FAILED`. The Facebook
publication waits while the image job is pending, generating, or failed, so an
admin can still retry a failed job with `Generate now`. After
`APP_FACEBOOK_IMAGE_PUBLICATION_WAIT_MAX` (default 24 hours) from the
publication's creation, it posts with the branded placeholder anyway.

Codex's console output is kept for the run. When an `ERROR:` line reports the
ChatGPT usage limit ("You've hit your usage limit … try again at Nov 4th, 2026
6:36 AM"), the job returns to the queue without using an attempt. Automatic
generation for all articles then pauses until the reported time, read as UTC
plus one minute, or for one hour if no time can be read. Without that, every
queued article would burn its three attempts against the limit. Other exit
failures record Codex's last `ERROR:` line in the job's error.

When Codex can't deliver an image (any failure except "no article text") and
`APP_FACEBOOK_IMAGE_CLAUDE_ENABLED=true`, the same job asks Claude Code for a flat
vector SVG instead. The app checks it (plain shapes only) and renders it to the same
1200 × 630 PNG with Batik. If Claude succeeds, the job completes as `GENERATED`. If
Claude also fails, a Codex usage limit still pauses the queue (the earlier reset wins
when both are limited), and other failures record both reasons. With Codex disabled,
Claude illustrates alone. See [Claude Code in Docker](CLAUDE_DOCKER.md). If Meta explicitly rejects a source thumbnail, publication is
deferred and the article joins the same image queue. The adapter validates image
size and dimensions, crops to 1200 × 630, and caches the PNG in the persistent
data volume. Completed generated images are shown on the feed and story page
through `/generated-images/{id}.png`, with visible AI illustration labels. The
Facebook post caption carries the same disclosure. This is a proof of concept,
not approval to use the ChatGPT login for this hosted workflow. See
[Codex in Docker](CODEX_DOCKER.md).

`APP_FACEBOOK_IMAGE_CODEX_ENABLED=false` is the code default. The deployment
operator has enabled it in the private `.env`; a permitted provider and
authentication route still needs confirmation. Migration V40 queues existing
articles with missing or locally invalid source thumbnails without resetting
any existing image status. `APP_FACEBOOK_IMAGE_CODEX_TIMEOUT_SECONDS` defaults to 180 seconds and
`APP_FACEBOOK_IMAGE_CODEX_ASSET_DIR` defaults to `/app/data/facebook-generated`.
The CLI call runs in a temporary directory with a reduced environment. It still
shares the application container and Codex credential volume, so it does not
provide the isolation intended for the later dedicated worker.
Codex's image tool writes its initial file under `CODEX_HOME/generated_images`;
the Java adapter accepts only a freshly created path inside that directory from
Codex's final response. If that response omits the path, it accepts a single
fresh PNG from the same directory. It performs the validation and copy itself
because the CLI's shell sandbox could not copy the file in the first live
attempt. Failure reasons distinguish a timeout, CLI exit, missing image, and
invalid image. An article with no title, lead, quote, summary, or cached text is
marked `FAILED` without calling Codex: there is no subject to illustrate, and
the branded placeholder remains available for Facebook publishing. The
scheduler removes such jobs before claiming its next ten-minute generation
slot. An admin can retry after correcting the article metadata.

Each generated image is stored twice in the asset directory. `article-<id>.png`
is the full-quality 1200x630 original, used unchanged for Facebook photo posts,
`og:image`, and `/generated-images/<id>.png`. `article-<id>.jpg` is a JPEG copy
at quality 0.82 for the website, about ten times smaller (for example 2.0 MB →
197 KB). The feed and story pages show the JPEG through
`/generated-images/<id>.jpg?v=<version>`. The version is the PNG's
last-modified time, so browsers may keep the image for a year, and a
regenerated image gets a new URL. The copy is written right after generation.
For images that existed before this change, it is created on the first request,
and it is rebuilt whenever the PNG is newer than it. Unversioned image URLs are
cached for ten minutes and revalidate with `Last-Modified`. Feed images use
`loading="lazy"`.

The ADMIN-only `/admin/facebook-thumbnails` page lists articles in pages of 50,
shows the source or generated image where available, filters by thumbnail state,
and shows attempts, next attempt, and last error. `Generate now` is available
for missing and failed image jobs. It claims one job immediately and processes
it outside the browser request; the automatic and manual workers use the same
database lock, so only one generation runs across app instances. A manual claim
also moves the next automatic slot ten minutes forward. Refresh the page to see
the result. It does not replace generated images or already published Facebook
posts.
Below the status table, a separately paginated queue lists every
`MISSING_THUMBNAIL` job in the scheduler's `next_attempt_at, article_id` order,
with its position and the number waiting. The count of currently generating
jobs appears beside it. Refresh updates both tables; manual claims can change
the queue order. Failed jobs need an explicit manual retry and do not appear in
the waiting queue.

The preferred admin-initiated ChatGPT OAuth flow is **not currently a suitable
production dependency** for this hosted site:

- OpenAI states that Codex app-server authentication has not been permitted for
  hosted services. Although its JSON-RPC device-code method returns a URL and
  user code suitable for a web UI, this restriction applies to the proposed
  integration. Do not implement that path without an approved route from OpenAI.
- Sign in with ChatGPT for hosted integrations requires a client ID and is
  currently offered to selected commercial partners. Its present ChatGPT-plan
  inference preview does not support image generation.
- OpenAI recommends API keys for automation and the Image API for programmatic
  image generation. A direct server-side Image API call is therefore the
  production provider for this feature. It uses API billing, not the admin's
  ChatGPT/Codex plan allowance.

The ADMIN settings UI can still provide a smooth **Connect image provider**
flow: enter an OpenAI API key once into a masked form, validate it server-side,
store it encrypted, show connection and usage status, and allow rotation or
disconnection. This is key-based authorization, not OAuth. Never send the key
or a Codex token back to browser JavaScript.

Official sources checked 24 October 2026:

- [Codex authentication and device login](https://learn.chatgpt.com/docs/auth)
- [Codex non-interactive mode](https://learn.chatgpt.com/docs/non-interactive-mode)
- [Codex app-server authentication restriction](https://learn.chatgpt.com/docs/app-server)
- [Sign in with ChatGPT client ID availability](https://developers.openai.com/siwc/request-client-id)
- [Sign in with ChatGPT preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
- [Image API generation and editing](https://developers.openai.com/api/docs/guides/image-generation)

## Product behavior

1. When a new article lacks a usable source thumbnail, record
   `MISSING_THUMBNAIL` in `facebook_article_image_generation` in the article
   creation transaction. If Meta explicitly rejects a source thumbnail before
   creating a post, defer publication and mark the same image status. Article
   creation never waits for Codex.
2. Build a bounded prompt from the article's title, lead, quote, and language.
   Request a clearly illustrative editorial image with no invented quotes,
   logos, text, identifiable real people, or claim of being a documentary photo.
   Label generated images as AI illustrations wherever they are displayed to
   readers. Keep prompt templates and their versions in configuration/code so
   output can be reproduced or audited.
3. Store the generated bytes under the persistent Docker data volume, with a
   content hash and MIME/size/dimension validation. The database records the
   article, provider, model, prompt version, status, attempts, timestamps,
   local asset path, and sanitized error. Do not store provider credentials or
   visitor data in the image record. Use a new Flyway migration when built.
4. The Facebook publication worker skips a pending article while image
   generation is enabled and its status is missing or generating. On success it
   uploads the generated image. After three failed attempts it uses the branded
   placeholder. Keep the existing uncertain Meta-post reconciliation rules and
   one-post-per-article guarantee.
5. Show image provenance, preview, job state, retry, daily/monthly generation
   caps, and provider connection in an ADMIN-only UI. Start with automatic
   generation disabled until an admin enables it. A retry must not replace an
   already-published Facebook image without an explicit replacement workflow.
6. The public feed and story page use a completed generated image with a visible
   AI disclosure. The original article URL and source attribution stay visible.

## Delivery phases

| Phase | Work | Completion check |
| --- | --- | --- |
| 0. Feasibility | Install the CLI in the Spring Boot image and verify its version. An opt-in adapter is present, but operator must still test `$imagegen` output, latency, and resource use. Separately verify Image API access and billing. Confirm account eligibility and any OpenAI approval before reconsidering hosted OAuth. | One generated sample, measured resource use, and a recorded provider decision. No production tokens or posts. |
| 1. Provider and admin connection | Add an `ArticleImageGenerator` interface, OpenAI Image API adapter, encrypted key storage, ADMIN connect/rotate/disconnect controls, server-side validation, and usage caps. | No token reaches browser JavaScript or logs; disconnected state preserves existing publishing. |
| 2. Durable generation | Add Flyway job/asset tables, bounded prompts, one concurrent generation on this server, timeout/retry policy, local asset validation, and an ADMIN preview/status view. | A missing-thumbnail article produces one valid asset or a recorded failure; retries are idempotent. |
| 3. Facebook integration | Delay the publication job for the bounded image window, then use source thumbnail, generated asset, or branded placeholder in that order. Record image provenance on publication. | A new article still creates at most one public post; provider outages fall back without losing the article. |
| 4. Optional Codex connection | Reassess only if OpenAI offers a permitted hosted OAuth path that includes image generation. If available, use a dedicated unprivileged worker and persistent credential volume; keep the same generator interface and job model. | Admin web authorization and token refresh work across worker restarts without exposing credentials. |

## Implementation boundaries

- Keep the API key in a dedicated secret store or encrypted database column
  using a separate server-side encryption key. Do not put it in `.env` mounted
  into every application container if the ADMIN connection UI stores it.
- The image worker receives only the article text needed for a prompt. Limit
  prompt size, image bytes, concurrent jobs, retries, and monthly spend.
- The current CLI shares the application container because Java needs access
  to the executable. Its auth cache stays in a Docker volume and must never be
  returned to the browser or included in application logs. A future separate
  worker should expose no public port or broad credential mounts; use a narrow
  internal job/result channel.
- Run the required test suite on a development machine. `AGENTS.md` forbids
  Gradle tests on the production deployment server.

## Decisions still needed

1. Confirm that generated images should be used only when the source thumbnail
   is missing or rejected (the assumption in this plan), rather than for every
   article.
2. Set a monthly image-generation budget and the maximum delay allowed before
   falling back to the branded placeholder.
3. Decide whether admins should review generated images before Facebook
   publication. The automatic path above publishes a labeled illustration
   after validation; a mandatory review would delay posts until approval.
