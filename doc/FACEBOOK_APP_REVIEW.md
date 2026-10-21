# Meta Page permissions: review draft

This draft reflects the implemented Page connection and Phase 2 publication flow.
It is text for the app owner to review and paste into Meta's App Review forms.
Do not claim that a permission has been used until a successful call appears in
Meta's test-call status. The app owner must personally review Meta's policies
and make the compliance attestations.

## First check whether Advanced Access is needed

Meta Standard Access is intended for apps used only by people with an app role.
If only the Co Za Dzban app admins connect their own Pages, check whether the
required permissions are available as Standard Access in **Manage everything
on your Page → Customize → Permissions and features** and in the **Facebook
Login for Business** configuration. Advanced Access review is needed when
people without app roles must connect their Pages.

The current saved Polish Page token was checked with Meta's `debug_token` on
20 October 2026. It had `business_management` and `pages_show_list`, but not
`pages_read_engagement`, `pages_manage_posts`, or `pages_manage_engagement`.
The Page token is encrypted in PostgreSQL. The app does not retain the User
token after OAuth, so user-level API tests require another authorization.

## Descriptions for the implemented permissions

### `pages_show_list`

> An administrator signs in with Facebook from Admin → Facebook Pages. The
> application calls `/me/accounts` to list only the Pages that administrator
> can manage, displays the available Pages, and lets the administrator assign
> one Page to an article language. The application never connects a Page that
> the administrator did not select.

### `business_management`

> The Page selected by the administrator belongs to a Meta business portfolio.
> This permission allows the Facebook Login for Business connection to access
> that administrator's assigned business Page through `/me/accounts`. The
> application stores the selected Page's details and encrypted Page token. It
> does not manage people, ads, or unrelated business assets.

### `pages_manage_posts`

> After an administrator enables a connected Page for an article language, the
> application creates one image post on that Page for each newly created
> article in that language. It calls `POST /{page-id}/photos` with the article
> title, a short lead, and an image. The post text contains no article URL.
> The application stores the returned Page post ID and permalink, then shows
> that permalink on the article's story page. Failed or uncertain requests are
> shown to administrators; uncertain requests are never retried automatically.

### `pages_read_engagement`

> After creating a Page photo post, the application reads the resulting Page
> post's `permalink_url` so it can give readers a link to the Facebook
> discussion from the article's story page. It reads only posts on the Page
> selected by the administrator. Reaction collection is planned for a later
> phase and should be described in a later review only after it is implemented.

## Defer `pages_manage_engagement`

Phase 2 does not create Page comments. Do not request Advanced Access for
`pages_manage_engagement` yet: there is no implemented end-to-end user
experience or successful test call to show. Phase 3 will add link comments,
reaction collection, and the application-level monthly link limit. Meta's
current review form also requires `pages_read_user_content` in the same
submission for `pages_manage_engagement`; assess that dependency with Phase 3.

## Suggested API calls and evidence

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
