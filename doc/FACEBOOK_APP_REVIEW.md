# Meta Page permissions: App Review submission

This draft reflects the implemented Page connection and Phase 2 publication flow.
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
| `pages_manage_posts` | Keep | Publish article photo posts on the selected Page. |
| `pages_show_list` | Keep | Display authorized Pages and check content creation tasks. |
| `pages_read_engagement` | Keep | Read the published post's permalink. |
| `business_management` | Keep only if necessary for the business-portfolio Page connection | Support discovery of the administrator's assigned business Page. |
| `public_profile` | Keep | Basic Facebook login identity. |
| `pages_manage_engagement` | Remove from this review submission | No implemented application comment workflow. |
| `pages_read_user_content` | Remove from this review submission | No implemented reading of user comments, ratings, tagged posts, or other user content. |

Use **Edit your submission** to remove the two deferred permissions from the
Advanced Access request. This is separate from changing a Login for Business
configuration or revoking an existing token. A manual comment test does not
justify claiming a comment-management feature in the application.

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
> post, allowing readers to open the discussion.
>
> This saves administrators from manually recreating each article announcement
> on Facebook and helps readers discover and discuss new articles. The permission
> is necessary to create these photo posts on the selected Page. The current
> implementation does not edit or delete Facebook posts, publish to personal
> profiles, or manage user-generated content.

### `pages_read_engagement`

> Co Za Dzban uses pages_read_engagement to retrieve the permalink of a photo
> post published by the application on an administrator-selected Facebook Page.
>
> After publishing, the application calls GET /{post-id}?fields=permalink_url
> using the selected Page's access token. If Facebook returns only a photo ID,
> the application first reads the photo's page_story_id to identify the post.
>
> It stores the resulting permalink and displays it on the article's story page
> as “Skomentuj na Facebooku” (“Comment on Facebook”). This lets readers open
> the correct Facebook discussion and helps administrators connect each article
> with its published Page announcement.
>
> The permission is needed to read the published post's permalink from Facebook.
> The current implementation does not collect follower identities, reactions,
> user comments, or engagement analytics.

The publisher constructs a fallback URL if the permalink read fails. A working
story link alone therefore does not prove a successful `pages_read_engagement`
call. Failed or uncertain publications are shown to administrators; uncertain
requests are never retried automatically.

## Defer `pages_manage_engagement` and `pages_read_user_content`

Phase 2 does not create Page comments. Do not request Advanced Access for
`pages_manage_engagement` yet: there is no implemented end-to-end user
experience to show. A manual Explorer comment succeeded on 21 October 2026,
but does not demonstrate an application feature. There is also no implemented
use of `pages_read_user_content`. Phase 3 plans link comments,
reaction collection, and the application-level monthly link limit. Meta's
current review form also requires `pages_read_user_content` in the same
submission for `pages_manage_engagement`; the supplied form and token-generation
failure confirmed the dependency in this session. Assess it with Phase 3.

## Suggested API calls and evidence

As of the additional tests on 21 October 2026, Explorer photo publication
succeeded with photo ID `122095079097524760` and post ID
`1415412431653305_122095079145524760`. The 14:05 Testing screenshot already
marked `pages_read_engagement` and `business_management` Completed. The latest
reported `pages_manage_posts` counter was still `0 of 1`, so its registration
remains pending despite the successful POST. Do not create duplicate posts to
repeat a successful test. The [kit's evidence log](FACEBOOK_APP_REVIEW_KIT.md#4-evidence-and-review-status-21-october-2026)
records the exact responses, additional comment tests, and 14:11 submission
status. Comment tests remain separate from the implemented application scope.

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
| `pages_read_engagement` | Capture the app's `GET /{post-id}?fields=permalink_url` after publishing, or read the selected Page's posts with a Page token that has this scope. |
| `pages_manage_engagement` | Wait for Phase 3, then capture a real Page comment created through its configured scheduler. |

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
   published post. Show the corresponding Meta test-call status if available.

Use a real article that can remain published: Phase 3 deletion synchronization
is not implemented yet. Separate short clips can reuse the same flow while
highlighting the step relevant to each permission.
