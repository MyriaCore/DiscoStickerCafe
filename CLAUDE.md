# DiscoSticker.cafe

Signed-in Discord users upload images and get an unguessable link to a downscaled PNG that renders sticker-sized in Discord.

## Stack

- `frontend/`: Next.js + TypeScript, static export (`output: 'export'`). No SSR, API routes, or middleware.
- `api/`: Spring Boot + Spring Security (OAuth2 login with Discord), Postgres + Flyway.
- Storage: SeaweedFS (S3 API) locally, S3 in production. The app uses only the S3 API; endpoint, credentials, and path-style access are configuration. Do not use MinIO: its free images are no longer published.
- `gateway/`: nginx, single origin. `/` goes to the frontend. `/api/*`, `/oauth2/*`, `/login/oauth2/*`, and `/s/*` go to Spring Boot.
- docker compose runs everything.

## Workflow

Changes go through OpenSpec: `/opsx:explore` → `/opsx:propose` → `/opsx:apply` → `/opsx:archive`. Project context and artifact rules are in `openspec/config.yaml`.

Work happens on a branch per feature or large effort (one branch may hold several OpenSpec changes), merged to `master` by pull request. Do not commit directly to `master`.

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

## Commands

<!-- The first change fills this in with exact build, test, type-check, and run commands. -->

Every command in this section must pass before a task is reported done.
