# Tasks

Conventions for every task:
- `[user]` marks a manual step for the user. Claude does not do it.
- Run commands from the repo root in Git Bash unless the command starts with `cd`.
- Claude never creates, reads, edits, or prints `secrets.env`, including through the shell.
- Never run `docker compose --env-file secrets.env config` or `docker inspect` on postgres, seaweedfs, bucket-init, or api. Config checks use `secrets.env.example` only.
- An integration test's Verify line must show that the named IT ran, with `Tests run:` greater than 0, not just that the build passed.

## 0. Prerequisites

- [x] 0.1 [user] Finish installing Docker Desktop, install WSL 2 (`wsl --install` as admin, then reboot), start Docker Desktop, and restart VS Code so `docker` and `node` are on PATH. Claude does not install or configure Docker or WSL.
  Verify: `docker version --format '{{.Server.Version}}' && docker pull chrislusf/seaweedfs:4.48`

## 1. Prove SeaweedFS through the S3 client

- [x] 1.1 Scaffold `api/`:
  - Generate a Maven project from Spring Initializr into an empty scratch directory, then move it into `api/`. Settings: Boot 4.1.1, Java 25, group `cafe.discosticker`, artifact `api`, package `cafe.discosticker.api`, dependencies `web,validation,testcontainers`.
  - Pin the wrapper: `./mvnw wrapper:wrapper -Dmaven=3.9.16`.
  - In `pom.xml`:
    - Use the Boot 4 names: `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, and test dependencies `spring-boot-starter-webmvc-test`, `spring-boot-testcontainers`, and `org.testcontainers:testcontainers-junit-jupiter`.
    - Import `software.amazon.awssdk:bom:2.55.14` and add `software.amazon.awssdk:s3`.
    - Declare `maven-failsafe-plugin` in `<build><plugins>`.
  - Delete the generated `contextLoads` test.
  - Add `api/target/` to `.gitignore`.
  - Run `git add api/mvnw && git update-index --chmod=+x api/mvnw`.

  Verify: `(set -o pipefail; cd api && ./mvnw -v | grep 'Apache Maven 3.9.16' && ./mvnw -B verify | grep -E 'failsafe:3\.5\.6:integration-test') && git ls-files -s api/mvnw | grep '^100755' && git check-ignore api/target`

- [x] 1.2 Write the first test (red):
  - Add `TestcontainersConfiguration` (a `@TestConfiguration`). It runs a `GenericContainer("chrislusf/seaweedfs:4.48")`:
    - command `mini -dir=/data -webdav=false -admin.ui=false -master.telemetry=false -s3.port.iceberg=0 -s3.port.lance=0 -s3.autoCreateBucket=false`
    - `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` set to random values generated at runtime
    - exposed port 8333, waiting on `Wait.forHttp("/healthz").forPort(8333)`
    - a comment pointing to `compose.yaml`
  - A `DynamicPropertyRegistrar` registers `app.s3.endpoint`, `region`, `access-key`, `secret-key`, `bucket`, and `path-style-access=true`.
  - Add `cafe.discosticker.api.storage.SeaweedS3IT` (`@SpringBootTest`, `@Import(TestcontainersConfiguration.class)`) with test `storesAndReadsBackObject`. It autowires `S3Client`, creates the bucket, puts random bytes under a key, gets them back, and asserts the bytes are equal.
  - It must fail because no `S3Client` bean exists.

  Verify: `(cd api && L=$(mktemp) && { ./mvnw -B verify -Dit.test=SeaweedS3IT >"$L" 2>&1; test $? -ne 0; } && grep -E 'Tests run: [1-9][0-9]*, Failures: [0-9]+, Errors: [1-9][0-9]*.* in cafe\.discosticker\.api\.storage\.SeaweedS3IT' "$L" && grep -rE "NoSuchBeanDefinitionException.*S3Client" target/failsafe-reports/)`

- [x] 1.3 Implement S3 configuration (green):
  - A `@Validated @ConfigurationProperties("app.s3")` record with `@NotBlank` endpoint, region, accessKey, secretKey, and bucket, plus boolean `pathStyleAccess`.
  - An `S3Client` bean built only from it: `endpointOverride`, `StaticCredentialsProvider`, and `forcePathStyle`.
  - If the IT fails on checksum or content-encoding errors, apply design D4 (`WHEN_REQUIRED` for both checksum settings) and comment it.

  Verify: `(set -o pipefail; cd api && ./mvnw -B verify -Dit.test=SeaweedS3IT | grep -E 'Tests run: [1-9][0-9]*, Failures: 0, Errors: 0, Skipped: 0.* in cafe\.discosticker\.api\.storage\.SeaweedS3IT')`

- [x] 1.4 Add tests to SeaweedS3IT that storage refuses unauthenticated access and implicit buckets:
  - `rejectsAnonymousRequest`: a client using `AnonymousCredentialsProvider` calls `GetObject` and gets an `S3Exception` with status 403.
  - `rejectsWrongSecretKey`: the right access key with a different secret gets 403.
  - `doesNotAutoCreateBucket`: `PutObject` to a missing bucket throws, and `HeadBucket` on that name then throws `NoSuchBucketException`.

  Verify: `(set -o pipefail; cd api && ./mvnw -B verify -Dit.test=SeaweedS3IT | grep -E 'Tests run: 4, Failures: 0, Errors: 0, Skipped: 0.* in cafe\.discosticker\.api\.storage\.SeaweedS3IT')`

## 2. Database and Flyway

- [x] 2.1 Add the Postgres and Flyway dependencies and the test that guards them:
  - Dependencies: `spring-boot-starter-flyway`, `org.flywaydb:flyway-database-postgresql`, `org.postgresql:postgresql` (runtime), and `org.testcontainers:testcontainers-postgresql` (test).
  - Add an `org.testcontainers.postgresql.PostgreSQLContainer("postgres:18.6-alpine")` bean with `@ServiceConnection` to `TestcontainersConfiguration`.
  - Add an empty `src/main/resources/db/migration/` (with `.gitkeep`).
  - Write `cafe.discosticker.api.db.FlywayMigrationIT` with test `flywayRanWithNoPendingMigrations`. It autowires `Flyway`, asserts `info().pending()` is empty, and asserts the configured DataSource's JDBC URL starts with `jdbc:postgresql:`.
  - There's no red step: Boot's auto-configuration provides the behavior, and the test guards it.

  Verify: `(set -o pipefail; cd api && L=$(mktemp) && ./mvnw -B verify | tee "$L" >/dev/null && grep -E 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0.* in cafe\.discosticker\.api\.db\.FlywayMigrationIT' "$L" && grep -E 'Tests run: 4, Failures: 0, Errors: 0, Skipped: 0.* in cafe\.discosticker\.api\.storage\.SeaweedS3IT' "$L")`

## 3. Health endpoint and api image

- [x] 3.1 Write `cafe.discosticker.api.health.HealthEndpointIT` (red). It uses `@SpringBootTest`, `@AutoConfigureMockMvc`, and `@Import(TestcontainersConfiguration.class)`. Test `healthIsUpWithoutDetails`: `GET /api/health` returns 200, `$.status` is `UP`, and `$.components` and `$.details` don't exist. It must fail with a 404 because Actuator isn't present yet.
  Verify: `(cd api && L=$(mktemp) && { ./mvnw -B verify -Dit.test=HealthEndpointIT >"$L" 2>&1; test $? -ne 0; } && grep -E 'Tests run: 1, Failures: 1.* in cafe\.discosticker\.api\.health\.HealthEndpointIT' "$L" && grep -rE 'expected:<200> but was:<404>' target/failsafe-reports/)`

- [x] 3.2 Add `spring-boot-starter-actuator` and set `management.endpoints.web.base-path: /api` and `management.endpoints.web.exposure.include: health` in `application.yml`. Leave `show-details` at its default.
  Verify: `(set -o pipefail; cd api && ./mvnw -B verify -Dit.test=HealthEndpointIT | grep -E 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0.* in cafe\.discosticker\.api\.health\.HealthEndpointIT')`

- [x] 3.3 Set `server.forward-headers-strategy: native` in `application.yml`. Per the amended plan this has no dedicated test; the login change proves forwarding end to end.
  Verify: `grep -E '^\s*forward-headers-strategy:\s*native\s*$' api/src/main/resources/application.yml && (set -o pipefail; cd api && L=$(mktemp) && ./mvnw -B verify | tee "$L" >/dev/null && grep -cE 'Tests run: [1-9][0-9]*, Failures: 0, Errors: 0, Skipped: 0.* in cafe\.discosticker\.api\.' "$L" | grep -x 3)`

- [x] 3.4 Add `api/Dockerfile` and `api/.dockerignore` (excluding `target/`):
  - Build stage `eclipse-temurin:25-jdk-alpine` runs `./mvnw -B -DskipTests package`.
  - Runtime stage `eclipse-temurin:25-jre-alpine` runs as a non-root user on port 8080.
  - The build context is `api/` only.

  Verify: `docker build -t dsc-api api && docker run --rm --entrypoint sh dsc-api -c 'command -v wget && test "$(id -u)" -ne 0 && echo non-root'`

## 4. Frontend

- [x] 4.1 Scaffold `frontend/`:
  - Run `npx create-next-app@16.4.0 frontend` with TypeScript, App Router, no Tailwind, no `src/` dir, npm, and no linter. Check `npx create-next-app@16.4.0 --help` for the exact flags.
  - Pin `typescript` to `~5.9.3`, and add the script `"typecheck": "tsc --noEmit"`.
  - Add `frontend/node_modules/`, `frontend/.next/`, `frontend/out/`, `*.tsbuildinfo`, and `next-env.d.ts` to the root `.gitignore`.

  Verify: `(cd frontend && npm ci && npm run typecheck && node -p "require('typescript/package.json').version" | grep -E '^5\.9\.') && git check-ignore frontend/node_modules frontend/.next` (`frontend/out` is checked in 4.2, once the build creates it)

- [x] 4.2 Configure the static export:
  - `next.config.ts` sets `output: 'export'` and `trailingSlash: true`.
  - Replace `app/page.tsx` with a plain placeholder page: no `next/image`, no data fetching.
  - Remove unused scaffold assets, the scaffold's `cacheComponents` and `partialPrefetching` settings, and `next/font/google` in `app/layout.tsx`.

  Verify: `(cd frontend && npm run typecheck && npm run build && test -f out/index.html && ! grep -rq 'next/image' app && ! grep -rq 'next/font/google' app) && git check-ignore frontend/out`

## 5. Gateway

- [x] 5.1 Add a root `.dockerignore` excluding `secrets*.env`, `**/node_modules`, `**/target`, `**/.next`, `**/out`, and `.git`. The real build-context check is 6.3, once `secrets.env` exists.
  Verify: `for p in 'secrets*.env' '**/node_modules' '**/target' '**/.next' '**/out' '.git'; do grep -qxF "$p" .dockerignore || echo "MISSING $p"; done | (! grep .)`

- [x] 5.2 Add `gateway/Dockerfile`, `gateway/nginx.conf`, and a shared proxy-headers include:
  - **Dockerfile:** multi-stage. `node:24-alpine` builds `frontend/` (`npm ci && npm run build`); `nginx:1.30.5-alpine` copies `frontend/out/` to `/usr/share/nginx/html`. The build context is the repo root.
  - **Server:** `listen 8080`, `server_tokens off`, `absolute_redirect off`.
  - **Proxied locations:** `location ^~ /api/`, `^~ /oauth2/`, `^~ /login/oauth2/`, and `^~ /s/` each `proxy_pass http://api:8080` and include headers `Host $host`, `X-Forwarded-Proto $scheme`, `X-Forwarded-Host $host`, `X-Forwarded-Port $server_port`, and `X-Forwarded-For $proxy_add_x_forwarded_for`.
  - **Static files:** `location / { try_files $uri $uri/ =404; }` with no `/index.html` fallback. `proxy_intercept_errors` stays off.

  Verify: `docker build -t dsc-gateway -f gateway/Dockerfile . && docker run --rm --add-host api:127.0.0.1 dsc-gateway nginx -t && T=$(docker run --rm --add-host api:127.0.0.1 dsc-gateway nginx -T) && test "$(echo "$T" | grep -oE 'proxy_set_header X-Forwarded-(Proto|Host|Port)' | sort -u | wc -l)" -eq 3 && test "$(echo "$T" | grep -cE 'location \^~ /(api|oauth2|login/oauth2|s)/')" -eq 4`

## 6. Compose stack

- [x] 6.1 Add `secrets.env.example` and `compose.yaml`:
  - **Check first:** re-read the official Postgres image docs (https://hub.docker.com/_/postgres). As of planning, 18+ declares `VOLUME /var/lib/postgresql`, so mount the named volume there.
  - `secrets.env.example` contains `POSTGRES_PASSWORD`, `S3_ACCESS_KEY`, and `S3_SECRET_KEY` with placeholder values.
  - `compose.yaml` has `name: discosticker` and these services:
    - postgres `postgres:18.6-alpine`
    - seaweedfs `chrislusf/seaweedfs:4.48`, running the same `mini` flags as `TestcontainersConfiguration`, with a comment pointing to it
    - bucket-init `amazon/aws-cli:2.37.12` (retry loop, no `set -x`)
    - api (build `api/`)
    - gateway (build `.` with `gateway/Dockerfile`, port `127.0.0.1:8080:8080`)
  - Secrets follow the design D7 table exactly, written as `${NAME:?set NAME in secrets.env}`.
  - Networks are `edge` and `backend` (D8). Healthchecks and `depends_on` conditions follow D13.

  Verify (each must pass; uses `secrets.env.example` only):
  - `docker compose --env-file secrets.env.example config --quiet`
  - `M=$(mktemp -d)/missing.env && grep -v '^S3_SECRET_KEY=' secrets.env.example > "$M" && out=$(docker compose --env-file "$M" config --quiet 2>&1); test $? -ne 0 && echo "$out" | grep S3_SECRET_KEY`
  - `docker compose --env-file secrets.env.example config --format json | node -e 'const c=JSON.parse(require("fs").readFileSync(0,"utf8"));const env=s=>Object.keys(c.services[s].environment||{});const bad=[...env("postgres").filter(k=>/S3|AWS/.test(k)),...env("gateway")];if(bad.length){console.error("leak:",bad);process.exit(1)}console.log("ok")'`
  - `test "$(grep -rhoE 'chrislusf/seaweedfs:[0-9.]+' compose.yaml api/src/test/java | sort -u | wc -l)" -eq 1 && for f in -webdav=false -admin.ui=false -master.telemetry=false -s3.port.iceberg=0 -s3.port.lance=0 -s3.autoCreateBucket=false; do grep -q -- "$f" compose.yaml && grep -rq -- "$f" api/src/test/java || echo "MISSING $f"; done | (! grep .)`

- [x] 6.2 [user] Copy `secrets.env.example` to `secrets.env` and set real values. Claude must never create, read, edit, or print this file.
  Verify: `git check-ignore -q secrets.env && test -s secrets.env && echo present-and-ignored` (checks only that the file exists and is ignored; it does not read the contents)

- [x] 6.3 Prove `secrets.env` is excluded from the root build context. The check uses a bind mount, so no image layer can capture the file even if the check fails.
  Verify: `printf 'FROM nginx:1.30.5-alpine\nRUN --mount=type=bind,target=/ctx test -e /ctx/compose.yaml && test ! -e /ctx/secrets.env\n' | docker build --no-cache -f - .`

- [x] 6.4 Start the stack. If `--wait` treats the exited bucket-init as a failure, switch to the fallback in the design's Risks section and record the working form in 7.2.
  Verify: `docker compose --env-file secrets.env up --build -d --wait --wait-timeout 300 && test "$(docker compose --env-file secrets.env ps gateway --format '{{.Health}}')" = healthy`

- [x] 6.5 Smoke-test routing and exposure through the gateway.
  Verify (each must pass):
  - `curl -fsS http://localhost:8080/ | grep -qi '<html' && echo root-ok`
  - `curl -fsS http://localhost:8080/api/health | grep -q '"status":"UP"' && echo health-ok`
  - `for p in /oauth2/x /login/oauth2/x /s/x; do curl -s -o /dev/null -w '%{http_code} %{content_type}' "http://localhost:8080$p" | grep -q '^404 application/json' || echo "FAIL $p"; done | (! grep .)`
  - `test "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/nope/)" = 404 && echo unknown-404`
  - `P=$(docker ps --filter label=com.docker.compose.project=discosticker --format '{{.Names}} {{.Ports}}' | grep -- '->') && test "$(echo "$P" | wc -l)" -eq 1 && echo "$P" | grep -E '^discosticker-gateway-1 127\.0\.0\.1:8080->8080/tcp'`

- [x] 6.6 Smoke-test storage hardening inside the running stack.
  Verify (each must pass):
  - `test "$(docker compose --env-file secrets.env exec -T seaweedfs curl -s -o /dev/null -w '%{http_code}' http://localhost:8333/stickers/)" = 403 && echo anon-403`
  - `docker compose --env-file secrets.env exec -T seaweedfs sh -c 'curl -s -o /dev/null --max-time 3 http://localhost:7333 && echo "OPEN 7333"; test "$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 http://localhost:23646/)" = 404 || echo "ADMIN UI 23646"; true' | (! grep .)` (the admin server always runs; this checks that its UI is off)
  - `docker compose --env-file secrets.env exec -T gateway sh -c 'for h in seaweedfs:8333/healthz postgres:5432; do wget -q -T 3 -O /dev/null "http://$h" 2>/dev/null && echo "REACHABLE $h"; done; true' | (! grep .)`
  - `docker compose --env-file secrets.env run --rm bucket-init && echo bucket-init-idempotent`

## 7. Docs and commands

- [x] 7.1 Copy the user's PM-approved decisions.md to `docs/decisions.md` byte for byte. Do not edit, reformat, reorder, or add entries.
  Verify: `sha256sum docs/decisions.md` prints `311a589670528e1ab12c6a9119bf8df6ebf1742c59fb1c7eca555c95758aed88`

- [x] 7.2 Fill in the CLAUDE.md "Commands" section with bash (Git Bash) forms. Add a PowerShell form only where it differs. The commands are:
  - `cd api && ./mvnw -B verify` (PowerShell: `cd api; .\mvnw.cmd -B verify`). Runs unit and integration tests; needs Docker running.
  - `cd frontend && npm ci && npm run typecheck && npm run build`
  - `docker compose --env-file secrets.env.example config --quiet` (validates compose without real secrets)
  - `docker compose --env-file secrets.env up --build -d --wait --wait-timeout 300`, or the fallback form recorded in 6.4
  - `curl -fsS http://localhost:8080/api/health`
  - `docker compose --env-file secrets.env down`

  Verify: run every command in the new Commands section in order, from a stopped stack (`docker compose --env-file secrets.env down` first). Each must exit 0, and the health call must print `"status":"UP"`.

## Workflow follow-up

- Open a PR from `feature/skeleton` to `master` after all tasks pass.
- Done 2026-10-10: the PM session approved this change's decisions, and they are recorded in `docs/decisions.md`.
- Archive the change with `/opsx:archive` after merge.
