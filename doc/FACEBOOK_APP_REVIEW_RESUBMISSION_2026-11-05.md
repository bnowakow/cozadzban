# Meta Page permission resubmission after screencast rejection

The feedback supplied on 5 November 2026 approved `public_profile` and
rejected `pages_manage_posts`, `pages_show_list`, `business_management`, and
`pages_read_engagement` because the screencast did not show the complete use
case. Meta described the use cases as allowed. The pasted submission header
says 5 November while the pasted detailed feedback says 22 October; check the
latest submission's own feedback before uploading again.

## What the app actually does

The website uses **Google** to sign an administrator into Co Za Dzban. The
administrator then starts a separate, interactive **Facebook Login for
Business** flow with **Connect a Facebook Page** at
`https://cozadzban.pl/admin/facebook-pages`. The app does **not** use a Meta
system-user token or a server-to-server-only authorization flow for this Page
connection. The Meta OAuth callback runs on the server. The app reads
`/me/accounts`, presents eligible Pages, and stores only the chosen Page's
token encrypted on the server. New Page connections start with publishing
disabled.

After the administrator enables publishing, a newly added article in the
connected language can create a photo post through `POST /{page-id}/photos`.
The app obtains the post permalink, measures reactions through
`GET /{post-id}?fields=reactions.limit(0).summary(total_count)`, and displays
the latest count in **Our FB reactions** at
`https://cozadzban.pl/admin/facebook-statistics`. It can also add a Page
authored story-link comment and synchronize a post edit or deletion; those
actions need their own permissions if requested. **Source FB reactions** use
a separate Meta app and are not evidence for this Page app's
`pages_read_engagement` request.

`business_management` is conditional. The app does not call
`/me/businesses`; it discovers Pages with `/me/accounts`. Keep the request
only if the chosen business-portfolio Page cannot be discovered without it
and the recording shows that business-owned Page being authorized and selected.
Otherwise remove this permission from the resubmission.

## Record a new, continuous end-to-end demonstration

Use a Page and administrator account that can be shown to reviewers. Before
recording, confirm that the Meta Login for Business configuration includes
the requested permissions, that the account has the Page content task, and
that a new article URL with a usable image is available. Use a test Page if
possible so a fresh Meta consent screen can be captured without disturbing
the production connection. Hide passwords, authorization codes, and tokens.
The admin UI is already in English; add English captions for the Meta screens
and the Polish public-story link.

| Step | Show on screen | Permission demonstrated |
| --- | --- | --- |
| 1 | Start logged out at `https://cozadzban.pl/`, sign in with Google as an allowlisted ADMIN, then open **Facebook Pages**. | Website access; this is not Facebook Login. |
| 2 | Select an existing article language and click **Connect a Facebook Page**. Show the Facebook Login for Business sign-in, permission grant, and Page selection screens from start to finish. | Meta authorization and the grant for each requested permission. |
| 3 | Back in the app, show **Choose authorized Page**, select the Page, and click **Use selected Page**. Show its name in **Connected Page settings**. | `pages_show_list` through the app's `/me/accounts` discovery. If retained, show the business-owned Page for `business_management`. |
| 4 | Enable **Publish new articles automatically** and save. Create a new, previously unused article in the same language. Wait for the publishing worker and open its resulting photo post on Facebook. | `pages_manage_posts`; the worker normally polls every 15 seconds, but an image wait or queue backlog can take longer. |
| 5 | Show the new article's story page and its **Skomentuj na Facebooku** link to that post. In **Facebook statistics**, show **Our FB reactions** and **Our reactions measured** for an app-published post after a successful measurement. | `pages_read_engagement`; the reaction scheduler normally polls every minute. Show a real measured count, including zero, rather than the separate **Source FB reactions** column. |

Caption each step with the permission name and the feature it enables. Keep
the recording of the app and Facebook Page itself as the main evidence;
Graph API Explorer can supplement it but does not replace the app workflow.
If Meta skips a previously granted consent screen, use a fresh test account
and Page arrangement or describe the returning-user flow accurately. Do not
present an Explorer-generated token as a login inside the app.

## Paste-ready note for the next review

Replace bracketed details and verify reviewer access before submitting:

```text
Co Za Dzban is a website at https://cozadzban.pl/. Its administrators sign in to the website with Google. The Page integration is not server-to-server-only and does not use a Meta system-user token. An administrator opens https://cozadzban.pl/admin/facebook-pages, selects article language [LANGUAGE], clicks Connect a Facebook Page, and completes Facebook Login for Business. The administrator grants the requested permissions, selects Page [PAGE NAME / PAGE ID], returns to the app, chooses that Page, and enables automatic publication.

The app calls /me/accounts to populate its Page chooser (pages_show_list). A new article in the connected language causes the app to publish a photo post on the selected Page (pages_manage_posts). The app reads the resulting post permalink and reaction total and displays the latest measured reaction count and measurement time in https://cozadzban.pl/admin/facebook-statistics (pages_read_engagement). The Page token is stored encrypted on the server and is never sent to browser JavaScript.

The attached recording starts with website sign-in, shows the complete Meta authorization and permission grant, then shows Page selection, publication of a new article, the Facebook post, and the reaction count in the app. Reviewer website access: [TESTED GOOGLE ADMIN ACCESS ARRANGEMENT]. Reviewer Meta Page access: [TESTED PAGE ACCESS ARRANGEMENT]. Article URL used in the recording: [ARTICLE URL].
```

If `business_management` remains requested, add this paragraph only after
demonstrating that the business-portfolio Page requires it:

```text
The selected Page belongs to business portfolio [BUSINESS NAME]. We use business_management in our Facebook Login for Business configuration so the administrator can authorize and discover that Page. The recording shows the business-owned Page in the Meta grant and then in our Page chooser. The application does not manage ads or business membership.
```

Reviewer credentials or access arrangements belong in Meta's private review
fields, not in this repository or a public recording. Check that the reviewer
can reach the ADMIN view and has an account with access to the selected Page.
