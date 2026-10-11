# Design

## Context

See proposal.md (Why) for motivation, and `specs/sticker-upload/spec.md` for the required behavior. Current state that shapes the approach:

- **api:**
  - Spring Boot 4.1.1 on Java 25, with Spring Security 7.1.1, Spring MVC 7.0.9 and Tomcat 11.0.24.
  - One `SecurityFilterChain` (`login/SecurityConfig`): deny by default, `/api/health` and `/s/**` permitted, 401 JSON entry point, `NullRequestCache`, `csrf.spa()`.
  - An `S3Client` bean (`storage/S3Config`) and `S3Properties` (`app.s3.*`). Data access uses `JdbcClient` and `TransactionTemplate`, and a `Clock` bean exists.
  - There is no multipart configuration, so Boot's defaults apply: 1MB per file, 10MB per request, parts written to disk from the first byte, and Tomcat swallowing 2MB of an aborted upload.
  - Integration tests are `*IT` classes on Testcontainers Postgres and SeaweedFS, driven by `LoginFlow` (a JDK `HttpClient` with a cookie jar) and `FakeDiscord`.
  - The image runs `java -jar` with no heap setting. With no container memory limit, the default heap is 25% of the host's memory: about 1 GB on the 4 GB server, more under Docker Desktop.
- **Observed in production (2026-10-11):** `GET https://discosticker.cafe/s/x` returns 404 JSON with `Set-Cookie: XSRF-TOKEN=…` and `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma` and `Expires`. These come from Spring Security's defaults on the single chain.
- **gateway:**
  - `routes.conf` is shared by the local and production servers and is built into the image.
  - `location ^~ /api/` proxies with `proxy_headers.conf`, which hides the api's copies of the four security headers.
  - `client_max_body_size` is unset, so nginx's 1m default applies.
- **frontend:** a client page (`app/page.tsx`) with `lib/session.ts`, which holds `readCookie` and the `X-XSRF-TOKEN` sign-out call.
- **Production server:** 2 vCPU, 4 GB of memory, no swapfile. Images build on the server.

Facts this design relies on, checked during exploration:

| Fact | Source |
|---|---|
| Discord renders stickers at 160 × 160 dp in desktop chat; uploaded sticker files are 320 × 320 | [Discord support](https://support.discord.com/hc/articles/4402687377815), [Discord API: Sticker](https://docs.discord.com/developers/resources/sticker) |
| Discord's docs don't say what size a pasted image link is drawn at; fetches are capped at 50 MB and 10 s | [Discord link previews](https://docs.discord.com/developers/link-previews/overview) |
| JDK PNG and JPEG readers return width and height from the header | [PNGImageReader:1549](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.desktop/share/classes/com/sun/imageio/plugins/png/PNGImageReader.java#L1549), [JPEGImageReader:842](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.desktop/share/classes/com/sun/imageio/plugins/jpeg/JPEGImageReader.java#L842) |
| JDK 25 decodes CMYK and YCCK JPEGs into a 4-band image; JPEG ICC profiles aren't applied by default; libjpeg reports truncated data as a warning, not an exception | [JPEGImageReader:940-964, 975-981, 263](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.desktop/share/classes/com/sun/imageio/plugins/jpeg/JPEGImageReader.java) |
| JPEG EXIF is exposed only as an `unknown` APP1 marker for the application to parse | [JPEG metadata](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/imageio/metadata/doc-files/jpeg_metadata.html) |
| APNG chunks are unknown to the PNG reader, which returns the static `IDAT` image | [PNG metadata](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/imageio/metadata/doc-files/png_metadata.html), [PNG 3 §4.9](https://www.w3.org/TR/png-3/) |
| With no `X-XSRF-TOKEN` header, Spring Security falls back to `request.getParameter("_csrf")`, and Tomcat parses multipart parts on `getParameter` | [CsrfTokenRequestHandler](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/csrf/CsrfTokenRequestHandler.java), [Request.java:3026](https://github.com/apache/tomcat/blob/11.0.24/java/org/apache/catalina/connector/Request.java#L3026) |
| Spring Security skips its cache headers when `Cache-Control`, `Expires` or `Pragma` is already set, and writes them at commit | [CacheControlHeadersWriter:59-65](https://github.com/spring-projects/spring-security/blob/7.1.1/web/src/main/java/org/springframework/security/web/header/writers/CacheControlHeadersWriter.java#L59-L65) |
| "If Tomcat does not swallow the body the client is unlikely to see the response"; default 2 MiB | [Tomcat HTTP connector](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html) |
| Safari converts a HEIC file to the first type in `accept`; an explicitly listed PNG is not converted | [WebKit bug 303803](https://bugs.webkit.org/show_bug.cgi?id=303803) |

## Goals / Non-Goals

**Goals:**
- Every `sticker-upload` requirement is proven by an automated test, except:
  - the form requirements, checked by type check, build and the [user] browser checks;
  - "Stickers show sticker-sized in Discord", checked by the [user] paste tests;
  - the "Real phone photo" scenario of "EXIF orientation applied", checked by the [user] uploads in 10.2 and 11.6 (D17).
- The `gateway-routing` requirements are proven by commands against the running stack.
- An upload's original bytes never reach a persistent disk, a log or storage.
- No new dependencies and no new secrets.

**Non-Goals:**
- Applying embedded color profiles, reading PNG `eXIf` orientation, or keeping animation.
- An automatic orphan sweep, a cap on rejected attempts, or ending sessions on a ban.
- Measuring or tuning decode speed beyond one recorded measurement.

## Decisions

### D1. One set of upload settings, also served to the form
All limits live in `app.upload.*`, bound to a validated `@ConfigurationProperties` record. Spring's multipart limit reads the file limit through a placeholder:
```yaml
app:
  upload:
    sticker-size: 160        # square box, px
    max-file-size: 10MB      # DataSize is binary: 10,485,760 bytes
    uploads-per-hour: 20
    max-side: 8192
    max-pixels: 32000000
    decode-permits: 2
    decode-wait: 10s
spring:
  servlet:
    multipart:
      max-file-size: ${app.upload.max-file-size}
      max-request-size: 10304KB   # max-file-size + 64 KiB of multipart framing
      resolve-lazily: true        # parse only when the upload controller asks (D2)
server:
  tomcat:
    max-swallow-size: 12MB        # above nginx's 11 MiB (D2)
```
- `GET /api/stickers/limits` returns `acceptedTypes` (from the same enum the type check uses, JPEG first), `maxBytes`, `uploadsPerHour` and `stickerSize` from that record. The form shows nothing it didn't get from there.
- A test asserts that the multipart request limit is exactly the file limit plus 64 KiB and that the swallow size is at least 12 MB, so a later edit can't break that relationship silently.
- The nginx value can't read Spring's settings. It is checked against the stack (3.1, 10.1), and `routes.conf` carries a comment naming `app.upload.max-file-size`.
- Alternative: hard-coding the numbers in the form. Rejected, because the decision requires the form to match the server.

### D2. The size limit at each layer
```
request body bytes (file plus about 0.5 KiB of multipart framing)
0 ------------------ 10 MiB ------------- 11 MiB ------------->
|  accepted          |  api 413           |  nginx 413 (HTML)
|  (file <= 10 MiB)  |  too_large,        |  never reaches the api,
|                    |  audited           |  not audited
```
- **nginx:** `location = /api/stickers { client_max_body_size 11m; proxy_pass http://api:8080; include /etc/nginx/proxy_headers.conf; }`.
  - An exact-match location wins over the `^~ /api/` prefix, so only this path gets 11m, and every other path keeps 1m.
  - It includes `proxy_headers.conf`, so the forwarded headers and header hiding are unchanged.
  - It sets no response headers, so the server-level security headers still apply.
- **Spring:**
  - `max-file-size` is 10 MiB and `max-request-size` is 10 MiB + 64 KiB.
  - With `resolve-lazily: true`, the body is parsed only when the upload controller reads its parts. A size failure is then raised inside that controller, and its own `@ExceptionHandler(MaxUploadSizeExceededException)` audits `too_large`, without a SHA-256 and with no byte size, since neither is known.
  - Other endpoints never parse multipart bodies.
  - If 7.2 shows the exception is raised before the handler is known, the fallback is a global advice that audits only for `POST /api/stickers`.
- **Tomcat:** `max-swallow-size: 12MB` is larger than anything nginx forwards. When the api rejects an upload early, Tomcat reads and discards the rest of the body, and nginx receives the JSON 413 instead of a reset connection (which nginx would turn into a 502).
- **Frontend:**
  - Files over `maxBytes` are refused before sending.
  - Any 413 shows the size message, whether the body is nginx's HTML or the api's JSON.
  - A network error shows a generic message that mentions the limit, because nginx's docs warn that browsers may not display an early 413.
- Alternative: equal limits in nginx and Spring. Rejected, because files just over 10 MiB would then never reach the api to be audited.

### D3. Request pipeline and responses
```
POST /api/stickers  (multipart part "file", X-XSRF-TOKEN header)
 nginx: body > 11 MiB ----------------------------> 413 nginx HTML    not audited
 Spring Security: missing/bad CSRF header --------> 403               not audited
                  signed out (valid CSRF) --------> 401 JSON          not audited
 upload controller (each exit below writes exactly 1 upload_audit row):
  1 parts: exactly one "file"? ---------- no -----> 400 invalid_request
    file > 10 MiB (multipart limit) --------------> 413 too_large  (no SHA-256)
  2 read bytes into memory; SHA-256; size
  3 precheck query: banned? ----------------------> 403 banned
                    accepted in 60 min >= 20? ----> 429 rate_limited + Retry-After
  4 magic bytes: PNG or JPEG? ----------- no -----> 415 unsupported_type
  5 header: side > 8192 or pixels > 32M ----------> 422 dimensions_too_large
    JPEG with 4 components (CMYK/YCCK) -----------> 422 unsupported_color
    JPEG APP1: EXIF orientation (default 1)
  6 decode permit within 10 s ----------- no -----> 503 busy
  7 decode; any exception or decoder warning -----> 422 unreadable
    shrink to fit S, orient, encode PNG (D7)
  8 write transaction (D9) -----------------------> 429 / 403 / 500 error (failed)
 201 {"path":"/s/<id>.png","width":w,"height":h}   Location: /s/<id>.png
```
- **Why steps 2 to 3 come first:** the ban and rate-limit answers don't depend on the file, and checking them before parsing saves decode work.
- **The precheck is one query:** it reads `users` by its unique `discord_id` index and counts accepted audit rows through a partial index. Step 8 checks both again under the row lock.
- **Error bodies** are `{"error":"<code>"}` only. File names, exception messages and the Content-Type are never echoed or logged.
- **Logging:** one INFO line per upload, with the outcome, the reason code and the processing time in milliseconds. It never includes the file name, the Discord ID or an IP. This gives real timings in production (11.6) without a timing test.
- **Links:** the api returns a relative `path`. The page adds `location.origin`, so no link is ever built from the forwarded `Host`.

### D4. Type detection and reader choice
- The type comes from the first bytes only: the PNG signature `89 50 4E 47 0D 0A 1A 0A`, or the JPEG `FF D8 FF`. Then the api takes the reader for exactly that format (`ImageIO.getImageReadersByFormatName("png" | "jpeg")`).
- `ImageIO.read` is never used. It picks whichever registered reader claims the stream, which would accept GIF, BMP, WBMP and TIFF.
- No ImageIO plugins are added. A unit test asserts that each format resolves to exactly one reader, the JDK's own (`com.sun.imageio.plugins.*`), so a library that registers plugins can't widen what parses without a failing test.
- `ImageIO.setUseCache(false)` is set at startup, and input is wrapped in `MemoryCacheImageInputStream`. ImageIO's default cache would otherwise copy the original bytes to a temp file (D14).
- Alternatives:
  - TwelveMonkeys imageio-jpeg 3.15.3 (BSD): registers JVM-wide plugins and widens the parsing surface, and the JDK already decodes what we accept.
  - Apache Tika for type detection: a large dependency for two signatures.

### D5. Header checks before decoding
- `reader.getWidth(0)` and `getHeight(0)` read only the PNG `IHDR` or the JPEG frame header. The caps use `long` arithmetic: side at most `max-side`, product at most `max-pixels`.
- **CMYK and YCCK:** the JPEG metadata tree (`javax_imageio_jpeg_image_1.0`) gives the frame's component count without decoding. Four components means CMYK or YCCK, rejected as `unsupported_color`.
  - JDK 25 can decode these, but only into a 4-band custom color space whose conversion to RGB is slow and, without a profile, approximate.
- **EXIF orientation:** the same metadata tree gives the APP1 bytes (`unknown` node, `MarkerTag` 225, starting `Exif\0\0`).
  - A small in-house parser reads the TIFF header (`II` or `MM`, 42), then IFD0's entry count, then tag `0x0112` (SHORT, count 1, value 1–8).
  - Every offset is bounds-checked. Any failure means orientation 1, and the parser never throws.
  - Alternative: metadata-extractor 2.21.0 (Apache-2.0, plus xmpcore). Rejected: it parses every metadata format in the file to get one tag, and it's a new dependency on untrusted input.
- PNG `eXIf` is ignored (Known limitations).

### D6. Decoding: warnings, concurrency and memory
- **Concurrency:** a fair `Semaphore(decode-permits)`, acquired with `tryAcquire(decode-wait)`. A timeout means `busy`.
  - The permit covers decode, resize and encode, and is released before any database or storage call.
  - The test for `busy` takes all permits from the bean and sets a short `decode-wait`.
- **Warnings:** an `IIOReadWarningListener` turns any decoder warning into `unreadable`. libjpeg reports truncated or corrupt data ("premature end of data") only as a warning, and the reader would otherwise return an image with a gray area. Any exception from `read` is also `unreadable`.
- **Memory** for the decoded image plus the first half-size premultiplied copy (D7) at the 32 MP cap:

  | Source | Bytes per pixel | At 32 MP |
  |---|---|---|
  | JPEG (3-byte BGR) | 3 + 1 | 128 MB |
  | PNG, 8-bit RGBA | 4 + 1 | 160 MB |
  | PNG, 16-bit RGBA | 8 + 1 | 288 MB |

  - Two permits give at most about 576 MB, plus at most 2 × 10 MiB of original bytes.
  - `-Xmx1g` in the api Dockerfile's `ENTRYPOINT` makes this the same budget locally and in production, instead of 25% of whatever memory the host has.
- **What the caps accept:** 12 MP and 24 MP phone photos pass. 48 MP and 50 MP "high-resolution" modes and panoramas wider than 8,192 px are rejected; most of those are over 10 MB anyway.

### D7. Shrink, orient and encode
- **Target size:** with S = `sticker-size`, if `max(w, h) > S`, the longer side becomes S and the shorter becomes `max(1, Math.round(short × S / (double) long))`.
  - It's computed on the stored dimensions. A square box makes fitting and 90° turns commute, so the result equals fitting the upright image.
- **Shrinking:** repeated halving with bilinear interpolation into `TYPE_INT_ARGB_PRE`, while both halves stay at or above the target, then one bicubic step to the exact target.
  - Each halving averages 2 × 2 pixels, so fine detail averages out instead of aliasing. A single bicubic step from 4,000 px to 160 px samples only a 4 × 4 neighborhood out of 25 × 25 source pixels.
  - Premultiplied alpha stops fully transparent pixels' hidden color from bleeding into edges.
  - Alternatives:
    - `Image.getScaledInstance(SCALE_AREA_AVERAGING)`: good quality but a slow, legacy API.
    - Thumbnailator 0.4.21 (MIT): the same algorithm, but a new dependency.
- **No shrink needed:** the image is copied into a working image unchanged.
- **Orientation:** applied after scaling, to the small image, with one `AffineTransform` per EXIF value (rotations and mirrors). This avoids a full-size rotated copy.
- **Encoding:**
  - Convert to `TYPE_INT_ARGB`, or to `TYPE_INT_RGB` when the source has no alpha, which saves a quarter of the size for photos.
  - Write with the JDK PNG writer and no source metadata. A test asserts the chunks are exactly `IHDR`, `IDAT`…, `IEND`. If the writer adds anything else, pass explicit minimal metadata.
- **Quality check (5.3):** a 4,000 × 3,000 image of alternating 1-pixel black and white stripes must come out with every pixel within 127 ± 16. A single-step resize leaves stripes or moiré, which this test would catch.

### D8. Ban check and hourly limit
- **Precheck (D3 step 3)** is one read-only query with no lock. It returns `users.id`, whether `banned_at` is set, and the user's accepted uploads in the last 60 minutes, with the oldest of their times.
- **Write transaction (D9)** starts with `select id, banned_at from users where discord_id = ? for update`. It then re-reads the ban and recounts accepted audit rows where `occurred_at > now() - interval '60 minutes'`.
  - Two concurrent uploads by the same user are serialized on that row. The second sees the first's committed accepted row and gets `rate_limited`.
  - `Retry-After` is the seconds until the oldest counted upload is 60 minutes old, rounded up, at least 1.
- **Counted from `upload_audit`, not `stickers`.** Audit rows are never deleted, so next weekend's owner delete can't free up slots.
- A signed-in user with no `users` row (deleted by hand) gets a failed upload with `error`, logged at ERROR.
- Alternatives:
  - An in-memory counter: lost on restart and not safe across instances.
  - nginx `limit_req`: per IP, not per user.
  - A `SERIALIZABLE` transaction: retries instead of waiting.

### D9. Order of writes
```
decode/shrink/encode done (no database or storage calls yet)
BEGIN; select id, banned_at from users where discord_id = ? for update
  banned or >= 20 accepted in 60 min -> insert audit rejected; COMMIT; 403/429
PUT <publicId>.png (Content-Type image/png)
  fails -> ROLLBACK; audit failed/error in a new transaction; 500
insert stickers; insert upload_audit accepted; COMMIT
  fails -> delete the object (best effort, logged); audit failed/error in a
           new transaction; 500
201
```
- **Guaranteed:** a `stickers` row always has its object, because the object is written before the row is committed.
- **Not guaranteed:** if the api dies between the PUT and the COMMIT, or the compensating delete fails, an orphan object remains.
  - It's unreachable: `/s/` serves only rows.
  - The runbook's orphan check finds it by comparing the bucket listing with `stickers.object_key`.
  - An automatic sweep comes with ban-and-purge.
- **Accepted means audited:** the sticker row and the accepted audit row commit together. This is the same rule as login.
- **Lock duration:** the row lock is held only for the PUT of one small PNG (at most about 100 KB at S = 160) over the internal network.
- Alternative: insert a pending row first, then PUT, then mark it ready. Rejected: it adds a state that every reader has to filter, and pending rows need their own cleanup.

### D10. Data model (Flyway V3, V4)
```sql
-- V3__create_stickers.sql
create table stickers (
    id          bigint generated always as identity primary key,
    public_id   text        not null unique check (public_id ~ '^[A-Za-z0-9_-]{21}[AQgw]$'),
    owner_id    bigint      not null references users (id),
    object_key  text        not null unique,
    width       integer     not null check (width > 0),
    height      integer     not null check (height > 0),
    png_bytes   integer     not null check (png_bytes > 0),
    created_at  timestamptz not null default now()
);
create index stickers_owner_created on stickers (owner_id, created_at);

-- V4__create_upload_audit.sql
-- One row per upload attempt by a signed-in user that passed the CSRF check.
-- No IP address. No foreign key: audit rows outlive stickers and accounts.
create table upload_audit (
    id                bigint generated always as identity primary key,
    occurred_at       timestamptz not null default now(),
    discord_id        bigint      not null,
    outcome           text        not null check (outcome in ('accepted', 'rejected', 'failed')),
    reason            text        check (reason in ('banned', 'too_large', 'unsupported_type',
                          'dimensions_too_large', 'unreadable', 'unsupported_color',
                          'rate_limited', 'busy', 'invalid_request', 'error')),
    original_sha256   bytea       check (octet_length(original_sha256) = 32),
    original_bytes    bigint      check (original_bytes >= 0),
    sticker_public_id text,
    check ((outcome = 'accepted') = (reason is null)),
    check ((outcome = 'failed') = (reason = 'error')),
    check ((outcome = 'accepted') = (sticker_public_id is not null)),
    check (outcome <> 'accepted' or original_sha256 is not null)
);
create index upload_audit_accepted_recent on upload_audit (discord_id, occurred_at)
    where outcome = 'accepted';
```
- **`public_id`'s check** accepts only the canonical 22-character encoding of 128 bits: the last character carries 2 bits, so it must be one of `A`, `Q`, `g`, `w`. Each sticker therefore has exactly one URL.
  - Java's decoder accepts the non-canonical variants, which is why lookups compare the string and never decode it.
- **`owner_id`** is the internal account key. It never leaves the api.
- **`original_bytes`** is null when the size isn't known (`too_large`).

### D11. Public IDs and object keys
- **Public ID:** 16 bytes from a shared `SecureRandom`, encoded with `Base64.getUrlEncoder().withoutPadding()` (22 characters).
- **Object key:** `<publicId>.png`, stored in `object_key`. Moving to a different key layout later is then a data change, not a code change.
- **Collisions:**
  - Two random IDs colliding has a probability of about 2⁻⁶⁵ even after 2³² stickers. A collision would overwrite an object before the unique constraint fails, and this is accepted rather than handled.
  - SeaweedFS's support for conditional PUT (`If-None-Match`) is unverified, so it isn't relied on.
- Alternative: a separate random object key. It adds a second random value to keep in step and protects nothing: anyone who can list the bucket can already read the images.

### D12. CSRF token from the header only
- Keep `csrf.spa()` (the cookie repository and token handling) and override its request handler with `csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler())`, about 15 lines:
  - `handle(...)` delegates to an `XorCsrfTokenRequestAttributeHandler` with a null attribute name, exactly as `spa()` does.
  - `resolveCsrfTokenValue(...)` returns only `request.getHeader(token.getHeaderName())`.
- The frontend sends the raw cookie value, which the filter compares to the stored token in constant time. That is the comparison `spa()` already makes when the header is present.
- **Why:** with no header, the default resolver calls `request.getParameter("_csrf")`. For `multipart/form-data`, Tomcat then parses every part to temp files, inside `CsrfFilter` and before authorization. Anyone could make the api parse up to the multipart limit before getting a 403.
- **Effect:** applies to every endpoint. Sign-out already uses the header, and the api has no server-rendered forms.

### D13. Serving `/s/<id>.png`
- **A second filter chain:** `SecurityFilterChain` with `@Order(1)` and `securityMatcher("/s/**")`:
  - `permitAll`;
  - CSRF disabled (only GET and HEAD are mapped);
  - session management `STATELESS`, so no session is read, created or touched;
  - `NullRequestCache`;
  - headers left at their defaults except `cacheControl` disabled, because the controller sets its own.

  This removes the `XSRF-TOKEN` cookie and Spring's `no-store` headers observed on `/s/x` today. The main chain is unchanged.
- **Controller** `GET /s/{file}`:
  1. `file` must match `^([A-Za-z0-9_-]{21}[AQgw])\.png$`, or it throws `ResponseStatusException(NOT_FOUND)` before any query.
  2. It looks up `object_key` and `png_bytes` by `public_id`. A missing row gets the same 404.
  3. On a hit, it sets `Content-Type: image/png`, `Content-Length` and `Cache-Control: public, max-age=86400` on the servlet response.
  4. It opens `GetObject` and `transferTo`s the stream onto the response. For HEAD, it skips the storage read.
  5. A row whose object is missing gets a 404 and an ERROR log.
- **Same 404 for malformed and unknown IDs:**
  - Both go through Boot's error handling, so they get the same JSON shape (`timestamp`, `status`, `error`, `path`).
  - Paths with extra segments match no handler and get the same.
  - The `/error` dispatch runs through the main chain, so its headers don't matter here.
- **Why the servlet response and not a `Resource`:** writing directly gives exact headers. Spring's `InputStreamResource` handling would answer a `Range` request with 416 and can read the stream to compute a length.
- **Nothing else needed:** the bytes are always our own PNG and nginx adds `nosniff`, so no `Content-Disposition` or CSP is needed. A CSP stays out of scope by decision.

### D14. Original bytes and temporary files
- **Where originals exist:** nginx's client-body buffer, Tomcat's multipart part, and the api's byte array. They are never in storage, the database or logs.
- **gateway:** compose mounts tmpfs at nginx's compiled-in client-body path, `/var/cache/nginx/client_temp:size=128m`. The path is checked with `nginx -V`.
- **api:**
  - Compose mounts `/tmp:size=128m` as tmpfs and sets `SPRING_SERVLET_MULTIPART_LOCATION=/tmp`. `file-size-threshold` stays 0B, so parts go to that tmpfs, not the heap.
  - `/tmp` is the JVM's `java.io.tmpdir`, so anything else that writes a temp file also stays in memory.
- **Disk safety:** the server has no swapfile, so tmpfs pages never reach disk. Each file is deleted when its request ends.
- **Capacity:** 128m holds about 11 maximum-size uploads in flight at each layer. Beyond that, the upload fails with an error, and no data is lost or persisted.
- **Logs:** no file name, Content-Type or byte is logged.
- **Test settings:** the ITs run Tomcat on the host with Boot's default temp location, so the tmpfs mounts are checked against the stack (2.3, 10.1).
- **Alternatives:**
  - `file-size-threshold` above 10 MiB, which keeps parts on the heap: 11 MiB of heap per in-flight upload, unbounded by any cap.
  - `client_body_buffer_size 11m` in nginx: memory per connection, including connections anyone can open.

### D15. Frontend
- **Shared cookie helper:** `lib/cookies.ts` holds `readCookie`, moved from `lib/session.ts`.
- **`lib/stickers.ts`:**
  - `getLimits()`;
  - `uploadSticker(file)`, which returns a tagged union: accepted with path and size, or rejected with a code or status;
  - `uploadMessage(result, limits)`, with fixed messages:

    | Code | Message |
    |---|---|
    | `too_large` | "That file is over {maxBytes} MB." |
    | `unsupported_type` | "Only {types} images can be uploaded." |
    | `dimensions_too_large` | "That image's dimensions are too large." |
    | `unreadable` | "That image couldn't be read. It may be damaged." |
    | `unsupported_color` | "CMYK JPEGs aren't supported. Save it as an RGB JPEG or a PNG." |
    | `rate_limited` | "You've reached {uploadsPerHour} uploads this hour. Try again later." |
    | `busy` | "The server is busy. Try again in a moment." |
    | `banned` | "This Discord account can't upload stickers." |
    | anything else | "Upload failed. Please try again." |

  - The `{…}` values come from the limits response. Known MIME types map to fixed labels (`image/jpeg` is "JPEG", `image/png` is "PNG"), and unknown ones are not shown.
- **Status handling:**
  - Any 413 shows the `too_large` message.
  - A 401 re-checks the session, which shows the sign-in link.
  - A 403 without our `banned` code (a CSRF failure; Boot's body carries `"error":"Forbidden"`, which isn't one of our codes) shows the generic message.
  - A network error shows the generic message plus "Files must be under {maxBytes} MB."
- **`app/page.tsx`:**
  - When signed in and the limits have loaded, it shows the form: `accept` built from `acceptedTypes` in order, the limits text, "Animated images become a still image.", and the browser-side checks.
  - It ensures an `XSRF-TOKEN` cookie exists before posting; the limits request sets one.
  - It shows the result link (`location.origin + path`) in a read-only input with a Copy button (`navigator.clipboard.writeText`, which works on HTTPS and localhost).
  - "Sticker uploads are coming soon." goes.
- **Nothing from the server is rendered as HTML.** The code's only uses are the message table and the limits numbers.
- **Checks:** no frontend test runner (by decision). Type check, build, grep checks (9.1) and [user] browser checks (10.2).

### D16. Discord paste test and the sticker size
- **Paste-test images:** `docs/paste-test/` holds generated PNGs, committed to `feature/upload`:
  - squares of 32, 64, 128, 160, 240, 320 and 480 px, each with its size printed inside and a 1 px border;
  - 320 × 160 and 160 × 320;
  - a transparent 160 × 160.
- **The test:** the maintainer pastes their raw.githubusercontent.com URLs (plain text in the README, for example https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/160.png) into a private channel next to a real server sticker, on desktop at 100% zoom and on a phone.
  - This answers what the docs don't: whether small images are drawn at natural size or upscaled, and how large the largest are drawn.
  - GitHub serves them as `image/png`, and Discord's rendering depends on the image, not the origin. The production paste (11.6) confirms it on our own origin.
- **The result:**
  - The square that matches a sticker becomes S, approved in the PM session (1.3).
  - If S isn't 160, 1.3 updates the default, the spec scenarios that name 160, and the limits scenario.
  - Nothing else depends on S until 7.2, which uses the configured value.

### D17. Test approach and fixtures
- **Unit tests (surefire, no Spring):**
  - type detection and the reader-class guard;
  - header caps and bombs, with a counting decoder seam proving decode is never called;
  - CMYK detection;
  - the EXIF parser;
  - fit arithmetic, stripes quality, alpha and palette transparency, and orientation transforms;
  - PNG output chunks;
  - truncated PNG and JPEG, and progressive JPEG;
  - `setUseCache(false)` being in effect.
- **Integration tests (failsafe):**

  | Test | Covers |
  |---|---|
  | `UploadLimitsIT`, `UploadSettingsIT` | the limits endpoint and D1's relationships |
  | `SchemaIT` (extended) | the new tables |
  | `ApiSecurityIT` (extended) | header-only CSRF |
  | `StickerUploadIT` | every upload requirement |
  | `StickerServingIT` | serving and 404s |

  - They extend `LoginFlow` with a multipart POST.
  - Storage is checked by listing the bucket before and after.
  - Audit rows are read per test with a fresh Discord ID.
  - Rate-limit windows are set up by inserting back-dated accepted audit rows.
  - The "PUT fails" case runs in a context whose `app.s3.bucket` names a bucket that doesn't exist.
  - The `busy` case takes every permit from the decode limiter bean and sets a short `decode-wait`.
- **Fixtures:**

  | Kind | Fixtures |
  |---|---|
  | Generated in code (`TestImages`, in memory) | text file named `sticker.png`; PNG plus appended HTML; GIF, WebP, BMP and SVG headers; truncated PNG and JPEG; PNG `IHDR` declaring 50,000 × 50,000; JPEG frame header patched to 65,000 × 65,000; cap boundaries (header-only); EXIF JPEGs with orientation 1, 3, 6 and 8 (little-endian) and 6 (big-endian), written through the JDK JPEG writer with an APP1 `unknown` marker; a malformed APP1; a red square on transparent green; a palette PNG with `tRNS`; a progressive JPEG; 1 × 1, S × S, (S+1) × S, S × (S+1) and 8,000 × 1; a valid PNG padded with `tEXt` to exactly 10,485,760 bytes, and 10,485,761 bytes; 4,000 × 3,000 stripes; a JPEG with an EXIF GPS IFD; a CMYK JPEG written as a 4-band raster (its frame header has 4 components, and the JDK reader decodes it as CMYK) |
  | Committed | None: every fixture is generated in code (5.2 confirmed the JDK writer produces a usable CMYK JPEG) |

  - **No committed real phone photo (PM decision).** A real photo's EXIF holds the device model, the camera software and the capture time, and committing it would publish them in the public repo.
    - Real-camera orientation is checked by hand instead: 10.2 step 2 and 11.6 upload a portrait photo straight from a phone camera and expect an upright, taller-than-wide sticker.
    - Those manual uploads are also the guard against the EXIF fixture writer and parser sharing the same mistake.
  - Dropped by PM decision: YCCK, 16-bit, grayscale and APNG fixtures, the gateway-limit unit test, the "commit fails after the PUT" test and a time-budget test. The APNG requirement is checked by hand in 10.2.

### D18. Deploy
- **gateway:** `routes.conf` is in the image, so it is rebuilt. The branch switch requires recreating the gateway, per the runbook.
- **api:** the image changes (code, migrations, `-Xmx1g`). Compose's new tmpfs mounts change the configuration of both containers, so `up` recreates them.
- **Secrets:** none added. `secrets.env` is untouched.
- **Migrations:** Flyway applies V3 and V4 at api start. There's no existing data to migrate.

## Threats considered (uploads, storage, sessions)

| # | Threat | Handling | Verified by |
|---|---|---|---|
| T1 | **Decompression bomb:** a small file declaring huge dimensions. | Width and height from the header; caps of 8,192 per side and 32 MP before any pixel is decoded (D5). | 5.3 (decoder never called); 7.2 |
| T2 | **Memory or CPU exhaustion** through big bodies or many concurrent uploads. | nginx 11 MiB, Spring 10 MiB, 2 decode permits with a 10 s wait and then `busy`, the pixel cap, `-Xmx1g`: at most about 576 MB of decode memory (D2, D6). | 3.1; 7.2 (`too_large`, `busy`); 2.3 (heap flag) |
| T3 | **Type confusion and polyglots:** HTML, SVG or script presented as an image; data appended to a valid image. | Magic bytes only; the JDK reader for that one format; a full re-encode drops everything but pixels; served only as `image/png`, with `nosniff` from nginx (D4, D7, D13). | 5.3; 7.2 (renamed file, appended HTML); 8.1; 11.5 |
| T4 | **Truncated or corrupt files** producing partial images. | Reader exceptions and any decoder warning mean `unreadable` (D6). | 5.3; 7.2 |
| T5 | **Decoder bugs** (JPEG decoding is native libjpeg). | Only the two JDK readers, pinned by a test; caps bound the work; JDK fixes arrive with base-image rebuilds. Residual risk accepted. | 5.3 (reader-class guard) |
| T6 | **CSRF on upload.** | The `X-XSRF-TOKEN` header must equal the `XSRF-TOKEN` cookie, which a cross-site page can't read; the session cookie is SameSite=Lax. | 7.2 (403 without a token) |
| T7 | **Pre-authorization body parsing:** anonymous multipart requests parsed to temp files during the CSRF check. | Header-only token resolution; lazy multipart parsing in the upload controller only (D2, D12). | 6.1 (resolver never calls `getParameter`; `_csrf` form field refused) |
| T8 | **Original bytes persisted** through temp files, ImageIO's cache, logs or storage. | tmpfs for nginx's client-body buffer and the api's `/tmp`, with no swap on the server; ImageIO cache off; nothing logged; storage holds only PNGs (D14). | 2.3 and 10.1 (mounts); 5.3 (cache off); 7.2 (storage holds only the PNG) |
| T9 | **Location and other metadata leaking** (EXIF GPS, text chunks, ICC). | The output PNG is written with no source metadata: exactly `IHDR`, `IDAT`, `IEND` (D7). | 5.3; 7.2 (GPS JPEG) |
| T10 | **Rate-limit race:** two uploads both take the 20th slot. | A per-user row lock and a recount inside the write transaction (D8). | 7.2 (two concurrent uploads at 19) |
| T11 | **Storage and database disagree** after a failure. | Object first, then the row and audit commit together; a compensating delete; a `failed` audit; a runbook orphan check (D9). | 7.2 (PUT fails); 7.4; 10.3 |
| T12 | **Guessing or enumerating links.** | 128 random bits from `SecureRandom`; a canonical-form check before any lookup; malformed and unknown IDs give the same 404; no database key in any response (D10, D11, D13). | 7.2 (exact JSON fields); 8.1 |
| T13 | **Cookies and session effects on public, cacheable responses:** a shared cache storing `Set-Cookie`, or viewing a sticker touching a session. | The `/s/**` chain has no CSRF filter, is stateless and sets no cookie (D13). | 8.1 |
| T14 | **Served stickers executed or rendered as something else.** | Always our own PNG, exactly `image/png`, `nosniff`, `X-Frame-Options: DENY` from nginx. | 8.1; 11.5 |
| T15 | **Purged content staying in caches.** | `max-age=86400` bounds the caches we influence. Discord's media proxy is outside our control (Known limitations). | 8.1 (header) |
| T16 | **Ban window:** a hand-banned user keeps an in-memory session for up to 8 hours. | Upload checks the ban in the precheck and again under the write lock (D8). | 7.2 (banned after sign-in) |
| T17 | **Audit flooding.** | Only signed-in requests that pass CSRF are audited. Rejected attempts aren't rate-limited this weekend (PM decision); each costs one row and bounded CPU. | 7.2 (signed-out and CSRF failures write no row) |
| T18 | **Database keys exposed.** | Responses carry only `path`, `width`, `height`; `owner_id` stays internal. | 7.2; 8.1 |
| T19 | **Log injection or data leaks through logs.** | File name, Content-Type and bytes are never logged; one INFO line holds the outcome, a fixed code and a duration (D3). | 7.2 (captured output never contains the uploaded file name) |
| T20 | **Links built from a forged Host header.** | The api returns a relative path; the page adds its own origin (D3). | 7.2 (`path` is relative) |

## Known limitations

- **Color profiles:** embedded profiles aren't applied, so Display P3 photos come out slightly duller.
- **Orientation:** PNG `eXIf` orientation is ignored.
- **Large photos:** 48 MP and 50 MP photos and panoramas wider than 8,192 px are rejected for their dimensions.
- **Shrinking:** averages in sRGB space, so very fine detail comes out slightly darker.
- **Not audited:** nginx's 413s, because they never reach the api.
- **Orphans:** an orphan object can remain after a crash between the PUT and the COMMIT. The runbook check finds it; the sweep comes with ban-and-purge.
- **Purge:** Discord's media proxy can keep showing a sticker after a future purge.
- **High-DPI screens:** if S stays 160, stickers look soft on them.
- **Rejected attempts:** not rate-limited.
- **Bans:** a banned user's existing session can still call `/api/me` and sign out until it ends. Only upload checks the ban.
- **Visitor IPs:** still only in nginx's access log (unchanged).
- **tmpfs and swap:** tmpfs keeps originals off disk only while the server has no swapfile. If one is ever added, tmpfs pages can be swapped to disk, so revisit D14.

## Risks / Trade-offs

- **[Discord draws image links at a size other than expected]** → The paste test (1.2) runs first, and S is one setting. Only 7.2's expectations depend on its value, through the configured property.
- **[A browser still sending a large body shows a network error, not nginx's 413]** → The browser-side check stops oversized files before sending. The generic message names the size limit.
- **[The api rejects early and nginx turns the reset into a 502]** → `max-swallow-size` is 12MB, above anything nginx forwards. 10.2 uploads 10.5 MiB through the gateway and expects JSON `too_large`.
- **[tmpfs fills under many large concurrent uploads]** → 128m per layer, about 11 maximum-size uploads in flight. A full tmpfs fails the request; it doesn't lose or persist data. Raise the size if it's ever seen.
- **[The api's `/tmp` tmpfs is `noexec`]** → The JDK's native libraries load from its own `lib` directory, and nothing extracts executables to `/tmp`. The health check and 10.2's uploads prove the JVM and decoders work.
- **[Decoder warnings reject legitimate photos]** → The real phone-camera uploads in 10.2 and 11.6 check this. If a real camera's files warn, narrow the rule to libjpeg's truncation and corrupt-data warnings, and record the change.
- **[The JDK writer can't produce a CMYK JPEG that its reader sees as 4-component]** → Commit a CMYK fixture made with an external tool, and record the command (5.2).
- **[The JDK PNG writer adds an ancillary chunk]** → 5.3's chunk test catches it. Write with explicit minimal metadata.
- **[Lazy multipart parsing raises the size exception outside the upload controller]** → 7.2's `too_large` case pins the behavior. The fallback is a global advice that audits only `POST /api/stickers` (D2).
- **[Decode time on 2 vCPUs]** → 2 permits and a 10 s wait. 5.5 measures a 12 MP decode once, and production's INFO line (11.6) gives real numbers.
- **[The server's build runs out of memory with two rebuilt images]** → Use the runbook's one-image-at-a-time fallback.
- **[A rollback to `master` breaks sticker links]** → The old code has no `/s/` handler, so links 404 until redeployed. Data and objects stay intact.

## Migration Plan

1. The paste test (1.1, 1.2) runs first. PM approval of S (1.3) can happen any time before 7.2.
2. Settings, runtime, gateway, schema, image pipeline, CSRF, upload, serving and frontend land in task order. Each task is verified by its own test before the next.
3. Local stack checks (10.1–10.3). The runbook updates land with the work they describe (3.3, 7.4, 8.3).
4. Production redeploy from `feature/upload` (section 11) follows the runbook's Redeploy section:
   - push, then compare the server's `HEAD` with GitHub's hash;
   - `up --build`;
   - recreate the gateway;
   - smoke checks;
   - a [user] upload and Discord paste in production.
5. **Rollback:** on the server, `git switch master`, `$Prod up --build -d --wait`, then recreate the gateway.
   - The new tables stay; Flyway doesn't roll back, and the old code ignores them.
   - Objects stay in the bucket, and links return 404 until the change is redeployed.
   - The tmpfs mounts go away with the old compose file.
6. Switching the server to `master` after the merge is the runbook's routine step, not a task here.

## Decision candidates for the PM session

One line each. This change does not add them to `docs/decisions.md`.

- The standard sticker size is an S × S box set by `app.upload.sticker-size`, 160 until the Discord paste test, with the final value approved after it.
- "10 MB" means 10 MiB (10,485,760 bytes) in the server, the limits endpoint and the form.
- nginx accepts 11 MiB on `/api/stickers` only; Spring allows a 10 MiB file in a 10304KB request; Tomcat swallows up to 12MB; nginx's 413s are not audited.
- Dimensions are read from the header and capped at 8,192 px per side and 32,000,000 pixels; at most 2 decodes at once, and a 10 s wait before `busy`; the api runs with `-Xmx1g`.
- Only the JDK's PNG or JPEG reader, chosen by magic bytes, is used; never ImageIO's auto-detection, and no extra ImageIO plugins.
- CMYK and YCCK JPEGs are rejected; embedded color profiles are ignored; an animated PNG becomes its still image; any decoder warning means `unreadable`.
- JPEG EXIF orientation is applied by a small in-house APP1 parser; output PNGs carry no metadata at all.
- Upload rejects banned users (403 `banned`, audited), checked before processing and again under the write lock.
- 20 accepted uploads per rolling 60 minutes, counted from accepted audit rows under a per-user row lock; rejected attempts don't count, and there is no cap on attempts this weekend.
- Order of writes: process, then lock and recount, PUT, and insert sticker plus audit in one commit; a compensating delete on failure; an orphan check in the runbook now, the sweep with ban-and-purge.
- The object key is `<publicId>.png`, stored in `stickers.object_key`.
- Upload audit outcomes are `accepted`, `rejected` or `failed`, with fixed reason codes; only signed-in requests that pass CSRF are audited.
- Endpoints: `POST /api/stickers` (201 with a relative `path`) and `GET /api/stickers/limits`.
- The CSRF token is accepted only from the `X-XSRF-TOKEN` header, on every endpoint, so request bodies are never parsed before authorization.
- `/s/**` has its own security filter chain (no CSRF, stateless, no cookies) and sends `Cache-Control: public, max-age=86400`; malformed and unknown IDs get the same 404.
- Upload bodies are buffered on 128m tmpfs in the gateway and the api, and ImageIO's disk cache is off, so originals never reach a persistent disk.
- The file picker lists JPEG before PNG, because Safari converts HEIC photos to the first listed type.
- The form shows "Animated images become a still image.", and the frontend contains no literal limits.
