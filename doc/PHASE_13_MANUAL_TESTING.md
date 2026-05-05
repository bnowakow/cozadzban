# Phase 13 Manual Testing Guide

## Prerequisites

- App running with valid OAuth2 env vars exported
- Logged-in session (complete Google OAuth flow first)

---

## TC-1: Add Article — happy path

1. Open http://localhost:8080
2. Click **Add Article** (visible only when authenticated)
3. Fill in a valid URL (e.g. `https://news.ycombinator.com`) and language (`en`)
4. Click **Submit**
5. **Expected:** modal closes, grid refreshes with new article, success toast appears

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
