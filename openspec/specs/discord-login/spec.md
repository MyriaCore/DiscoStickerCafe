# discord-login Specification

## Purpose

Lets visitors sign in with their Discord account. It enforces the ban and account-age rules before an authenticated session exists, keeps a minimal account record and an audit trail of identified login attempts, and gives the frontend a signed-in session it can read and end.

## Requirements

### Requirement: Sign-in starts with a Discord authorization redirect
A request to `/oauth2/authorization/discord` SHALL redirect to `https://discord.com/oauth2/authorize`. The redirect SHALL carry the client ID of the environment's Discord application, the scope `identify` only, a `state` value, and a `redirect_uri` equal to the client-facing origin followed by `/login/oauth2/code/discord`.

#### Scenario: Local redirect URI
- **WHEN** a client requests `GET http://localhost:8080/oauth2/authorization/discord` from the local gateway
- **THEN** the response is a redirect to `https://discord.com/oauth2/authorize` whose decoded `redirect_uri` is `http://localhost:8080/login/oauth2/code/discord`

#### Scenario: Production redirect URI
- **WHEN** a client requests `GET https://discosticker.cafe/oauth2/authorization/discord`
- **THEN** the response is a redirect to `https://discord.com/oauth2/authorize` whose decoded `redirect_uri` is `https://discosticker.cafe/login/oauth2/code/discord`, and whose `client_id` is the production application's

#### Scenario: Identify scope only
- **WHEN** the authorization redirect is issued
- **THEN** its `scope` parameter is exactly `identify`, and it includes a non-empty `state`

### Requirement: Login callback validates state
The login callback at `/login/oauth2/code/discord` SHALL accept a response only when its `state` matches the one issued to the same browser session. Otherwise, or when Discord returns an error (including denied consent), the login SHALL fail without contacting Discord's token endpoint.

#### Scenario: State mismatch
- **WHEN** the callback is requested with a `state` that was not issued to this browser session
- **THEN** no request is made to Discord's token endpoint, and the browser is redirected to `/?login=error`

#### Scenario: Consent denied
- **WHEN** Discord redirects back with `error=access_denied` and a valid `state`
- **THEN** the browser is redirected to `/?login=error` and is not signed in

### Requirement: Banned accounts are rejected at login
A login SHALL be rejected when the Discord account is marked as banned in the account record. The check SHALL run after Discord confirms the account's identity and before an authenticated session is created.

#### Scenario: Banned account
- **WHEN** a Discord account marked as banned completes the Discord authorization
- **THEN** the browser is redirected to `/?login=banned` and is not signed in

#### Scenario: Banned outranks too new
- **WHEN** an account that is both banned and less than 7 days old completes the Discord authorization
- **THEN** the browser is redirected to `/?login=banned`

### Requirement: Accounts less than 7 days old are rejected at login
A login SHALL be rejected when the Discord account is less than 7 days old, measured from the creation time encoded in its Discord ID (`(id >> 22) + 1420070400000` milliseconds since the Unix epoch) to the server's current time. The check SHALL need no extra Discord API call and SHALL run before an authenticated session is created.

#### Scenario: Account just under 7 days old
- **WHEN** an account created 7 days minus one millisecond ago completes the Discord authorization
- **THEN** the browser is redirected to `/?login=too_new` and is not signed in

#### Scenario: Account exactly 7 days old
- **WHEN** an account created exactly 7 days ago, and not banned, completes the Discord authorization
- **THEN** the login is accepted

### Requirement: A rejected login leaves no authenticated session
When a login is rejected for any reason, the pre-login session that held the OAuth state SHALL be destroyed. No session SHALL ever hold the rejected user's identity.

#### Scenario: Rejected login then API call
- **WHEN** a login is rejected and the browser then requests `GET /api/me` with every cookie it received during the attempt
- **THEN** the response is 401

#### Scenario: Pre-login session destroyed
- **WHEN** a login is rejected
- **THEN** the session created for the authorization request has been invalidated, and at no point during the attempt was a signed-in user stored in any session

### Requirement: Login failure redirects use fixed reason codes
After a failed login, the browser SHALL be redirected to `/?login=<code>`, where `<code>` is exactly one of `banned`, `too_new` or `error`. The redirect SHALL NOT include exception messages, provider error text, or any other request-derived value.

#### Scenario: Only fixed codes in Location
- **WHEN** a login fails because the account is banned, too new, Discord returned an error, or the state was missing or invalid
- **THEN** the `Location` header is exactly `/?login=banned`, `/?login=too_new` or `/?login=error`

### Requirement: An accepted login starts a new session
An accepted login SHALL issue a session ID different from the pre-login session's ID, store the signed-in user in that session, and redirect the browser to `/`. The redirect target SHALL NOT be taken from the request.

#### Scenario: Session ID changes at login
- **WHEN** an eligible account completes the Discord authorization
- **THEN** the response sets a session cookie whose value differs from the pre-login session cookie, and redirects to `/`

### Requirement: Session cookie attributes
The session cookie SHALL be HttpOnly, SameSite=Lax and Path=/, with no Domain, Max-Age or Expires attribute. In production it SHALL be named `__Host-SESSION` and be Secure. Locally (plain HTTP) it SHALL be named `SESSION`. The session ID SHALL never appear in a URL.

#### Scenario: Production cookie
- **WHEN** a client requests `GET https://discosticker.cafe/oauth2/authorization/discord`
- **THEN** the response sets `__Host-SESSION` with the attributes Path=/, Secure, HttpOnly and SameSite=Lax, and without Domain, Max-Age or Expires

#### Scenario: No session ID in URLs
- **WHEN** any response redirects within the site
- **THEN** its `Location` contains no `jsessionid` path parameter

### Requirement: Session idle timeouts
A pre-login session SHALL expire after 10 minutes without requests. A signed-in session SHALL expire after 8 hours without requests.

#### Scenario: Signed-in idle timeout
- **WHEN** a login is accepted
- **THEN** the new session's maximum idle time is 8 hours

#### Scenario: Pre-login idle timeout
- **WHEN** a session is created for an authorization request
- **THEN** its maximum idle time is 10 minutes

### Requirement: Signed-out API requests get 401 JSON
A signed-out request to any `/api/` path other than `/api/health` SHALL receive 401 with a JSON body and no `Location` header. It SHALL never be redirected to a login page, and it SHALL NOT create a session.

#### Scenario: Signed-out current user
- **WHEN** a client with no session requests `GET /api/me`
- **THEN** the response is 401 with a JSON body, has no `Location` header, and sets no session cookie

#### Scenario: Health stays public
- **WHEN** a client with no session requests `GET /api/health`
- **THEN** the response is 200 with `"status":"UP"`

### Requirement: Paths outside the permitted set are denied
The api SHALL permit signed-out access only to `/api/health`, `/s/**`, and the two login endpoints. Every other path SHALL require sign-in, and a signed-out request to it SHALL receive 401 JSON. A request to an unhandled path under `/s/` SHALL receive the api's 404 JSON.

#### Scenario: Unhandled prefixed paths while signed out
- **WHEN** a client with no session requests `GET /oauth2/x`, `GET /login/oauth2/x` and `GET /s/x`
- **THEN** the first two responses are 401 with a JSON body, and the third is 404 with a JSON body

### Requirement: Current user endpoint
`GET /api/me` SHALL return the signed-in user as JSON with exactly two fields: `discordId` (a string) and `displayName` (Discord's global name, or the username when there is none). It SHALL NOT expose database keys.

#### Scenario: Signed in
- **WHEN** a signed-in client requests `GET /api/me`
- **THEN** the response is 200 with a JSON body whose only fields are `discordId` and `displayName`

### Requirement: CSRF protection on state-changing API requests
State-changing requests (`POST`, `PUT`, `PATCH`, `DELETE`) SHALL require an `X-XSRF-TOKEN` header that matches the token in the `XSRF-TOKEN` cookie. The api SHALL set that cookie on API responses, readable by page scripts (not HttpOnly), with Path=/.

#### Scenario: Token cookie issued
- **WHEN** a client requests `GET /api/me`, signed in or not
- **THEN** the response sets an `XSRF-TOKEN` cookie without HttpOnly

#### Scenario: Missing token
- **WHEN** a signed-in client sends `POST /api/logout` without an `X-XSRF-TOKEN` header
- **THEN** the response is 403 and the session stays signed in

### Requirement: Sign-out
`POST /api/logout` with a valid CSRF token SHALL invalidate the session and respond 204 with no redirect. Sign-out SHALL NOT be possible with `GET`.

#### Scenario: Sign out
- **WHEN** a signed-in client sends `POST /api/logout` with a valid `X-XSRF-TOKEN` header, then requests `GET /api/me` with its old cookies
- **THEN** the first response is 204 and the second is 401

#### Scenario: GET does not sign out
- **WHEN** a signed-in client requests `GET /api/logout`
- **THEN** the session stays signed in

### Requirement: Minimal account record
An accepted login SHALL create or update an account record holding only the Discord ID, the first-seen time, the last-login time and the ban marker. A rejected login SHALL NOT create or change an account record. The system SHALL NOT store the user's email, username, display name, avatar, Discord tokens or IP address.

#### Scenario: First accepted login
- **WHEN** an eligible account signs in for the first time
- **THEN** one account record exists for its Discord ID with a last-login time, and no other user data is stored

#### Scenario: Too-new account
- **WHEN** an account less than 7 days old completes the Discord authorization
- **THEN** no account record is created for it

### Requirement: Discord tokens are not retained
The Discord access and refresh tokens SHALL be used only to read the user's identity during login. After that they SHALL NOT be kept in the session, in memory, in the database or in logs.

#### Scenario: After an accepted login
- **WHEN** a login is accepted
- **THEN** no stored authorized-client entry, session attribute, database row or log line contains the Discord access or refresh token

### Requirement: Login audit records for identified login attempts
Each login where Discord confirmed the account's identity SHALL write exactly one audit record. The record holds the Discord ID, the outcome (`accepted` or `rejected`), the reason (`banned` or `too_new` when rejected, empty when accepted) and the time. It SHALL NOT contain an IP address.

#### Scenario: Accepted login audited
- **WHEN** an eligible account signs in
- **THEN** exactly one audit record exists for that attempt with outcome `accepted` and no reason

#### Scenario: Banned login audited
- **WHEN** a banned account completes the Discord authorization
- **THEN** exactly one audit record exists for that attempt with outcome `rejected` and reason `banned`

#### Scenario: Too-new login audited
- **WHEN** an account less than 7 days old completes the Discord authorization
- **THEN** exactly one audit record exists for that attempt with outcome `rejected` and reason `too_new`

### Requirement: Unidentified login failures are logged, not audited
A login failure that happens before Discord confirms an identity (a provider error, denied consent, or a missing or invalid state) SHALL write no audit record. It SHALL write one application log line at WARN level naming the failure kind. The line SHALL NOT include the authorization code, the state value or the client's IP address.

#### Scenario: Forged callback
- **WHEN** a client with no session requests `/login/oauth2/code/discord?code=x&state=y`
- **THEN** no audit record is written, a WARN line is logged, and the browser is redirected to `/?login=error`

### Requirement: Home page shows sign-in state
The home page SHALL show a sign-in link to `/oauth2/authorization/discord` when `GET /api/me` returns 401. It SHALL show the display name and a sign-out button when signed in. Sign-out SHALL send `POST /api/logout` with the `X-XSRF-TOKEN` header.

#### Scenario: Signed out
- **WHEN** a visitor with no session opens the home page
- **THEN** a sign-in link to `/oauth2/authorization/discord` is shown

#### Scenario: Signed in
- **WHEN** a signed-in visitor opens the home page
- **THEN** their display name and a sign-out button are shown, and choosing sign-out returns the page to the signed-out state

### Requirement: Login failure messages are fixed text
The home page SHALL map the `login` query parameter's codes `banned`, `too_new` and `error` to fixed messages, and any other value to a generic failure message. It SHALL NOT render the parameter's raw value.

#### Scenario: Unknown code
- **WHEN** a visitor opens `/?login=<script>alert(1)</script>`
- **THEN** the page shows the generic failure message, and the parameter's value does not appear in the page
