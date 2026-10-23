# Meta Page permissions: App Review submission

This draft reflects the implemented Page connection, publication, engagement,
link-comment, and post synchronization flows (phases 1–4).
It is text for the app owner to review and paste into Meta's App Review forms.
Updated from the App Review session on 21–22 October 2026. See the
[review kit](FACEBOOK_APP_REVIEW_KIT.md) for exact requests, troubleshooting,
recording instructions, and the dated evidence log. A successful API response,
Meta's test-call counter, and an implemented application workflow are separate
pieces of evidence. The app owner must personally review Meta's policies and
make the compliance attestations.

## Permissions for this submission

| Permission | Decision | Implemented purpose |
| --- | --- | --- |
| `pages_manage_posts` | Keep | Publish article photo posts, update their captions after article edits, and delete posts after article deletion. |
| `pages_show_list` | Keep | Display authorized Pages and check content creation tasks. |
| `pages_read_engagement` | Keep | Read post permalinks, reaction totals, and photo object IDs needed for caption updates. |
| `business_management` | Keep only if necessary for the business-portfolio Page connection | Support discovery of the administrator's assigned business Page. |
| `public_profile` | Keep | Basic Facebook login identity. |
| `pages_manage_engagement` | Request for the implemented workflow | Add the article's canonical story URL as a Page-authored comment under a selected post. |
| `pages_read_user_content` | No implemented use | The application does not read people's comments, ratings, or tagged posts; resolve any Meta review dependency before requesting it. |

The 21–22 October review evidence below predates the Phase 3 scheduler. Its
advice to defer comment permissions described that earlier implementation and
is not the current application scope. Confirm the permissions actually granted
to the stored Page token and the requirements shown in the current Meta review
form; implementation alone does not establish approval or token access.

## First check whether Advanced Access is needed

Meta Standard Access is intended for apps used only by people with an app role.
If only the Co Za Dzban app admins connect their own Pages, check whether the
required permissions are available as Standard Access in **Manage everything
on your Page → Customize → Permissions and features** and in the **Facebook
Login for Business** configuration. Advanced Access review is needed when
people without app roles must connect their Pages.

The saved Polish Page token was historically checked with Meta's `debug_token` on
20 October 2026. It had `business_management` and `pages_show_list`, but not
`pages_read_engagement`, `pages_manage_posts`, or `pages_manage_engagement`.
This is not a fresh statement of current scopes: later Explorer authorization
does not prove that the application's stored token changed. The Page token
is encrypted in PostgreSQL. The app does not retain the User
token after OAuth, so user-level API tests require another authorization.

## Descriptions for the implemented permissions

### `pages_show_list`

> Co Za Dzban uses pages_show_list to let an administrator choose which
> Facebook Page to connect for article publishing.
>
> The administrator signs into the website with Google, opens Admin → Facebook
> Pages, selects an article language, and starts Facebook Login for Business.
> After authorization, the application calls GET /me/accounts to retrieve the
> available Pages, including their IDs, names, and assigned tasks. It displays
> the authorized Pages so the administrator can explicitly select one and
> assign it to the chosen article language.
>
> This permission is necessary to populate the Page chooser and check whether
> the administrator has the tasks needed to create content. It helps identify
> the correct Page and prevents automatic selection of a publishing destination.
> The application stores the selected Page's connection details and encrypted
> Page access token. Publishing remains disabled until explicitly enabled and saved.

### `business_management`

Use this description only if the selected Page belongs to a Meta business
portfolio and the permission is required for its discovery. If discovery works
without it, remove the request. A manual `/me/businesses` test alone does not
establish application necessity; the app does not call that endpoint.

> Co Za Dzban requests business_management to support connecting an
> administrator's Facebook Page associated with a Meta business portfolio
> through Facebook Login for Business.
>
> The administrator opens Admin → Facebook Pages and authorizes access. The
> application calls GET /me/accounts to discover available Pages. The
> administrator explicitly selects a Page and assigns it to an article language.
> The application stores the connection details and encrypted Page access token.
> Publishing remains disabled until the administrator enables it and saves.
>
> This enables administrators to connect their business-managed Page and publish
> article announcements without manually recreating each announcement on Facebook.
> The permission supports access to the business-associated Page during connection.
> The application does not create or claim ad accounts, manage advertisements, or
> modify business portfolio membership.

### `pages_manage_posts`

> Co Za Dzban uses pages_manage_posts to publish newly added articles as photo
> posts on a Facebook Page explicitly selected by an administrator.
>
> The administrator opens Admin → Facebook Pages, connects Facebook, selects an
> authorized Page, and assigns it to an article language. Publishing remains
> disabled until the administrator enables “Publish new articles automatically”
> and saves the settings.
>
> When a new article is added in that language, the application calls
> POST /{page-id}/photos with an image, the article title, and a short lead.
> The caption contains no external article URL. The application stores the
> returned post ID and links the article's story page to the published Facebook
> post, allowing readers to open the discussion. When an article is edited or
> deleted, it queues a Page post update or deletion and records the outcome.
>
> This saves administrators from manually recreating each article announcement
> on Facebook and helps readers discover and discuss new articles. The permission
> is necessary to create and maintain these photo posts on the selected Page.
> The application does not publish to personal profiles.

### `pages_read_engagement`

> Co Za Dzban uses pages_read_engagement to retrieve post metadata and reaction
> totals for photo posts published on an administrator-selected Facebook Page.
>
> After publishing, the application calls GET /{post-id}?fields=permalink_url
> using the selected Page's access token. If Facebook returns only a photo ID,
> the application first reads the photo's page_story_id to identify the post.
>
> It stores the resulting permalink and displays it on the article's story page
> as “Skomentuj na Facebooku” (“Comment on Facebook”). The scheduler reads
> GET /{post-id}?fields=reactions.limit(0).summary(total_count) to rank eligible
> posts for link comments. An article edit may read the post's object_id to
> locate the photo whose caption needs updating.
>
> The application stores reaction counts and measurement times, without
> collecting follower identities or reading people's comments.

The publisher constructs a fallback URL if the permalink read fails. A working
story link alone therefore does not prove a successful `pages_read_engagement`
call. Failed or uncertain publications are shown to administrators; uncertain
requests are never retried automatically.

## `pages_manage_engagement` and `pages_read_user_content`

The implemented scheduler calls POST `/{post-id}/comments` with the article's
canonical story URL using the selected Page token. It waits for the configured
minimum post age, ranks eligible posts by the latest reaction total, and
respects a configurable monthly confirmed-link budget (default five) per Page.
The ADMIN Page view shows comment status and failures. This is the application
workflow to demonstrate for `pages_manage_engagement`.

> Co Za Dzban uses pages_manage_engagement to add a Page-authored comment
> containing the canonical story URL below a photo post that the application
> published on an administrator-selected Facebook Page.
>
> An administrator connects and enables a Page, sets the minimum post age and
> monthly link budget, and adds articles. The application publishes photo posts
> without links in their captions. Its scheduler reads post reaction totals,
> selects eligible posts, and calls POST /{post-id}/comments with the matching
> https://cozadzban.pl/stories/{article-id} URL. The application records the
> comment ID and status. Administrators can review failures or uncertain
> results before retrying. The default budget is five confirmed link comments
> per Page calendar month.

The code does not read user-authored content, so there is no application
workflow to claim for `pages_read_user_content`. The October 2026 Meta form
presented it as a dependency for `pages_manage_engagement`; the app owner must
resolve that requirement in the current review form without describing a use
the application does not implement.

## Suggested API calls and evidence

As of the additional tests on 21 October 2026, Explorer photo publication
succeeded with photo ID `122095079097524760` and post ID
`1415412431653305_122095079145524760`. The 14:05 Testing screenshot already
marked `pages_read_engagement` and `business_management` Completed. The latest
reported `pages_manage_posts` counter was still `0 of 1`, so its registration
remains pending despite the successful POST. Do not create duplicate posts to
repeat a successful test. The [kit's evidence log](FACEBOOK_APP_REVIEW_KIT.md#4-evidence-and-review-status-21-october-2026)
records the exact responses, additional comment tests, and 14:11 submission
status. Those manual tests predate the scheduler and do not prove its current
token grant or end-to-end operation.

Further tests confirmed two additional photo publications (captioned 17:33
and 17:34 on 21 October) and a successful edit of post
`1415412431653305_122095192317524760` with response `{"success": true}`.
On 22 October, the alternative text-only **POST `/1415412431653305/feed`**
also succeeded, returning posts `1415412431653305_122095704147524760` and,
on repetition, `1415412431653305_122095704465524760`. The user confirmed that
the last test worked. See the [working feed test and exact JSON](FACEBOOK_APP_REVIEW_KIT.md#22-october-text-only-feed-test-confirmed-working).

The latest supplied Testing screenshot (22 October, 10:24 Warsaw time, before
the feed responses) still showed `pages_manage_posts` at `0 of 1`. No later
counter screenshot was supplied. Do not confuse successful API responses with
verified dashboard registration. No more duplicate writes are needed. The
manual edit and text-post tests do not change the application's implemented
photo-only publishing flow or justify claiming those features in the review.

Use a User token minted for the exact Meta app and granted the requested
permissions. A saved Page token cannot perform user-level `/me/accounts` or
`/me/businesses` calls. Never paste tokens into screenshots, tickets, or the
repository.

| Permission | End-to-end call or action to capture |
| --- | --- |
| `pages_show_list` | Connect Page in the app; the callback calls `GET /me/accounts?fields=id,name,access_token,tasks`. |
| `business_management` | With a User token, make a read-only Business Manager API call such as `GET /me/businesses?fields=id,name`; verify Meta attributes it to this permission. |
| `pages_manage_posts` | With an authorized Page token, create one real article and capture the app's `POST /{page-id}/photos` and resulting Page post. |
| `pages_read_engagement` | Capture the app's post permalink or reaction-count GET for an app-published post. |
| `pages_manage_engagement` | Capture a real canonical story-link comment created by the configured scheduler and its confirmed status in the ADMIN Page view. |

Meta determines whether a call satisfies its review counter. Check its Testing
screen after each successful call. A Graph HTTP error does not demonstrate
working permission access.

## Screencast outline for implemented permissions

1. Start with the app and Meta account identity visible, but hide tokens and
   other secrets.
2. Sign in as an ADMIN on cozadzban.pl, open **Admin → Facebook Pages**, and
   start the Page connection for an article language.
3. Show Meta authorization and the Page selection in the application.
4. Enable and save the Page, then add a real article in that language.
5. Show the new image post on the selected Facebook Page and that its text has
   no external link.
6. Open `/stories/{article-id}` and follow **Skomentuj na Facebooku** to the
   published post. Show the reaction check in **Admin → Facebook Pages**.
7. For the comment permission, let an eligible post reach the minimum age and
   show its confirmed canonical story-link comment on Facebook and in the app.

Use a real article that can remain published. Separate short clips can reuse
the same flow while highlighting the step relevant to each permission.
