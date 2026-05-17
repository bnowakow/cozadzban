# Phase 13 Manual Testing Guide

## Prerequisites

- App running with valid OAuth2 env vars exported
- Logged-in session (complete Google OAuth flow first)

---

## TC-1: Add Article — happy path

1. Open http://localhost:8080
2. Click **Add Article** (visible only when authenticated)
3. Fill in a valid URL (e.g. `https://news.ycombinator.com`) and language (`en`)
4. Leave **Published at** empty
5. Click **Submit**
6. **Expected:** modal closes, grid refreshes with new article, success toast appears
7. **Expected:** article creator is assigned to the logged-in user internally; creator is not editable in the modal

---

## TC-2: Validation — blank URL

1. Open modal, leave URL blank, fill language
2. Click **Submit**
3. **Expected:** error toast "URL is required", modal stays open

---

## TC-3: Validation — invalid URL format

1. Open modal, enter `not-a-url`, fill language
2. Click **Submit**
3. **Expected:** error toast "URL must start with http:// or https://"

---

## TC-4: Validation — blank language

1. Open modal, fill valid URL, leave language blank
2. Click **Submit**
3. **Expected:** error toast "Language is required"

---

## TC-5: 409 conflict

1. Submit the same URL a second time
2. **Expected:** error toast with conflict detail message, article not duplicated

---

## TC-6: 422 enrichment failure

1. Open modal, enter an unreachable URL: `https://thisdomaindoesnotexist.invalid/page`
2. Click **Submit**
3. **Expected:** error toast with enrichment failure detail

---

## TC-7: Cancel

1. Open modal, fill some fields
2. Click **Cancel**
3. **Expected:** modal closes, grid unchanged

---

## TC-8: Session expiry overlay (login overlay)

### Option A — delete cookie manually
1. Log in and open the modal (do not submit yet)
2. Open DevTools → Application → Cookies → delete `JSESSIONID`
3. Fill in a valid URL and language, click **Submit**
4. **Expected:** modal content replaced with:
   - Header: "Session Expired"
   - Message: "Your session has expired. Please log in again."
   - **Login with Google** button
   - **Cancel** button

### Option B — short session timeout
1. Add to `src/main/resources/application-local.properties`:
   ```properties
   server.servlet.session.timeout=10s
   ```
2. Restart app, log in, open modal
3. Wait 10+ seconds, then submit
4. **Expected:** same overlay as Option A
5. Remove the `session.timeout` line after testing

### TC-8a: Cancel from overlay
- Click **Cancel** on the overlay → dialog closes, no navigation

### TC-8b: Login from overlay
- Click **Login with Google** → navigates to `/auth/login` → OAuth flow → returns to `/`

---

## TC-9: Add Article button hidden when not authenticated

1. Log out (click **Logout** or clear `JSESSIONID` cookie)
2. Open http://localhost:8080
3. **Expected:** only **Login** button in top-right, no **Add Article** button

---

## TC-10: Publication date enrichment

1. Log in
2. Add an article whose page exposes publication metadata (`article:published_time`, JSON-LD `datePublished`, or `time[datetime]`)
3. Leave **Published at** empty
4. **Expected:** created article shows a populated `publishedAt` when metadata is available
5. Add an article without recognizable publication metadata
6. **Expected:** `publishedAt` may be empty/null; article creation still succeeds

---

## TC-11: Manual publication date override

1. Open **Add Article**
2. Fill URL and language
3. Set **Published at** using date picker + time picker
4. Submit
5. **Expected:** saved article uses the manually selected publication timestamp even if enrichment finds a different value
6. Edit the article and clear **Published at**
7. **Expected:** saved article has `publishedAt = null`

---

## TC-12: Language normalization and validation

1. Add article with language `PL`
2. **Expected:** saved language is normalized to `pl`
3. Add article with invalid language `not_a_language`
4. **Expected:** validation error toast; article is not created

---

## TC-13: Article list filters

1. Ensure articles exist in at least two languages
2. Open the article list
3. Use language chips or the overflow language menu to select one language
4. **Expected:** feed shows only articles with that normalized language
5. Set `publishedAt` date/time range
6. **Expected:** feed shows only articles whose `publishedAt` is inside the range
7. Set `createdAt` date/time range through the API
8. **Expected:** API response shows only articles whose `createdAt` is inside the range
9. Combine language + date filters
10. **Expected:** filters compose with pagination and sorting

---

## TC-14: Sorting

1. Sort by `publishedAt`
2. **Expected:** rows reorder by source publication timestamp, preserving null handling defined by implementation
3. Sort by `createdAt`
4. **Expected:** rows reorder by DB creation timestamp

---

## TC-15: Creator visibility

1. Log out and open the public article list
2. **Expected:** no creator email/id is visible
3. Log in and open the article list
4. **Expected:** creator email/id may be visible where the authenticated UI needs it
5. Open `/rss`
6. **Expected:** RSS never contains creator email/id

---

## TC-16: Analytics consent

1. Start app with analytics IDs configured:
   - `GOOGLE_ANALYTICS_MEASUREMENT_ID`
   - `STATCOUNTER_PROJECT_ID`
   - `STATCOUNTER_SECURITY_ID`
2. Open the UI in a fresh browser profile
3. **Expected:** analytics-only cookie consent banner is shown
4. Refresh the page without choosing Accept/Decline
5. **Expected:** consent banner is still shown (until explicit choice)
6. Reject analytics
7. **Expected:** Google Analytics and StatCounter scripts are not loaded
8. Clear/reopen consent settings and accept analytics
9. **Expected:** Google Analytics and StatCounter scripts are loaded
10. Start app with IDs blank
11. **Expected:** no analytics scripts are rendered and no analytics consent prompt is required

---

## TC-17: RSS discovery

1. Open the article list page
2. View page source or inspect the document head
3. **Expected:** page includes:
   ```html
   <link rel="alternate" type="application/rss+xml" title="Co za zjeb RSS" href="/rss">
   ```
4. Check the article list top bar
5. **Expected:** visible RSS icon/link is present and points to `/rss`
