# Facebook Page phase 3 testing

1. Deploy the Phase 3 code and Flyway migration V36. Confirm the Page is connected and enabled, and that its token grants Page engagement reads and comment creation.
2. For a controlled test, set **Minimum post age (hours) = 1**. Consider setting **Links per month = 1** before deployment: the scheduler posts real public comments up to the configured cap.
3. In **Admin → Facebook Pages → Recent Facebook engagement**, click **Refresh engagement**. Check reaction totals and the link state. A selected article moves through `SELECTED` and `ATTEMPTING` to `CONFIRMED`; a rejected request appears under **Link comment problems**.
4. Open the Facebook post and confirm that the comment contains only the canonical `https://cozadzban.pl/stories/{articleId}` URL. Open that story page and its Facebook post link.
5. Review the Page's monthly link budget and any uncertain comment or post synchronization results before retrying them.

- [ ] **Reminder after testing:** restore **Minimum post age (hours)** from **1** to the intended production value (**24 hours**, unless the administrator chooses another value), then save Page settings. If the monthly link limit was lowered for the test, restore the intended limit too.
