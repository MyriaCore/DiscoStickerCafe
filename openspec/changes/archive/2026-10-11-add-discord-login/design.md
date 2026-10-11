# Design

## Context

See proposal.md (Why) for motivation, and `specs/discord-login/spec.md` for the required behavior. Current state that shapes the approach:

- **api:**
  - Spring Boot 4.1.1, which manages Spring Security 7.1.1 and Tomcat 11.0.24 (checked in the Boot 4.1.1 BOM). There is no Spring Security on the classpath yet.
  - `server.forward-headers-strategy: native` is already set. Boot then installs Tomcat's RemoteIpValve, which trusts `X-Forwarded-Proto`, `-Host` and `-Port` only from internal ranges. Those include `172.16.0.0/12` and `192.168.0.0/16`, which cover Docker's bridge networks.
  - Health lives at `/api/health`.
  - Integration tests are `*IT` classes run by failsafe, against Testcontainers Postgres and SeaweedFS (`TestcontainersConfiguration`, which already generates per-run credentials).
- **gateway:**
  - `routes.conf` is shared by the local and production servers.
  - Each api location includes `proxy_headers.conf`, which is copied into the image (so a change needs a gateway rebuild).
  - Only production includes `security_headers.conf`. The local server (port 8080) sends none of the four headers.
  - Only the production 443 server for discosticker.cafe includes the routes. www redirects to the apex before reaching the api.
- **frontend:** a bare static export, with `trailingSlash: true` and no fallback to `index.html`.
- **Production:**
  - The server runs the same compose stack from a clone, and the maintainer does every server step.
  - `secrets.env` currently holds only server-generated hex values.
  - Docker's default `json-file` log driver keeps container logs with no rotation configured.

## Goals / Non-Goals

**Goals:**
- Every behavior in `specs/discord-login` is proven by an automated test, apart from the two frontend requirements, which are checked by type check, build and review. The local stack and production then show the same behavior through nginx.
- The header fix lands, and is checked, before any OAuth code.
- No new secret value ever passes through Claude Code, the repository, an image or a log.

**Non-Goals:**
- Making a ban end an existing session. A ban takes effect at the next login; restarting the api signs everyone out.
- Surviving restarts. Sessions are in memory this weekend.
- Any change to how nginx logs requests.

## Decisions

### D1. nginx hides the api's security headers (`proxy_hide_header` in `proxy_headers.conf`)
- Spring Security writes these headers by default:
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`
  - `Pragma` and `Expires`
  - `X-XSS-Protection: 0`
  - on secure requests only, `Strict-Transport-Security: max-age=31536000 ; includeSubDomains`
- nginx's `add_header ... always` appends its own copies rather than replacing them. A browser honors the first HSTS header it sees, so the one-year value would win.
- **Fix:** add four `proxy_hide_header` lines (`Strict-Transport-Security`, `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options`) to `proxy_headers.conf`. That file is already included in all four api locations, and `proxy_hide_header` doesn't interact with `add_header` inheritance.
- Spring's `Cache-Control`/`Pragma`/`Expires` headers stay: no-store is right for API responses. `X-XSS-Protection: 0` stays too; it's harmless.
- Alternatives:
  - Disabling Spring Security's header writing (`headers.disable()`) would also drop Cache-Control.
  - Putting the directives at server level in `routes.conf` would work through inheritance, but they belong next to the other proxy settings.
- **The check can't fail before Spring Security exists:**
  - Locally, the api sends no headers today and the local server adds none.
  - Even with Spring Security, HSTS is only written on secure requests.
  - So the check comes in three parts:
    1. Locally, none of the four reach the client through the gateway (task 1.1).
    2. A negative control in 5.5 proves the api itself does send `X-Frame-Options`/`X-Content-Type-Options`, so the gateway is what removes them.
    3. Production shows exactly one of each, with HSTS exactly `max-age=300` (8.5).

### D2. Discord client registration
```yaml
spring.security.oauth2.client:
  registration.discord:
    provider: discord
    client-id: ${DISCORD_CLIENT_ID}
    client-secret: ${DISCORD_CLIENT_SECRET}
    client-authentication-method: client_secret_basic
    authorization-grant-type: authorization_code
    redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
    scope: identify
  provider.discord:
    authorization-uri: https://discord.com/oauth2/authorize
    token-uri: https://discord.com/api/oauth2/token
    user-info-uri: https://discord.com/api/v10/users/@me
    user-name-attribute: id
```
- **`redirect-uri` must be set.** Discord isn't one of Spring's predefined providers, so Boot supplies no default, and `ClientRegistration` refuses an empty value.
  - `/login/` is written literally instead of Spring's `{action}` variable, because `{action}` can be overridden by an `?action=` query parameter on the authorization request.
  - `{baseUrl}` comes from the request's scheme, host and port as RemoteIpValve rewrote them. That yields `http://localhost:8080` locally and `https://discosticker.cafe` in production (default port dropped).
- **Discord's endpoints and auth:**
  - Discord accepts HTTP Basic client authentication at the token endpoint. The user endpoint is versioned (v10), because unversioned routes go to v6.
  - Discord documents no PKCE. Contrary to what exploration concluded, Spring Security 7.1 sends PKCE (`code_challenge`, S256) for this confidential client too, as the local stack showed in task 5.5. So the flow is protected by both `state` and PKCE, and the first real login (6.2) confirms that Discord accepts it.
- `${DISCORD_CLIENT_ID}` and `${DISCORD_CLIENT_SECRET}` have no defaults, so the api fails at startup if either is missing.
- Tests set both to random per-run values in `TestcontainersConfiguration`, following its existing credential pattern.

### D3. Two Discord applications; client ID committed, secret in `secrets.env`
- **Two applications:** "DiscoSticker (dev)", with redirect `http://localhost:8080/login/oauth2/code/discord`, and "DiscoSticker.cafe", with redirect `https://discosticker.cafe/login/oauth2/code/discord`. The production secret therefore never sits on the laptop, and the dev app can't send authorization codes to production.
- **Client IDs are committed:** a client ID is public (it appears in every authorization URL). It isn't a secret, so it follows the decision that `secrets.env` holds only secrets. The dev ID goes in `compose.yaml`, and `compose.prod.yaml` overrides it.
- **The secret is `DISCORD_CLIENT_SECRET`:** in `secrets.env`, passed to the api only as `${DISCORD_CLIENT_SECRET:?…}`, with a placeholder in `secrets.env.example`.
- **No www redirect URI:** the www server redirects every path to the apex before any login starts.
- Alternative: one application with both redirect URIs. It is simpler, but the production secret would have to live on the laptop as well.

### D4. Ban and account-age checks in a custom `OAuth2UserService`
- **Flow:**
  - The custom `DiscordOAuth2UserService` wraps `DefaultOAuth2UserService`, which calls `/users/@me` with the access token.
  - It parses `id` as a long.
  - It asks `LoginPolicy` for a decision: banned first (the `users.banned_at` lookup), then age, using an injected `Clock`.
  - On rejection it throws `LoginRejectedException extends OAuth2AuthenticationException`, which carries the Discord ID and a reason code.
- **Why this runs before any session exists:** it runs inside `OAuth2LoginAuthenticationFilter.attemptAuthentication`, before session-fixation handling, before the SecurityContext is saved, and before the success handler. So no authenticated session ever exists for a rejected user.
- **Wiring:** the service is wired explicitly with `oauth2Login(o -> o.userInfoEndpoint(u -> u.userService(...)))`, not found by bean type. A second `OAuth2UserService` bean would make the type lookup fall back to the default service without any error.
- **Age rule:** `createdAt = Instant.ofEpochMilli((id >> 22) + 1420070400000L)`, and the account is rejected when `createdAt.plus(7 days)` is after `now`. Exactly 7 days is therefore allowed.
- Alternatives:
  - Checking in the success handler: too late, because the session ID has already rotated and the context is saved.
  - Wrapping the `AuthenticationProvider`: possible, but it splits Discord parsing from the rules for no gain.

### D5. Who writes the audit row, and what is only logged
- **Rejected rows:** the `AuthenticationFailureHandler` writes them. When the exception is a `LoginRejectedException`, it inserts `login_audit(discord_id, 'rejected', reason)`.
- **Accepted rows:** the `AuthenticationSuccessHandler` writes them. Spring runs it after the session ID has rotated and the SecurityContext is saved, so "accepted" means a signed-in session really exists. In one transaction, it upserts `users` (sets `last_login_at`; inserts on first login) and inserts `login_audit(discord_id, 'accepted', null)`.
- **Everything else is only logged:** any other `AuthenticationException` from the login filter writes one WARN log line and no row. This covers `authorization_request_not_found`, `invalid_state_parameter`, Discord `error=` responses (including `access_denied`) and token or user-info failures.
  - The line names only the OAuth2 error code (for example, `login failed before identity: authorization_request_not_found`).
  - It never includes the `code` or `state` values, the exception's free-text description, or the client's address.
- **Two writers, no duplicates:** each path writes exactly once. Accepted rows can't be written for sessions that weren't created.
- Alternative: writing rows inside the user service. It knows the Discord ID, but it would write "accepted" before the session exists.

### D6. Sessions: Tomcat in memory, 10-minute pre-login and 8-hour signed-in idle timeouts
- `server.servlet.session.timeout: 10m`. The only anonymous sessions are those Spring creates to hold an `OAuth2AuthorizationRequest` (the `state`, redirect URI and registration ID) at `/oauth2/authorization/discord`.
- **Failure handler, before redirecting:** invalidates the session (`request.getSession(false)?.invalidate()`) and clears the SecurityContext.
- **Success handler:** calls `session.setMaxInactiveInterval(8h)`.
- **Session fixation:** Spring Security's default `changeSessionId()` rotates the ID at authentication.
- **Signed-out API calls create no session:** `requestCache(NullRequestCache)` stops `ExceptionTranslationFilter` from creating a session to save the request on every signed-out 401. It also means no saved request can steer the post-login redirect.
- **No session IDs in URLs:** `server.servlet.session.tracking-modes: cookie` turns off Tomcat's `;jsessionid=` URL rewriting.
- Alternative: Spring Session JDBC. It survives redeploys and allows ending a session by principal, but it adds a dependency and two tables, and serializes the security context into Postgres. Deferred to next weekend.

### D7. Session cookie
- `server.servlet.session.cookie`: `name: SESSION`, `http-only: true`, `same-site: lax`, with no `max-age` (it ends with the browser session) and no `domain`. The path is `/` (root context).
- **Secure is not forced.** Tomcat marks the session cookie Secure when `request.isSecure()`, which RemoteIpValve sets from `X-Forwarded-Proto: https` in production.
- **Production uses `__Host-SESSION`:** `compose.prod.yaml` sets `SERVER_SERVLET_SESSION_COOKIE_NAME: __Host-SESSION`.
  - This doesn't work locally because Chrome rejects `__Host-` cookies on `http://localhost`, even though it accepts `Secure` ones there (httpwg/http-extensions#2605; Chromium 40202941). Safari accepts neither.
  - In production the prefix also acts as an end-to-end check: if forwarded headers ever stop marking requests secure, the browser refuses the cookie and login fails loudly instead of running with a non-Secure cookie.
- **SameSite=Lax is required, not just a default.** Discord's return to `/login/oauth2/code/discord` is a cross-site top-level GET, which Lax allows and Strict blocks. With Strict, the pre-login session (and its `state`) would be missing, and every login would fail.

### D8. Authorization rules and the signed-out response
- **Rules:**
  - `/api/health` → permitted
  - `/s/**` → permitted (public stickers come with uploads)
  - `/api/**` → authenticated
  - ERROR dispatches → permitted, so Boot's JSON error bodies (404, 500) render
  - anything else → `denyAll()`
- The two OAuth endpoints (`/oauth2/authorization/{id}` and `/login/oauth2/code/{id}`) are handled by their filters before authorization.
- **One entry point:** `exceptionHandling.authenticationEntryPoint` writes `401` with `Content-Type: application/json` and `{"error":"unauthorized"}`. It replaces `oauth2Login`'s default redirect-to-login entry point, so nothing ever redirects to Discord on its own; sign-in starts only from the link.
- **Results for the gateway-routing scenario:** while signed out, `/oauth2/x` and `/login/oauth2/x` return 401 JSON, and `/s/x` returns 404 JSON.
- Alternative: `anyRequest().permitAll()` would leave the existing gateway-routing scenario unchanged, but deny-by-default is safer as the api grows.

### D9. CSRF and sign-out
- **CSRF uses `csrf(c -> c.spa())`, new in Spring Security 7.0:**
  - `CookieCsrfTokenRepository.withHttpOnlyFalse()` writes the `XSRF-TOKEN` cookie and reads the `X-XSRF-TOKEN` header.
  - A handler accepts the raw token from the header, and the BREACH-masked token from form parameters.
  - It turns off deferred token loading, so any API response sets the cookie (including a 401 from `/api/me`). Spring Security 6's extra "CsrfCookieFilter" workaround isn't needed.
- **Token rotation:** the cookie is cleared at login and logout, so the frontend re-reads it from the next `/api/me` response.
- **Sign-out:**
  - Endpoint: `logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(NO_CONTENT)))`. Spring's default `/logout` isn't proxied by nginx, and its default success redirect would point at a page that doesn't exist.
  - With CSRF on, LogoutFilter matches POST only.
  - Logout also invalidates the session and deletes the session cookie (by its configured name).

### D10. Minimal principal; Discord tokens dropped
- The user service returns `new DefaultOAuth2User(Set.of(ROLE_USER), Map.of("id", "<discord id>", "name", "<global_name or username>"), "id")`. The rest of Discord's user object (avatar, locale, flags and so on) is never stored in the session.
- A no-op `OAuth2AuthorizedClientRepository` (save and remove do nothing, load returns null) is passed to `oauth2Login`.
  - Without it, Boot's default keeps every user's access and refresh tokens in an in-memory `InMemoryOAuth2AuthorizedClientService` until restart.
  - Nothing calls Discord after login, so the tokens are simply dropped.

### D11. Data model (Flyway, `JdbcClient`)
- `V1__create_users.sql`: `users(id bigint generated always as identity primary key, discord_id bigint not null unique, created_at timestamptz not null default now(), last_login_at timestamptz, banned_at timestamptz)`.
  - `last_login_at` is nullable so a maintainer can ban an account by hand before its first login.
- `V2__create_login_audit.sql`: `login_audit(id bigint generated always as identity primary key, occurred_at timestamptz not null default now(), discord_id bigint not null, outcome text not null check (outcome in ('accepted','rejected')), reason text check (reason in ('banned','too_new')), check ((outcome = 'accepted') = (reason is null)))`.
  - `discord_id` is not null because only identified attempts are audited.
  - There is no foreign key to `users`, because rejected too-new accounts have no user row.
- Database access uses `JdbcClient` (Spring JDBC, already present through the Flyway starter), not JPA.
- Discord IDs fit in a signed 64-bit integer until about 2084. They are parsed with `Long.parseLong`, and a non-numeric ID fails the login as an error.
- **Manual ban:** `update users set banned_at = now() where discord_id = <id>`, or an `insert` for an account that has never signed in. Documented in the runbook.
- Alternative: one generic audit table shared with uploads. Upload audit needs its own columns (SHA-256, sticker reference), so separate tables keep both strict.

### D12. Redirects after login
- **Success:** `SimpleUrlAuthenticationSuccessHandler` with default target `/` and `alwaysUseDefaultTargetUrl = true`, wrapped by the auditing success handler.
- **Failure:** the reason code is mapped from a fixed enum. `LoginRejectedException` gives `banned` or `too_new`; every other failure gives `error`. The handler redirects to the literal `/?login=` plus the code. No exception message, provider `error_description`, or request value reaches the URL.

### D13. Frontend
- **Page:** `app/page.tsx` becomes a client component (static export still works), with a small helper in `lib/session.ts`:
  - `getMe()`: `fetch('/api/me')`. A 200 response is the user; 401 means signed out.
  - `signOut()`: `POST /api/logout` with `X-XSRF-TOKEN` read from `document.cookie`, then re-run `getMe()`.
  - `loginMessage(code)`: a fixed map with a generic default.
- **Sign-in:** a plain `<a href="/oauth2/authorization/discord">`, a full navigation rather than a fetch. The OAuth flow needs top-level redirects.
- **The `login` parameter:** it is read once, from `window.location.search` in an effect, and only its mapped message is rendered. No `dangerouslySetInnerHTML`.
- **No test runner this weekend:** the mapping is checked in review (task 6.1).

### D14. Test approach
- **Unit tests (surefire):**
  - `AccountAgeTest`: snowflake to instant, the 7-day boundary to the millisecond, a non-numeric ID.
  - `LoginPolicyTest`: banned outranks too new; neither means accepted.
- **Integration tests (failsafe):** `@SpringBootTest(webEnvironment = RANDOM_PORT)`, driven with a JDK `HttpClient` (redirects off, an explicit cookie jar) against the real Tomcat. This covers real `Set-Cookie` headers, the state check, RemoteIpValve and the real database.
- **`FakeDiscord`** (test sources) is a JDK `com.sun.net.httpserver.HttpServer` on a random port.
  - `/token` asserts the `Authorization: Basic` header, `grant_type=authorization_code` and the `redirect_uri`, and returns a bearer token.
  - `/users/@me` returns the user configured for the test, with an ID built from a chosen creation time.
  - A `DynamicPropertyRegistrar` points `token-uri` and `user-info-uri` at it. There are no new test dependencies and no mocks of our own classes.
- **`SessionRecorder`** (a test `HttpSessionListener` plus `HttpSessionAttributeListener` bean, which Boot registers with Tomcat) records:
  - each session created and destroyed;
  - every attribute name ever set;
  - the session objects, so `getMaxInactiveInterval()` can be read later.
  - It lets tests prove that a rejected attempt never stored `SPRING_SECURITY_CONTEXT`, and that the pre-login session was destroyed.
- **Audit outcomes** are asserted by counting `login_audit` rows for the test's Discord ID. Each test uses a fresh random ID, so tests don't interfere with each other.
- **Token retention** is checked three ways:
  - the authorized-client service has no entry;
  - no recorded session attribute value holds the token;
  - Boot's `OutputCaptureExtension` shows the token string in no log output.
- **The forwarded-header variant** sends `X-Forwarded-Proto: https`, `X-Forwarded-Host: discosticker.cafe` and `X-Forwarded-Port: 443` from loopback. Loopback is an internal proxy, so this proves the production `redirect_uri` and the Secure cookie flag without nginx.
- The `__Host-SESSION` name is a production setting, checked in 8.5.

### D15. Entering the production client secret
- Discord issues the secret in the Developer Portal, so it can't be generated on the server. The runbook procedure:
  1. Open the prod application's OAuth2 page, reset or reveal the secret, and copy it.
  2. On the server, run `IFS= read -rs -p 'DISCORD_CLIENT_SECRET (prod app): ' v`, paste into the prompt, and append it with `printf` to `secrets.env` (which keeps mode 600). Then `unset v`.
  3. Clear the Windows clipboard (`Set-Clipboard -Value ' '`), and clear clipboard history if it is on.
- It is never written to a file on the maintainer's machine, never echoed, and never in shell history (`read -s` takes it from the terminal).
- The checks print owner, mode and variable names only.
- The dev secret does live in the local `secrets.env`, which is its purpose.

### D16. Deploying from the feature branch
- Same pattern as the walking skeleton deploy:
  1. Take GitHub's `feature/login` hash on Windows.
  2. On the server: fetch, switch, pull with `--ff-only`, and compare `HEAD` to that hash.
  3. Add the secret.
  4. `$Prod up --build`. The gateway image must rebuild because `proxy_headers.conf` is built in.
  5. Recreate the gateway, because the branch changed.
- The secret goes in before `up`. Otherwise compose stops at interpolation (`DISCORD_CLIENT_SECRET` is `:?`) without touching the running containers.
- Switching to `master` after the merge becomes a routine step in the runbook's Redeploy section, not a task here, so the change can be archived on its branch before the merge.

## Threats considered (auth, sessions, secrets, headers)

| # | Threat | Handling | Verified by |
|---|---|---|---|
| T1 | **Login CSRF / forged callback:** an attacker makes the victim's browser complete the attacker's login, or replays a code. | Spring issues a random `state` stored in the victim's pre-login session and rejects any callback whose state doesn't match. No token request is made. The cookie is SameSite=Lax, so it still arrives on Discord's top-level redirect. | 5.3 (state mismatch, no session: no token request; forged callback) |
| T2 | **CSRF on API writes** (sign-out now, uploads later). | `csrf.spa()`: state-changing requests need `X-XSRF-TOKEN` equal to the `XSRF-TOKEN` cookie, which a cross-site page can't read. Logout is POST only. | 5.2 (missing token gives 403; GET doesn't sign out) |
| T3 | **Session fixation.** | The session ID changes at authentication (`changeSessionId`). The pre-login session is destroyed on failure. | 5.3 (cookie value differs after login; pre-login session destroyed) |
| T4 | **Session ID leaks through URLs** (Referer, logs). | `tracking-modes: cookie` turns off `;jsessionid=` rewriting. | 5.3 (no `jsessionid` in any Location) |
| T5 | **Session cookie theft or downgrade.** | HttpOnly; Secure in production through the forwarded scheme; `__Host-` prefix in production (no Domain, Path=/, Secure required by the browser); SameSite=Lax; no persistent Max-Age. There is no CSP yet, so HttpOnly is what protects the cookie from an XSS bug. | 5.3 (attributes, Secure with forwarded https); 8.5 (production attributes, checked separately) |
| T6 | **Open redirect after login.** | The success target is always `/` (`alwaysUseDefaultTargetUrl`, `NullRequestCache`). Failure targets are `/?login=` plus a fixed code. Nothing is taken from the request. | 5.3 (Location is exactly `/` or one of the three) |
| T7 | **`?login=` injection** (reflected text or XSS). | The server only emits fixed codes. The page maps codes to fixed text, any other value to a generic message, and never renders the raw parameter or uses `dangerouslySetInnerHTML`. | 5.3 (fixed codes); 6.1 (review greps); 6.2 (manual `?login=<script>` check) |
| T8 | **Anonymous audit flooding.** There are no IPs and no rate limits, so anyone can hit the callback. | Audit rows are written only after Discord confirms an identity. Other failures are logged at WARN without request values. A forged callback costs no database write. | 5.3 (forged callback, consent denied: zero rows) |
| T9 | **Pre-login session memory exhaustion:** each request to `/oauth2/authorization/discord` creates an in-memory session. | Pre-login sessions expire after 10 minutes and are small (about 1–2 KB). Signed-out API calls create none (`NullRequestCache`). No `limit_req` this weekend (accepted risk; see Risks). | 5.2 (signed-out `/api/me` sets no session cookie); 5.3 (10-minute idle on the pre-login session) |
| T10 | **Client secret leak** (repository, image, logs, transcript, laptop). | Name-only references; `${DISCORD_CLIENT_SECRET:?}` for the api only; prod value typed in on the server with `read -s`; dev and prod apps separate; no `docker compose config` with `secrets.env` and no `docker inspect` on the api. Spring doesn't log the client secret, and security DEBUG logging isn't enabled in production. If it leaks, reset it in the portal and repeat 8.3. | 2.2 (config check with the example file; api only); 7.1 (runbook checks); 8.3 (names, owner, mode only) |
| T11 | **Discord token retention.** | Tokens are used once for `/users/@me`, then dropped (no-op authorized-client repository). The principal holds only the ID and display name. | 5.3 (no authorized client, no session attribute, no log line with the token) |
| T12 | **Over-collection of personal data.** | `identify` scope only (no email). Stored: Discord ID, timestamps, ban marker. Audit: Discord ID, outcome, reason, time. No IPs. | 5.3 (scope exactly `identify`); 3.1 (table columns) |
| T13 | **Header precedence:** Spring's one-year HSTS (and duplicate nosniff and frame headers) overriding or doubling nginx's. | `proxy_hide_header` for all four in every api location (D1). | 1.1, 1.2, 5.5 (negative control), 8.5 (exactly once, HSTS `max-age=300`) |
| T14 | **A banned user keeps using an existing session.** | Accepted for this weekend: the ban is read at login, so it takes effect at the user's next login. To make it immediate, the maintainer restarts the api (`$Prod restart api`), which signs everyone out because sessions are in memory. Documented in the runbook. | 6.2 and 8.6 (ban, then the login is rejected); 7.1 (runbook text) |
| T15 | **Forged forwarded headers** changing the `redirect_uri` or the Secure flag. | nginx overwrites `X-Forwarded-Proto`, `-Host` and `-Port` on every proxied request. RemoteIpValve honors them only from internal addresses. Only the production apex 443 server reaches the api. Discord also rejects any `redirect_uri` that isn't registered exactly. | 5.3 (forwarded variant from loopback); 5.5 and 8.5 (redirect URIs through the real gateway) |
| T16 | **Rule bypass through a client-supplied identity.** | The Discord ID comes only from Discord's `/users/@me` over TLS, called with a token from a code exchanged by our server with the client secret. | 5.3 (FakeDiscord token and user flow) |

## Known limitations

- **"No visitor IPs" covers audit records (and application logs) only.**
  - nginx's access log still records visitor IP addresses in Docker's container logs. That means IPv4 addresses; IPv6 visitors appear as the Docker bridge address. Those logs use the default `json-file` driver with no rotation configured.
  - Rotation and retention are out of scope. They're recorded with next weekend's work on IP storage, the IPv6 visitor address and retention.
- **A redeploy, restart or reboot signs everyone out** (in-memory sessions). Accepted; Postgres-backed sessions come next weekend.
- **A ban takes effect at the next login**, unless the maintainer restarts the api (T14).
- `/oauth2/authorization/<unknown id>` returns 500 JSON (Spring's response for an unknown registration). It is harmless; no link points there.
- Discord's docs don't explicitly state that redirect URIs must match exactly. The design assumes they do (RFC 9700 §2.1, plus community reports of trailing-slash failures), and the real logins in 6.2 and 8.6 confirm it.

## Risks / Trade-offs

- [Chrome later accepts `__Host-` on localhost, or the dev and prod cookie names drift further] → Only the name differs, through one env line in `compose.prod.yaml`. Every other attribute is identical and tested.
- [Pre-login session flood exhausts api memory, with no `limit_req`] → 10-minute expiry and small sessions. The api restarts `unless-stopped` if it ever runs out of memory. Rate limiting arrives with upload rate limits next weekend.
- [The maintainer browses the local stack at `127.0.0.1:8080`] → The redirect URI would then be `http://127.0.0.1:8080/...`, which the dev app doesn't list, and the cookie host differs. The runbook and 6.2 say to use `http://localhost:8080`.
- [Discord rejects or mishandles PKCE parameters, which it doesn't document] → The real login in 6.2 shows it. If Discord rejects them, turn PKCE off for this registration (`ClientRegistration.ClientSettings` with `requireProofKey(false)`, or the authorization-request customizer) and record the decision.
- [Spring's authorize URL leaves `:` and `/` unencoded in `redirect_uri`, or encodes them] → The smoke checks URL-decode the Location before matching, so they pass either way.
- [The production `secrets.env` lacks the secret at redeploy] → Compose fails at interpolation and names `DISCORD_CLIENT_SECRET` without touching running containers. 8.3 runs before 8.4.
- [The secret is pasted with a trailing newline or space] → `read -r` with `IFS=` keeps it as typed. A wrong value shows up as a failed login with a WARN `invalid_token_response`, and 8.3 can be repeated after deleting the line.
- [Spring Security 7 APIs differ from older examples] → Use only the lambda DSL and `PathPatternRequestMatcher`-style matchers. The ITs are the guard.
- [PM doesn't approve the rule wording in time] → Task 7.2 stays unchecked. Nothing else depends on it.

## Migration Plan

1. Section 1 (headers) lands and is checked locally first.
2. The maintainer sets up the Discord applications and the local secret (2.1, 2.3) in parallel with sections 3–5.
3. Production redeploy from `feature/login` (section 8, all `[user]`). The secret is added before `up`, and the gateway is recreated after the branch switch.
4. Rollback: on the server, `git switch master` (the pre-login code), then `$Prod up --build -d --wait` and recreate the gateway. The extra `DISCORD_CLIENT_SECRET` line in `secrets.env` is harmless to the old stack. The new tables stay. Flyway doesn't roll back; the old code doesn't use them.
5. Nothing to migrate: no users exist yet.

## Proposed rule wording (for the PM session; not applied by this change until approved)

- `CLAUDE.md`, replacing "Run the ban and account-age checks at login, before a session is created.":
  > Run the ban and account-age checks at login, before an authenticated session is created.
- `openspec/config.yaml`, replacing "Ban and account-age checks run at login, before a session is created.":
  > Ban and account-age checks run at login, before an authenticated session is created.

Task 7.2 applies the exact wording the maintainer supplies after approval, which may differ from this draft.

## Decision candidates for the PM session

One line each. Not added to `docs/decisions.md` by this change.

- "Before a session is created" means before an **authenticated** session: the pre-login session holds only the OAuth state and is destroyed on rejection.
- Audit rows only for logins where Discord confirmed an identity (accepted, banned, too_new); unidentified failures are logged at WARN. *Why:* with no IPs and no rate limit, anonymous requests could flood the table.
- Separate dev and prod Discord applications; client IDs are committed per environment, and only `DISCORD_CLIENT_SECRET` is a secret. *Why:* the production secret never sits on the laptop.
- Third-party-issued secrets are typed in on the server with `read -s`, never echoed or stored on the maintainer's machine. *Why:* Discord issues the secret, so it can't be generated on the server.
- Registration id `discord`, redirect template `{baseUrl}/login/oauth2/code/{registrationId}` with `/login/` written literally. *Why:* `{action}` can be overridden by a query parameter.
- Tomcat in-memory sessions this weekend: pre-login 10 minutes, signed-in 8 hours idle; a redeploy signs everyone out. Postgres-backed sessions next weekend.
- Session cookie `__Host-SESSION` in production and `SESSION` locally; HttpOnly, SameSite=Lax, Secure from the forwarded scheme, no Max-Age. *Why:* Chrome rejects `__Host-` on http://localhost.
- CSRF with `csrf.spa()` (`XSRF-TOKEN` cookie, `X-XSRF-TOKEN` header); sign-out is `POST /api/logout`, returning 204.
- The api denies by default; signed-out requests get 401 JSON and are never redirected to a login page.
- Discord tokens are dropped after login; the session holds only the Discord ID and display name; `users` stores no name, email or avatar.
- Login failures redirect with fixed codes (`banned`, `too_new`, `error`), and the frontend shows fixed text for them.
- A ban takes effect at the next login; restarting the api applies it immediately by signing everyone out.
- No frontend test runner this weekend; frontend checks are type check plus build.
- Switching the server to `master` after a merge is a routine Redeploy step in the runbook, so a change can be archived on its branch before the merge.
