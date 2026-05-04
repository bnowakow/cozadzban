# Phase 11 Manual Testing Guide

## Prerequisites

Set environment variables for Google OAuth2:
```bash
export GOOGLE_CLIENT_ID="your-google-oauth-client-id"
export GOOGLE_CLIENT_SECRET="your-google-oauth-client-secret"  
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES="your-google-client-id"
```

## Step 1: Start the Application

```bash
cd /Users/sup/code/cozazjeb
./gradlew bootRun
```

The app should start on `http://localhost:8080`

## Step 2: Test OAuth Login Redirect

**Test:** GET /auth/login redirects to Google OAuth

```bash
curl -v http://localhost:8080/auth/login
```

**Expected:**
- HTTP 302 or 303 redirect
- Location header contains: `https://accounts.google.com/o/oauth2/v2/auth?...`
- OR redirects to `/oauth2/authorization/google`

## Step 3: Test Unauthenticated /auth/me

**Test:** GET /auth/me returns 401 without session

```bash
curl http://localhost:8080/auth/me
```

**Expected:**
- HTTP 401 Unauthorized
- No body or error message

## Step 4: Manual OAuth Flow (Browser)

1. Open browser to: `http://localhost:8080/auth/login`
2. You'll be redirected to Google OAuth consent screen
3. Complete the authentication
4. You'll be redirected back to `http://localhost:8080/` (or configured redirect URI)
5. Session cookie `JSESSIONID` will be created

## Step 5: Verify Session Cookie

**In browser DevTools (F12):**
- Storage → Cookies → localhost
- Look for `JSESSIONID` cookie
- **Should have:**
  - ✓ HttpOnly flag (checked)
  - ✓ SameSite=Lax (in Chrome: "Lax")
  - ✗ Secure flag (not set in localhost, but would be in production)

## Step 6: Test /auth/me with Session

After completing Step 4 above, in the **same browser**:

```bash
# In DevTools Console, run:
fetch('http://localhost:8080/auth/me')
  .then(r => r.json())
  .then(d => console.log(d))
```

**Expected response:**
```json
{
  "email": "your-email@example.com",
  "role": "ADMIN or USER (depending on allowlist)",
  "allowlisted": true
}
```

## Step 7: Test /auth/logout

**In browser**, after authenticated session from Step 4:

```bash
# In DevTools Console:
fetch('http://localhost:8080/auth/logout', { method: 'POST' })
  .then(r => console.log('Status:', r.status))
```

**Expected:**
- HTTP 204 No Content
- Session cookie `JSESSIONID` is cleared/removed

## Step 8: Verify Logout Worked

**In browser**, after Step 7:

```bash
# In DevTools Console:
fetch('http://localhost:8080/auth/me')
  .then(r => console.log('Status:', r.status))
```

**Expected:**
- HTTP 401 Unauthorized
- No user info returned

## Step 9: Verify REST API Still Works (Regression Check)

**Test:** Bearer token API endpoint unchanged

```bash
# Without authentication:
curl http://localhost:8080/api/articles

# With bearer token (if you have one):
curl -H "Authorization: Bearer YOUR_JWT_TOKEN" http://localhost:8080/api/articles
```

**Expected:**
- Endpoints still accept bearer tokens as before
- No change to REST API auth mechanism
- 403 if you don't have a valid token and it's a protected endpoint

## Troubleshooting

### OAuth Login Redirect Fails
- Verify `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` are set
- Check `spring.security.oauth2.client.registration.google.scope` in `application.properties`

### /auth/me Returns Error
- Ensure you've completed OAuth flow and have active session
- Check JSESSIONID cookie exists
- Verify email matches an entry in `app_user` allowlist table

### Session Cookie Missing
- OAuth callback handler didn't execute properly
- Check Spring Security configuration in `SecurityConfig.kt`
- Verify `spring.servlet.session.cookie.http-only=true` is set

### CSRF Errors on Logout
- POST /auth/logout requires CSRF token in production
- In localhost/curl testing, CSRF is typically disabled in dev
- Browser automatically handles CSRF tokens

## Summary Checklist

- [ ] OAuth login redirects to Google
- [ ] GET /auth/me returns 401 when unauthenticated  
- [ ] After OAuth flow, GET /auth/me returns user + role
- [ ] JSESSIONID cookie has HttpOnly flag
- [ ] POST /auth/logout returns 204
- [ ] GET /auth/me returns 401 after logout
- [ ] REST API endpoints still work with bearer tokens
