# Tasks

Conventions for every task:
- `[user]` marks a manual step for the maintainer. Claude Code does not do it. Claude never connects to the server, never runs commands on it, and never creates, reads or prints any `secrets.env`.
- Claude's commands run in Git Bash from the repo root (CLAUDE.md), with `MSYS_NO_PATHCONV=1` for `docker` commands that pass container paths. Commands on the maintainer's Windows machine are **Windows PowerShell 5.1**, run from the repo root: `curl.exe` (never `curl`), `-o NUL`, no `&&`. Server commands are **bash**, run over SSH as `deploy` from `/srv/discosticker`, with `Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'` defined.
- Never run `docker compose config` with `secrets.env`, and never run `docker inspect` on postgres, seaweedfs, bucket-init or api. Config checks pass `secrets.env.example`.
- Write redirect URIs and www.discosticker.cafe as plain text, never as markdown links.
- Placeholders: `<dev-client-id>` and `<prod-client-id>` are the public client IDs from 2.1 (committed in 2.2). `<your-discord-id>` is the maintainer's Discord user ID (Developer Mode → Copy User ID).
- **Tracks:** 2.1 and 2.3 (user) can run at any time before 5.5. Sections 3–5 (Claude) don't need them until 5.5. After 2.2 lands, the local stack doesn't start without 2.3.
- **The local header check** (Git Bash; used in 1.1, 1.2 and 5.5) must print `0`:
  ```bash
  curl -sS -D - -o /dev/null http://localhost:8080/api/health | grep -ciE '^(strict-transport-security|x-content-type-options|referrer-policy|x-frame-options):'
  ```
- **The production header check** (PowerShell; used in 1.1 and 8.5) must print `1` on all 15 lines:
  ```powershell
  $names = 'strict-transport-security','x-content-type-options','referrer-policy','x-frame-options'
  foreach ($p in '/','/api/health','/nope/') {
    $h = curl.exe -sI "https://discosticker.cafe$p" | ForEach-Object { $_.TrimEnd() }
    foreach ($n in $names) { '{0,-12} {1,-26} {2}' -f $p, $n, @($h | Select-String -Pattern "^${n}:").Count }
    '{0,-12} {1,-26} {2}' -f $p, 'hsts = max-age=300', @($h | Select-String -Pattern '^strict-transport-security: max-age=300$').Count
  }
  ```

## 1. Gateway: nginx is the only source of the security headers

- [ ] 1.1 Write the header checks first (design D1).
  - Replace the "Security headers" smoke check in `docs/deploy.md` (the `Select-String` loop that lists the four header lines) with the production header check above, which counts each header and requires HSTS exactly `max-age=300`.
  - Run the local header check against the running local stack.
  - Neither check can fail yet: the api sends no security headers before Spring Security. The local check is the one that would catch the api's headers once 5.4 adds Spring Security, and 5.5 adds a negative control proving the api does send them.
  Verify: the local header check prints `0`. The production header check (PowerShell, run now as a baseline) prints `1` on all 15 lines. `Select-String -Path docs\deploy.md -SimpleMatch -Pattern 'hsts = max-age=300'` prints one line.

- [ ] 1.2 Add `proxy_hide_header` for `Strict-Transport-Security`, `X-Content-Type-Options`, `Referrer-Policy` and `X-Frame-Options` to `gateway/proxy_headers.conf`, with a comment saying nginx is the only source of these headers. The file is included in all four api locations, and it's built into the gateway image, so the stack must be rebuilt.
  Verify: `docker compose --env-file secrets.env up --build -d --wait --wait-timeout 300` exits 0. `MSYS_NO_PATHCONV=1 docker compose --env-file secrets.env exec gateway nginx -T 2>/dev/null | grep -cE '^\s*proxy_hide_header (Strict-Transport-Security|X-Content-Type-Options|Referrer-Policy|X-Frame-Options);'` prints `4`. `curl -fsS http://localhost:8080/api/health` prints `"status":"UP"`. The local header check prints `0`.

## 2. Discord applications and the client secret

- [ ] 2.1 [user] In the Discord Developer Portal (discord.com/developers/applications), create two applications. In each, open OAuth2, add exactly one redirect, and leave "Public Client" off:
  - **DiscoSticker (dev)**, redirect: http://localhost:8080/login/oauth2/code/discord
  - **DiscoSticker.cafe**, redirect: https://discosticker.cafe/login/oauth2/code/discord
  Type each URI character for character, with no trailing slash. Don't add a URI for www.discosticker.cafe: the gateway redirects www to the apex before any login starts. Send Claude both client IDs (they're public). Keep both secrets in the portal for now; 2.3 and 8.3 enter them.
  Verify: each application's OAuth2 page lists exactly one redirect, matching the line above, and "Public Client" is off. Claude has both client IDs.

- [ ] 2.2 Wire the names (design D3):
  - `secrets.env.example` gains `DISCORD_CLIENT_SECRET=change-me-discord-client-secret`.
  - The `api` service in `compose.yaml` gains `DISCORD_CLIENT_SECRET: ${DISCORD_CLIENT_SECRET:?set DISCORD_CLIENT_SECRET in secrets.env}` and `DISCORD_CLIENT_ID: "<dev-client-id>"`.
  - The `api` service in `compose.prod.yaml` gains `DISCORD_CLIENT_ID: "<prod-client-id>"` and `SERVER_SERVLET_SESSION_COOKIE_NAME: __Host-SESSION`.
  - No other service gets the secret.
  Verify (all with `secrets.env.example` only):
  - `docker compose --env-file secrets.env.example config --quiet; echo $?` prints `0`.
  - `grep -v '^DISCORD_CLIENT_SECRET=' secrets.env.example > "$TEMP/no-discord.env"; docker compose --env-file "$TEMP/no-discord.env" config --quiet` fails, and its message names `DISCORD_CLIENT_SECRET`.
  - `docker compose --env-file secrets.env.example config --format json | python -c "import json,sys; s=json.load(sys.stdin)['services']; print(sorted(k for k,v in s.items() if 'DISCORD_CLIENT_SECRET' in (v.get('environment') or {})))"` prints `['api']`.
  - `docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env.example config --format json | python -c "import json,sys; e=json.load(sys.stdin)['services']['api']['environment']; print(e['DISCORD_CLIENT_ID'], e['SERVER_SERVLET_SESSION_COOKIE_NAME'])"` prints `<prod-client-id> __Host-SESSION`.

- [ ] 2.3 [user] Add the dev application's secret to the local `secrets.env` without echoing it. Run in PowerShell from the repo root, after 2.2 has landed:
  ```powershell
  $f = Join-Path (Get-Location) 'secrets.env'
  if (Select-String -Path $f -Pattern '^DISCORD_CLIENT_SECRET=' -Quiet) { Write-Error 'DISCORD_CLIENT_SECRET already set' } else {
    $s = Read-Host -AsSecureString 'DISCORD_CLIENT_SECRET (dev app)'
    $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { $v = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b) }
    $raw = [IO.File]::ReadAllText($f)
    $nl = if ($raw.Length -gt 0 -and -not $raw.EndsWith("`n")) { "`n" } else { '' }
    [IO.File]::AppendAllText($f, "${nl}DISCORD_CLIENT_SECRET=$v`n")
    Remove-Variable v, s, b
  }
  Set-Clipboard -Value ' '
  ```
  Verify: `Compare-Object ((Get-Content secrets.env | Where-Object { $_ -notmatch '^(#|$)' }) -replace '=.*','') ((Get-Content secrets.env.example | Where-Object { $_ -notmatch '^(#|$)' }) -replace '=.*','')` prints nothing. `Select-String -Path secrets.env -Pattern '^DISCORD_CLIENT_SECRET=.' -Quiet` prints `True`.

## 3. Database schema

- [ ] 3.1 Write `SchemaIT` first (design D11). It checks that:
  - the `users` and `login_audit` tables exist;
  - inserting a duplicate `users.discord_id` fails;
  - `login_audit` rejects an outcome other than `accepted` or `rejected`, a reason other than `banned` or `too_new`, an accepted row with a reason, and a rejected row without one;
  - neither table has an email, name, avatar, token or IP column;
  - no table name contains `session`;
  - Flyway reports nothing pending.
  Verify: `cd api && ./mvnw -B verify -Dit.test=SchemaIT` fails on the missing tables.

- [ ] 3.2 Add `api/src/main/resources/db/migration/V1__create_users.sql` and `V2__create_login_audit.sql` as in design D11, and remove `db/migration/.gitkeep`.
  Verify: `cd api && ./mvnw -B verify` passes, including `SchemaIT` and `FlywayMigrationIT`.

## 4. Login rules (unit)

- [ ] 4.1 Write `AccountAgeTest` and `LoginPolicyTest` first (design D4).
  - `AccountAgeTest`:
    - a known Discord ID maps to its creation instant through `(id >> 22) + 1420070400000`;
    - with a fixed `Clock`, an account created 7 days minus 1 ms ago is too new;
    - exactly 7 days old is allowed;
    - a non-numeric ID is rejected.
  - `LoginPolicyTest`:
    - banned gives `banned`;
    - banned and too new gives `banned`;
    - too new gives `too_new`;
    - neither gives accepted.
  Verify: `cd api && ./mvnw -B test -Dtest='AccountAgeTest,LoginPolicyTest'` fails (the classes don't exist yet).

- [ ] 4.2 Implement `AccountAge` and `LoginPolicy` with an injected `Clock` bean.
  Verify: `cd api && ./mvnw -B test -Dtest='AccountAgeTest,LoginPolicyTest'` passes.

## 5. Spring Security, Discord login and login audit

- [ ] 5.1 Add the test support (design D14), with no new test dependencies:
  - `TestcontainersConfiguration` registers a random `DISCORD_CLIENT_ID` and `DISCORD_CLIENT_SECRET` per run.
  - `FakeDiscord`: a JDK `HttpServer` with a token endpoint and a user endpoint. The token endpoint records each request and asserts Basic auth, `grant_type=authorization_code` and the `redirect_uri`. The user endpoint returns a per-test user built from a chosen creation time.
  - `FakeDiscord` registers `token-uri` and `user-info-uri` through a `DynamicPropertyRegistrar`.
  - `SessionRecorder`: a session and attribute listener bean.
  - A `LoginFlow` helper: a JDK `HttpClient` with redirects off and a cookie jar. It starts `/oauth2/authorization/discord`, reads `state`, and calls the callback.
  Verify: `cd api && ./mvnw -B verify` passes; the existing tests are unchanged.

- [ ] 5.2 Write `ApiSecurityIT` first (`specs/discord-login`: signed-out API requests, permitted paths, current user, CSRF, sign-out). It covers:
  - Signed-out `GET /api/me`: 401 with `application/json`, no `Location`, and no session cookie set.
  - Signed-out `GET /api/health`: 200 with `"status":"UP"`.
  - Signed-out `GET /oauth2/x` and `GET /login/oauth2/x`: 401 JSON. `GET /s/x`: 404 JSON.
  - Any `GET /api/me` response sets `XSRF-TOKEN` with Path=/ and without HttpOnly.
  - Signed in through `LoginFlow`:
    - `GET /api/me` returns 200 with exactly the fields `discordId` (a string) and `displayName` (the global name, or the username when the global name is null).
    - `POST /api/logout` without `X-XSRF-TOKEN` returns 403, and the user stays signed in.
    - `GET /api/logout` leaves the user signed in.
    - `POST /api/logout` with the token returns 204, and `/api/me` with the old cookies then returns 401.
  Verify: `cd api && ./mvnw -B verify -Dit.test=ApiSecurityIT` fails.

- [ ] 5.3 Write `DiscordLoginIT` first (`specs/discord-login`: sign-in redirect, state, ban, age, rejected sessions, fixed codes, accepted session, cookie, timeouts, account record, token retention, audit, unidentified failures). It covers:
  - **Authorization redirect:**
    - The Location goes to `https://discord.com/oauth2/authorize`, and its `client_id` is the test client ID.
    - `scope` is exactly `identify`, and `state` is not empty.
    - The decoded `redirect_uri` is `http://localhost:<port>/login/oauth2/code/discord`.
    - With `X-Forwarded-Proto: https`, `X-Forwarded-Host: discosticker.cafe` and `X-Forwarded-Port: 443`, it is `https://discosticker.cafe/login/oauth2/code/discord`, and the session cookie gains Secure. Without them, the cookie has no Secure.
    - The cookie is always HttpOnly, SameSite=Lax and Path=/, with no Domain, Max-Age or Expires.
    - The pre-login session's maximum idle time is 600 s.
  - **Accepted** (account 8 days old):
    - The Location is exactly `/`, and the session cookie value differs from the pre-login one.
    - The session's maximum idle time is 28800 s, and a `users` row has `last_login_at` set.
    - There is exactly one `login_audit` row: `accepted`, no reason.
    - No Discord token is retained: the authorized-client service returns null; no recorded session attribute value contains the fake token; and the captured output doesn't contain it.
  - **Banned** (a `users` row with `banned_at` set):
    - The Location is exactly `/?login=banned`.
    - `SessionRecorder` shows the pre-login session destroyed and `SPRING_SECURITY_CONTEXT` never set, and `/api/me` with every received cookie returns 401.
    - There is exactly one `rejected`/`banned` row, and the `users` row is unchanged.
    - Banned and 1 day old gives `/?login=banned`.
  - **Too new** (6 days old):
    - The Location is exactly `/?login=too_new`, with the same no-session assertions.
    - No `users` row exists, and there is exactly one `rejected`/`too_new` row.
  - **Consent denied** (`error=access_denied` with a valid `state`):
    - The Location is `/?login=error`, with no `/token` request and no audit row.
    - One WARN line is logged, and it doesn't contain the `state` value.
  - **Forged callback** (`?code=forged-code-123&state=forged-state-456`), once with no session and once with a real pre-login session whose issued state differs:
    - The Location is `/?login=error`, with no `/token` request and no audit row.
    - One WARN line is logged, and it contains neither `forged-code-123` nor `forged-state-456`.
  - **Every redirect:** no `Location` contains `jsessionid`, and every failure `Location` is one of the three fixed values.
  Verify: `cd api && ./mvnw -B verify -Dit.test=DiscordLoginIT` fails.

- [ ] 5.4 Implement (design D2, D4–D12):
  - In `api/pom.xml`, add `spring-boot-starter-security-oauth2-client`, plus `spring-boot-starter-security-oauth2-client-test` in test scope.
  - In `application.yml`: the registration and provider (D2), `server.servlet.session.timeout: 10m`, the session cookie settings (D7) and `tracking-modes: cookie`.
  - `SecurityConfig`: the authorization rules and 401 JSON entry point (D8), `NullRequestCache`, `csrf.spa()`, logout at `/api/logout` returning 204, `oauth2Login` with the user service wired explicitly, the no-op authorized-client repository, and the success and failure handlers.
  - Classes: `DiscordOAuth2UserService`, `LoginRejectedException`, `LoginAuditWriter` and `UserAccounts` (both on `JdbcClient`), the success handler (upsert, audit, 8 h idle, redirect `/`), the failure handler (audit or WARN, invalidate the session, redirect with a fixed code), and `MeController`.
  Verify: `cd api && ./mvnw -B verify` passes, including `HealthEndpointIT`, `FlywayMigrationIT`, `SeaweedS3IT`, `SchemaIT`, `ApiSecurityIT` and `DiscordLoginIT`.

- [ ] 5.5 Check through the running local stack. This needs 2.3 done.
  Verify:
  - `docker compose --env-file secrets.env up --build -d --wait --wait-timeout 300` exits 0, and `curl -fsS http://localhost:8080/api/health` prints `"status":"UP"`.
  - The local header check prints `0`.
  - Negative control, proving the api itself sends two of the headers: `docker compose --env-file secrets.env exec api wget -S -O /dev/null http://localhost:8080/api/health 2>&1 | grep -ciE '^\s*x-(frame-options|content-type-options):'` prints `2`.
  - `curl -sS -o /dev/null -w '%{redirect_url}\n' http://localhost:8080/oauth2/authorization/discord | sed 's/%3A/:/gI; s/%2F/\//gI'` prints a URL starting `https://discord.com/oauth2/authorize?` that contains `redirect_uri=http://localhost:8080/login/oauth2/code/discord`, `scope=identify` and `client_id=<dev-client-id>`.
  - `for p in /api/me /oauth2/x /login/oauth2/x /s/x; do curl -sS -o /dev/null -w "$p %{http_code} %{content_type}\n" "http://localhost:8080$p"; done` prints 401, 401, 401 and 404, each with `application/json`.

## 6. Frontend

- [ ] 6.1 Implement `frontend/lib/session.ts` and make `frontend/app/page.tsx` a client page (design D13):
  - `getMe()`
  - `signOut()`, which POSTs `/api/logout` with `X-XSRF-TOKEN` read from the `XSRF-TOKEN` cookie
  - `loginMessage(code)`, a fixed map for `banned`, `too_new` and `error`, with a generic default
  - The page shows a plain `<a href="/oauth2/authorization/discord">` link when signed out, and the display name with a sign-out button when signed in.
  - The `login` parameter is read once and passed only to `loginMessage()`.
  - There is no frontend test runner; the mapping is checked in review.
  Verify: `cd frontend && npm ci && npm run typecheck && npm run build` passes. `grep -rnE 'dangerouslySetInnerHTML|innerHTML|outerHTML|document\.write' frontend/app frontend/lib` prints nothing. `grep -rnE 'location\.search|URLSearchParams|useSearchParams' frontend/app frontend/lib` prints exactly one line, and review confirms that its value reaches the page only through `loginMessage()`, whose default branch returns the generic text.

- [ ] 6.2 [user] Sign in end to end on the local stack, in a browser at http://localhost:8080 (not 127.0.0.1):
  1. Sign in with the dev application. Your display name shows. Reload, and you're still signed in.
  2. Sign out; the sign-in link shows.
  3. Open `http://localhost:8080/?login=%3Cscript%3Ealert(1)%3C/script%3E`: the generic failure message shows, with no alert and no script text.
  4. Ban yourself (`docker compose --env-file secrets.env exec postgres psql -U discosticker -d discosticker -c "update users set banned_at = now() where discord_id = <your-discord-id>"`), sign in again, and see the "banned" message. Then unban (`... set banned_at = null ...`).
  5. Sign in, run `docker compose --env-file secrets.env restart api`, reload, and you're signed out.
  Verify (PowerShell): `docker compose --env-file secrets.env exec postgres psql -U discosticker -d discosticker -c "select outcome, reason, occurred_at from login_audit order by id"` shows `accepted` rows and one `rejected | banned` row, and every step above behaved as described.

## 7. Runbook and rules

- [ ] 7.1 Update `docs/deploy.md`:
  - **Create secrets.env:** a step for third-party secrets: `DISCORD_CLIENT_SECRET` copied from the prod application's OAuth2 page, entered with `IFS= read -rs` on the server, then the Windows clipboard cleared with `Set-Clipboard -Value ' '` (and clipboard history if it's on). It is never written to a file on Windows.
  - **Redeploy:**
    - Deploying from a feature branch takes the hash with `git ls-remote origin refs/heads/<branch>`.
    - Add any new secret before `up`.
    - Recreate the gateway after any branch switch.
    - A routine "after a merge, switch to `master`" step.
  - **Smoke checks:** login checks (the authorization redirect's `redirect_uri`, `scope` and `client_id`; the `__Host-SESSION` attributes; `/api/me` 401 JSON; `/oauth2/x`, `/login/oauth2/x` and `/s/x`), alongside the header check from 1.1. Browse at http://localhost:8080, not 127.0.0.1, locally.
  - **Bans:**
    - `update users set banned_at = now() where discord_id = <id>`, or an insert for an account that has never signed in.
    - A ban takes effect at the next login. `$Prod restart api` applies it immediately and signs everyone out.
    - Unbanning sets `banned_at = null`.
  - **Known limitation:** nginx's access log in Docker's container logs holds visitor IPv4 addresses, with no rotation yet (next weekend).
  Every code block is labelled `bash` or `powershell`.
  Verify (PowerShell; each prints nothing unless noted):
  - `$f = $null; Get-Content docs\deploy.md | ForEach-Object { if ($_ -match '^```(\w*)$') { if ($null -ne $f) { $f = $null } else { $f = $matches[1] } } elseif ($null -ne $f) { if ($_ -match '(\$Prod|docker compose)\b.*\sconfig\b' -or $_ -match 'docker inspect') { "config/inspect: $_" }; if ($f -eq 'powershell' -and ($_ -match '&&' -or $_ -match '(^|[\s(;])curl\s' -or $_ -match '/dev/null' -or $_ -match '\|\s*(grep|sed|awk|cut)\b' -or $_ -match '^\s*export\s')) { "powershell: $_" }; if ($f -notin 'bash','powershell') { "unlabelled fence: $_" } } }`
  - `Select-String -Path docs\deploy.md -Pattern '(PASSWORD|KEY|SECRET)=[^<\s$"%]'`
  - `Get-ChildItem -Recurse -File docs\deploy.md,openspec\changes\add-discord-login | Select-String -Pattern '\]\([^)]*(www\.discosticker|/login/oauth2/code/)|<https?://(www\.discosticker|[^>]*/login/oauth2/code/)'`
  - `Select-String -Path docs\deploy.md -SimpleMatch -Pattern 'read -rs','DISCORD_CLIENT_SECRET','Set-Clipboard','git ls-remote origin refs/heads/','banned_at','restart api','hsts = max-age=300','__Host-SESSION','http://localhost:8080'` prints at least one line for each pattern.

- [ ] 7.2 Apply the rule wording to `CLAUDE.md` and `openspec/config.yaml` ("before an authenticated session is created"). **Needs approval:** do this only after the maintainer confirms PM approval, and use the exact wording the maintainer provides; design.md's "Proposed rule wording" is only a draft. Until then, leave this task unchecked. Nothing else depends on it.
  Verify: `Select-String -Path CLAUDE.md,openspec\config.yaml -SimpleMatch -Pattern 'before a session is created'` prints nothing. `Select-String -Path CLAUDE.md,openspec\config.yaml -SimpleMatch -Pattern 'before an authenticated session is created'` prints one line from each file (or the approved wording's equivalent phrase).

## 8. Production redeploy (all [user])

- [ ] 8.1 [user] Push `feature/login`, then take GitHub's hash on Windows: `git ls-remote origin refs/heads/feature/login`.
  Verify: one line with a hash that equals `git rev-parse HEAD` on Windows for the pushed commit.

- [ ] 8.2 [user] On the server:
  ```bash
  cd /srv/discosticker
  Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'
  git fetch origin
  git switch feature/login
  git pull --ff-only
  git rev-parse HEAD
  ```
  Verify: the last line equals the hash from 8.1. If not, stop.

- [ ] 8.3 [user] Add the production secret, before any `up` (design D15). In the Developer Portal, open the prod application's OAuth2 page, reset or reveal the secret and copy it, then on the server:
  ```bash
  cd /srv/discosticker
  if grep -q '^DISCORD_CLIENT_SECRET=' secrets.env; then echo "DISCORD_CLIENT_SECRET already set" >&2; else
    IFS= read -rs -p 'DISCORD_CLIENT_SECRET (prod app): ' v; echo
    printf 'DISCORD_CLIENT_SECRET=%s\n' "$v" >> secrets.env; unset v
  fi
  ```
  Then, on Windows: `Set-Clipboard -Value ' '`. If clipboard history is on, clear it too (Settings → System → Clipboard → Clear).
  Verify (server): `stat -c '%U:%G %a' secrets.env` prints `deploy:deploy 600`. `diff <(cut -d= -f1 secrets.env) <(grep -Ev '^(#|$)' secrets.env.example | cut -d= -f1); echo $?` prints only `0`. `git check-ignore -q secrets.env; echo $?` prints `0`.

- [ ] 8.4 [user] Build and start. The gateway rebuilds because `proxy_headers.conf` is in its image. Recreate the gateway because the branch changed:
  ```bash
  $Prod up --build -d --wait --wait-timeout 900; echo "exit=$?"
  $Prod up -d --force-recreate --no-deps --wait gateway
  $Prod exec gateway ls /etc/nginx/conf.d
  $Prod exec gateway nginx -t -q; echo "nginx-t=$?"
  $Prod ps -a --format '{{.Service}} | {{.Status}}'
  ```
  If the build runs out of memory, use the runbook's one-at-a-time fallback.
  Verify: `exit=0`; `discosticker.conf` is listed; `nginx-t=0`; gateway, api, postgres and seaweedfs are `(healthy)`; bucket-init is `Exited (0)`.

- [ ] 8.5 [user] Smoke checks from Windows (PowerShell).
  - The production header check (Conventions) prints `1` on all 15 lines.
  - Then the login redirect and cookie, each attribute checked separately (replace `<prod-client-id>`):
    ```powershell
    $r = curl.exe -sS -D - -o NUL https://discosticker.cafe/oauth2/authorization/discord | ForEach-Object { $_.TrimEnd() }
    $loc = [uri]::UnescapeDataString(($r | Select-String -Pattern '^location: (.+)$').Matches[0].Groups[1].Value)
    'authorize      ' + ($loc -match '^https://discord\.com/oauth2/authorize\?')
    'redirect_uri   ' + ($loc -match '[?&]redirect_uri=https://discosticker\.cafe/login/oauth2/code/discord(&|$)')
    'scope          ' + ($loc -match '[?&]scope=identify(&|$)')
    'client_id      ' + ($loc -match '[?&]client_id=<prod-client-id>(&|$)')
    $c = ($r | Select-String -Pattern '^set-cookie: __Host-SESSION=').Line
    foreach ($a in 'Path=/','Secure','HttpOnly','SameSite=Lax') { '{0,-15}{1}' -f $a, ($c -match (';\s*' + [regex]::Escape($a) + '(;|$)')) }
    'no Domain/Max-Age/Expires ' + (($null -ne $c) -and ($c -notmatch ';\s*(Domain|Max-Age|Expires)='))
    foreach ($p in '/api/me','/oauth2/x','/login/oauth2/x','/s/x') { curl.exe -sS -o NUL -w "$p %{http_code} %{content_type}`n" "https://discosticker.cafe$p" }
    ```
  Verify: the header check prints `1` on all 15 lines. The next nine lines (four for the redirect, four cookie attributes, and the no-Domain/Max-Age/Expires check) all print `True`. The last four lines print `/api/me 401`, `/oauth2/x 401`, `/login/oauth2/x 401` and `/s/x 404`, each with `application/json`.

- [ ] 8.6 [user] Sign in end to end in production:
  1. Open https://www.discosticker.cafe, choose sign in, and approve. You end on https://discosticker.cafe signed in, with your display name shown.
  2. Sign out.
  3. On the server, ban yourself: `$Prod exec postgres psql -U discosticker -d discosticker -c "update users set banned_at = now() where discord_id = <your-discord-id>"`. Sign in again, and see the "banned" message.
  4. Unban (`... set banned_at = null ...`), then sign in once more.
  Verify (server): `$Prod exec postgres psql -U discosticker -d discosticker -c "select outcome, reason, occurred_at from login_audit order by id desc limit 5"` shows, newest first, `accepted`, `rejected | banned`, `accepted`. `$Prod logs --since 30m api | grep -ciE 'access_token|client_secret'` prints `0`.

## Workflow follow-up

- Take design.md's "Decision candidates for the PM session" and "Proposed rule wording" to the PM session. `docs/decisions.md` is edited only when the maintainer asks; 7.2 waits for approved wording.
- Archive the change (`/opsx:archive`) on `feature/login` once section 8 passes and 7.2 is done or explicitly deferred, then open the PR to `master` and merge it with a merge commit.
- After the merge, switch the server to `master` using the runbook's routine Redeploy step (taking GitHub's `master` hash from Windows, then recreating the gateway). This is not a task in this change.
