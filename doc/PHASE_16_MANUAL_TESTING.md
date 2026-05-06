# Phase 16 Manual Testing Guide — Article ownership and soft-deleted users

## Prerequisites

App and Postgres must be running:
```bash
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
# OR just use the docker compose app service if already built
```

You need at least one allowlisted user with ADMIN role.  
Obtain a Google OIDC Bearer JWT for your test account(s) and export:

```bash
export ADMIN_TOKEN="eyJ..."   # JWT for an ACTIVE ADMIN user
export USER_TOKEN="eyJ..."    # JWT for an ACTIVE USER (non-admin)
```

---

## Step 1: Verify status column default

List users — every user should show `status: ACTIVE` (the migration default).

```bash
curl -s http://localhost:8080/api/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.[].status'
```

**Expected:** All values are `"ACTIVE"`.

---

## Step 2: Soft-delete a user

Pick a USER (not the last ACTIVE ADMIN) and note its `id` (e.g. `1`):

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -X PATCH http://localhost:8080/api/users/1/status \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"status":"DELETED"}'
```

**Expected:** `204`

Confirm the user is now DELETED:

```bash
curl -s http://localhost:8080/api/users \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.[] | select(.id==1) | .status'
```

**Expected:** `"DELETED"`

---

## Step 3: Deleted user is blocked from API writes

Use the JWT of the deleted user to attempt creating an article:

```bash
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/api/articles \
  -H "Authorization: Bearer $DELETED_USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com","language":"en"}'
```

**Expected:** `403`

---

## Step 4: Last-ACTIVE-admin protection

If there is only one ACTIVE ADMIN, attempt to delete them:

```bash
curl -s -w "\n%{http_code}" \
  -X PATCH http://localhost:8080/api/users/<LAST_ADMIN_ID>/status \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"status":"DELETED"}'
```

**Expected:** `409` with a `lastAdminRequired` problem detail body.

---

## Step 5: Restore a deleted user

```bash
curl -s -w "\n%{http_code}" \
  -X PATCH http://localhost:8080/api/users/1/status \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"status":"ACTIVE"}'
```

**Expected:** `200` with the user object showing `"status": "ACTIVE"`.

---

## Step 6: Admin UI — status column and restore button

1. Open **http://localhost:8080/admin** in a browser (log in as ADMIN).
2. The users grid should have a **Status** column showing `ACTIVE` or `DELETED`.
3. For a DELETED user the **Actions** cell should show a **Restore** button (no Delete button).
4. For an ACTIVE user the **Actions** cell should show the **Delete** button (no Restore button).
5. Click **Restore** on a DELETED user — the row status should flip to `ACTIVE`.
6. Click **Delete** on an ACTIVE non-admin user — the row status should flip to `DELETED`.

---

## Step 7: Article `createdBy` in authenticated response

Create an article (as an ACTIVE user/admin) and note the returned `id`:

```bash
curl -s -X POST http://localhost:8080/api/articles \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://news.ycombinator.com","language":"en"}' | jq '{id,createdBy}'
```

**Expected:** Response includes `createdBy: { id: <number>, email: "<your email>" }`.

Fetch the same article **unauthenticated**:

```bash
curl -s http://localhost:8080/api/articles/<ID> | jq 'has("createdBy")'
```

**Expected:** `false` — `createdBy` is absent for anonymous requests.

Fetch the same article **authenticated**:

```bash
curl -s http://localhost:8080/api/articles/<ID> \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq '.createdBy'
```

**Expected:** `{ "id": <number>, "email": "<your email>" }`

---

## Step 8: Creator is immutable (PUT/PATCH must not change it)

```bash
# Note original createdBy from Step 7
curl -s -X PUT http://localhost:8080/api/articles/<ID> \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://news.ycombinator.com","language":"pl"}' | jq '.createdBy'
```

**Expected:** `createdBy` is unchanged — same `id` and `email` as after creation.

---

## Step 9: RSS contains no creator/user data

```bash
curl -s http://localhost:8080/rss | grep -i "email\|creator\|author\|user"
```

**Expected:** No output — the RSS feed must not expose any user data.

---

## Step 10: Anonymous article list omits creator

```bash
curl -s "http://localhost:8080/api/articles" | jq '.content[0] | has("createdBy")'
```

**Expected:** `false` — list responses never include `createdBy` (field is `@JsonIgnore` on the `Article` domain object; `ArticlePage` wraps `List<Article>`, not `List<ArticleResponse>`).
