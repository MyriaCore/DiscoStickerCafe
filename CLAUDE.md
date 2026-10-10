# DiscoSticker.cafe

Signed-in Discord users upload images and get an unguessable link to a downscaled PNG that renders sticker-sized in Discord.

## Stack

- `frontend/`: Next.js + TypeScript, static export (`output: 'export'`). No SSR, API routes, or middleware.
- `api/`: Spring Boot + Spring Security (OAuth2 login with Discord), Postgres + Flyway.
- Storage: MinIO locally, S3 in production.
- `gateway/`: nginx, single origin. `/` goes to the frontend. `/api/*`, `/oauth2/*`, `/login/oauth2/*`, and `/s/*` go to Spring Boot.
- docker compose runs everything.

## Workflow

Changes go through OpenSpec: `/opsx:explore` → `/opsx:propose` → `/opsx:apply` → `/opsx:archive`. Project context and artifact rules are in `openspec/config.yaml`.

## Rules every change follows

- Work test-first where practical. Every task states how it is verified: a test, a type check, or a command against the running stack.
- Uploads:
  - Enforce a size limit at nginx and again in Spring.
  - Detect the type from content (magic bytes), never from the extension or Content-Type.
  - Check dimensions before decoding the full image.
  - Re-encode to PNG on the server.
  - Never store or serve the original bytes. The audit record keeps their SHA-256.
- Rate-limit uploads per user.
- Write audit records for logins, uploads (accepted and rejected).
- Public IDs are 128-bit random values, base64url-encoded. Database keys never appear in URLs.
- The browser talks only to nginx. Do not add CORS configuration.
- Run the ban and account-age checks at login, before a session is created.

## Commands

<!-- The first change fills this in with exact build, test, type-check, and run commands. -->

Every command in this section must pass before a task is reported done.
