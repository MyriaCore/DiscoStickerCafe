# Proposal

## Why

Uploading requires Discord sign-in (see `docs/decisions.md`), and nothing can be uploaded, rate-limited or audited per user until a signed-in user exists. This change adds Discord login. It enforces the ban and account-age rules before an authenticated session exists and writes an audit record for each login where Discord confirmed who the user is. It then redeploys to https://discosticker.cafe.

## What Changes

- **Gateway first:** nginx becomes the only source of the four security headers. Every api location hides the backend's own `Strict-Transport-Security`, `X-Content-Type-Options`, `Referrer-Policy` and `X-Frame-Options`. Otherwise Spring Security's one-year HSTS would override the gateway's `max-age=300`.
- **Discord login in the api:**
  - Sign-in starts at `/oauth2/authorization/discord` and returns to `/login/oauth2/code/discord`, with only the `identify` scope (no email).
  - Separate dev and prod Discord applications.
  - Redirect URIs are built from the client-facing origin: `http://localhost:8080/...` locally and `https://discosticker.cafe/...` in production.
- **Login rules:**
  - Banned accounts and accounts less than 7 days old (by Discord ID creation time) are rejected before an authenticated session is created.
  - The pre-login session, which holds only the OAuth state, is destroyed on rejection.
  - The browser is sent to `/?login=banned|too_new|error`, using fixed codes only.
- **Sessions:**
  - Tomcat in-memory sessions. A pre-login session lasts 10 minutes idle; a signed-in session lasts 8 hours idle.
  - The cookie is HttpOnly, SameSite=Lax and Path=/. In production it is Secure and named `__Host-SESSION`; locally it is `SESSION`.
  - The session ID changes at login.
  - A redeploy signs everyone out.
- **API surface:**
  - Signed-out requests get a 401 JSON response and are never redirected to a login page.
  - `GET /api/me` returns the signed-in user's Discord ID and display name.
  - `POST /api/logout` returns 204.
  - CSRF uses a readable `XSRF-TOKEN` cookie and an `X-XSRF-TOKEN` header.
  - Everything not explicitly permitted is denied.
- **Data:**
  - Two Flyway migrations: `users` (Discord ID, first-seen and last-login times, ban marker) and `login_audit` (Discord ID, outcome, reason, time).
  - No email, name, avatar, Discord tokens or IP addresses are stored.
  - Discord tokens are discarded after the user lookup.
- **Login audit:**
  - Rows are written only for logins where Discord confirmed an identity: accepted, banned, too_new.
  - Provider errors, denied consent and a missing or invalid state are logged at WARN and get no row.
- **Frontend:**
  - The home page shows a sign-in link, or the signed-in name and a sign-out button.
  - A `?login=` code maps to fixed text, and unknown codes get a generic message. The raw parameter is never rendered.
- **Secrets:**
  - A new `DISCORD_CLIENT_SECRET`, passed only to the api.
  - The public client IDs are committed per environment.
  - In production the secret is typed in by hand on the server, never echoed. This is the first third-party-issued secret.
- **Runbook:** `docs/deploy.md` gains:
  - entering the Discord secret;
  - login smoke checks;
  - a header-count check;
  - the manual ban procedure;
  - deploying from a feature branch;
  - the post-merge switch to `master` as a routine Redeploy step.
- **Rule wording (needs approval):** a task, which runs only after the maintainer approves the exact wording, changes the CLAUDE.md and `openspec/config.yaml` rule to "before an **authenticated** session is created".

## Out of scope

- Uploads and anything under `/s/` beyond returning 404 JSON.
- Rate limits of any kind, including nginx `limit_req` on the login endpoints.
- Storing visitor IP addresses, the IPv6 visitor-address fix, and nginx access-log rotation or retention.
- Admin tooling or a ban UI, and ban audit events. Bans stay manual database updates.
- The "My stickers" page and owner-only delete.
- Remember-me and other login providers.
- Postgres-backed sessions (Spring Session JDBC). They move to next weekend.
- A frontend test runner. Frontend checks stay at type check plus build.
- Production storage changes.
- Editing `docs/decisions.md`. Decision candidates are listed in design.md for the PM session.

## Capabilities

### New Capabilities

- `discord-login`: sign-in with Discord, the ban and account-age rules at login, the session that sign-in creates (cookie, lifetime, CSRF, sign-out), the signed-in user endpoint, the API's signed-out behavior, the minimal account record, and login audit records.

### Modified Capabilities

- `gateway-routing`:
  - "Api path prefixes proxied to the backend": while signed out, `/oauth2/x` and `/login/oauth2/x` now return the api's 401 JSON, and `/s/x` its 404 JSON. They never return the gateway's 404 page.
  - "Security headers on every HTTPS response": the gateway becomes the only source of the four headers. Each appears exactly once on proxied responses, and locally none are passed through from the api.
- `database-migrations`: the repository now contains migrations, for users and login audit, and startup applies them. There are no session tables.
- `production-hosting`: "Production secrets live only on the server" allows third-party-issued secrets (the Discord client secret) to be typed in by hand on the server, never echoed and never stored on the maintainer's machine. All other values are still generated on the server.

## Impact

- **api:**
  - New dependencies: `spring-boot-starter-security-oauth2-client`, plus its `-test` starter in test scope.
  - New: security configuration, the login rules, the Discord user service, the audit writer, `/api/me`, and `/api/logout`.
  - New `application.yml` settings: OAuth2 client and provider, session timeout and cookie, cookie-only session tracking.
  - New migrations.
  - New tests, with a fake Discord server for the integration tests.
- **gateway:** `proxy_headers.conf` gains four `proxy_hide_header` lines. Because the file is built into the image, the gateway is rebuilt.
- **frontend:** `app/page.tsx` becomes a client page, plus a small session helper. No new dependencies.
- **compose:**
  - `DISCORD_CLIENT_SECRET` (required, api only) and `DISCORD_CLIENT_ID` in `compose.yaml`.
  - `compose.prod.yaml` overrides the client ID and sets the `__Host-SESSION` cookie name.
  - `secrets.env.example` gains the new name.
- **Discord Developer Portal:** two applications, one redirect URI each. This is maintainer work.
- **Production:** the server's `secrets.env` gains one line, and the stack is redeployed from `feature/login`.

## Security impact

- **New attack surface:** an OAuth login flow and authenticated sessions. The design lists the threats considered:
  - OAuth state and login CSRF
  - API CSRF
  - session fixation
  - session IDs in URLs
  - cookie flags
  - open redirect
  - `?login=` injection
  - audit flooding
  - pre-login session memory
  - client-secret handling
  - Discord token retention
  - header precedence between nginx and Spring Security
  - when a ban takes effect
- **Effects on the project's security constraints:**
  - **Ban and account-age checks:** these are implemented here. The wording becomes "before an authenticated session is created", because Spring's OAuth flow stores its `state` in a pre-login session. That session never holds a user and is destroyed on rejection. The rule text changes only after PM approval (task 7.2).
  - **Audit records for logins, accepted and rejected:** implemented for every login where Discord identified the account. Unidentified failures are logged, not audited, so anonymous requests can't flood the audit table.
  - **No CORS:** unchanged. Everything stays same-origin through nginx.
  - **Secrets:** one new secret, by name only. It is passed to the api alone, and compose refuses to start without it. The production-hosting spec gains the third-party-secret exception.
  - **Public IDs and database keys:** no database key appears in a URL or in `/api/me`. The Discord ID is Discord's public identifier, not one of ours.
  - **Security headers:** nginx stays their only source. The api's copies are hidden.
  - **Visitor IPs:** none are stored in audit records or application logs. nginx's access log still records them (see design.md, Known limitations).
