# DiscoSticker.cafe

Signed-in Discord users upload images and get an unguessable link to a downscaled PNG that renders sticker-sized in Discord.

## Stack

- `frontend/`: Next.js + TypeScript, static export (`output: 'export'`). No SSR, API routes, or middleware.
- `api/`: Spring Boot + Spring Security (OAuth2 login with Discord), Postgres + Flyway.
- Storage: SeaweedFS (S3 API) locally and, this weekend, in production on the app server's internal network; AWS S3 replaces it later as a configuration change plus a data copy. The app uses only the S3 API; endpoint, credentials, and path-style access are configuration. Do not use MinIO: its free images are no longer published.
- `gateway/`: nginx, single origin. `/` goes to the frontend. `/api/*`, `/oauth2/*`, `/login/oauth2/*`, and `/s/*` go to Spring Boot.
- docker compose runs everything.

## Workflow

Changes go through OpenSpec: `/opsx:explore` → `/opsx:propose` → `/opsx:apply` → `/opsx:archive`. Project context and artifact rules are in `openspec/config.yaml`.

Work happens on a branch per feature or large effort (one branch may hold several OpenSpec changes), merged to `master` by pull request. Do not commit directly to `master`.

`docs/decisions.md` records why each project decision was made. Edit it only when the user explicitly asks; decisions are approved in the user's PM session before they go in. If a change would contradict a recorded decision, stop and ask.

## Rules every change follows

- Work test-first where practical. Every task states how it is verified: a test, a type check, or a command against the running stack.
- Uploads:
  - Enforce a size limit at nginx and again in Spring.
  - Detect the type from content (magic bytes), never from the extension or Content-Type.
  - Check dimensions before decoding the full image.
  - Re-encode to PNG on the server.
  - Never store or serve the original bytes. The audit record keeps their SHA-256.
- Rate-limit uploads per user.
- Write audit records for logins and uploads, accepted and rejected.
- Public IDs are 128-bit random values, base64url-encoded. Database keys never appear in URLs.
- The browser talks only to nginx. Do not add CORS configuration.
- Run the ban and account-age checks at login, before a session is created.
- Bans are set manually in the database for now; no ban UI or ban audit events.
- Secrets live only in `secrets.env`, which is gitignored. Refer to them by variable name; never put a secret value in source, `application.yml`, compose files, Dockerfiles, tests, or OpenSpec artifacts. Keep the committed `secrets.env.example` listing the same variable names with placeholder values. Do not read or print `secrets.env`.
- Never run `docker compose config` with secrets.env, or `docker inspect` on services that hold secrets; both print secret values. Config checks use secrets.env.example only.

## Commands

Every command in this section must pass before a task is reported done.

Run from the repo root in Git Bash. A PowerShell form is given only where it differs.

```bash
# api: unit and integration tests (needs Docker running for Testcontainers)
cd api && ./mvnw -B verify          # PowerShell: cd api; .\mvnw.cmd -B verify

# frontend: install, type-check, static export
cd frontend && npm ci && npm run typecheck && npm run build

# compose: validate the stack definition without real secrets
docker compose --env-file secrets.env.example config --quiet

# stack: build and start, waiting until every service is healthy
docker compose --env-file secrets.env up --build -d --wait --wait-timeout 300

# stack: health through the gateway (expects "status":"UP")
curl -fsS http://localhost:8080/api/health

# stack: stop
docker compose --env-file secrets.env down
```

In Git Bash, set `MSYS_NO_PATHCONV=1` for `docker` commands that pass container paths (such as `/etc/...`); otherwise they are rewritten to Windows paths.
