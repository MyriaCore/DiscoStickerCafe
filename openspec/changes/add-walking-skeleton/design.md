# Design

## Context

The repo contains only configuration. `api/`, `frontend/`, and `gateway/` are empty and there is no compose file. See proposal.md for motivation and scope; the specs in this change define the required behavior.

Planning checks (2026-10-10):

- **Host:** Windows 11 Home, Oracle JDK 25.0.4.1, Node 24.21.0 and npm 11.19.0. The Docker CLI is 29.8.2 with Compose v5.5.1, but there is no Docker engine yet and WSL is not installed.
- **Versions** (from Maven Central, npm, Docker Hub, and endoflife.date):
  - Spring Boot 4.1.1, which supports Java 17–26
  - Next.js 16.4.0, which needs Node 20.9 or later
  - nginx 1.30.5 (stable branch)
  - Postgres 18.6
  - SeaweedFS 4.48
  - Testcontainers 2.0.5
  - AWS SDK for Java v2 2.55.14
  - Maven 3.9.16
  - TypeScript 5.11.1 (`create-next-app` 16.4 scaffolds `^5`)
- **Spring Boot 4 and Testcontainers 2 names**, checked against the Boot 4.0 migration guide and the 4.1.1 / 2.0.5 jars on Central:
  - Web MVC: `spring-boot-starter-webmvc`, with `spring-boot-starter-webmvc-test` for tests (`spring-boot-starter-web` is deprecated).
  - Flyway: `spring-boot-starter-flyway` plus `org.flywaydb:flyway-database-postgresql`. Boot manages its version (Flyway 12.4.0).
  - Testcontainers: `org.testcontainers:testcontainers-junit-jupiter` and `org.testcontainers:testcontainers-postgresql`. The 1.x names `junit-jupiter` and `postgresql` do not exist at 2.0.5.
  - Classes:
    - `org.testcontainers.postgresql.PostgreSQLContainer` (the old `org.testcontainers.containers.PostgreSQLContainer` still ships, but don't use it)
    - `org.testcontainers.containers.GenericContainer`
    - `org.testcontainers.containers.wait.strategy.Wait`
    - `org.testcontainers.junit.jupiter.{Testcontainers,Container}`
    - `org.springframework.boot.testcontainers.service.connection.ServiceConnection`
  - Boot manages Testcontainers 2.0.5 and failsafe/surefire 3.5.6. The starter parent configures failsafe only in `pluginManagement`, so the plugin has to be declared to run.
- **SeaweedFS 4.48 behavior**, from source and the wiki:
  - `weed mini` runs master, volume, filer, and the S3 gateway on port 8333 in one process, with `GET /healthz`. The image includes `curl`.
  - With no credentials configured, S3 runs in **Allow-All** mode and accepts anonymous requests.
  - It reads `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` as a single global admin identity.
  - WebDAV, an admin UI with no password, telemetry, the Iceberg and Lance ports, and `s3.autoCreateBucket` are all on by default.
  - Path-style addressing is the only mode when `-s3.domainName` is empty.
- **Postgres 18 image:** the official docs say `VOLUME` moved to `/var/lib/postgresql` (PGDATA is `/var/lib/postgresql/18/docker`). Recheck this when writing compose.

## Goals / Non-Goals

**Goals:**
- Every service starts under compose in a known order and reports healthy. Each spec scenario has a test or a command.
- The S3 client is proven against SeaweedFS before anything else depends on storage.
- The secret-delivery pattern is set here and reused by later changes, such as the Discord client secret in the login change.

**Non-Goals:**
- No reusable "storage service" abstraction beyond the `S3Client` bean and its properties. The upload change shapes that.
- No production profile and no production credentials provider. The api uses static credentials from properties.
- No CI pipeline.

## Decisions

### D1. Maven with wrapper, Spring Boot 4.1.1, Java 25
- Maven Wrapper pinned to 3.9.16, and `spring-boot-starter-parent` 4.1.1.
- Unit tests are `*Test` (surefire); integration tests are `*IT` (failsafe, declared explicitly).
- Package: `cafe.discosticker.api`.
- *Alternative:* Gradle. It's faster, with a build cache, but has more moving parts, and `.gitattributes` already plans for `mvnw.cmd`.

### D2. One shared Testcontainers configuration for integration tests
- One `@TestConfiguration` class under `src/test` (`TestcontainersConfiguration`) is imported by every `@SpringBootTest` integration test. It holds:
  - The SeaweedFS `GenericContainer`, running the same image and `mini` flags as compose (D5) and waiting on `Wait.forHttp("/healthz").forPort(8333)`.
  - From group 2 on, a `PostgreSQLContainer("postgres:18.6-alpine")` annotated with `@ServiceConnection`.
- A `DynamicPropertyRegistrar` bean feeds the SeaweedFS endpoint and credentials into `app.s3.*`. The credentials are random values generated when the test runs, so no credential appears in test source.
- Spring's context cache shares the containers across ITs.
- *Alternative:* a separate container per test class. It's slower and duplicates the setup.

### D3. S3 client configured only from properties
- A validated `@ConfigurationProperties("app.s3")` record holds:
  - `endpoint`, `region`, `accessKey`, `secretKey`, and `bucket`, each `@NotBlank`
  - `pathStyleAccess` (boolean)
- One `S3Client` bean uses `endpointOverride`, `StaticCredentialsProvider`, and `forcePathStyle`.
- Missing properties fail startup.
- *Alternative:* the SDK's default credential chain. Production will likely want it with IAM roles, but it's out of scope here, and static credentials keep local and test behavior explicit.

### D4. Checksum fallback (only if needed)
AWS SDK v2.30 and later sends CRC32 trailing checksums by default. SeaweedFS 4.48's source handles `STREAMING-*-TRAILER` payloads, so no override is planned. **If SeaweedS3IT fails on checksum or content-encoding errors**, set `requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)` and `responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)` on the `S3Client` builder, keep the IT green, and note the override in a comment on the bean.

### D5. SeaweedFS runs `weed mini` with the extras off
- Image: `chrislusf/seaweedfs:4.48`.
- Command: `mini -dir=/data -webdav=false -admin.ui=false -master.telemetry=false -s3.port.iceberg=0 -s3.port.lance=0 -s3.autoCreateBucket=false`.
- No `-bucket` flag: its failures only log a warning.
- Credentials come only from `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` (see the secrets table).
- The tag and flags appear in two places, `compose.yaml` and `TestcontainersConfiguration`. A comment in each points to the other, and task 6.1 checks that they match.
- *Alternative:* separate `master`, `volume`, `filer`, and `s3` containers. That's closer to production SeaweedFS, but four services where one is enough.

### D6. Bucket created by a one-shot `bucket-init` service
- Image: `amazon/aws-cli:2.37.12`, with the entrypoint overridden to `sh`.
- It loops up to 30 times, 2 s apart: `aws s3api head-bucket` succeeds, or `aws s3api create-bucket` succeeds, or it retries. It exits 1 if all attempts fail.
- It uses `--endpoint-url http://seaweedfs:8333`.
- The api waits on it with `depends_on: {bucket-init: {condition: service_completed_successfully}}`.
- *Alternatives:*
  - `weed mini -bucket=`: it only logs a warning on failure.
  - `weed shell s3.bucket.create`: it bypasses S3 auth, so it doesn't prove the keys work.

### D7. Secrets reach containers by name through compose interpolation
- Run compose as `docker compose --env-file secrets.env …`.
- Each service lists the variables it needs in `environment:` as `${NAME:?set NAME in secrets.env}`. Never use `env_file: secrets.env`, which would give every variable to that service.
- Non-secret settings are written directly in `compose.yaml`: the database name and user, bucket, region, endpoint, and path-style flag.
- *Alternative:* compose `secrets:` with `*_FILE` variables. Postgres supports it, but SeaweedFS reads credentials only from env or a JSON file. That would mix two mechanisms, and decision 23 in `docs/decisions.md` already records environment variables through compose.

**Least-privilege mapping** (rows are `secrets.env` variables; cells are the variable name inside each container):

| `secrets.env` variable | postgres | seaweedfs | bucket-init | api | gateway |
|---|---|---|---|---|---|
| `POSTGRES_PASSWORD` | `POSTGRES_PASSWORD` | — | — | `SPRING_DATASOURCE_PASSWORD` | — |
| `S3_ACCESS_KEY` | — | `AWS_ACCESS_KEY_ID` | `AWS_ACCESS_KEY_ID` | `APP_S3_ACCESS_KEY` | — |
| `S3_SECRET_KEY` | — | `AWS_SECRET_ACCESS_KEY` | `AWS_SECRET_ACCESS_KEY` | `APP_S3_SECRET_KEY` | — |

`secrets.env.example` lists exactly these three names with placeholder values. Non-secret values in `compose.yaml`:
- **postgres:** `POSTGRES_USER=discosticker`, `POSTGRES_DB=discosticker`
- **api:** `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `APP_S3_ENDPOINT=http://seaweedfs:8333`, `APP_S3_REGION=us-east-1`, `APP_S3_BUCKET=stickers`, `APP_S3_PATH_STYLE_ACCESS=true`
- **bucket-init:** `AWS_REGION`

`application.yml` contains no secret placeholders at all; Spring's relaxed binding reads the environment variables.

### D8. Two compose networks
- `edge` holds the gateway and api.
- `backend` holds the api, postgres, seaweedfs, and bucket-init.
- The gateway can't resolve or reach postgres or seaweedfs. Only the gateway publishes a port, `127.0.0.1:8080:8080`, which binds to loopback only. Compose project `name: discosticker`.
- *Alternative:* one default network. It's simpler, but the gateway would sit next to SeaweedFS's unauthenticated filer and master HTTP ports.

### D9. The frontend export is baked into the gateway image
- `gateway/Dockerfile` is a multi-stage build. A `node:24-alpine` stage runs `npm ci && npm run build` in `frontend/`, and a `nginx:1.30.5-alpine` stage copies `frontend/out/` to `/usr/share/nginx/html`.
- The build context is the repository root, so a root `.dockerignore` excludes `secrets*.env`, `**/node_modules`, `**/target`, `**/.next`, `**/out`, and `.git`.
- *Alternative:* a separate static-files container. Frontend-only rebuilds would be smaller, but it adds one more service and one more hop.

### D10. nginx routing
- nginx listens on 8080, with `server_tokens off` and `absolute_redirect off`.
- `location ^~ /api/`, `^~ /oauth2/`, `^~ /login/oauth2/`, and `^~ /s/` each `proxy_pass http://api:8080` (no URI part, so the path passes unchanged) and include a shared proxy-headers file:
  - `Host $host`
  - `X-Forwarded-Proto $scheme`
  - `X-Forwarded-Host $host`
  - `X-Forwarded-Port $server_port`
  - `X-Forwarded-For $proxy_add_x_forwarded_for`
- The `^~` modifier keeps any later regex location, such as image caching, from capturing `/s/*.png`.
- `location / { try_files $uri $uri/ =404; }` with `index index.html`. There is no `/index.html` fallback.
- `proxy_intercept_errors` stays off, so the api's JSON 404s pass through unchanged.
- nginx listens on the same port it's published on, so `$server_port` matches what the browser sees.
- *Alternative:* `trailingSlash: false` with `try_files $uri $uri.html $uri/`. It also works, but the `.html` rewrite is extra.

### D11. Next.js 16.4 static export with TypeScript ~5.11
- `next.config.ts` sets `output: 'export'` and `trailingSlash: true`.
- One placeholder `app/page.tsx` with no `next/image`; the default scaffold page uses it, which breaks `output: 'export'`.
- `package.json` has a `typecheck` script running `tsc --noEmit`.
- `typescript` is pinned to `~5.11.1`.
- No test runner and no linter in this change.
- *Alternative:* TypeScript 7.0. It's faster, but Next 16.4 runs it only through an experimental CLI fallback.

### D12. Health via Actuator at `/api/health`
- `management.endpoints.web.base-path=/api` and `management.endpoints.web.exposure.include=health`. `show-details` stays at its default of `never`.
- `server.forward-headers-strategy=native`: Tomcat's RemoteIpValve trusts private-range proxies, which covers the compose network.
- Note for the login change: Spring Security must permit `/api/health`.
- *Alternative:* a hand-written controller. It has fewer dependencies, but no automatic database check.

### D13. Container healthchecks
- **postgres:** `pg_isready -U discosticker -d discosticker`
- **seaweedfs:** `curl -fsS http://localhost:8333/healthz`
- **api:** `wget -qO- http://localhost:8080/api/health | grep -q UP`, using busybox `wget` in the Temurin Alpine image (checked in task 3.4)
- **gateway:** `wget -qO- http://localhost:8080/ >/dev/null`
- Start order: gateway after api (healthy). api after postgres (healthy) and bucket-init (completed successfully). bucket-init after seaweedfs (healthy).

### D14. No commands that print secret values (resolved: project rule)
- `CLAUDE.md` and `openspec/config.yaml` now carry the rule: never run `docker compose config` with `secrets.env`, or `docker inspect` on services that hold secrets, because both print secret values. Config checks use `secrets.env.example` only.
- In this change, the secret-holding services are postgres, seaweedfs, bucket-init, and api. Every Verify line that runs `docker compose config` passes `secrets.env.example`.
- *Alternative:* redact the output, for example by piping `config` through a filter. Rejected: a single unfiltered run leaks the values into the terminal and the session transcript.

## Threats considered (storage and secrets)

| # | Threat | Handling | Verified by |
|---|---|---|---|
| T1 | SeaweedFS fails open: if S3 keys are missing it accepts anonymous requests (Allow-All). | `${VAR:?}` on every secret in compose, so compose refuses to start. The api's `app.s3` properties are `@NotBlank`. | SeaweedS3IT anonymous and wrong-key tests (1.4); missing-secret config check (6.1); in-stack anonymous `curl` returns 403 (6.6). |
| T2 | `secrets.env` ends up in a Docker build context and from there in an image layer or the build cache. | The api build context is `api/` only. The gateway context is the root, which has a `.dockerignore` excluding `secrets*.env`. | A throwaway build that copies the whole context and asserts `secrets.env` is absent (6.3). |
| T3 | Exposed ports: Postgres, the SeaweedFS S3/filer/master/volume ports, or the api reachable from the host or LAN. | Only the gateway publishes, on `127.0.0.1` only. The other services stay on internal networks, and the gateway isn't on `backend` (D8). | `docker ps` port listing (6.5); the gateway can't resolve `seaweedfs` (6.6). |
| T4 | The SeaweedFS admin UI (no password by default) and WebDAV give unauthenticated access to stored files. | `-admin.ui=false -webdav=false`. The remaining internal filer and master HTTP ports are unauthenticated, but only `backend` members can reach them. | `curl` to ports 23646 and 7333 inside the seaweedfs container fails to connect (6.6). |
| T5 | Auto-created buckets: a typo in the bucket name silently creates a new bucket. | `-s3.autoCreateBucket=false`, no `-bucket`; only bucket-init creates the bucket. | SeaweedS3IT: writing to a missing bucket is rejected (1.4). |
| T6 | Secret values leak through `docker compose config` or `docker inspect` output. Both print resolved environment values into the terminal and the session transcript. | Never run `docker compose --env-file secrets.env config` or `docker inspect` on postgres, seaweedfs, bucket-init, or api. Config checks use `secrets.env.example` only. No `set -x` in bucket-init. Actuator exposes only `health`, not `env` or `configprops`. Claude never creates, reads, edits, or prints `secrets.env`. | Task Verify lines only ever pass `secrets.env.example` to `config`. |
| T7 | SeaweedFS logs the identity name `admin-<first 8 chars of access key ID>`. | The access key ID is not the secret key, so this is accepted. Don't paste raw SeaweedFS logs anywhere public. | — |
| T8 | SeaweedFS telemetry sends cluster statistics off the machine. | `-master.telemetry=false`. | Flag present in compose and the IT (6.1 tag/flag check). |
| T9 | Clients spoof `X-Forwarded-*` headers. | nginx overwrites Proto, Host, and Port with `proxy_set_header`, and Tomcat trusts only private-range proxies. `X-Forwarded-For` is appended, so the later audit change must take the right-most entry nginx added, not the first. | `nginx -T` shows the headers set (5.2). The login change proves forwarding end to end. |
| T10 | The api holds a global admin identity on storage (it could delete buckets). | Accepted locally: SeaweedFS env credentials can't be scoped, and a scoped `s3.json` would mean writing a file that contains the secret. Production S3 uses a scoped IAM policy, decided with hosting. | — |

## Risks / Trade-offs

- [No Docker engine yet] → Task 0 is a `[user]` step. Nothing that needs Docker is attempted before it.
- [`docker compose up --wait` may count the exited bucket-init as a failure] → If it does, the apply step records the working form in CLAUDE.md Commands. For example, `up --build -d` followed by polling the gateway healthcheck. Either way, task 6.4's Verify line requires exit 0 and a healthy gateway.
- [SeaweedFS answers `/healthz` before its filer is ready] → bucket-init retries (D6). The IT's first S3 call is a bucket creation, so add a retry there if it turns out flaky.
- [The SeaweedFS tag or flags drift between compose and the IT] → Comments in both places, and the grep check in 6.1.
- [Spring Boot 4 / Testcontainers 2 names] → Use the names listed under Context. Don't use 1.x or Boot 3 names from memory.
- [SDK checksum incompatibility] → The D4 fallback.
- [The Temurin Alpine image may lack `wget`] → Task 3.4 checks. If it's missing, install `curl` in the runtime stage and switch the healthcheck.
- [`nginx -t` resolves the `api` upstream at config-test time] → The Verify lines pass `--add-host api:127.0.0.1`. At runtime the gateway starts only after the api is healthy.
- [Node 24's active support ends 2026-10-20] → Keep `node:24-alpine` and revisit after Node 26 becomes LTS on 2026-10-28.
- [Flyway 12.4 with Postgres 18] → FlywayMigrationIT runs against 18.6. If Flyway warns that the version is unsupported, record it and continue, since there are no migrations yet.
- [On Windows, `api/mvnw` loses its executable bit] → `git update-index --chmod=+x api/mvnw` (task 1.1).

## Migration Plan

This is a new project with nothing to migrate. To roll back, close the PR, or revert the merge commit on `master`. To clean up locally, run `docker compose --env-file secrets.env down -v` (this deletes the local Postgres and SeaweedFS volumes).

## Open Questions

- Production S3 credentials provider (the SDK default chain with an IAM role, or static credentials). Deferred to the hosting decision; D3's properties don't block either option.

## Recorded-decision candidates

These are for approval in the PM session. `docs/decisions.md` is only copied in this change, not edited.

- The api builds with Maven (wrapper 3.9.16) on Java 25 LTS and Spring Boot 4.1.1.
- Local storage runs SeaweedFS `weed mini` pinned to 4.48, with the admin UI, WebDAV, telemetry, Iceberg/Lance, and bucket auto-creation turned off.
- A one-shot bucket-init container creates the bucket through the S3 API, and the api waits for it to exit 0.
- Each container receives only the secrets it needs, by name, through compose interpolation from `secrets.env`. Every secret is required (`:?`), and `env_file: secrets.env` is not used.
- The Next.js export is copied into the gateway image by a multi-stage build, with `trailingSlash: true` and no fallback to `index.html`.
- Only the gateway publishes a port (`127.0.0.1:8080`), and it is not on the network with Postgres and SeaweedFS.
- `/api/health` is Actuator's health endpoint, with details hidden.
- TypeScript stays on 5.11 until Next.js fully supports TypeScript 7.
- Integration tests share one Testcontainers configuration with random runtime credentials. Each IT Verify line checks that the named test actually ran.
