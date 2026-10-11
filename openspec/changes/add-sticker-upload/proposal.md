# Proposal

## Why

Signed-in users can't do anything yet: login is live at https://discosticker.cafe, but the product's one feature, turning an image into a sticker-sized link for Discord, doesn't exist. This change adds the upload, the processing and the public sticker link, with every security property in `docs/decisions.md` (content typing, size and dimension limits, re-encoding, no stored originals, private storage, per-user rate limits, audit records). It then redeploys to production.

## What Changes

- **Upload endpoint:** `POST /api/stickers` takes one image as multipart form data, signed in only, with the `X-XSRF-TOKEN` header. Success returns 201 with the sticker's path (`/s/<id>.png`) and its dimensions. Every rejection returns a fixed reason code in JSON.
- **What's accepted:**
  - PNG and JPEG only, detected from the file's first bytes. The file name and Content-Type are ignored.
  - At most 10 MiB (10,485,760 bytes, shown as "10 MB").
  - At most 8,192 px per side and 32,000,000 pixels in total, both read from the header before any pixels are decoded.
  - Truncated or corrupt files, and CMYK JPEGs, are rejected.
  - An animated PNG is accepted as its still image.
- **Processing:**
  - Every upload is decoded and re-encoded to PNG.
  - An image larger than the standard sticker size is shrunk to fit a square of that size, keeping its aspect ratio. An image at or below it keeps its dimensions. Images are never scaled up.
  - The standard size is one setting, `app.upload.sticker-size`, defaulting to 160 px. A Discord paste test early in the tasks, then PM approval, set the final value.
  - Transparency is kept. A JPEG's EXIF orientation is applied, and every piece of metadata (including EXIF GPS location) is dropped.
  - At most 2 images are decoded at once. An upload that can't start decoding within 10 seconds gets a "busy" response.
- **Rules per user:**
  - At most 20 accepted uploads in any 60 minutes. The 21st gets a "try again later" response (429). Rejected attempts don't count.
  - Banned users can't upload. This closes the window in which a hand ban only took effect at the next login.
  - Concurrent uploads can't both take the last slot.
- **Original bytes:**
  - Never stored or served. Object storage holds only the re-encoded PNG.
  - Upload bodies are buffered on size-capped tmpfs in the gateway and the api, so originals never reach a persistent disk.
  - The audit record keeps the original's SHA-256 and size.
- **Upload audit:** one record per upload attempt by a signed-in user that passed the CSRF check, accepted, rejected or failed. It holds the Discord ID, a fixed reason code, the original's SHA-256 and size when the bytes were read, and the sticker's public ID when accepted. It holds no IP address.
- **Limits endpoint:** `GET /api/stickers/limits` (signed in) returns the accepted types, size limit, hourly limit and sticker size. These are the same settings the server enforces.
- **Serving:** `GET /s/<id>.png` is public.
  - The ID is 128 random bits in base64url (22 characters).
  - Responses are exactly `Content-Type: image/png` with `Content-Length` and `Cache-Control: public, max-age=86400`, and never set a cookie.
  - Malformed and unknown IDs get the same 404 JSON.
  - The bucket stays private, with no presigned URLs.
- **CSRF:** the api reads the CSRF token only from the `X-XSRF-TOKEN` header, never from a form field. A multipart body is then never parsed before authorization.
- **Gateway:**
  - `/api/stickers` alone accepts request bodies up to 11 MiB. Every other path keeps nginx's 1 MiB default.
  - Files just over 10 MiB reach the api and are rejected there with an audit record. Anything over 11 MiB gets nginx's 413.
  - The client-body temp path is on tmpfs.
- **Frontend:**
  - The signed-in home page gets an upload form whose numbers all come from the limits endpoint, with "Animated images become a still image."
  - In the browser, a size and type check as a convenience only.
  - The resulting link with a copy button.
  - Fixed messages for each reason code.
- **Runtime:** the api runs with `-Xmx1g`, so memory behaves the same locally and on the 4 GB server.
- **Database:** Flyway migrations V3 (`stickers`) and V4 (`upload_audit`).
- **Runbook:** `docs/deploy.md` gains upload smoke checks, a recent-uploads query and an orphan-object check, and a manual takedown procedure (the row first, then the object; audit rows kept).
- **Production redeploy** from `feature/upload`, following the runbook's Redeploy section.

## Out of scope

- GIF and WebP uploads, and keeping animation (next weekend).
- The "My stickers" page, owner-only delete, admin ban-and-purge, a report link, and importing server stickers.
- An automatic sweep of orphaned storage objects (it comes with ban-and-purge). This change adds a runbook check only.
- An in-app or audited takedown. This change adds a manual runbook procedure only.
- Ending existing sessions when a user is banned. Upload is blocked at once; other signed-in endpoints still work until the session ends.
- A cap on rejected upload attempts. Only accepted uploads are rate-limited this weekend.
- Applying embedded color profiles (Display P3 photos are treated as sRGB), and PNG `eXIf` orientation.
- The theme change, a CDN, storing visitor IPs, Postgres-backed sessions, a frontend test runner and nginx log rotation.
- Editing `CLAUDE.md`, `openspec/config.yaml` or `docs/decisions.md`. Decision candidates are listed in design.md for the PM session.

## Capabilities

### New Capabilities

- `sticker-upload`: signed-in upload of a PNG or JPEG, type, size and dimension checks, re-encoding to a sticker-sized PNG, the per-user rate limit and ban check, upload audit records, the limits endpoint, the upload form, and public serving of stickers at `/s/<id>.png`.

### Modified Capabilities

- `gateway-routing`: adds the upload request-size limit (11 MiB on `/api/stickers` only), memory-backed buffering of request bodies, and the requirement that sticker responses pass through with their content type and cache headers intact.
- `database-migrations`: "Migrations applied at startup" now also creates the stickers and upload audit tables.

## Impact

- **api:**
  - New: upload and limits endpoints, sticker serving, the image pipeline (type detection, header checks, EXIF orientation, decode, resize, PNG encode), rate limit and ban check, upload audit writer, a second security filter chain for `/s/**`, and a header-only CSRF token resolver.
  - New `app.upload.*` settings, and multipart and Tomcat swallow limits in `application.yml`.
  - Migrations V3 and V4.
  - `-Xmx1g` in the api Dockerfile.
  - No new dependencies: image handling uses the JDK's ImageIO and Java 2D.
- **gateway:** `routes.conf` gains `location = /api/stickers`. It's built into the image, so the gateway is rebuilt and, after the branch switch, recreated.
- **compose:** tmpfs mounts for the api's `/tmp` (multipart temp files) and the gateway's client-body temp path, local and production. No new secrets.
- **frontend:** an upload form and a small stickers helper. No new dependencies.
- **docs:** `docs/deploy.md` (smoke checks, queries). `docs/paste-test/` holds the paste-test images.
- **Production:** redeploy from `feature/upload`, rebuilding api and gateway. `secrets.env` is unchanged.

## Security impact

- **New attack surface:**
  - parsing untrusted images (native libjpeg for JPEG);
  - multipart request bodies;
  - a public, unauthenticated serving path;
  - a write path that touches both object storage and the database.

  design.md lists each threat and how it's handled.
- **Effects on the project's security constraints:**
  - **Uploads:** implemented as the constraints require.
    - Size limit at nginx (11 MiB on the upload path) and in Spring (10 MiB per file).
    - Type from magic bytes only.
    - Dimensions from the header before decoding.
    - Re-encoding to PNG.
    - Originals never stored or served, with their SHA-256 kept in the audit record.
  - **Per-user rate limits:** 20 accepted uploads per rolling hour, enforced under a per-user database lock.
  - **Audit records:** every upload attempt by a signed-in user that passes CSRF is audited, accepted or rejected. Requests rejected before that (signed out, CSRF, nginx's 413) are not: those can come from anyone, so auditing them would let anonymous requests flood the table, as with login.
  - **Public IDs:** 128-bit random, base64url. The `stickers` primary key and the owner's account ID never appear in a URL or response.
  - **No CORS:** unchanged.
  - **Ban and account-age checks at login:** unchanged. Upload adds a ban check of its own (defense in depth).
  - **Bans set by hand:** unchanged. There is no ban UI; an upload rejected for a ban gets an upload audit record, not a ban audit event.
  - **Secrets:** none added.
  - **Security headers:** nginx stays their only source. `/s/` responses get `X-Content-Type-Options: nosniff` from nginx and set `Content-Type: image/png` exactly.
  - **CSRF:** tightened. The token is accepted only from the header, which the frontend already uses for sign-out.
