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

## Security

- **2026-10-10** — Uploading requires Discord sign-in. *Why:* every upload can be audited and tied to an account that can be banned.
- **2026-10-10** — Login rejects banned accounts and Discord accounts less than 7 days old, before a session is created. *Why:* it blocks banned users and throwaway accounts made just to upload.
- **2026-10-10** — Until admin ban-and-purge exists, bans are set by hand in the database, with no ban UI or ban audit events. *Why:* admin tooling is next weekend's work.
- **2026-10-10** — Uploads are typed from their content (magic bytes), size-limited at nginx and in Spring, dimension-checked before full decode, and re-encoded to PNG. *Why:* security is a core goal of the project, not polish.
- **2026-10-10** — Original upload bytes are never stored or served; the audit record keeps their SHA-256. *Why:* only the re-encoded image is ever served.
- **2026-10-10** — Public sticker IDs are 128-bit random values in base64url, and database keys never appear in URLs. *Why:* sticker links can't be guessed or enumerated.
- **2026-10-10** — Audit records cover logins and uploads, accepted and rejected. *Why:* a refused account or upload still leaves a trace.
- **2026-10-10** — Secrets live only in a gitignored `secrets.env` and reach containers as environment variables through docker compose; a committed `secrets.env.example` lists the variable names. *Why:* the repo is public, and a secret pushed once stays in its history.
- **2026-10-10** — Each service gets only the secrets it needs, through compose `${VAR:?}` interpolation; `env_file: secrets.env` is never used. *Why:* least privilege, and a missing key stops startup.
- **2026-10-10** — `secrets.env` holds only secrets; the bucket name, region and endpoint live in compose or `application.yml`.
- **2026-10-10** — Claude Code's file tools are denied reading and editing `secrets.env`, and `CLAUDE.md` forbids reading or printing it any other way. *Why:* anything Claude reads ends up in the session transcript.
- **2026-10-10** — Claude Code never runs `docker compose config` with `secrets.env`, or `docker inspect` on services that hold secrets. *Why:* both print secret values into the transcript.
- **2026-10-10** — How production gets its secrets is decided with hosting; Vault is out of scope this weekend.
- **2026-10-10** — Compose requires the storage keys, and a test proves anonymous and wrong-key requests are rejected. *Why:* SeaweedFS accepts anonymous requests when its keys are missing, and storage must fail closed.
- **2026-10-10** — SeaweedFS runs `weed mini` 4.48 with WebDAV, the admin UI, telemetry and bucket auto-creation off; its always-on admin server serves only `/health` and `/metrics`, on the internal network. *Why:* fewer exposed services, and no buckets created by accident.
- **2026-10-10** — Only the gateway publishes a port; Postgres, SeaweedFS and the api stay on the internal network.
- **2026-10-10** — The Vultr firewall allows 80 and 443 from anywhere (IPv4 and IPv6) and 22 only from the maintainer's home IPv4; key-only SSH stays on, with Vultr's web console as the fallback. *Why:* only the site is public, and password-guessing bots can't reach SSH at all.

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

## Scope

- **2026-10-10** — Target: a live, demo-able app at discosticker.cafe by Sunday night. Unfinished work moves to next weekend instead of shipping at lower quality.
- **2026-10-10** — Login and rejected-upload audit records stay in this weekend's scope; ban audit records move to next weekend with admin ban-and-purge.
- **2026-10-10** — The "My stickers" page and owner-only delete move to next weekend.
- **2026-10-10** — Uploads accept only PNG and JPEG this weekend; WebP moves to next weekend.
- **2026-10-10** — The walking skeleton deploys to discosticker.cafe as soon as it merges, before login. *Why:* hosting problems surface early instead of on Sunday night.
- **2026-10-10** — Next weekend: GIFs, importing server stickers, a report link, admin ban-and-purge, IP retention cleanup, the "My stickers" page with owner-only delete, WebP uploads, and an optional GitLab mirror.
