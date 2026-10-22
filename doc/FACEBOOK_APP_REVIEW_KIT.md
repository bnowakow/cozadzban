# Facebook App Review kit

Prepared on 21 October 2026; updated through 22 October 2026. Target:
https://cozadzban.pl/. Instructions are templates; the dated evidence section
separately records responses and screenshots supplied during the session.
Use with [the permission descriptions](FACEBOOK_APP_REVIEW.md).

## Session identifiers and submission scope

| Object | Value observed during the 21–22 October 2026 session |
| --- | --- |
| Meta app | `Co Za Dzban - manage page` / `1736960178429899` (Explorer and review links) |
| Submission | `1737008538425063` |
| Page | `Co Za Dzban` / `1415412431653305` (successful `/me/accounts` response) |
| Existing photo | `122094504921524760` |
| Photo's Page post | `1415412431653305_122094504921524760` (returned `page_story_id`) |
| Manually created test comment | `122094504921524760_3712794725538543` |
| Additional test photo | `122095079097524760` |
| Additional photo's Page post | `1415412431653305_122095079145524760` (returned `post_id`) |
| Additional test comment on the original post | `122094504921524760_975816145003015` |
| 21 October photo test, caption labelled 17:33 | Photo `122095192089524760`; post `1415412431653305_122095192143524760` |
| 21 October photo test, caption labelled 17:34 | Photo `122095192287524760`; post `1415412431653305_122095192317524760` |
| First 22 October text-only post | `1415412431653305_122095704147524760` |
| Second 22 October text-only post | `1415412431653305_122095704465524760` |

These are public/API object identifiers, not tokens. Match the app ID in
Explorer, the token's issuing app, the review dashboard, and the deployed
application's configured app ID; similar names alone do not prove a match.
The deployed app ID was not verified in this session. Legal-page metadata in
the repository was observed referencing another app ID, `1119389000493227`;
do not treat that metadata as confirmation of the OAuth app being reviewed.

An earlier request used `61595742805131`, which was not the Page ID returned
by discovery. Use the ID from a successful `/me/accounts` response instead of
assuming an ID from a browser URL is the correct Graph Page object.

Keep `pages_manage_posts`, `pages_show_list`, `pages_read_engagement`, and
`public_profile`; keep `business_management` only if needed for this
business-portfolio Page connection. Remove `pages_manage_engagement` and
`pages_read_user_content` from the current review request. The manual comment
test below is retained as troubleshooting history, not a feature claim.

## 1. Graph API Explorer: copy-paste requests

**Latest working alternative (22 October):** the user confirmed that the
text-only `POST /1415412431653305/feed` test worked after photo creation and
post editing had already succeeded. Its exact JSON and returned IDs are in
the [22 October evidence](#22-october-text-only-feed-test-confirmed-working).
This is a manual diagnostic, not the application's photo publishing workflow.

Open https://developers.facebook.com/tools/explorer/ and select **your Co Za
Dzban Meta app**, not the default Graph API Explorer app. Select the version
configured on the deployed server (the repository default is `v26.0`). The
Explorer selects the version separately; paste the paths below without a
hostname or version prefix.

Generate a **User access token** using your app-role account, with the scopes
you are testing: `pages_show_list`, `pages_read_engagement`,
`pages_manage_posts`, and, only if needed for your business setup,
`business_management`. The account must have access to the selected Page and
the task needed to create content. If a scope is unavailable, fix app/Login
for Business configuration or access eligibility; a request cannot grant it.
The application's OAuth configuration must grant the same permissions before
recording the app flow. Explorer authorization does not update the token
already saved in Co Za Dzban: reconnect the Page in the application.

Use Explorer's token control. Never put tokens in these request paths or in
the application frontend, and keep the token panel out of recordings.

### User-token requests (GET)

Confirm the identity:

```text
/me?fields=id,name
```

Check which permissions were actually granted:

```text
/me/permissions
```

List authorized Pages without exposing their tokens in the response:

```text
/me/accounts?fields=id,name,tasks&limit=100
```

Expected: `data` contains the intended Page, with a content creation or
management task. Copy its ID as `PAGE_ID`. An empty list does not demonstrate
the app's Page selection flow. Follow pagination if necessary.

If you need to obtain a Page token, the following matches the app's discovery
fields. Run this **off camera**; the response contains secrets. Prefer the
Explorer's Page-token selector when it is available.

```text
/me/accounts?fields=id,name,access_token,tasks&limit=100
```

For `business_management`, while still using a User token:

```text
/me/businesses?fields=id,name&limit=100
```

If that returns an accessible business, copy its ID as `BUSINESS_ID`. For a
business-owned Page, an optional read is:

```text
/BUSINESS_ID/owned_pages?fields=id,name&limit=100
```

Replace uppercase placeholders with actual IDs. This optional request only
fits a Page owned by that business; assigned/client assets differ. These
business requests are manual diagnostics, not an implemented app workflow:
the app itself calls `/me/accounts`, not `/me/businesses`. A successful manual
business request alone does not establish an allowed-use justification for
Advanced Access. Confirm whether this permission is actually needed for your
Login for Business setup before requesting review.

### Page-token requests (GET)

Switch to the token for the **selected Page**, minted for the same Meta app.
Verify that `/me` now resolves to that Page:

```text
/me?fields=id,name
```

Read a small set of posts from that Page:

```text
/PAGE_ID/posts?fields=id,message,permalink_url&limit=5
```

Copy a returned post's complete ID, typically `PAGE_ID_POST_ID`, as `POST_ID`.
Prefer the post just created through the application. This matches the app's
permalink lookup and is the focused `pages_read_engagement` test:

```text
/POST_ID?fields=permalink_url
```

Expected: a successful JSON response containing `permalink_url`. Opening a
link on the story page alone is insufficient evidence: the app can construct
a fallback Facebook URL when this Graph read fails.

### Publishing test (POST): creates a real Page photo post

For review evidence, prefer creating a real article **through Co Za Dzban**
after connecting and enabling the Page. This exercises the actual integration
and stores the resulting post ID and story link in the application.

For a separate manual permission diagnostic, select **POST** and paste:

```text
/PAGE_ID/photos
```

Add these in Explorer's request parameters (not in the path):

| Parameter | Value |
| --- | --- |
| `url` | `https://cozadzban.pl/cozadzban-logo.png` |
| `caption` | `Co Za Dzban — Facebook Page publishing integration test.` |
| `published` | `true` |

Use the selected Page token with publishing permissions. Verify the image URL
is publicly reachable before submitting. This publishes a visible post; run
once on a Page where that is acceptable. Do not include an article URL in the
caption. Expect `id` and normally `post_id`. Use `post_id` for `POST_ID` above,
not the photo ID. If only a photo ID is returned, the app's fallback lookup is:

```text
/PHOTO_ID?fields=page_story_id
```

Use the returned `page_story_id` for the permalink request. A manual Explorer
post is not linked to an application article and cannot replace the app
screencast. Do not make a second manual post if the application test already
demonstrates publishing successfully.

After each permission test, inspect the app's Testing screen. Record the
method, path, time, HTTP outcome, returned object ID, and whether Meta counted
the call. Never record tokens. Successful API responses do not guarantee that
Meta attributes them to a particular permission. Do not submit errors as proof
of successful access. Further comment tests are unnecessary for the current
submission, which defers `pages_manage_engagement` and `pages_read_user_content`.

### Exact requests for the Page used in this session

Use a **User token** for these GET requests:

```text
/me?fields=id,name
/me/permissions
/me/accounts?fields=id,name,tasks
/me/businesses?fields=id,name
```

Use the selected **Page token** for these GET requests, one at a time:

```text
/me?fields=id,name
/1415412431653305/posts?fields=id,message,permalink_url&limit=5
/1415412431653305/photos
/122094504921524760?fields=id,page_story_id,link
/1415412431653305_122094504921524760?fields=permalink_url
```

For a manual photo-publishing test, change the method to **POST**:

```text
/1415412431653305/photos
```

Set `url`, `caption`, and `published` using the publishing parameter table
above. A GET of `/photos` lists existing photos and does not test publishing.
Do not create another public post if publishing has already been demonstrated.

### Historical comment test (deferred feature)

The following **POST** succeeded manually in Explorer, using a Page token:

```text
/1415412431653305_122094504921524760/comments
```

In **Params → Add parameter**, enter key `message` and value
`Co Za Dzban — comment publishing test for Meta App Review.` Alternatively,
select **JSON** and enter this body, using either Params or JSON:

```json
{
  "message": "Co Za Dzban — comment publishing test for Meta App Review."
}
```

Successful response supplied by the user:

```json
{
  "id": "122094504921524760_3712794725538543"
}
```

This created a real comment. Do not rerun it to refresh a dashboard counter.
The app has no corresponding comment workflow; an Explorer success cannot
replace the required end-to-end app demonstration for Advanced Access.

### Troubleshooting observed during the session

| Symptom | Interpretation and next action |
| --- | --- |
| `(#100)` on `/me`, or unsupported GET with subcode `33` on an object | Check the exact object ID, token validity, issuing app, Page assignment, and scopes. The early `/me` failure was not conclusively diagnosed. An error does not prove that the object is deleted. |
| Configuration listed only `business_management` and `pages_show_list` | Read/publish scopes were absent from the displayed configuration. Add the needed permissions, authorize a fresh User token, and obtain a fresh Page token. Verify actual grants with `/me/permissions` using a User token. |
| Page has `MANAGE`, `CREATE_CONTENT`, and `MODERATE` tasks, but a request fails | Page tasks and granted OAuth scopes are separate checks. Tasks alone do not establish token permission access. |
| “User or Page” shows a Page while configuration says “User access token” | Validate the token privately with Meta's Access Token Debugger and verify `/me?fields=id,name`; configuration labels alone do not establish the actual token type. |
| `(#100) Tried accessing nonexisting field (permalink_url)` on `/1415412431653305` | This is a Page object, not a post. Read the photo's `page_story_id`, then request `permalink_url` on that full post ID. |
| `(#200) You do not have sufficient permissions` on comment POST | Check the Page token's `pages_manage_engagement` grant and Page assignment/tasks. Minting another token without fixing missing grants is insufficient. |
| OAuth says `Invalid Scopes: pages_read_user_content` when adding `pages_manage_engagement` | The review form required `pages_read_user_content` and `pages_show_list` as dependencies. Add/enable the required scope under the same app's Page use case and Login for Business configuration, then authorize again. The user reported this resolved token generation. If the scope is unavailable, inspect app/use-case eligibility instead of repeatedly retrying. For the current submission, defer both comment-related permissions. |
| Error `197`, subcode `1446001`, “No comment entered” | The POST had no message body. Add the `message` parameter or JSON body above; selecting POST and entering the path does not supply the text. Adding the parameter produced the confirmed successful comment. |

Changing the Login for Business configuration does not update existing tokens.
Explorer authorization also does not update the token stored in Co Za Dzban;
reconnect through the app to refresh its authorized Page connection. Never
put access tokens in screenshots, documentation, frontend code, localStorage,
or sessionStorage. Use Meta's
[Access Token Debugger](https://developers.facebook.com/tools/debug/accesstoken/)
privately when diagnosing a token; do not paste its token value into this log.

## 2. Reviewer instructions: paste after replacing placeholders

Do not submit this section until reviewer access and the chosen article have
been verified on the deployed site. Website login uses Google; Facebook OAuth
is a separate connection flow available to an application ADMIN.

> Application URL: https://cozadzban.pl/
>
> Website access: sign in with the Google account [REVIEWER ACCOUNT], which
> has been provisioned with the ADMIN role in our application. [INSERT THE
> TESTED REVIEWER ACCESS ARRANGEMENT IN META'S PRIVATE ACCESS FIELD.]
>
> 1. Open https://cozadzban.pl/admin/facebook-pages. Choose [LANGUAGE] under
>    “Article language”, then click “Connect a Facebook Page”. This language
>    must already have at least one article in the application.
> 2. Complete Facebook Login for Business using an account authorized to
>    manage [PAGE NAME / PAGE ID], and authorize that Page. Back in the app,
>    choose it under “Choose authorized Page” and click “Use selected Page”.
> 3. Under “Connected language”, select [LANGUAGE]. Enable “Publish new
>    articles automatically”, then click “Save Page settings”.
> 4. Return to the home page and use the add-article dialog to add the real,
>    previously unused article URL [ARTICLE URL]. Its detected language must
>    match [LANGUAGE]. Use a fresh article; existing articles are not the
>    intended publication trigger.
> 5. Allow the background publishing worker to run (it checks every 15
>    seconds; network processing and a queue backlog can add time). Open
>    [FACEBOOK PAGE URL] and verify the new photo post contains the article
>    title and short lead, with no external article URL in the caption.
> 6. Open the new article's story page at
>    https://cozadzban.pl/stories/[ARTICLE ID]. Follow “Skomentuj na Facebooku”
>    to the same Facebook post. This is a link to Facebook's discussion; the
>    application does not create comments in the current release.
>
> `pages_show_list` supplies the Page chooser. `pages_manage_posts` publishes
> the photo. `pages_read_engagement` reads that post's `permalink_url` for the
> story's Facebook discussion link. Facebook tokens are handled server-side;
> the selected Page token is stored encrypted.

Check the deployed privacy and deletion pages before submitting:

- https://cozadzban.pl/privacy-policy.html
- https://cozadzban.pl/user-data-deletion.html

Do not provide personal Google/Facebook passwords in this document or claim
that reviewer access is ready until the access arrangement has been tested.

## 3. Screencast script and narration

Record the real application and Facebook flow. This document is a recording
script, not a generated video. No live actions or successful permission tests
are implied. Use a real article that can remain published; deleting it in the
app does not currently synchronize deletion to Facebook.

Before recording, choose the Page, the article language, and a previously
unused article URL. Confirm Google ADMIN access, Meta app-role access, Page
tasks, and all required granted scopes. Reconnect the app if the stored token
lacks them. Do not disconnect an existing production connection just to stage
a recording. If Meta skips a previously completed consent step, show the
actual returning-user flow and describe that accurately; do not fabricate a
consent screen.

Capture only the relevant browser area and hide token controls. Keep unrelated
accounts, password-manager popups, and notifications out of the recording.

On macOS, open the already authenticated app in the browser, hide unrelated
sidebars, and press **Command+Shift+5**. Choose **Record Selected Portion**,
frame the website and its address bar, and click **Record**. Capture the flow
below, then stop using **Command+Control+Escape** or the menu-bar stop control.
Keep the original video for review and captioning. A new real article will
create a public Page post, so select one suitable to remain published first.

No recording was generated or inspected by the assistant in this session:
native browser access timed out on an earlier attempt, and the available
browser-control tools later exposed no video recorder. The subsequent review
form screenshot marked screencast uploads complete, but the uploaded files and
their contents were not supplied for verification. Explorer-only footage does
not demonstrate the application's full publishing workflow.

| Approximate time | Action on screen | English narration / caption |
| --- | --- | --- |
| 00:00–00:15 | Show Co Za Dzban and navigate to Admin → Facebook Pages. | “Co Za Dzban publishes new articles as image posts on a Page selected by an administrator.” |
| 00:15–00:40 | Select the existing article language, click Connect a Facebook Page, and show the real Meta authorization flow. | “The administrator authorizes access to the Page they manage using Facebook Login for Business.” |
| 00:40–01:00 | Show Choose authorized Page; select the intended Page and click Use selected Page. | “pages_show_list provides this list of authorized Pages. I explicitly choose this Page for this article language.” |
| 01:00–01:15 | Show the connected Page identity; enable automatic publication and save. | “Publishing starts only after an administrator explicitly enables and saves it.” |
| 01:15–01:50 | Add the chosen new article through the app. Show successful creation and the story. | “Adding a new article in the configured language queues one Page photo post.” |
| 01:50–02:20 | Wait for the worker, then show the real photo post on Facebook. | “pages_manage_posts publishes this image with the article title and a short lead. The caption contains no external article link.” |
| 02:20–02:40 | Return to the story and follow Skomentuj na Facebooku to the same post. | “The app requests the post's permalink_url using pages_read_engagement and uses the resulting link for readers to open the Facebook discussion.” |
| 02:40–03:00 | Optionally show a successful token-hidden permalink request and Meta's test-call status. | “This successful API response confirms the permalink read. Here is the test status for this app.” |

Use the permalink narration only after confirming that Graph read succeeded;
otherwise describe the failure and fix it before submitting. Show a successful
Explorer request separately if the app's read outcome cannot be established.
Do not show a token-bearing network trace to prove it.

Suggested clip names, once recordings exist:

- `01-page-connection.mp4`: authorization and explicit Page selection.
- `02-photo-publication.mp4`: add article and show the resulting photo post.
- `03-post-permalink.mp4`: successful permalink read and the story discussion link.

The approximate timeline can be longer if authorization, enrichment, or
publication takes longer. Adapt the clips to the instructions shown in your
actual Meta submission form. Manual business API diagnostics are separate
from this screencast; there is no business-management UI to demonstrate.

## 4. Evidence and review status: 21 October 2026

This records user-supplied Explorer responses and review screenshots, not calls
executed by the assistant. No token values or pagination cursors are retained.

| Action | Evidence supplied | What remains unverified |
| --- | --- | --- |
| GET `/me/accounts?fields=id,name,tasks` | Returned Co Za Dzban, Page `1415412431653305`; tasks included `MANAGE`, `CREATE_CONTENT`, `MODERATE`, `MESSAGING`, `ADVERTISE`, `ANALYZE`, `MANAGE_LEADS`, `VIEW_MONETIZATION_INSIGHTS` | Application's stored token scopes and deployed app ID |
| GET `/1415412431653305/photos` | Returned existing photo `122094504921524760`, created `2026-10-20T15:39:04+0000` | This does not demonstrate a new photo publication |
| GET `/122094504921524760?fields=id,page_story_id,link` | Returned `page_story_id` = `1415412431653305_122094504921524760` and a Facebook photo link | Final post `permalink_url` response was not supplied |
| POST to that post's `/comments` | Returned comment ID `122094504921524760_3712794725538543` after fixing authorization and adding `message` | Whether Meta counted this call; application comment functionality remains unimplemented |
| POST `/1415412431653305/photos` (additional afternoon test) | Returned photo `122095079097524760` and post `1415412431653305_122095079145524760`; exact request and response below | Successful manual `pages_manage_posts` call; latest reported counter still `0 of 1`. No app-triggered publication supplied |
| POST `/1415412431653305_122094504921524760/comments` (additional afternoon test) | Returned comment ID `122094504921524760_975816145003015` | Successful manual comment creation; no later completed `pages_manage_engagement` counter supplied |
| GET `/122094504921524760_3712794725538543?fields=id,message,created_time` | Returned that comment ID, message `Co Za Dzban — Page comment permission test, 21 October 2026.`, and `created_time` = `2026-10-21T09:29:41+0000` | Page-authored comment read does not establish attribution to `pages_read_user_content` |
| GET `/me/businesses?fields=id,name` | Request suggested; afternoon Testing screen marked `business_management` Completed | Raw successful response not supplied; exact call responsible for completion unknown; application necessity remains conditional |
| Screencast | Later form marked uploads complete for publishing, Page listing, business management, and permalink reading | No video artifact was generated or viewed by the assistant; reviewer access was not tested |

The 11:22 Testing screenshot still showed zero calls for the listed permissions.
The screen's instructions said results can take **up to 24 hours** after a
successful test and each test is valid for **30 days**. These are the supplied
dashboard's statements, not a promise of attribution or approval.

In the earlier App Review screenshot (11:45 Warsaw time):

- `pages_show_list` was fully marked complete.
- `pages_manage_posts`, `business_management`, and `pages_read_engagement`
  had green description, screencast, and compliance checks, but the required
  API-test confirmation was still unchecked.
- The deferred comment-related permissions were absent from the visible cards;
  their removal was recommended, but a full final submission list was not shown.
- Earlier, `public_profile` was marked complete. Its card was not visible in
  the 11:45 screenshot.

### Additional tests and dashboard updates (afternoon, Warsaw time)

The **14:05 Testing screenshot**, before the additional write responses were
supplied, showed:

| Permission | Displayed Testing status |
| --- | --- |
| `pages_read_engagement` | Completed |
| `business_management` | Completed |
| `public_profile` | 14 API test calls |
| `pages_show_list` | 10 API test calls |
| `pages_manage_posts` | 0 of 1 required |
| `pages_manage_engagement` | 0 of 1 required |
| `pages_read_user_content` | 0 of 1 required |

The user subsequently supplied a successful **POST** to:

```text
/1415412431653305/photos
```

Request parameters:

| Key | Value |
| --- | --- |
| `url` | `https://cozadzban.pl/cozadzban-logo.png` |
| `caption` | `Co Za Dzban — Page publishing permission test, 21 October 2026.` |
| `published` | `true` |

Returned JSON:

```json
{
  "id": "122095079097524760",
  "post_id": "1415412431653305_122095079145524760"
}
```

This is confirmed photo publication, not just a GET of an existing photo. It
demonstrates a successful `pages_manage_posts` write for the selected Explorer
app. The photo ID and post ID differ: use the returned **post_id** for this
optional permalink GET, whose response has not been supplied:

```text
/1415412431653305_122095079145524760?fields=permalink_url
```

The additional comment **POST** targeted the original post, not this new post:

```text
/1415412431653305_122094504921524760/comments
```

Parameter `message` was
`Co Za Dzban — Page comment permission test, 21 October 2026.` The response was:

```json
{
  "id": "122094504921524760_975816145003015"
}
```

The subsequent comment GET read the **earlier** comment
`122094504921524760_3712794725538543`, not the newly returned comment ID. It
returned the message and creation time recorded in the evidence table. Its
message differs from the earlier POST example; the thread does not establish
why. Preserve the actual response without inferring an edit operation.

Reading a Page-authored comment does not establish that Meta used
`pages_read_user_content`; reading an actual person's comment would be a more
relevant diagnostic for that scope, but no such response was supplied. Further
comment tests are unnecessary for the current submission, which defers both
comment-related permissions.

The **14:11 App Review screenshot** displayed only `pages_manage_posts`,
`pages_show_list`, `business_management`, `pages_read_engagement`, and
`public_profile`. The two comment-related permissions were absent from that
submission view, even though the broader use-case Testing screen still listed
them. `pages_show_list` and `public_profile` were fully marked complete. The
other three cards had completed descriptions, screencast uploads, compliance
acknowledgements, and applicable dependencies, while their API-test confirmation
remained unchecked.

The user's subsequent 21 October report still showed **`pages_manage_posts`: 0 of
1 API calls required**, despite the successful photo POST above. This is pending
dashboard registration, not evidence that the POST failed. No screenshot or
response at that point confirmed that counter completed.

### Later 21 October photo creation and post editing

Two more **POST** requests to `/1415412431653305/photos` used the same public
logo URL and `published: true`, with captions distinguished by time text:

| Caption | Returned photo `id` | Returned `post_id` |
| --- | --- | --- |
| `Co Za Dzban — Page publishing permission test, 21 October 2026. 17:33` | `122095192089524760` | `1415412431653305_122095192143524760` |
| `Co Za Dzban — Page publishing permission test, 21 October 2026. 17:34` | `122095192287524760` | `1415412431653305_122095192317524760` |

The times above are literal caption text supplied by the user, not independently
verified API execution timestamps. In the JSON editor use a boolean
`"published": true`; Explorer Params also accepted the string value `true` in
the successful publishing requests.

An alternative **edit** test then targeted the full post ID from the second
response, using **POST**:

```text
/1415412431653305_122095192317524760
```

JSON request:

```json
{
  "message": "Co Za Dzban — updated publishing permission test."
}
```

Confirmed response:

```json
{
  "success": true
}
```

This changed the text of an existing public post and demonstrated another
successful `pages_manage_posts` operation without creating another post. The
optional verification GET below was suggested, but no response was supplied:

```text
/1415412431653305_122095192317524760?fields=id,message
```

The application's own implementation still only creates photo posts; this
manual edit test does not establish an edit feature in Co Za Dzban.

### 22 October: text-only feed test confirmed working

The **22 October, 10:24 Testing screenshot** still showed
`pages_manage_posts`, `pages_manage_engagement`, and `pages_read_user_content`
at `0 of 1`. `pages_read_engagement` and `business_management` were Completed;
`public_profile` showed 14 calls and `pages_show_list` 10 calls. Thus, successful
photo creation and editing had not yet been reflected in the displayed
`pages_manage_posts` counter. The previous day's requests labelled 17:33–17:34
were still within the stated 24-hour window at the time of this screenshot.

The user tried a different endpoint: creating a **text-only** Page post through
`/feed`, instead of uploading a photo or editing a post.

Graph API Explorer settings:

| Setting | Value |
| --- | --- |
| App | `Co Za Dzban - manage page` / `1736960178429899` |
| Token | Co Za Dzban Page token with publishing access |
| Version | `v26.0` |
| Method | **POST** |
| Path | `/1415412431653305/feed` |

JSON body:

```json
{
  "message": "Co Za Dzban — text-only Page publishing test, 22 October 2026.",
  "published": true
}
```

First successful response:

```json
{
  "id": "1415412431653305_122095704147524760"
}
```

Repeating that request created a second public text post:

```json
{
  "id": "1415412431653305_122095704465524760"
}
```

**User confirmation:** after these responses, the user said “yup, last test
work” and requested this documentation update. Retain `/feed` as the working
alternative test case for `pages_manage_posts`. Both returned IDs prove
successful post creation; no later Testing screenshot was supplied to establish
whether Meta's counter changed. The user's confirmation is recorded without
inventing a registration time or a completed dashboard status.

These tests created real public posts. Do not rerun the publishing requests
merely to repeat the same evidence. Photo creation, existing-post editing, and
text-post creation have all now succeeded manually. A different endpoint or
another duplicate write does not guarantee faster counter attribution.

For App Review, continue to describe and demonstrate the actual application
photo flow. The text-only `/feed` request and manual edit are diagnostics, not
implemented application features or reasons to change the submitted feature
descriptions. No deletion test was performed, and no cleanup of the public
test posts/comments was confirmed in this conversation.

Next steps: verify the same app is used throughout and inspect **Review →
Testing** after the stated update window. The photo publishing call has now
succeeded; `pages_read_engagement` and `business_management` were already marked
Completed in Testing. No further API calls are needed merely to repeat those
tests. Do not repeat successful write requests just because counters lag:
the additional photo and comments created real public content.
If testing is marked complete there but the submission card remains unchecked,
reopen its **Get started** form and inspect the outstanding confirmation.
Do not mark calls complete or submit the review based only on supplied templates.

## 5. Verification limits and Meta reference links

Requests and UI labels above were checked against repository source, including
FacebookPageGraphClient, FacebookPhotoPublisher, FacebookPublicationWorker,
and FacebookPageSettingsView. The dated evidence above records the supplied
successful responses and dashboard status. The deployed version, live token
scopes, reviewer access, missing call results, and final dashboard completion
have not been independently verified.
The original review draft's token check dated 20 October is historical, not
a fresh check of today's permissions.

Meta documentation fetches were blocked or rate-limited while preparing this
kit, so current review rules and permission dependencies could not be
independently confirmed. Check the actual app dashboard and these references
before submission:

- [Graph API Explorer](https://developers.facebook.com/tools/explorer/)
- [User accounts edge](https://developers.facebook.com/docs/graph-api/reference/user/accounts/)
- [User businesses edge](https://developers.facebook.com/docs/graph-api/reference/user/businesses/)
- [Page posts](https://developers.facebook.com/docs/pages-api/posts/)
- [App Review](https://developers.facebook.com/docs/apps/review/)

If only app-role administrators connect Pages, first check whether Standard
Access already supports the intended use. Leave unimplemented comment and
reaction features out of this submission. Compliance attestations must be
reviewed and made by the app owner.
