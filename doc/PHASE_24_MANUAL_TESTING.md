# Phase 24 Manual Testing Guide — Facebook import candidate approval

## Prerequisites

App and Postgres must be running, and Facebook import must be configured for a
valid ADMIN-owned importer account:

```bash
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

Log in through the Vaadin UI as an ACTIVE ADMIN user.

---

## Step 1: ADMIN-only import entry point

1. Open `http://localhost:8080/`.
2. Confirm an ADMIN user sees **Import Facebook Posts** next to **Add Article**.
3. Log in as a non-admin ACTIVE user.
4. Confirm the non-admin user does not see **Import Facebook Posts**.

**Expected:** only ADMIN users can see and start the UI import.

---

## Step 2: Candidate approval modal

1. As ADMIN, click **Import Facebook Posts**.
2. Wait until the importer finishes a discovery pass.
3. Confirm a modal appears in the same browser session that started the import.

**Expected modal contents:**

- one row per discovered candidate;
- each row shows a runtime-unique `candidateId` that can be searched in logs;
- candidates already present in the article store do not appear;
- URL is clickable and opens in a new tab/window;
- source Facebook post URL is shown as a separate clickable link when known;
- language shows the configured `app.facebook-import.language`;
- each row has exactly one selected decision, **Accept** or **Reject**;
- **Accept** is selected by default;
- **Submit** resumes import;
- pressing Enter also submits.

---

## Step 3: Approved versus rejected candidates

1. In the modal, leave at least one candidate accepted.
2. Mark at least one candidate rejected.
3. Submit the modal.

**Expected:**

- accepted URLs continue through the normal import path;
- rejected URLs are not sent to the article API/server;
- logs for the discovery pass include each URL with its source Facebook post URL, approved/rejected status, and `candidateId`;
- final import logs include a `rejected URLs:` summary using the same one-URL-per-line format as `failed URLs:`.

---

## Step 4: REST trigger remains non-interactive

Start import through the admin REST endpoint:

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/api/admin/facebook-import/run \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Expected:** `202`, no approval modal is required, and discovered candidates are accepted automatically.
