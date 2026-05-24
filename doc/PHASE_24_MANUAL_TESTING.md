# Phase 24 Manual Testing Guide — Facebook import proposal inbox

## Prerequisites

App and Postgres must be running. Configure the server with an ACTIVE import bot user:

```bash
APP_MACHINE_AUTH_ENABLED=true
APP_MACHINE_AUTH_HEADER_NAME=X-CoZaDzban-M2M-Key
APP_MACHINE_AUTH_API_KEY=<shared-secret>
APP_MACHINE_AUTH_PRINCIPAL_EMAIL=facebook-import-bot@cozadzban.pl
```

Configure the worker with the matching key and proposal endpoints:

```bash
APP_FACEBOOK_IMPORT_ENABLED=true
APP_FACEBOOK_IMPORT_TARGET_API_BASE_URL=http://localhost:8080
APP_FACEBOOK_IMPORT_TARGET_API_KEY=<shared-secret>
APP_FACEBOOK_IMPORT_TARGET_PROPOSAL_PATH=/api/facebook-import/proposals
APP_FACEBOOK_IMPORT_TARGET_PROPOSAL_EXISTS_PATH=/api/facebook-import/proposals/exists
APP_FACEBOOK_IMPORT_TARGET_RUN_PATH=/api/facebook-import/runs
```

Log in through the Vaadin UI as an ACTIVE USER or ADMIN.

---

## Step 1: Proposal view access

1. Open `http://localhost:8080/`.
2. Confirm every ACTIVE logged-in user sees **Article Proposals**.
3. Open `/article-proposals`.
4. Confirm anonymous users are denied or redirected to login.

**Expected:** proposal review is available to ACTIVE USER and ADMIN accounts.

---

## Step 2: Non-blocking import

1. As ADMIN, click **Import Facebook Posts**.
2. Confirm no approval modal appears.
3. The UI should immediately show a success toast and remain usable.
4. Open `/article-proposals`.

**Expected:** discovered candidates appear as pending proposals after worker submission.

---

## Step 3: Proposal grid

1. Confirm the grid is sorted by **Submitted** descending.
2. Confirm the default status filter shows only pending proposals.
3. Change the filter to **All**, **Accepted**, **Rejected**, and **Failed**.
4. Click article and Facebook post links.

**Expected:** links open in a new tab/window, and the filter shows the selected status set.

---

## Step 4: Language correction and accept

1. Open a pending proposal with **Review** or row double-click.
2. Correct the language field if needed.
3. Click **Accept**.

**Expected:** a normal Article is created with the corrected language, owned by the configured
import bot user, and the proposal becomes `accepted`.

---

## Step 5: Reject and failed retry

1. Reject a pending proposal.
2. Confirm it disappears from the default pending filter and appears under **Rejected**.
3. Temporarily break article creation for a proposal, then click **Accept**.
4. Confirm the proposal becomes **Failed** and logs contain the error.
5. Fix the problem, reopen the failed proposal, correct language if needed, and accept again.

**Expected:** failed accepted proposals stay reviewable and can be retried.

---

## Step 6: Duplicate prevention

1. Submit a proposal for a URL that already exists in `article`.
2. Submit the same proposal URL twice with different Facebook post URLs.

**Expected:** no duplicate pending proposal is created. Canonical article URL is the proposal
identity; Facebook post URL is review context only.
