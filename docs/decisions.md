# Decisions

One line per decision, with the reason. Project-wide rules are enforced in `openspec/config.yaml` and `CLAUDE.md`; this file records why.

## Architecture

- **2026-10-10** — Backend is Spring Boot with Spring Security, not Express. *Why:* hands-on Spring Security experience securing image upload.
- **2026-10-10** — One origin at discosticker.cafe behind an nginx gateway; every service runs under docker compose. *Why:* the browser talks only to nginx, so there's no CORS to configure.
- **2026-10-10** — The frontend is a Next.js static export served by nginx. *Why:* it can later become a Next.js server container behind the same nginx without changing routes.
- **2026-10-10** — Postgres stores users, stickers and audit records.
- **2026-10-10** — Local object storage uses SeaweedFS's S3 endpoint, not MinIO; the app talks only to the S3 API, configured by endpoint, credentials and path-style setting. *Why:* MinIO's free images were pulled in September 2026, and using only the S3 API makes the move to S3 in production a configuration change.
- **2026-10-10** — The walking skeleton includes a bare Next.js static export served by nginx; page content and API calls come with the login change. *Why:* nginx routing is proven before OAuth, so a failed Discord redirect can't be a routing problem.
- **2026-10-10** — Toolchain: Maven wrapper, Java 25, Spring Boot 4.1.1, AWS SDK BOM 2.55.14, Next.js 16.4 and Node 24.
- **2026-10-10** — TypeScript is pinned to `~5.9.3` until Next.js fully supports a newer major version.
- **2026-10-10** — The Next.js export is built into the gateway image with `trailingSlash: true` and no fallback to `index.html`. *Why:* one fewer container, and unknown paths return 404 instead of a 200 HTML page.
- **2026-10-10** — A one-shot bucket-init container creates the bucket and must succeed before the api starts. *Why:* failures are loud, and the storage keys are checked before the app runs.
- **2026-10-10** — Spring uses `forward-headers-strategy=native` behind nginx; the login change proves it end to end. *Why:* Tomcat then trusts forwarded headers only from internal proxy addresses.
- **2026-10-10** — Production runs on a new Vultr instance, separate from the myriaco.re server, using the same docker compose stack.
- **2026-10-10** — This weekend, production storage is SeaweedFS on that server, reachable only on the internal network; AWS S3 replaces it later as a configuration change plus a data copy.
- **2026-10-10** — Production is a Vultr vc2-2c-4gb instance (2 vCPU, 4 GB, Debian 13) running the same compose stack plus `compose.prod.yaml`; images build on the server from a clone of the public repo, with no registry or CI.
- **2026-10-10** — Production and local nginx share one routes include; `compose.prod.yaml` mounts the production server config from the checkout. *Why:* routes can't drift between local and production.
- **2026-10-10** — Long-running services restart `unless-stopped`; bucket-init stays one-shot. *Why:* the stack comes back after a reboot with no manual steps, without bucket-init restarting in a loop.
- **2026-10-10** — nginx serves HTTPS with a Let's Encrypt certificate for `discosticker.cafe` and `www.discosticker.cafe`, issued and renewed by Debian's certbot through the running gateway (webroot), and registered without an email address. *Why:* Let's Encrypt no longer sends expiry emails, and renewal is automatic.
- **2026-10-10** — `www.discosticker.cafe` redirects (301) to `https://discosticker.cafe`; the GoDaddy `www` CNAME stays.
- **2026-10-10** — The renewal hook reloads the gateway and is quiet on success. *Why:* certbot labels any stderr as an error, and its log is the only place a failed renewal shows up while there's no monitoring.

## Security

- **2026-10-10** — Uploading requires Discord sign-in. *Why:* every upload can be audited and tied to an account that can be banned.
- **2026-10-10** — Login rejects banned accounts and Discord accounts less than 7 days old, before an authenticated session is created; the pre-login session holds only the OAuth state and is destroyed when a login is rejected. *Why:* it blocks banned users and throwaway accounts made just to upload.
- **2026-10-10** — Until admin ban-and-purge exists, bans are set by hand in the database, with no ban UI or ban audit events. *Why:* admin tooling is next weekend's work.
- **2026-10-10** — Uploads are typed from their content (magic bytes), size-limited at nginx and in Spring, dimension-checked before full decode, and re-encoded to PNG. *Why:* security is a core goal of the project, not polish.
- **2026-10-10** — Original upload bytes are never stored or served; the audit record keeps their SHA-256. *Why:* only the re-encoded image is ever served.
- **2026-10-10** — Public sticker IDs are 128-bit random values in base64url, and database keys never appear in URLs. *Why:* sticker links can't be guessed or enumerated.
- **2026-10-10** — Audit records cover logins and uploads, accepted and rejected. *Why:* a refused account or upload still leaves a trace.
- **2026-10-10** — Login audit rows are written only when Discord confirmed an identity (accepted, banned, too new); provider errors and missing or invalid OAuth state go to the application log only. *Why:* with no IPs and no rate limit, anyone could otherwise flood the audit table anonymously.
- **2026-10-10** — Login rejections reach the page only as fixed reason codes (`banned`, `too_new`, `error`) mapped to fixed messages; the raw `?login=` value is never rendered. *Why:* rendering it would let a crafted link inject text into the page.
- **2026-10-10** — Login uses separate dev and prod Discord applications with registration id `discord`; the registered redirect URIs are `http://localhost:8080/login/oauth2/code/discord` (dev) and `https://discosticker.cafe/login/oauth2/code/discord` (prod). Only `DISCORD_CLIENT_SECRET` goes in `secrets.env`, and only the api receives it. *Why:* the production client secret only ever exists on the server.
- **2026-10-10** — Sessions are Tomcat's in-memory sessions this weekend: pre-login sessions expire after 10 minutes, signed-in sessions after 8 hours idle, and a redeploy signs everyone out. A hand ban takes effect at the user's next login. *Why:* Postgres-backed sessions cost about 30 minutes this weekend; they move to next weekend.
- **2026-10-10** — Secrets live only in a gitignored `secrets.env` and reach containers as environment variables through docker compose; a committed `secrets.env.example` lists the variable names. *Why:* the repo is public, and a secret pushed once stays in its history.
- **2026-10-10** — Each service gets only the secrets it needs, through compose `${VAR:?}` interpolation; `env_file: secrets.env` is never used. *Why:* least privilege, and a missing key stops startup.
- **2026-10-10** — `secrets.env` holds only secrets; the bucket name, region and endpoint live in compose or `application.yml`.
- **2026-10-10** — Claude Code's file tools are denied reading and editing `secrets.env`, and `CLAUDE.md` forbids reading or printing it any other way. *Why:* anything Claude reads ends up in the session transcript.
- **2026-10-10** — Claude Code never runs `docker compose config` with `secrets.env`, or `docker inspect` on services that hold secrets. *Why:* both print secret values into the transcript.
- **2026-10-10** — In production, `secrets.env` is created by hand on the server in the deploy checkout, owned by the deploy user with mode 600, with values generated on the server, or, when a third party issues them (Discord's client secret), typed in by hand on the server without echo (`read -rs`), never on the maintainer's machine; it is never committed, copied into an image, or handled by Claude Code, and Claude never connects to the server. Vault stays out of scope. *Why:* the secrets are generated where they're used, so they never travel or appear in a transcript. (Replaces the earlier "decided with hosting" entry.)
- **2026-10-10** — Compose requires the storage keys, and a test proves anonymous and wrong-key requests are rejected. *Why:* SeaweedFS accepts anonymous requests when its keys are missing, and storage must fail closed.
- **2026-10-10** — SeaweedFS runs `weed mini` 4.48 with WebDAV, the admin UI, telemetry and bucket auto-creation off; its always-on admin server serves only `/health` and `/metrics`, on the internal network. *Why:* fewer exposed services, and no buckets created by accident.
- **2026-10-10** — Only the gateway publishes a port; Postgres, SeaweedFS and the api stay on the internal network.
- **2026-10-10** — The Vultr firewall allows 80 and 443 from anywhere (IPv4 and IPv6) and 22 only from the maintainer's home IPv4; key-only SSH stays on, with Vultr's web console as the fallback. *Why:* only the site is public, and password-guessing bots can't reach SSH at all.
- **2026-10-10** — ufw stays active on the server and allows 22, 80 and 443 for IPv4 and IPv6; the Vultr firewall is the perimeter and ufw is the host backstop. *Why:* Vultr's image shipped ufw active, and its IPv6 rules are what let IPv6 traffic reach `docker-proxy`.
- **2026-10-10** — SSH is key-only with root login disabled, set in a `00-hardening.conf` drop-in. The `deploy` user is in the `sudo` and `docker` groups: it runs Docker and `$Prod` directly and uses `sudo` for system changes. *Why:* membership in `docker` is root-equivalent, which is accepted because the only way in is key-only SSH as `deploy` from one IPv4 address.
- **2026-10-10** — TLS 1.2 and 1.3 only; TLS by bare IP or for an unknown host is refused (`ssl_reject_handshake`), and plain-HTTP requests for unknown hosts are closed; no OCSP stapling. *Why:* Let's Encrypt ended OCSP in 2025.
- **2026-10-10** — The gateway sends four fixed security headers with `always`: HSTS `max-age=300` (no includeSubDomains or preload), `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY`; no Content-Security-Policy this weekend. HSTS is raised once HTTPS has been stable. *Why:* a long HSTS max-age is hard to undo if something goes wrong.
- **2026-10-10** — nginx is the only source of those security headers; the login change hides any copies the api sends (`proxy_hide_header`). *Why:* Spring Security's default one-year HSTS would otherwise override `max-age=300`.
- **2026-10-10** — Docker updates are manual: Docker's apt repository is not an allowed origin for unattended-upgrades. unattended-upgrades installs Debian's security and stable point-release updates (allowed origins: trixie `label=Debian`, trixie `label=Debian-Security`, trixie-security). *Why:* Debian stable updates are conservative fixes, and manual Docker updates avoid surprise daemon restarts.

## Process

- **2026-10-10** — Every change goes through OpenSpec: explore → propose → apply → archive.
- **2026-10-10** — Project rules live in `openspec/config.yaml` and `CLAUDE.md`, and any rule change updates both files.
- **2026-10-10** — Proposals state what's out of scope, designs list the threats considered, and every task ends with a `Verify:` line. *Why:* security requirements become verified tasks, not just claims.
- **2026-10-10** — The project context spells out what a Next.js static export can't do (SSR, API routes, middleware, image optimization). *Why:* otherwise Claude Code reaches for them by habit, and they fail at build time.
- **2026-10-10** — Work test-first where practical; every task states how it's verified.
- **2026-10-10** — Each feature or large effort gets its own branch, which may hold several OpenSpec changes, and merges to `master` by pull request. The initial commit and the two setup commits were the only direct pushes to `master`.
- **2026-10-10** — The repo enforces LF line endings with `.gitattributes`. *Why:* shell scripts with CRLF endings fail inside Linux containers.
- **2026-10-10** — The repo is public on GitHub from the first commit. *Why:* this is a portfolio project.
- **2026-10-10** — Decisions are approved in the maintainer's PM session before they go in this file, and Claude Code edits it only when asked. If a change would contradict a recorded decision, Claude Code stops and asks.
- **2026-10-10** — Pull requests merge with a merge commit, not squash, and each change is archived on its branch before the merge. *Why:* the history keeps each change's trail from proposal to verified code.
- **2026-10-10** — Deploys run from the feature branch before it merges; the server switches to `master` after the merge.
- **2026-10-10** — `add-production-deploy` is archived as the first commit on the login branch. *Why:* its last task (switching the server to `master`) can only run after the merge.
- **2026-10-10** — From the login change on, switching the server to `master` after a merge is a routine step in the runbook's Redeploy section, not a task in each change, so every change archives on its branch before the merge.
- **2026-10-10** — Server checkouts are verified against GitHub's branch hash, taken from the maintainer's machine, never the server's own `origin/*`; after a branch switch, the gateway is recreated, not just reloaded. *Why:* a stale `origin/master` once checked out pre-deploy files and silently broke the gateway's config mount.

## Scope

- **2026-10-10** — Target: a live, demo-able app at discosticker.cafe by Sunday night. Unfinished work moves to next weekend instead of shipping at lower quality.
- **2026-10-10** — Login and rejected-upload audit records stay in this weekend's scope; ban audit records move to next weekend with admin ban-and-purge.
- **2026-10-10** — The "My stickers" page and owner-only delete move to next weekend.
- **2026-10-10** — Uploads accept only PNG and JPEG this weekend; WebP moves to next weekend.
- **2026-10-10** — The walking skeleton deploys to discosticker.cafe as soon as it merges, before login. *Why:* hosting problems surface early instead of on Sunday night.
- **2026-10-10** — The deploy has no local rehearsal script and no swapfile; if the server build runs out of memory, the api and gateway are built one at a time. *Why:* both cost time without reducing real risk for this deploy.
- **2026-10-10** — The login change starts by hiding the api's security headers (`proxy_hide_header`), before any OAuth work.
- **2026-10-10** — The login change skips Postgres-backed sessions and a frontend test runner (Vitest) this weekend; the frontend checks stay type check plus build. *Why:* together they save about an hour, which keeps the 1 pm live-test round and upload's Sunday time realistic.
- **2026-10-10** — Audit records don't store visitor IP addresses this weekend; the Discord account is the identity behind every audited action. nginx's access log still records visitor IPv4 addresses in Docker's container logs, which aren't rotated yet. Storing IPs, with the IPv6 visitor-address fix (IPv6 visitors currently appear as the Docker bridge address) and retention cleanup, moves to next weekend. *Why:* sign-in already ties every action to a bannable account, it stores less personal data, and it saves time for upload.
- **2026-10-10** — Next weekend: GIFs, importing server stickers, a report link, admin ban-and-purge, storing visitor IPs in audit records (with the IPv6 visitor-address fix and retention cleanup), the "My stickers" page with owner-only delete, WebP uploads, raising the HSTS max-age, a Content-Security-Policy, Postgres-backed sessions, a frontend test runner, nginx log rotation, and an optional GitLab mirror.
