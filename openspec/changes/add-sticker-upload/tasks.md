# Tasks

Conventions for every task:
- `[user]` marks a manual step for the maintainer, which Claude Code does not do. Claude never connects to the server, never runs commands on it, and never creates, reads or prints any `secrets.env`.
- Where each kind of command runs:
  - **Claude's commands:** Git Bash from the repo root (CLAUDE.md), with `MSYS_NO_PATHCONV=1` for `docker` commands that pass container paths.
  - **The maintainer's Windows commands:** **Windows PowerShell 5.1** from the repo root, using `curl.exe` (never `curl`), `-o NUL`, and no `&&`.
  - **Server commands:** **bash**, over SSH as `deploy`, from `/srv/discosticker`, with `Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'` defined.
- Never run `docker compose config` with `secrets.env`, and never run `docker inspect` on postgres, seaweedfs, bucket-init or api. Config checks pass `secrets.env.example`.
- Write the paste-test URLs and all example links as plain text, never as markdown links.
- Every command in CLAUDE.md's Commands section must pass before a Claude task is reported done.
- **Placeholders:**
  - S is the standard sticker size: 160 until 1.3 approves a value.
  - `<id>` is a sticker's public ID.
  - `<your-discord-id>` is the maintainer's Discord user ID.
- **Tracks:**
  - 1.2 (user) can run at any time.
  - There is no 5.1: the committed real phone photo was dropped (design D17). Task numbers are unchanged.
  - 1.3 must be done before section 11.
  - Nothing else waits on the user until 10.2.
- **The local header check** (Git Bash) must print `0`:
  ```bash
  curl -sS -D - -o /dev/null http://localhost:8080/api/health | grep -ciE '^(strict-transport-security|x-content-type-options|referrer-policy|x-frame-options):'
  ```
- **The production header check** (PowerShell) must print `1` on all 15 lines:
  ```powershell
  $names = 'strict-transport-security','x-content-type-options','referrer-policy','x-frame-options'
  foreach ($p in '/','/api/health','/nope/') {
    $h = curl.exe -sI "https://discosticker.cafe$p" | ForEach-Object { $_.TrimEnd() }
    foreach ($n in $names) { '{0,-12} {1,-26} {2}' -f $p, $n, @($h | Select-String -Pattern "^${n}:").Count }
    '{0,-12} {1,-26} {2}' -f $p, 'hsts = max-age=300', @($h | Select-String -Pattern '^strict-transport-security: max-age=300$').Count
  }
  ```
- **The local size-limit check** (Git Bash; design D2) sends three anonymous bodies through the gateway:
  ```bash
  d=$(mktemp -d); head -c 12058624 /dev/zero > "$d/big"; head -c 11010048 /dev/zero > "$d/mid"; head -c 2097152 /dev/zero > "$d/two"
  for c in "big /api/stickers" "mid /api/stickers" "two /api/logout"; do set -- $c
    curl -sS -o /dev/null -w "$1 $2 %{http_code} %{content_type}\n" -H 'Content-Type: application/octet-stream' --data-binary @"$d/$1" "http://localhost:8080$2"
  done
  ```
  - `big` is 11.5 MiB, `mid` 10.5 MiB and `two` 2 MiB.
  - Once 3.2 lands it must print `big /api/stickers 413 text/html`, `mid /api/stickers 403 application/json` and `two /api/logout 413 text/html`. The 403 is the api's CSRF rejection, which proves the 10.5 MiB body reached the api.

## 1. Discord paste test (first)

- [x] 1.1 Generate the paste-test images into `docs/paste-test/` (design D16), using a throwaway script in the scratchpad that is not committed.
  - Squares of 32, 64, 128, 160, 240, 320 and 480 px, named `<n>.png`, each with its size printed inside and a 1 px border.
  - `320x160.png`, `160x320.png` and `160-transparent.png`.
  - Add `docs/paste-test/README.md` with each image's URL as plain text (https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/<name>.png) and 1.2's procedure. Commit on `feature/upload`.

  Verify: `file docs/paste-test/*.png` prints `PNG image data, <w> x <h>` matching each file name, with `RGBA` for `160-transparent.png`. `grep -c 'raw.githubusercontent.com' docs/paste-test/README.md` prints `10`. `grep -c '](http' docs/paste-test/README.md` prints `0`.
  Notes (2026-10-11, Claude): generated with a throwaway `PasteTestImages.java` in the session scratchpad (not committed). `file` lists all ten at their named sizes: nine `8-bit/color RGB`, and `160-transparent.png` as `8-bit/color RGBA`. The README has `10` raw URLs and `0` markdown links. `git check-attr` reports `binary: set` for the PNGs; they total 50 KB. Not committed: the maintainer said not to commit or push during apply, so committing the images is the first step of 1.2.

- [x] 1.2 [user] Run the paste test.
  1. Push (PowerShell): `git push -u origin feature/upload`.
  2. In a private Discord channel, post a real server sticker. Then paste each README URL as its own message, on desktop at 100% zoom and on a phone.
  3. For each image, note whether it looks smaller than, the same size as, or larger than the sticker; whether 32 and 64 are drawn at their natural size or enlarged; and how the two rectangles fit.

  Verify: the Notes under this task record, per image and device, its size relative to the sticker, and which square matches it.
  Notes (2026-10-11, reported by the user):
  - Desktop at 100% zoom:
    - 32 and 64 are drawn tiny, at natural size, with no upscaling.
    - 128 is smaller than the sticker.
    - 160 and 160-transparent are about the same size as the sticker.
    - 240, 320 and 480 are larger.
    - 320x160 is wider at the same height, and 160x320 is taller at the same width.
  - Phone: 160 matches the sticker.
  - Conclusion: Discord draws pasted image links at their natural size, and 160 matches a sticker on desktop and phone.

- [x] 1.3 [user] Approve the standard sticker size S in the PM session, from 1.2's results. Claude then makes the change:
  - If S isn't 160, Claude sets `app.upload.sticker-size` to S (or 2.2 uses S, if it hasn't landed yet).
  - Claude also updates every place that names 160: the `sticker-upload` spec delta (the re-encoding and limits scenarios), design.md D1 and D16, and test expectations that use the default.

  Verify: `grep -n 'sticker-size' api/src/main/resources/application.yml` shows S. `openspec validate add-sticker-upload --strict` passes. `cd api && ./mvnw -B verify` passes.
  Notes (2026-10-11): the PM approved S = 160 (reported by the user). The default already matches, so code, specs and tests are unchanged.
  - The grep prints `61:    sticker-size: 160`.
  - `openspec validate add-sticker-upload --strict` prints "Change 'add-sticker-upload' is valid".
  - `./mvnw -B verify` last passed after 5.4 (51 unit, 52 integration tests), and no code has changed since.

## 2. Upload settings, limits endpoint and runtime

- [x] 2.1 Write the settings tests first (design D1, D2; spec "Upload limits endpoint").
  - `UploadLimitsIT`:
    - signed out gets 401 JSON;
    - signed in gets exactly `{"acceptedTypes":["image/jpeg","image/png"],"maxBytes":10485760,"uploadsPerHour":20,"stickerSize":160}`, with S in place of 160 once 1.3 approves it;
    - a nested class with `@TestPropertySource` (`app.upload.max-file-size=5MB`, `app.upload.uploads-per-hour=7`, `app.upload.sticker-size=96`) gets `5242880`, `7` and `96`. 7.2 checks that uploads enforce changed values.
  - `UploadSettingsIT`:
    - Boot's multipart `max-file-size` equals `app.upload.max-file-size`;
    - `max-request-size` is exactly 64 KiB more;
    - `resolve-lazily` is true;
    - Tomcat's `max-swallow-size` is at least 12MB.

  Verify: `cd api && ./mvnw -B verify -Dit.test=UploadLimitsIT,UploadSettingsIT` compiles and both ITs fail: there is no endpoint and no setting yet.
  Notes (2026-10-11, Claude): compiled; 7 tests, 6 failures. The two signed-in limits tests got 404, and all four `UploadSettingsIT` tests failed: the file limit was null, the request limit was Boot's default, `resolve-lazily` was false and the swallow size was 2MB. The signed-out 401 test already passes (deny-by-default).

- [x] 2.2 Implement the settings and the endpoint.
  - `UploadProperties`: `app.upload.*`, validated.
  - `application.yml`: the settings exactly as in design D1.
  - `GET /api/stickers/limits`. Its `acceptedTypes` come from the same type enum the upload check uses (design D4), JPEG first.

  Verify: `cd api && ./mvnw -B verify` passes, including `UploadLimitsIT` and `UploadSettingsIT`.

- [x] 2.3 Set the runtime and temp storage (design D6, D14).
  - `api/Dockerfile`: `ENTRYPOINT ["java", "-Xmx1g", "-jar", "/app/app.jar"]`.
  - `compose.yaml`, api: `tmpfs: ["/tmp:size=128m"]` and `SPRING_SERVLET_MULTIPART_LOCATION: /tmp`.
  - `compose.yaml`, gateway: `tmpfs: ["/var/cache/nginx/client_temp:size=128m"]`.
  - Each gets a comment saying originals never reach a persistent disk. `compose.prod.yaml` is unchanged, because compose merges these into production.

  Verify:
  - `docker compose --env-file secrets.env.example config --quiet` exits 0.
  - `docker compose --env-file secrets.env up --build -d --wait --wait-timeout 300` exits 0, and `curl -fsS http://localhost:8080/api/health` prints `"status":"UP"`.
  - `MSYS_NO_PATHCONV=1 docker compose --env-file secrets.env exec api sh -c "grep ' /tmp ' /proc/mounts; tr '\0' ' ' < /proc/1/cmdline; echo"` prints a `tmpfs /tmp tmpfs` line containing `size=131072k`, and a command line containing `-Xmx1g`.
  - `MSYS_NO_PATHCONV=1 docker compose --env-file secrets.env exec gateway sh -c "nginx -V 2>&1 | grep -o 'http-client-body-temp-path=[^ ]*'; grep client_temp /proc/mounts"` prints `http-client-body-temp-path=/var/cache/nginx/client_temp`, and a `tmpfs` line for that path containing `size=131072k`.
  Notes (2026-10-11, Claude):
  - `config --quiet` exited 0. `up --build` brought every service to healthy (bucket-init exited), and health printed `"status":"UP"`.
  - api: `tmpfs /tmp tmpfs rw,nosuid,nodev,noexec,relatime,size=131072k 0 0` and `java -Xmx1g -jar /app/app.jar`.
  - gateway: `http-client-body-temp-path=/var/cache/nginx/client_temp` and `tmpfs /var/cache/nginx/client_temp tmpfs rw,nosuid,nodev,noexec,relatime,size=131072k 0 0`.
  - Both are writable by the processes that use them: `/tmp` is `drwxrwxrwt` and already holds Tomcat's work directories (running as `app`), and nginx's master made `client_temp` owned by `nginx`.

## 3. Gateway upload limit

- [x] 3.1 Write the gateway check first and run it as a baseline (spec `gateway-routing` "Upload request size limit"). This needs 2.2 and 2.3 deployed to the local stack.

  Verify: the local size-limit check (Conventions) prints `413 text/html` on all three lines: nothing has raised nginx's 1m default yet. Record the output in Notes.
  Notes (2026-10-11, Claude): baseline, with 2.2 and 2.3 running: `big /api/stickers 413 text/html`, `mid /api/stickers 413 text/html`, `two /api/logout 413 text/html`.

- [x] 3.2 Add `location = /api/stickers` to `gateway/routes.conf` exactly as in design D2: `client_max_body_size 11m`, `proxy_pass http://api:8080`, `include /etc/nginx/proxy_headers.conf`, and no response headers. A comment says the limit must stay above `app.upload.max-file-size` plus multipart framing, and below Tomcat's `max-swallow-size`.

  Verify:
  - `docker compose --env-file secrets.env up --build -d --wait --wait-timeout 300` exits 0.
  - The local size-limit check prints `big /api/stickers 413 text/html`, `mid /api/stickers 403 application/json` and `two /api/logout 413 text/html`.
  - `MSYS_NO_PATHCONV=1 docker compose --env-file secrets.env exec gateway nginx -T 2>/dev/null | grep -cE '^\s*client_max_body_size 11m;'` prints `1`.
  - The local header check prints `0`.

  Notes (2026-10-11, Claude):
  - `up --build` exited 0 with every service healthy.
  - The size-limit check printed `big /api/stickers 413 text/html`, `mid /api/stickers 403 application/json` and `two /api/logout 413 text/html`. The 10.5 MiB body passed nginx (buffered through the tmpfs `client_temp`), reached the api, got its CSRF 403 JSON, and came back with no 502: Tomcat swallowed the rest of the body.
  - `nginx -T` shows exactly `1` `client_max_body_size 11m;` line, and the local header check prints `0`.

- [x] 3.3 Update the runbook for the limit (`docs/deploy.md`).
  - Under "Smoke checks", add this PowerShell upload-limit check:
    ```powershell
    $d = Join-Path $env:TEMP 'upload-limit'; New-Item -ItemType Directory -Force $d | Out-Null
    foreach ($c in @(@('big',12058624,'/api/stickers'), @('mid',11010048,'/api/stickers'), @('two',2097152,'/api/logout'))) {
      $f = Join-Path $d $c[0]; [IO.File]::WriteAllBytes($f, (New-Object byte[] $c[1]))
      curl.exe -sS -o NUL -w "$($c[0]) $($c[2]) %{http_code} %{content_type}`n" -H 'Content-Type: application/octet-stream' --data-binary "@$f" "https://discosticker.cafe$($c[2])"
    }
    ```
    It expects `big /api/stickers 413 text/html`, `mid /api/stickers 403 application/json` and `two /api/logout 413 text/html`.
  - Under "Redeploy", note that the upload limit lives in `gateway/routes.conf`, which is built into the gateway image, so changing it needs a gateway rebuild.

  Verify: `grep -nE '12058624|routes.conf' docs/deploy.md` shows the new lines. 11.5 runs the check against production.
  Notes (2026-10-11, Claude):
  - The check went in after "api prefixes" in "Smoke checks", using the runbook's `\n` in `-w` (curl expands it). The note went before "A change to `gateway/prod/` only" in "Redeploy".
  - The grep shows lines 398 (the check) and 530 (the note).
  - As a syntax check, the same PowerShell 5.1 snippet run against `http://localhost:8080` printed `big /api/stickers 413 text/html`, `mid /api/stickers 403 application/json` and `two /api/logout 413 text/html`.

## 4. Schema

- [x] 4.1 Extend `SchemaIT` first (design D10).
  - **Columns:**
    - `stickers` has exactly `id`, `public_id`, `owner_id`, `object_key`, `width`, `height`, `png_bytes`, `created_at`.
    - `upload_audit` has exactly `id`, `occurred_at`, `discord_id`, `outcome`, `reason`, `original_sha256`, `original_bytes`, `sticker_public_id`.
  - **`public_id`:**
    - Rejects 21 and 23 characters, `+`, `/`, and a last character outside `AQgw`.
    - Accepts a canonical ID.
    - `public_id` and `object_key` are unique, and `owner_id` must reference `users`.
  - **Audit checks:**
    - `accepted` needs no reason, a public ID and a SHA-256.
    - `rejected` needs a reason other than `error`, and no public ID.
    - `failed` needs reason `error`.
    - An unknown outcome or reason is rejected, and a SHA-256 must be 32 bytes.
  - **Index:** `upload_audit_accepted_recent` exists.

  Verify: `cd api && ./mvnw -B verify -Dit.test=SchemaIT` fails only on the new tests.
  Notes (2026-10-11, Claude): 19 tests; all 9 new ones fail (5 failures, 4 errors: missing tables and index), and the 10 existing tests pass.

- [x] 4.2 Add `api/src/main/resources/db/migration/V3__create_stickers.sql` and `V4__create_upload_audit.sql` as in design D10.

  Verify: `cd api && ./mvnw -B verify` passes, including `SchemaIT` and `FlywayMigrationIT`.
  Notes (2026-10-11, Claude): passed, with 10 unit tests and 52 integration tests, including `SchemaIT` (19) and `FlywayMigrationIT` (1, no pending migrations).

## 5. Image pipeline (unit tests, no Spring)

- [x] 5.2 Add the generated fixtures, `TestImages` in test sources, listed in design D17. They include the CMYK JPEG, written as a 4-band raster through the JDK JPEG writer.

  Verify: `cd api && ./mvnw -B test -Dtest=TestImagesTest` passes. The test proves each fixture has its intended property:
  - the two bombs' headers declare 50,000 × 50,000 and 65,000 × 65,000;
  - the padded PNG is exactly 10,485,760 bytes and decodes;
  - each EXIF fixture's APP1 starts with `Exif\0\0` and uses its intended byte order (`II` or `MM`);
  - the GPS JPEG has a GPS IFD pointer;
  - the CMYK JPEG's frame header has 4 components. If the JDK writer can't produce that, commit a CMYK JPEG made with an external tool instead, record the command in Notes, and have the test check that file.

  Notes (2026-10-11, Claude):
  - `TestImagesTest` passes (9 tests). Each fixture is checked with ImageIO and raw bytes only, never with the pipeline under test.
  - The generated CMYK JPEG has 4 frame components, and the JDK reader decodes it as a 4-band `TYPE_CMYK` image, so no committed CMYK file is needed.
  - The JDK writer does not add an Adobe APP14 segment, contrary to design D17's draft wording. D17 is corrected.
  - The test also checks the orientation fixtures' independent Java 2D rotations, the truncated files, the progressive frame (SOF2) and the transparent fixtures' hidden green.

- [x] 5.3 Write the pipeline unit tests first (design D4–D7; spec requirements on type, dimensions, damaged and CMYK images, re-encoding, transparency, EXIF orientation and metadata). The pipeline classes start as stubs that throw.
  - **`ImageTypeTest`:**
    - PNG and JPEG signatures are detected.
    - A text file named `sticker.png`, GIF, WebP, BMP, SVG, empty and 2-byte inputs are unsupported.
    - `ImageIO.getImageReadersByFormatName` returns exactly one reader each for `png` and `jpeg`, from `com.sun.imageio.plugins`.
    - `ImageIO.getUseCache()` is false once the pipeline's initializer has run.
  - **`ImageHeaderTest`:**
    - Both bombs give `dimensions_too_large`, with a counting decoder seam at 0 calls.
    - The headers 8,193 × 1 and 5,657 × 5,657 are rejected; 8,192 × 1 and 8,000 × 4,000 pass.
    - The CMYK JPEG gives `unsupported_color`.
  - **`ExifOrientationTest`:**
    - Orientations 1, 3, 6 and 8 in little-endian, and 6 in big-endian, are read correctly.
    - No APP1, a non-EXIF APP1, a truncated APP1 and out-of-range offsets each give 1 without throwing.
  - **`StickerImagesTest` (S = 160):**
    - Fitting: 4,000 × 3,000 gives 160 × 120; 161 × 160 gives 160 × 159; 160 × 161 gives 159 × 160; 160 × 160 and 1 × 1 are unchanged; 8,000 × 1 gives 160 × 1.
    - Quality: the stripes image gives every pixel within 127 ± 16.
    - Transparency: red on transparent green keeps alpha 0 in its transparent areas, with no edge pixel where green exceeds red. The palette `tRNS` entry gives alpha 0.
    - Orientation: orientations 3, 6 and 8 put a marked corner pixel where upright display puts it, and 6 turns 40 × 20 into 20 × 40.
  - **`PngOutputTest`:**
    - Output starts with the PNG signature, and its chunk types are exactly `IHDR`, one or more `IDAT`, then `IEND`, including for the GPS JPEG.
    - An opaque source gives color type RGB; a transparent one gives RGBA.
  - **`ImageDecodeTest`:**
    - A truncated PNG and a truncated JPEG both give `unreadable`, the JPEG through the warning listener.
    - A progressive JPEG decodes.
    - The appended-HTML PNG decodes, and its output doesn't contain the HTML bytes.

  Verify: `cd api && ./mvnw -B test` compiles, and every class above fails against the stubs.
  Notes (2026-10-11, Claude):
  - Compiled: 51 unit tests, 9 failures, 22 errors. Every test of the new behavior fails against the stubs.
  - The one that passes is `ImageTypeTest`'s reader-class guard, which checks the JDK's registered readers rather than new code, so it holds already.
  - The existing tests and `TestImagesTest` pass.
  - Two additions beyond the list:
    - `StickerImagesTest.busyWhenEveryPermitIsHeld`, a unit-level check of the decode limiter (7.2 still covers `busy` end to end);
    - `ImageDecodeTest.textNamedAsPngIsUnsupported`.

- [x] 5.4 Implement the pipeline (design D4–D7):
  - type detection;
  - header checks and CMYK detection;
  - the EXIF APP1 parser;
  - decoding with the warning listener;
  - the decode limiter (`decode-permits`, `decode-wait`);
  - shrink, orient and encode;
  - `ImageIO.setUseCache(false)` at startup.

  Verify: `cd api && ./mvnw -B verify` passes, including every 5.3 class and `TestImagesTest`.
  Notes (2026-10-11, Claude):
  - Passed: 51 unit tests (every 5.3 class, plus `TestImagesTest`) and 52 integration tests.
  - The pipeline is in `cafe.discosticker.api.stickers.image`:
    - `ImageCodecs` picks the JDK's own reader or writer and uses in-memory streams.
    - `ImageHeader` checks the caps, CMYK and EXIF from metadata.
    - `ExifOrientation` is the APP1 parser.
    - `ImageIoDecoder` treats any decoder warning as unreadable.
    - `DecodeLimiter` is a fair semaphore.
    - `StickerImages` fits, shrinks, orients and encodes.
  - `StickersConfig` makes the limiter and pipeline beans. Creating the pipeline turns off ImageIO's disk cache.
  - A throwaway probe confirmed the truncated-JPEG path is the warning path: without the listener, the JDK returns a full 128 × 128 image with the warnings "Truncated File - Missing EOI marker" and "Corrupt JPEG data: premature end of data segment".

- [x] 5.5 Measure decode, shrink and encode time once (PM decision: no time-budget test).
  - Input: a generated 4,000 × 3,000 noisy JPEG (quality 0.9).
  - Method: a throwaway timing run that isn't committed, on the dev machine, taking the best of 3 after one warm-up.

  Verify: the Notes under this task record the time, the machine and the JDK version.
  Notes (2026-10-11, Claude):
  - Setup: a throwaway `Timing.java` in the session scratchpad (not committed), run against `target/classes` and `target/test-classes`.
  - Machine: AMD Ryzen 5 7600X (12 threads), Windows 11, Java 25.0.4.1, `-Xmx1g` (the production heap).
  - Input: a 4,000 × 3,000 noise JPEG at quality 0.9, 10,762,136 bytes. Noise doesn't compress, so this is a worst case and slightly over the 10 MiB upload limit; real 12 MP photos are about 3–5 MB.
  - Results, best of 3 after one warm-up:
    - decode alone: 164 ms;
    - decode, shrink and encode: 204 ms, giving a 160 × 120 PNG of 34,281 bytes;
    - the warm-up run: 267 ms.
  - Expect the 2-vCPU server to be slower. Production's `Upload accepted … in <n> ms` lines (11.6) give the real numbers, still far inside the 10 s `busy` wait.

## 6. CSRF token from the header only

- [x] 6.1 Write the tests first (design D12; spec "CSRF token read only from the header").
  - `HeaderOnlyCsrfTokenRequestHandlerTest` (unit):
    - a request with only a `_csrf` parameter resolves to null;
    - with the header, it returns the header's value;
    - the request is a `MockHttpServletRequest` subclass whose `getParameter*` and `getPart*` methods fail the test if called.
  - `ApiSecurityIT`: `POST /api/logout` with the matching token only as a `_csrf` form field gets 403, and `GET /api/me` still returns 200. The existing header-based sign-out test still gets 204.

  Verify: `cd api && ./mvnw -B verify -Dit.test=ApiSecurityIT` fails only on the new form-field test, and the unit test fails against a stub.
  Notes (2026-10-11, Claude):
  - `ApiSecurityIT` ran 11 tests with 1 failure, the new form-field test: `expected: 403 but was: 204`.
    - A token sent as a `_csrf` form field, XOR-masked the way Spring renders tokens into forms (`LoginFlow.xorMaskedCsrfToken`), currently signs the user out. The parameter path is live.
    - The raw token in the same field already gets 403, because the SPA handler XOR-decodes parameter values. That's why the test sends both.
  - The 3 unit tests fail against the stub.
  - The handler also has to load the token in `handle`, as `spa()` does (it sets a null attribute name, which calls the token supplier). That's what puts `XSRF-TOKEN` on every response, and the unit test pins it.

- [x] 6.2 Implement `HeaderOnlyCsrfTokenRequestHandler`, and set it with `csrfTokenRequestHandler(...)` after `csrf.spa()` in `SecurityConfig`.

  Verify: `cd api && ./mvnw -B verify` passes, including `ApiSecurityIT` and `DiscordLoginIT`.
  Notes (2026-10-11, Claude): passed, with 54 unit and 53 integration tests: `ApiSecurityIT` 11 (including the new form-field test and the unchanged XSRF cookie and header sign-out tests) and `DiscordLoginIT` 10.

## 7. Upload endpoint, rate limit, ban check and audit

- [x] 7.1 Add the test support:
  - `LoginFlow.postMultipart(path, parts, headers…)`: a hand-built multipart body over the JDK `HttpClient`;
  - a storage helper that lists the bucket's keys and reads an object;
  - an upload-audit row reader by Discord ID;
  - a helper that inserts back-dated accepted audit rows.

  Verify: `cd api && ./mvnw -B verify` passes; the existing tests are unchanged.
  Notes (2026-10-11, Claude): passed, with 54 unit and 53 integration tests.
  - `LoginFlow` gained `postMultipart` with `FormPart` (file and field parts), plus a body-carrying `post` and `head`. `head` is for section 8.
  - `UploadTestSupport` provides:
    - the bucket's keys and objects, and ensuring the bucket exists;
    - a user's audit and sticker rows;
    - back-dated audit rows and banning;
    - SHA-256;
    - `UploadClient`, which signs in a fresh user and uploads with the CSRF header.

- [x] 7.2 Write `StickerUploadIT` first (spec `sticker-upload`, every upload requirement; design D2, D3, D8, D9). Every case also asserts the bucket's key count, and the sticker and audit rows for that test's Discord ID.
  - **Sign-in and CSRF:**
    - signed out with a valid token gets 401 JSON;
    - signed out without a token gets 403;
    - signed in without a token gets 403;
    - signed in with the token only in a `_csrf` part gets 403;
    - none of these stores anything or writes an audit row.
  - **Ban:** a user banned after signing in gets 403 `banned`, audited as `rejected | banned`.
  - **Type:**
    - a text file named `sticker.png` with Content-Type `image/png` gets 415, audited with its SHA-256;
    - GIF, WebP, BMP and SVG files named `sticker.png` get 415;
    - a valid PNG named `photo.txt` with Content-Type `text/plain` gets 201;
    - a PNG with appended HTML gets 201, and the stored object doesn't contain the HTML.
  - **Size:** 10,485,761 bytes gets 413 `too_large`, audited with no SHA-256 and no size. The 10,485,760-byte PNG gets 201.
  - **Dimensions:** both bombs get 422 `dimensions_too_large`.
  - **Damaged:** the truncated PNG and JPEG get 422 `unreadable`. The CMYK JPEG gets 422 `unsupported_color`.
  - **Request shape:** no `file` part, or two `file` parts, gets 400 `invalid_request`.
  - **Output:**
    - a 4,000 × 3,000 JPEG becomes a 160 × 120 PNG;
    - S × S and 1 × 1 are unchanged;
    - a small JPEG becomes a PNG of the same size;
    - an EXIF-6 40 × 20 JPEG becomes 20 × 40;
    - the transparent PNG keeps its alpha-0 areas;
    - the GPS JPEG gives only `IHDR`, `IDAT` and `IEND`;
    - the stored object differs from the original.
  - **Response:** 201, `Location` equal to `path`, `path` matching `^/s/[A-Za-z0-9_-]{21}[AQgw]\.png$`, and JSON fields exactly `path`, `width` and `height`.
  - **Error bodies:** every rejection's JSON has only the `error` field.
  - **Audit:** an accepted row holds the Discord ID, the original's SHA-256 and byte size, the public ID, and no reason.
  - **Rate limit:**
    - 20 accepted rows from 59 minutes ago give 429 `rate_limited`, with a `Retry-After` from 1 to 60, audited;
    - 20 accepted rows from 61 minutes ago give 201;
    - 19 accepted rows plus 5 rejected give 201;
    - with 19 accepted rows, two concurrent uploads give exactly one 201 and one 429.
  - **Busy:** with every permit taken from the decode limiter bean and `app.upload.decode-wait=200ms`, an upload gets 503 `busy`, audited.
  - **Storage failure:** a nested context whose `app.s3.bucket` names a missing bucket gets 500 `error`, no stickers row, and an audit row `failed | error`.
  - **Settings followed:** in a nested context with `app.upload.max-file-size=1MB`, `uploads-per-hour=2` and `sticker-size=96`:
    - a 1.5 MB PNG gets 413;
    - the third upload gets 429;
    - a 200 × 100 PNG becomes 96 × 48.
  - **Logs:** `OutputCaptureExtension` shows exactly one `Upload <outcome>` INFO line per upload, and no uploaded file name ever appears.

  Verify: `cd api && ./mvnw -B verify -Dit.test=StickerUploadIT` compiles and fails, because there is no upload endpoint yet.
  Notes (2026-10-11, Claude):
  - Ran with `-Dit.test='StickerUpload*IT'`: compiled, 30 tests, 26 failures.
  - The 4 that pass are the sign-in and CSRF cases (401 signed out with a token; 403 without one, signed in or out; 403 with the token only in a `_csrf` part). Spring Security already enforces those, and they check nothing was stored or audited.
  - The three "nested context" cases are separate classes: `StickerUploadBusyIT` (`decode-wait=200ms`), `StickerUploadStorageFailureIT` and `StickerUploadSettingsIT`. Two reasons:
    - The Testcontainers `DynamicPropertyRegistrar` sets `app.s3.bucket` and outranks `@TestPropertySource`, so the storage-failure context gets a fresh storage container where nothing creates the bucket.
    - An outer `@BeforeEach` would otherwise run for nested tests and create that bucket.
  - Back-dated audit rows use the database's `now()`, the clock the hourly window uses, so clock skew between Windows and the Docker VM can't move the 59- and 61-minute boundaries.

- [x] 7.3 Implement the upload (design D2, D3, D8–D11):
  - the upload controller, reading parts lazily, with its `too_large` handler;
  - the upload service, following D3's order;
  - the precheck query and the locked write transaction;
  - the storage PUT with compensation;
  - `UploadAuditWriter`, writing `failed` rows in a new transaction;
  - public IDs;
  - the INFO line `Upload <outcome>[ <reason>] in <n> ms`.

  Verify: `cd api && ./mvnw -B verify` passes, including `StickerUploadIT`.
  Notes (2026-10-11, Claude):
  - Passed: 54 unit tests and 83 integration tests, including `StickerUploadIT` (25), `StickerUploadBusyIT` (1), `StickerUploadStorageFailureIT` (1) and `StickerUploadSettingsIT` (3).
  - Lazy multipart parsing raises the size error inside the upload controller, so its own handler audits `too_large`. Design D2's fallback isn't needed.
  - Two test fixes during the run, neither a code change:
    - `assertAccepted` now takes the upload as a `Supplier`, so its "before" snapshot really is taken before the upload.
    - "Stored ≠ original" is asserted only for non-PNG inputs. A plain PNG written by the JDK re-encodes byte-for-byte to itself with the same deterministic writer. The polyglot, transparency and GPS cases show PNGs are re-encoded.
  - The run's log lines show one INFO line per upload: 18 `Upload accepted`, `Upload rejected <code>` for each rejection, and 1 `Upload failed error` with its ERROR line. The uploaded file names never appear.
  - The other CLAUDE.md commands also pass:
    - compose config check: exit 0;
    - `up --build`: exit 0, health `"status":"UP"`;
    - frontend `npm ci`, `typecheck` and `build`: exit 0.

- [x] 7.4 Update the runbook for uploads (`docs/deploy.md`; design D9).
  - Add "Recent uploads" and "Orphan check" with:
    ```bash
    $Prod exec postgres psql -U discosticker -d discosticker -c "select occurred_at, discord_id, outcome, reason, original_bytes, sticker_public_id from upload_audit order by id desc limit 20"
    $Prod run --rm --no-deps --entrypoint aws bucket-init s3 ls s3://stickers --endpoint-url http://seaweedfs:8333 | awk '{print $4}' | sort > /tmp/objects.txt
    $Prod exec -T postgres psql -U discosticker -d discosticker -At -c "select object_key from stickers" | sort > /tmp/rows.txt
    comm -23 /tmp/objects.txt /tmp/rows.txt   # orphan objects: in storage with no row (safe to delete)
    comm -13 /tmp/objects.txt /tmp/rows.txt   # rows without an object: must print nothing
    ```
  - Under "Sign-in and bans", add that a ban now blocks uploads at once (audited as `rejected | banned`), while other signed-in endpoints keep working until the session ends.

  Verify: `grep -nE 'upload_audit|comm -23|blocks uploads' docs/deploy.md` shows the new lines. 10.3 runs the orphan check locally.
  Notes (2026-10-11, Claude):
  - Added a new "Uploads" section before "Lockout recovery", holding:
    - what is audited and logged;
    - "Recent uploads": the audit query, plus a log grep;
    - "Orphan check", with how to delete an orphan, and a warning to stop if a row has no object.
  - The "Sign-in and bans" ban paragraph now says a ban blocks uploads at once.
  - The grep shows lines 573, 596, 601 and 610.
  - The check's commands ran against the local stack (`docker compose --env-file secrets.env` in place of `$Prod`): the bucket listing exited 0, with 0 objects and 0 rows, and both `comm` lines printed nothing. 10.3 repeats it after the browser uploads.

- [x] 7.5 Add a "Take down a sticker" subsection to `docs/deploy.md` "Uploads" (design D19, PM-approved 2026-10-11). It holds `[user]` steps, step 0 from Windows and the rest on the server:
  0. Find the sticker's ID from Windows with the existing "list every sticker" query, which becomes this subsection's step 0. Its ssh command uses `;`, not `&&`, so the runbook checks pass.
  1. Check the ID's format in the shell (`^[A-Za-z0-9_-]{21}[AQgw]$`) before any SQL.
  2. Look up the sticker's object key, owner Discord ID and `created_at`.
  3. Delete the row with `returning object_key`.
  4. Delete the object with the aws CLI from the bucket-init image (`$Prod run --rm --no-deps --entrypoint aws bucket-init s3 rm …`), only if the returned key equals `<id>.png`.
  5. Run 11.7's Verify steps.

  It also states:
  - browsers that loaded the sticker can keep showing it for up to a day (`max-age=86400`), and Discord's media proxy is outside our control, so ask the poster to delete their message;
  - `upload_audit` rows stay;
  - the takedown itself isn't audited this weekend;
  - a takedown doesn't free a rate-limit slot.

  No command prints a secret or uses `config` or `inspect`.

  Verify:
  - `grep -nE 'Take down a sticker|returning object_key|s3 rm|max-age=86400' docs/deploy.md` shows the new lines.
  - The runbook checks from add-discord-login 7.1 print nothing: every code block labelled `bash` or `powershell`; no `docker compose config` or `docker inspect`; no bash-style commands in PowerShell blocks; no secret-like values.

  Notes (2026-10-11, Claude):
  - **Placement:** added "### Take down a sticker" at the end of "Uploads", after the orphan check. Step 0 is the "list every sticker" query, moved there from after "Recent uploads". Its ssh command now uses `;`.
  - **ID check:** each server block that uses the ID repeats `[[ $ID =~ ^[A-Za-z0-9_-]{21}[AQgw]$ ]]` (steps 1, 2, 3 and 5). A bad ID prints `Not a sticker ID: stop`, runs no SQL, and never exits the shell.
  - **`-Atq`, not `-At`:** step 3 captures the key with `psql -Atq`. Tested locally, `-At` also prints the `DELETE n` status line after `returning`, so the captured key would never equal `<id>.png`.
  - **Local dry run:** the subsection's bash blocks were extracted and run against the local stack, with `docker compose --env-file secrets.env` in place of `$Prod`. The test used a synthetic sticker: a copy of a local test sticker's object under a fresh random ID, plus its row, with no audit row.
    - The link went from `200 image/png` to `404 application/json`, checked with step 5's PowerShell `curl.exe` before and the same request from Git Bash after.
    - Step 3 returned `<id>.png`, and step 4 printed `delete: s3://stickers/<id>.png`.
    - The count printed `0`, and the audit query printed `(0 rows)`, as expected for a synthetic row.
    - The orphan check found 4 objects and 4 rows, and both `comm` lines printed nothing.
    - A malformed ID (a non-canonical last character, or a space) printed `Not a sticker ID: stop` at steps 1, 2, 3 and 5 and `Object not deleted` at step 4. It ran no SQL, and the script ran to its end.
    - An unknown well-formed ID printed `(0 rows)` and `Returned object key: none, so no row was deleted`, and deleted nothing.
    - The local stack's four real stickers are unchanged.
  - **Verify:**
    - The grep shows lines 430, 621, 623, 671, 682, 690 and 710. Lines 623 to 710 are new.
    - Both 7.1 checks (fences, config/inspect and PowerShell bash-isms; secret-like values) printed nothing.
    - On a scratch copy with `&&` put back in step 0, check 1 flags that line.
  - **Not rerun:** CLAUDE.md's Commands, because this task changes only `docs/deploy.md` and this file.

## 8. Serving stickers

- [x] 8.1 Write `StickerServingIT` first (spec "Stickers served publicly", "Unknown and malformed sticker IDs look the same"; design D13). Each test first uploads a sticker through the API.
  - **GET, signed out and signed in:**
    - 200, `Content-Type` exactly `image/png`, and `Content-Length` equal to the stored object's size;
    - `Cache-Control` exactly `public, max-age=86400`;
    - no `Set-Cookie`, `Pragma` or `Expires` header;
    - the body equals the stored object.
  - **Signed in:** the session cookie is unchanged, and `GET /api/me` still returns 200 afterwards.
  - **HEAD:** the same status and headers, with an empty body.
  - **404s:** an unknown canonical ID gets 404 JSON. Each of these also gets 404 JSON, with fields equal to the unknown case apart from `path` and `timestamp`:
    - 21 and 23 characters;
    - `+`, and an encoded `/`;
    - a last character of `B`;
    - `.jpg`;
    - `/s/<id>.png/x`.
  - **No lookup for malformed IDs:** a `@MockitoSpyBean` on the sticker repository records no lookup for them.
  - The existing `ApiSecurityIT` check that `/s/x` returns 404 JSON still passes.

  Verify: `cd api && ./mvnw -B verify -Dit.test=StickerServingIT` compiles and fails, because there is no serving yet.
  Notes (2026-10-11, Claude):
  - Compiled; all 4 tests fail. The three GET and HEAD tests get 404, and the 404 test's positive control (`find` called for a well-formed unknown ID) isn't met.
  - `LoginFlow` gained `getBytes`, for binary bodies.
  - The malformed list uses a literal `/`, which splits the ID into two path segments. An encoded `%2F` never reaches `/s/` handling: checked through the gateway in 8.2, Tomcat refuses it with its own 400 HTML page (no version or report shown), for any path.
  - The list also includes `/s/<id>` with no extension and `/s/x`.

- [x] 8.2 Implement the `/s/**` security filter chain and the serving controller (design D13).

  Verify: `cd api && ./mvnw -B verify` passes, including `StickerServingIT` and `ApiSecurityIT`.
  Notes (2026-10-11, Claude):
  - Passed, with 54 unit and 87 integration tests, including `StickerServingIT` (4) and `ApiSecurityIT` (11).
  - The `/s/**` chain (`StickerServingSecurityConfig`) is ordered first: `permitAll`, no CSRF, stateless, `NullRequestCache`, and Spring's cache-control headers off. The main chain is unchanged.
  - `StickerServingController` checks the ID's canonical form before any lookup, and writes `Content-Type: image/png`, `Content-Length` and `Cache-Control: public, max-age=86400` itself. HEAD skips the storage read.
  - Through the rebuilt local stack:
    - `/s/x` and `/s/AAAAAAAAAAAAAAAAAAAAAA.png` give `404 application/json`;
    - `/s/AAAAAAAAAA%2FAAAAAAAAAAA.png` gives 400 from Tomcat (an HTML page with no server info), which is existing behavior for any path containing `%2F`.

- [x] 8.3 Update the runbook for serving (`docs/deploy.md` "Smoke checks").
  - Extend the prefixed-path loop to `'/api/me','/api/stickers/limits','/oauth2/x','/login/oauth2/x','/s/x','/s/AAAAAAAAAAAAAAAAAAAAAA.png'`. It expects 401, 401, 401, 401, 404 and 404, each `application/json`.
  - Add a sticker header check to run after an upload (PowerShell):
    ```powershell
    curl.exe -sSI "https://discosticker.cafe/s/<id>.png" | Select-String -Pattern '^(HTTP|content-type|content-length|cache-control|set-cookie|x-content-type-options):?'
    ```

  Verify: `grep -nE 'AAAAAAAAAAAAAAAAAAAAAA|api/stickers/limits|cache-control' docs/deploy.md` shows the new lines.
  Notes (2026-10-11, Claude):
  - The grep shows lines 391 and 393 (the extended loop) and 430 and 433 (the sticker header check).
  - The extended loop, run in PowerShell 5.1 against `http://localhost:8080`, printed `/api/me`, `/api/stickers/limits`, `/oauth2/x` and `/login/oauth2/x` 401, and `/s/x` and `/s/AAAAAAAAAAAAAAAAAAAAAA.png` 404, all `application/json`.

## 9. Frontend

- [x] 9.1 Implement the frontend (design D15; spec "Upload form", "Browser-side pre-check", "Upload results shown as fixed text"):
  - `frontend/lib/cookies.ts`, with `readCookie` moved out of `lib/session.ts`;
  - `frontend/lib/stickers.ts`;
  - the upload form in `frontend/app/page.tsx`;
  - removing "Sticker uploads are coming soon.".

  Verify:
  - `cd frontend && npm ci && npm run typecheck && npm run build` passes.
  - `grep -rnE --include='*.ts' --include='*.tsx' '10 ?MB|10485760|\b20\b|\b160\b' frontend/app frontend/lib` prints nothing.
  - `grep -rn 'dangerouslySetInnerHTML' frontend/app frontend/lib` prints nothing.
  - `grep -c 'Animated images become a still image.' frontend/app/page.tsx` prints `1`.

  Notes (2026-10-11, Claude):
  - `npm ci`, `npm run typecheck` and `npm run build` all exit 0. The literal-limits grep and the `dangerouslySetInnerHTML` grep print nothing, and the "Animated images" grep prints `1`.
  - `lib/cookies.ts` now holds `readCookie`, and `lib/session.ts` imports it.
  - `lib/stickers.ts` has:
    - `getLimits` (returns null, so the form is hidden, unless all three numbers are positive integers);
    - `uploadSticker`, which accepts only a `path` matching the canonical sticker pattern;
    - `precheck`;
    - `uploadMessage`, with the fixed messages from design D15. Any 413 shows the size message; unknown codes get the generic one.
  - The form is a `StickerUpload` component in `app/page.tsx`:
    - `accept` follows the api's order;
    - a refused file shows its message and disables Upload;
    - the link is `location.origin` plus the checked path, with a Copy button;
    - a 401 ends the session view with a notice;
    - "Sticker uploads are coming soon." is gone.
  - Uses React 19's `SubmitEvent` type; `FormEvent` is deprecated in the current `@types/react`.

## 10. Local stack checks

- [x] 10.1 Run every command in CLAUDE.md's Commands section, then these checks against the running stack (Git Bash):
  - the local header check;
  - the local size-limit check;
  - `for p in /api/me /api/stickers/limits /oauth2/x /login/oauth2/x /s/x /s/AAAAAAAAAAAAAAAAAAAAAA.png; do curl -sS -o /dev/null -w '%{url} %{http_code} %{content_type}\n' "http://localhost:8080$p"; done`;
  - the tmpfs and heap checks from 2.3.

  Verify:
  - The header check prints `0`.
  - The size-limit check prints the three lines given in Conventions.
  - The loop prints 401, 401, 401, 401, 404 and 404, each with `application/json`.
  - The 2.3 checks print as described there.

  Notes (2026-10-11, Claude):
  - CLAUDE.md commands, all exit 0:
    - `./mvnw -B verify`: 54 unit and 87 integration tests;
    - frontend `npm ci && npm run typecheck && npm run build`;
    - the compose config check against `secrets.env.example`;
    - `up --build -d --wait`;
    - health `"status":"UP"`;
    - `down`. The stack was then started again (`up -d --wait`, healthy) for 10.2.
  - The header check printed `0`.
  - The size-limit check printed `big /api/stickers 413 text/html`, `mid /api/stickers 403 application/json` and `two /api/logout 413 text/html`.
  - The path loop printed 401 for `/api/me`, `/api/stickers/limits`, `/oauth2/x` and `/login/oauth2/x`, and 404 for `/s/x` and `/s/AAAAAAAAAAAAAAAAAAAAAA.png`, all `application/json`.
  - 2.3's checks:
    - api: `tmpfs /tmp tmpfs rw,nosuid,nodev,noexec,relatime,size=131072k 0 0`, and `java -Xmx1g -jar /app/app.jar`;
    - gateway: `http-client-body-temp-path=/var/cache/nginx/client_temp`, and `tmpfs /var/cache/nginx/client_temp tmpfs rw,nosuid,nodev,noexec,relatime,size=131072k 0 0`.
  - The gateway serves the new frontend: the "Animated images become a still image." text is in `/_next/static/chunks/3uougnfvfxju6.js`.

- [x] 10.2 [user] Check the upload in a browser at http://localhost:8080, signed in with the dev application:
  1. The form states JPEG and PNG, 10 MB, 20 uploads per hour and "Animated images become a still image." Opening http://localhost:8080/api/stickers/limits in the same browser shows the same numbers.
  2. Upload a portrait photo taken straight from a phone camera (not a screenshot), so it carries an EXIF orientation. The sticker is upright and taller than wide, its link opens a PNG, and Copy puts the link on the clipboard. This is the real-camera orientation check (design D17).
  3. Upload a transparent PNG. Opening its link shows the transparency kept.
  4. Upload a small icon, for example 32 × 32. The sticker is the same size.
  5. Download the animated PNG example from Wikipedia's "APNG" article and upload it. It's accepted, and the sticker doesn't move.
  6. Rename a `.txt` file to `.png` and upload it: the type message shows. Then pick a file over 10 MB: the size message shows, and DevTools' Network tab shows no request.
  7. In the DevTools console, run this snippet, which bypasses the form:
     ```js
     const t = decodeURIComponent(document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN=')).split('=')[1]);
     const fd = new FormData(); fd.append('file', new Blob([new Uint8Array(11010048)], {type: 'image/png'}), 'big.png');
     const r = await fetch('/api/stickers', {method: 'POST', headers: {'X-XSRF-TOKEN': t}, body: fd});
     console.log(r.status, await r.text());
     ```
     It prints `413 {"error":"too_large"}`, not a 502.

  Verify (PowerShell): `docker compose --env-file secrets.env exec postgres psql -U discosticker -d discosticker -c "select outcome, reason, original_bytes, sticker_public_id from upload_audit order by id"` shows:
  - `accepted` rows for steps 2 to 5;
  - `rejected | unsupported_type` for step 6;
  - `rejected | too_large` for step 7.

  Every step behaved as described.

  Notes (2026-10-11, reported by the user):
  - The form shows JPEG and PNG, 10 MB, 20 per hour and the animated-image note, matching `/api/stickers/limits`.
  - `upload_audit` and the stickers:
    - The phone photo was accepted (7,737,554 bytes) and stored as 120 × 160 portrait, upright.
    - The 32 × 32 icon was accepted, unchanged.
    - The APNG (Wikipedia's bouncing ball) was accepted as a still 100 × 100 RGBA image, with transparency kept.
    - `docs/paste-test/160-transparent.png` was accepted, 160 × 160 with transparency.
    - The renamed `.txt` was rejected as `unsupported_type`.
  - The DevTools 10.5 MiB snippet printed `413 {"error":"too_large"}`, audited as `too_large` with no size or SHA-256. The file over 10 MB was refused in the browser, with no request sent.
  - Sticker headers, checked with curl:
    - `image/png`, a `Content-Length` matching the stored object, and `Cache-Control: public, max-age=86400`;
    - no `Set-Cookie`, `Pragma` or `Expires`;
    - HEAD returns 200.

- [x] 10.3 After 10.2, run 7.4's orphan check against the local stack, in Git Bash, with `docker compose --env-file secrets.env` in place of `$Prod`. Also count the bucket's keys and compare with `select count(*) from stickers`.

  Verify: both `comm` commands print nothing, and the two counts are equal.
  Notes (2026-10-11, Claude):
  - The bucket listing exited 0, and both `comm` commands printed nothing.
  - The bucket has 4 keys for 4 `stickers` rows.
  - `upload_audit` has 6 rows:
    - 4 `accepted` with public IDs (7,737,554, 325, 61,968 and 6,728 bytes);
    - `rejected | unsupported_type` (16 bytes);
    - `rejected | too_large` with no size.

    These match 10.2: the photo, the icon, the APNG, the transparent PNG, the renamed text file and the 10.5 MiB snippet.

## 11. Production redeploy

- [x] 11.1 [user] Push, then take GitHub's hash (PowerShell): `git push origin feature/upload`, then `git ls-remote origin refs/heads/feature/upload`.

  Verify: the hash equals `git rev-parse HEAD` on Windows.
  Notes (2026-10-11, reported by the user): pushed `feature/upload`. `git ls-remote origin refs/heads/feature/upload` and `git rev-parse HEAD` both print `abb05e859f91e832e3a46cda5ecbad1b86bb6fcb`.

- [x] 11.2 [user] On the server:
  ```bash
  cd /srv/discosticker
  Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'
  git fetch origin
  git switch feature/upload
  git pull --ff-only
  git rev-parse HEAD
  ```

  Verify: the last line equals the hash from 11.1. If not, stop.
  Notes (2026-10-11, reported by the user):
  - `fetch` created `origin/feature/upload`, `git switch feature/upload` set up tracking, and `git pull --ff-only` was already up to date.
  - `git rev-parse HEAD` printed `abb05e859f91e832e3a46cda5ecbad1b86bb6fcb`, matching 11.1.

- [x] 11.3 [user] This change adds no secret. Confirm on the server: `diff <(cut -d= -f1 secrets.env) <(grep -Ev '^(#|$)' secrets.env.example | cut -d= -f1); echo $?`.

  Verify: it prints only `0`.
  Notes (2026-10-11, reported by the user): the names diff printed only `0`. No new secrets.

- [x] 11.4 [user] Build, start, and recreate the gateway. `routes.conf` is in its image, and the branch changed.
  ```bash
  $Prod up --build -d --wait --wait-timeout 900; echo "exit=$?"
  $Prod up -d --force-recreate --no-deps --wait gateway
  $Prod exec gateway ls /etc/nginx/conf.d
  $Prod exec gateway nginx -t -q; echo "nginx-t=$?"
  $Prod exec gateway sh -c "grep client_temp /proc/mounts"
  $Prod exec api sh -c "grep ' /tmp ' /proc/mounts; tr '\0' ' ' < /proc/1/cmdline; echo"
  $Prod ps -a --format '{{.Service}} | {{.Status}}'
  ```
  If the build runs out of memory, use the runbook's one-image-at-a-time fallback.

  Verify:
  - `exit=0`.
  - `discosticker.conf` is listed.
  - `nginx-t=0`.
  - Both tmpfs lines contain `size=131072k`, and the api's command line contains `-Xmx1g`.
  - gateway, api, postgres and seaweedfs are `(healthy)`, and bucket-init is `Exited (0)`.

  Notes (2026-10-11, reported by the user):
  - `$Prod up --build -d --wait --wait-timeout 900` gave `exit=0`; the api and gateway images rebuilt in about 57 s.
  - After recreating the gateway:
    - `conf.d` lists `discosticker.conf`, and `nginx-t=0`;
    - gateway tmpfs `/var/cache/nginx/client_temp ... size=131072k`;
    - api tmpfs `/tmp ... size=131072k`, and the api command line `java -Xmx1g -jar /app/app.jar`;
    - api, gateway, postgres and seaweedfs `(healthy)`, and bucket-init `Exited (0)`.
  - Postgres and seaweedfs stayed up throughout (Up 20 hours), so their volumes were untouched.

- [x] 11.5 Run the signed-out smoke checks from Windows PowerShell (Claude may run these):
  - the production header check;
  - the upload-limit check from 3.3;
  - the extended prefixed-path loop from 8.3.

  Verify:
  - The header check prints `1` on all 15 lines.
  - The upload-limit check prints `big /api/stickers 413 text/html`, `mid /api/stickers 403 application/json` and `two /api/logout 413 text/html`.
  - The loop prints 401 for `/api/me`, `/api/stickers/limits`, `/oauth2/x` and `/login/oauth2/x`, and 404 for `/s/x` and the unknown ID, each `application/json`.
  - The output is recorded in Notes.
  Notes (2026-10-11, run from Windows PowerShell 5.1: by Claude at 13:00, as reported by the user, and again by Claude in this session with the same results):
  - The production header check printed `1` on all 15 lines: each of the four headers exactly once on `/`, `/api/health` and `/nope/`, and HSTS exactly `max-age=300`.
  - The upload-limit check printed `big /api/stickers 413 text/html`, `mid /api/stickers 403 application/json` and `two /api/logout 413 text/html`.
  - The path loop gave 401 `application/json` for `/api/me`, `/api/stickers/limits`, `/oauth2/x` and `/login/oauth2/x`, and 404 `application/json` for `/s/x` and `/s/AAAAAAAAAAAAAAAAAAAAAA.png`.
  - An unknown sticker's response carries `0` `Set-Cookie` headers. Before this change, production's `/s/x` sent an `XSRF-TOKEN` cookie.
  - Health is `UP` over IPv4 and IPv6, and `/` returns 200.

- [x] 11.6 [user] In production, signed in at https://discosticker.cafe:
  1. The form's numbers match https://discosticker.cafe/api/stickers/limits.
  2. Upload a portrait photo straight from a phone camera, a transparent PNG and a small icon. Each link has the form https://discosticker.cafe/s/<id>.png, and the photo's sticker is upright and taller than wide.
  3. For one link, run 8.3's sticker header check. It shows status 200, `content-type: image/png`, a `content-length`, `cache-control: public, max-age=86400` and `x-content-type-options: nosniff` once, and no `set-cookie`.
  4. Paste each link into a Discord channel next to a real server sticker, on desktop and on a phone. Each shows at about sticker size (spec "Stickers show sticker-sized in Discord").

  Verify (server):
  - `$Prod exec postgres psql -U discosticker -d discosticker -c "select occurred_at, outcome, reason, original_bytes, sticker_public_id from upload_audit order by id desc limit 5"` shows the three `accepted` rows.
  - 7.4's orphan check prints nothing for both `comm` lines.
  - `$Prod logs --since 30m api | grep -c 'Upload accepted'` prints `3`. The processing times on those lines, and the Discord results, are recorded in Notes.

  Notes (2026-10-11):
  - Reported by the user:
    - Form numbers: match https://discosticker.cafe/api/stickers/limits.
    - Uploads:
      - the portrait phone photo, 7,737,554 bytes, stored upright (`H2ZiQwhbnSaDxXwThBypPQ`);
      - `docs/paste-test/160-transparent.png`, 6,728 bytes, transparency kept (`9ye3r5Wy-pTL6Ib5gy6udg`);
      - the small icon, 325 bytes, unchanged (`xAsUaq-qkNbGYbTEtsq51A`).
    - Discord, next to a real server sticker, on desktop: the photo and the 160 transparent image show at about sticker size, with the photo upright; the 32 icon stays small (never scaled up).
    - Discord on a phone (the Android app): the production photo link (`H2ZiQwhbnSaDxXwThBypPQ`, 120 × 160) shows at about sticker size and upright, matching desktop.
    - The 160 transparent image and the 32 icon were checked on desktop only, by the maintainer's choice: about sticker size and small, respectively. 1.2's paste test had already shown the phone drawing a 160 image at sticker size.
    - Server:
      - `upload_audit` has exactly 3 accepted rows (7737554, 6728 and 325 bytes) with matching public IDs.
      - The log lines are `Upload accepted in 4415 ms` (the photo, the first upload after the api restart, so a cold JVM and decoder), `Upload accepted in 62 ms` and `Upload accepted in 51 ms`. No file names or Discord IDs appear in the logs.
      - The orphan check printed nothing for both `comm` lines.
  - Checked by Claude from Windows in this session, on all three stickers:
    - GET 200 and HEAD 200;
    - `Content-Type: image/png`, and `Content-Length` 40578, 6728 and 325, each equal to the downloaded size;
    - `Cache-Control: public, max-age=86400`;
    - `x-content-type-options: nosniff` exactly once;
    - no `Set-Cookie`, `Pragma` or `Expires`.
  - `file` on the downloads: 120 × 160 RGB, 160 × 160 RGBA and 32 × 32 RGB.
  - Done: the phone result is recorded above, and step 4's phone check covers the photo link only.

- [ ] 11.7 [user] Run the takedown procedure (`docs/deploy.md` "Uploads" → "Take down a sticker"; design D19) once in production, on one of the maintainer's own test stickers from 11.6.
  Verify:
  - Before (PowerShell): `curl.exe -sS -o NUL -w "%{http_code} %{content_type}" https://discosticker.cafe/s/<id>.png` prints `200 image/png`. After: the same command prints `404 application/json`.
  - On the server, the orphan check prints nothing for both `comm` lines.
  - `$Prod exec postgres psql -U discosticker -d discosticker -c "select outcome, sticker_public_id from upload_audit where sticker_public_id = '<id>'"` still shows one `accepted` row.
  - `$Prod exec postgres psql -U discosticker -d discosticker -At -c "select count(*) from stickers where public_id = '<id>'"` prints `0`.

## Workflow follow-up

- Archive the change on `feature/upload` (`/opsx:archive`) after 11.7, then open the pull request. The maintainer merges with a merge commit.
- After the merge, switching the server to `master` is the runbook's routine Redeploy step.
- The PM session records the approved decision candidates from design.md in `docs/decisions.md`. This change does not edit it.
