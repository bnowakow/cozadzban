# Phase 4: story views and Facebook statistics

After deploying Flyway migration V37 and the Phase 4 code:

1. Open an existing `/stories/{articleId}` page in a normal browser and refresh it twice.
2. Open **Admin → Story and Facebook statistics**. Confirm that the article table is visible below the Page summary and filters, with rows and the **Article**, **Views total**, **Views 7 days**, and **FB reactions** columns. Select the article's language and refresh the report. Its **Views total** and **Views 7 days** should increase by two. Counts are page loads, not unique visitors.
3. Click that article's link in the statistics table and confirm the server-rendered `/stories/{articleId}` page opens without a Vaadin route error. Check its source icon and name, back link, and thumbnail. The back link should return to the report; a directly opened story should return to the article list. Check the article's **FB reactions**, measurement time, post status, and link-comment status. Page totals appear above the article table.
4. Request the story with a crawler user agent or a non-HTML `Accept` header. Refresh the report and verify that the view count did not change. A missing article must return 404 and remain absent from the report.
5. Confirm that the public story page contains no view count and the admin report contains no visitor or article creator data.

The link-selection scheduler continues to rank articles by Facebook reactions. Story views are retained for a future ranking rule.

- [ ] After the separate Phase 3 link test, restore **Minimum post age (hours)** from **1** to the intended production value (**24 hours**, unless the administrator chooses another value). See [FACEBOOK_PHASE3_TESTING.md](FACEBOOK_PHASE3_TESTING.md).
