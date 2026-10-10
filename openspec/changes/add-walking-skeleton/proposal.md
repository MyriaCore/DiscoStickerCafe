# Proposal

## Why

The repo has no runnable code yet. Before login and upload are built, every service needs to be wired together and proven to talk to each other. Each later change can then start from a known-good stack and run exact build, test, and run commands. If routing, storage, or configuration is broken, it should fail here, not in the middle of an OAuth or upload change.

## What Changes

- Add a docker compose stack with five services: nginx gateway, Spring Boot api, Postgres, SeaweedFS, and a one-shot bucket-init step. Healthchecks and `depends_on` conditions control start order.
- nginx serves the Next.js static export at `/`. It proxies `/api/*`, `/oauth2/*`, `/login/oauth2/*`, and `/s/*` to the api and sends `X-Forwarded-Proto/Host/Port`. Only the gateway publishes a host port.
- The api exposes `GET /api/health`. A datasource and Flyway are configured with no migrations yet. An S3 client is configured entirely from properties: endpoint, region, credentials, and path-style access.
- A Testcontainers integration test proves that the S3 client can store an object in a SeaweedFS bucket and read it back, and that SeaweedFS rejects requests without valid credentials. This is the first test in the change.
- Add `secrets.env.example` listing every secret variable name with placeholder values. Each container receives only the secrets it needs. Compose refuses to start if any required secret is unset.
- Placeholder Next.js + TypeScript page built with `output: 'export'`. No data fetching or UI work.
- Add build outputs to `.gitignore` and add a root `.dockerignore` that keeps `secrets*.env` out of build contexts.
- Fill in the CLAUDE.md "Commands" section with the exact build, test, type-check, and run commands.
- Copy the approved `decisions.md` into `docs/decisions.md` unchanged.

## Out of scope

- Spring Security, OAuth2 login, sessions, and CSRF. `/oauth2/*` and `/login/oauth2/*` are routed but nothing handles them yet.
- Database migrations and schema.
- Uploads, image processing, rate limits, and audit records.
- Production hosting, production S3, and production secret handling.
- Frontend UI, data fetching, and a frontend test runner.

## Capabilities

### New Capabilities

- `gateway-routing`: single-origin routing at nginx. Covers the static frontend at `/`, proxying the api path prefixes, forwarded headers, and 404 for unknown paths. Only the gateway is reachable from the host.
- `object-storage`: the api stores and reads objects through the S3 API, configured only by properties. The bucket exists before the api starts, and the storage service rejects unauthenticated or wrongly authenticated requests.
- `service-health`: a health endpoint at `/api/health` through the gateway that reports the api's status, including database connectivity.
- `database-migrations`: schema changes are applied by versioned Flyway migrations at api startup. Startup succeeds with zero migrations.
- `secrets-handling`: secrets come only from the gitignored `secrets.env`. Each service receives only the secrets it needs. Startup fails if a required secret is missing, and the committed example file lists every secret name.

### Modified Capabilities

None. No specs exist yet.

## Security impact

- **Storage:** SeaweedFS accepts anonymous requests whenever no credentials are configured. This change makes missing S3 keys a startup failure and adds a test that unauthenticated requests are rejected. SeaweedFS's admin UI, WebDAV, telemetry, and bucket auto-creation are turned off, and its ports are not published.
- **Secrets:** this change introduces the secrets-delivery pattern every later change will reuse: compose interpolation from `secrets.env`, scoped to each service. It also keeps `secrets.env` out of Docker build contexts. Commands that print resolved values (`docker compose config` with real secrets, `docker inspect`) are excluded from the workflow.
- **CORS and single origin:** unchanged. The browser talks only to nginx, and there is no CORS configuration.
- **Upload, audit, ban, rate-limit, and public-ID constraints:** no effect, since none of these features exist yet.

## Impact

- New code: `api/` (Maven, Spring Boot 4.1.1, Java 25), `frontend/` (Next.js 16.4, TypeScript 5.11), `gateway/` (nginx 1.30.5 Dockerfile and config), and `compose.yaml` at the repo root.
- New files: `secrets.env.example`, `.dockerignore`, and `docs/decisions.md`. `.gitignore` and the Commands section of `CLAUDE.md` are updated.
- New images: `postgres:18.6-alpine`, `chrislusf/seaweedfs:4.48`, `nginx:1.30.5-alpine`, `amazon/aws-cli:2.37.12`, `eclipse-temurin:25`, and `node:24-alpine`.
- Developer machine: needs a running Docker engine. On Windows 11 Home that means Docker Desktop on WSL 2, which the user sets up.
