# Phase 24 Manual Testing Guide — Facebook import proposal inbox

## Prerequisites

App and Postgres must be running. Configure the server with an ACTIVE import bot user:

```bash
APP_MACHINE_AUTH_ENABLED=true
APP_MACHINE_AUTH_HEADER_NAME=X-CoZaDzban-M2M-Key
APP_MACHINE_AUTH_API_KEY=<shared-secret>
APP_MACHINE_AUTH_PRINCIPAL_EMAIL=facebook-import-bot@cozadzban.pl
APP_NOTIFICATIONS_ENABLED=true
APP_NOTIFICATIONS_PUSHOVER_APP_TOKEN=<pushover-application-token>
APP_NOTIFICATIONS_ENCRYPTION_KEY=<base64-or-hex-aes-key>
```

Shared Facebook import configuration for any runtime that submits to this server:

```bash
APP_FACEBOOK_IMPORT_SCHEDULE_ENABLED=true
APP_FACEBOOK_IMPORT_SCHEDULE_INTERVAL=8h
APP_FACEBOOK_IMPORT_SCHEDULE_INITIAL_DELAY=0s
APP_FACEBOOK_IMPORT_TARGET_API_BASE_URL=http://localhost:8080
APP_FACEBOOK_IMPORT_TARGET_API_KEY=<shared-secret>
APP_FACEBOOK_IMPORT_RUN_TIMEOUT=1h
APP_FACEBOOK_IMPORT_TARGET_PROPOSAL_PATH=/api/facebook-import/proposals
APP_FACEBOOK_IMPORT_TARGET_PROPOSAL_EXISTS_PATH=/api/facebook-import/proposals/exists
APP_FACEBOOK_IMPORT_TARGET_RUN_PATH=/api/facebook-import/runs
APP_FACEBOOK_IMPORT_SCHEDULE_RUN_ON_STARTUP=false
```

Configure a worker with Selenium import enabled:

```bash
APP_FACEBOOK_IMPORT_SELENIUM_ENABLED=true
APP_FACEBOOK_IMPORT_SELENIUM_PROFILE_URL=https://www.facebook.com/bartek.dobrowolski.nowakowski
APP_FACEBOOK_IMPORT_SELENIUM_USERNAME=<facebook-login-email>
APP_FACEBOOK_IMPORT_SELENIUM_PASSWORD=<facebook-password-if-used>
APP_FACEBOOK_IMPORT_SELENIUM_BROWSER=FIREFOX
APP_FACEBOOK_IMPORT_SELENIUM_HEADLESS=false
APP_FACEBOOK_IMPORT_SELENIUM_REUSE_BROWSER_ACROSS_RESTARTS=true
APP_FACEBOOK_IMPORT_SELENIUM_DRIVER_SESSION_FILE=logs/facebook-import-firefox-session.properties
APP_FACEBOOK_IMPORT_SELENIUM_SCROLLS=8
APP_FACEBOOK_IMPORT_SELENIUM_WAIT_AFTER_LOGIN=8s
APP_FACEBOOK_IMPORT_SELENIUM_WAIT_AFTER_PAGE_OPEN=5s
APP_FACEBOOK_IMPORT_SELENIUM_WAIT_AFTER_SCROLL=2s
APP_FACEBOOK_IMPORT_SELENIUM_MANUAL_LOGIN_TIMEOUT=3m
```

Configure a server-only runtime without a local Selenium/browser importer:

```bash
APP_FACEBOOK_IMPORT_SELENIUM_ENABLED=false
```

The worker uses Spring Batch for import lifecycle/history. `spring.batch.job.enabled=false`
must remain set so jobs only start through the worker scheduler or admin trigger, and
`spring.batch.jdbc.initialize-schema=never` keeps Batch metadata DDL owned by Flyway.

Log in through the Vaadin UI as an ACTIVE USER or ADMIN. For notification checks, open
`/notification-settings`, save a Pushover user key, optionally load and select one or more
Pushover devices, enable **Article proposal review notifications**, and as ADMIN also enable
**Facebook login required notifications**. Leaving the device selection empty sends to all
active Pushover devices for that user key.

---

## Step 1: Proposal view access

1. Open `http://localhost:8080/`.
2. Confirm every ACTIVE logged-in user sees **Article Proposals**.
3. Open `/article-proposals`.
4. Confirm anonymous users are denied or redirected to login.

**Expected:** proposal review is available to ACTIVE USER and ADMIN accounts.

---

## Step 2: Non-blocking import

1. As ADMIN on a runtime with Selenium enabled, click **Import Facebook Selenium**.
2. Confirm no approval modal appears.
3. The UI should immediately show a success toast and remain usable.
4. Open `/article-proposals`.

**Expected:** discovered candidates appear as pending proposals after worker submission.
Server-only runtimes without an enabled importer show no manual import button.

---

## Step 3: Scheduled worker and Facebook login

1. Start the worker with `APP_FACEBOOK_IMPORT_SCHEDULE_ENABLED=true` and
   `APP_FACEBOOK_IMPORT_SCHEDULE_INITIAL_DELAY=0s`.
2. Confirm the worker waits until the configured interval unless `APP_FACEBOOK_IMPORT_SCHEDULE_RUN_ON_STARTUP=true`.
3. If Facebook opens a login or two-factor screen, complete the manual login in the Selenium
   browser before `APP_FACEBOOK_IMPORT_SELENIUM_MANUAL_LOGIN_TIMEOUT`.
4. Confirm logs contain a login-required event/message and the run continues after login.
5. Confirm opted-in ADMIN users do not receive a Pushover login-required notification for the
   worker-startup run.
6. Let a later scheduled interval run require login or two-factor approval.
7. Confirm opted-in ADMIN users receive a Pushover login-required notification for that scheduled run.
8. Let a scheduled run exceed `APP_FACEBOOK_IMPORT_SELENIUM_MANUAL_LOGIN_TIMEOUT`.
9. Confirm opted-in ADMIN users receive a Pushover login-timeout notification through the same
   **Facebook login required notifications** preference.
10. Trigger a manual Selenium import and force the same Facebook login screen.
11. Confirm manual import login-required and login-timeout events are logged but do not send Pushover notifications.
12. Temporarily reduce `APP_FACEBOOK_IMPORT_SCHEDULE_INTERVAL` in a local test environment and
   confirm a new tick is skipped while a previous import is still active.

**Expected:** scheduled imports use the same non-blocking proposal flow, Selenium login-required runs wait for manual authorization,
only scheduled interval login-required and login-timeout events notify opted-in admins, and
only one import runs at a time on the runtime.

---

## Step 4: Proposal grid

1. Confirm the grid is sorted by **Submitted** descending.
2. Confirm the default status filter shows only pending proposals.
3. Change the filter to **All**, **Accepted**, **Rejected**, and **Failed**.
4. Click article and Facebook post links.

**Expected:** links open in a new tab/window, and the filter shows the selected status set.

---

## Step 5: Language correction and accept

1. Open a pending proposal with **Review** or row double-click.
2. Correct the language field if needed.
3. Click **Accept**.

**Expected:** a normal Article is created with the corrected language, owned by the configured
import bot user, and the proposal becomes `accepted`.

---

## Step 6: Reject and failed retry

1. Reject a pending proposal.
2. Confirm it disappears from the default pending filter and appears under **Rejected**.
3. Temporarily break article creation for a proposal, then click **Accept**.
4. Confirm the proposal becomes **Failed** and logs contain the error.
5. Fix the problem, reopen the failed proposal, correct language if needed, and accept again.

**Expected:** failed accepted proposals stay reviewable and can be retried.

---

## Step 7: Duplicate prevention

1. Submit a proposal for a URL that already exists in `article`.
2. Submit the same proposal URL twice with different Facebook post URLs.

**Expected:** no duplicate pending proposal is created. Canonical article URL is the proposal
identity; Facebook post URL is review context only.

---

## Step 8: Proposal notifications

1. Complete a scheduled or manual import that submits at least one new proposal.
2. Confirm opted-in ACTIVE users receive one Pushover summary after the server records run completion.
3. Complete another import that submits zero new proposals.

**Expected:** proposal-review notifications are sent only when `submittedCount > 0`.
