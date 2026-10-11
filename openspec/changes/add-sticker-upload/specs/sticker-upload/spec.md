# Spec Delta

## Purpose

Lets a signed-in Discord user upload a PNG or JPEG and get a permanent, unguessable link to a re-encoded PNG that shows at sticker size in Discord. It covers the checks on every upload, the per-user limits, the upload audit trail, the upload form, and public serving of stickers.

## ADDED Requirements

### Requirement: Uploading requires sign-in and a CSRF header
`POST /api/stickers` SHALL accept an upload only from a signed-in user whose request carries an `X-XSRF-TOKEN` header matching the `XSRF-TOKEN` cookie. A signed-out request with a valid token SHALL get 401 JSON. A request without a valid token SHALL get 403. In both cases nothing SHALL be stored and no upload audit record SHALL be written.

#### Scenario: Signed out with a valid CSRF token
- **WHEN** a signed-out client sends a valid PNG to `POST /api/stickers` with an `X-XSRF-TOKEN` header matching its `XSRF-TOKEN` cookie
- **THEN** the response is 401 with a JSON body, no object is added to storage, and no sticker record or upload audit record is written

#### Scenario: Signed out without a CSRF token
- **WHEN** a signed-out client sends a valid PNG to `POST /api/stickers` with no `X-XSRF-TOKEN` header
- **THEN** the response is 403, no object is added to storage, and no sticker record or upload audit record is written

#### Scenario: Signed in without a CSRF token
- **WHEN** a signed-in user sends a valid PNG to `POST /api/stickers` with no `X-XSRF-TOKEN` header
- **THEN** the response is 403, and nothing is stored or audited

### Requirement: CSRF token read only from the header
The api SHALL read the CSRF token only from the `X-XSRF-TOKEN` request header. A token sent as a form field or multipart part SHALL NOT be accepted, and the api SHALL NOT parse a request body to look for one. An upload's body is therefore never parsed before the request is authorized.

#### Scenario: Token sent only as a multipart field
- **WHEN** a signed-in user sends a valid PNG to `POST /api/stickers` with the matching token only in a `_csrf` multipart field and no `X-XSRF-TOKEN` header
- **THEN** the response is 403, and nothing is stored or audited

#### Scenario: Token sent only as a form field on sign-out
- **WHEN** a signed-in user sends `POST /api/logout` with the matching token only as a `_csrf` form field
- **THEN** the response is 403, and the user is still signed in

### Requirement: Banned users cannot upload
An upload SHALL be rejected with 403 and reason `banned` when the signed-in user's account is marked as banned, including when the ban was set after their session began. Nothing SHALL be stored, and the rejection SHALL be audited.

#### Scenario: Banned after signing in
- **WHEN** a signed-in user is banned by hand in the database and then uploads a valid PNG
- **THEN** the response is 403 with error code `banned`, nothing is stored, and an upload audit record with outcome `rejected` and reason `banned` is written

### Requirement: Type detected from content
The api SHALL accept only PNG and JPEG uploads, identified by the file's leading bytes: the PNG signature, or the JPEG start-of-image marker. The file name and the declared Content-Type SHALL be ignored. Any other content SHALL be rejected with 415 and reason `unsupported_type`, with nothing stored and the rejection audited.

#### Scenario: Text file named as a PNG
- **WHEN** a signed-in user uploads a text file named `sticker.png` with Content-Type `image/png`
- **THEN** the response is 415 with error code `unsupported_type`, nothing is stored, and the audit record holds the file's SHA-256

#### Scenario: Other image formats
- **WHEN** a signed-in user uploads a GIF, a WebP, a BMP or an SVG file, each named `sticker.png`
- **THEN** each is rejected with `unsupported_type`, and nothing is stored

#### Scenario: Name and Content-Type contradict valid content
- **WHEN** a signed-in user uploads a valid PNG named `photo.txt` with Content-Type `text/plain`
- **THEN** the upload is accepted

#### Scenario: Data appended to a valid PNG
- **WHEN** a signed-in user uploads a valid PNG followed by appended HTML
- **THEN** the upload is accepted, and the served sticker is a newly encoded PNG that does not contain the appended bytes

### Requirement: Upload size limit
The api SHALL reject an uploaded file larger than 10 MiB (10,485,760 bytes) with 413 and reason `too_large`, before decoding it, with nothing stored. The rejection SHALL be audited when the request reaches the api. Requests too large for the gateway's limit are refused there and not audited. A valid file of exactly 10 MiB SHALL be accepted.

#### Scenario: One byte over the limit
- **WHEN** a signed-in user uploads a file of 10,485,761 bytes
- **THEN** the response is 413 with error code `too_large`, nothing is stored, and an audit record with reason `too_large` and no SHA-256 is written

#### Scenario: Exactly at the limit
- **WHEN** a signed-in user uploads a valid PNG of exactly 10,485,760 bytes
- **THEN** the upload is accepted

### Requirement: Dimensions checked before decoding
The api SHALL read an image's width and height from its header and, without decoding its pixels, reject it with 422 and reason `dimensions_too_large` when either side exceeds 8,192 pixels or the total exceeds 32,000,000 pixels. Nothing SHALL be stored, and the rejection SHALL be audited.

#### Scenario: Header-only PNG bomb
- **WHEN** a signed-in user uploads a small PNG whose header declares 50,000 × 50,000 pixels
- **THEN** the response is 422 with error code `dimensions_too_large`, its pixels are never decoded, and nothing is stored

#### Scenario: Header-only JPEG bomb
- **WHEN** a signed-in user uploads a small JPEG whose frame header declares 65,000 × 65,000 pixels
- **THEN** the response is 422 with error code `dimensions_too_large`, its pixels are never decoded, and nothing is stored

#### Scenario: At the limits
- **WHEN** image headers declare 8,193 × 1, 5,657 × 5,657 (32,001,649 pixels), 8,192 × 1 and 8,000 × 4,000 pixels
- **THEN** the first two are rejected with `dimensions_too_large`, and the last two pass the dimension check

### Requirement: Damaged and CMYK images rejected
The api SHALL reject with 422 a PNG or JPEG that is truncated or corrupt, including a JPEG whose decoder reports damaged data (reason `unreadable`), and a JPEG in CMYK or YCCK color (reason `unsupported_color`). Nothing SHALL be stored, and the rejection SHALL be audited.

#### Scenario: Truncated PNG
- **WHEN** a signed-in user uploads a PNG cut off partway through its image data
- **THEN** the response is 422 with error code `unreadable`, and nothing is stored

#### Scenario: Truncated JPEG
- **WHEN** a signed-in user uploads a JPEG cut off partway through its image data
- **THEN** the response is 422 with error code `unreadable`, and no sticker with a partly gray image is created

#### Scenario: CMYK JPEG
- **WHEN** a signed-in user uploads a CMYK JPEG
- **THEN** the response is 422 with error code `unsupported_color`, and nothing is stored

### Requirement: Malformed upload requests rejected
An upload request SHALL carry exactly one file, in a multipart part named `file`. Otherwise the api SHALL respond 400 with reason `invalid_request`, store nothing, and audit the rejection.

#### Scenario: No file part
- **WHEN** a signed-in user sends a multipart upload with no part named `file`
- **THEN** the response is 400 with error code `invalid_request`, and nothing is stored

#### Scenario: Two files
- **WHEN** a signed-in user sends a multipart upload with two parts named `file`
- **THEN** the response is 400 with error code `invalid_request`, and nothing is stored

### Requirement: Re-encoded to a sticker-sized PNG
Every accepted upload SHALL be decoded and re-encoded as a PNG. S is the configured sticker size, 160 by default. When the upright image (after EXIF orientation) is wider or taller than S, it SHALL be shrunk to fit within S × S, keeping its aspect ratio: the longer side becomes S, and the shorter side is rounded to the nearest pixel, at least 1. Otherwise its dimensions SHALL be unchanged; images are never scaled up.

#### Scenario: Larger photo shrunk to fit
- **WHEN** a signed-in user uploads a 4,000 × 3,000 JPEG and S is 160
- **THEN** the sticker is a 160 × 120 PNG

#### Scenario: One pixel over
- **WHEN** the upright image is (S + 1) × S pixels
- **THEN** the sticker is S × round(S² ÷ (S + 1)) pixels, which is 160 × 159 when S is 160

#### Scenario: Exactly the sticker size
- **WHEN** the upright image is S × S pixels
- **THEN** the sticker is S × S pixels, re-encoded as a PNG

#### Scenario: Small icon
- **WHEN** a signed-in user uploads a 1 × 1 PNG
- **THEN** the sticker is a 1 × 1 PNG

#### Scenario: Small JPEG
- **WHEN** a signed-in user uploads a JPEG no larger than S × S
- **THEN** the sticker has the same dimensions and is a PNG

#### Scenario: Extreme aspect ratio
- **WHEN** the upright image is 8,000 × 1 pixels and S is 160
- **THEN** the sticker is 160 × 1 pixels

### Requirement: Transparency kept
A sticker made from a PNG with transparency SHALL keep that transparency. Shrinking SHALL NOT tint edges with the color stored in fully transparent pixels.

#### Scenario: Transparent PNG shrunk
- **WHEN** a signed-in user uploads a PNG larger than S × S showing an opaque red square on a fully transparent background whose stored color is green
- **THEN** the sticker's fully transparent areas stay fully transparent, and no partly transparent edge pixel has more green than red

#### Scenario: Palette PNG with transparency
- **WHEN** a signed-in user uploads a palette PNG whose transparency chunk marks one palette entry fully transparent
- **THEN** the pixels using that entry are fully transparent in the sticker

### Requirement: EXIF orientation applied
For a JPEG with an EXIF orientation, the sticker SHALL show the image upright, and "dimensions unchanged" SHALL mean the upright size. Missing or malformed orientation data SHALL be treated as upright.

#### Scenario: Phone photo stored sideways
- **WHEN** a signed-in user uploads a 40 × 20 JPEG whose EXIF orientation is 6 (rotate 90° clockwise to display)
- **THEN** the sticker is 20 × 40 pixels and shows the image upright

#### Scenario: Other rotations
- **WHEN** a signed-in user uploads JPEGs with EXIF orientation 3 and 8, in little-endian EXIF, and orientation 6 in big-endian EXIF
- **THEN** each sticker shows the image upright

#### Scenario: Real phone photo
- **WHEN** a signed-in user uploads a portrait photo straight from a phone camera, stored sideways with an EXIF orientation
- **THEN** the sticker is taller than it is wide

#### Scenario: Malformed orientation data
- **WHEN** a signed-in user uploads a JPEG whose EXIF segment is truncated or points outside itself
- **THEN** the upload is accepted and the image is treated as upright

### Requirement: Metadata removed
A sticker PNG SHALL contain only image data. No EXIF data (including GPS location), text, color profile or other metadata from the upload SHALL be carried into it.

#### Scenario: Photo with location data
- **WHEN** a signed-in user uploads a JPEG whose EXIF data includes GPS coordinates
- **THEN** the sticker PNG contains only IHDR, IDAT and IEND chunks

### Requirement: Animated PNGs become a still image
An animated PNG SHALL be accepted as a PNG, and its sticker SHALL be the still image that viewers without animation support show.

#### Scenario: Animated PNG upload
- **WHEN** a signed-in user uploads an animated PNG
- **THEN** the upload is accepted, and the sticker is a non-animated PNG of the file's still image

### Requirement: Original bytes never stored or served
The original bytes of an upload SHALL NOT be written to object storage, the database, application logs or persistent disk, and SHALL never be served. While a request is processed they SHALL exist only in memory or on memory-backed temporary storage. Object storage SHALL hold only re-encoded PNGs.

#### Scenario: Accepted JPEG
- **WHEN** a signed-in user's JPEG upload is accepted
- **THEN** storage holds exactly one new object, it is the PNG served at the sticker's link, and neither it nor any database value equals the original file's bytes

#### Scenario: Temporary storage is memory-backed
- **WHEN** the stack is running, locally or in production
- **THEN** the api's multipart temporary directory and the gateway's request-body temporary directory are size-limited tmpfs mounts

### Requirement: Accepted upload returns an unguessable link
An accepted upload SHALL respond 201 with JSON holding exactly `path` (`/s/<id>.png`), `width` and `height`. `<id>` SHALL be 128 bits from a cryptographically secure random generator, encoded as 22 base64url characters without padding. The response SHALL NOT contain any database key. The form SHALL show the link as the page's origin followed by `path`.

#### Scenario: Link format
- **WHEN** a signed-in user's upload is accepted
- **THEN** the response is 201, `path` matches `^/s/[A-Za-z0-9_-]{21}[AQgw]\.png$`, and the JSON has exactly the fields `path`, `width` and `height`

#### Scenario: Link in production
- **WHEN** a user's upload is accepted on discosticker.cafe
- **THEN** the form shows a link of the form `https://discosticker.cafe/s/<id>.png`

### Requirement: Rejections use fixed reason codes
Every upload rejected or failed by the api after authorization SHALL get a JSON body whose only field is `error`, holding exactly one of `banned`, `too_large`, `unsupported_type`, `dimensions_too_large`, `unreadable`, `unsupported_color`, `rate_limited`, `busy`, `invalid_request` or `error`. The body SHALL NOT include exception messages, file names or other request-derived text.

#### Scenario: Error body
- **WHEN** a signed-in user's upload is rejected for any of the reasons above
- **THEN** the body is a JSON object whose only field is `error`, and its value is the code for that reason

### Requirement: Hourly upload limit per user
A user SHALL have at most 20 accepted uploads in any 60-minute window. A further upload SHALL get 429 with reason `rate_limited` and a `Retry-After` header, with nothing stored and the rejection audited. Rejected uploads SHALL NOT count toward the limit. Concurrent uploads SHALL NOT both be accepted for the last remaining slot.

#### Scenario: 21st upload within an hour
- **WHEN** a user with 20 accepted uploads in the last 60 minutes uploads another valid image
- **THEN** the response is 429 with error code `rate_limited` and a `Retry-After` of at least 1 second, nothing is stored, and the rejection is audited

#### Scenario: Older uploads leave the window
- **WHEN** a user whose 20 accepted uploads are all more than 60 minutes old uploads a valid image
- **THEN** the upload is accepted

#### Scenario: Rejections don't count
- **WHEN** a user with 19 accepted uploads and several rejected attempts in the last 60 minutes uploads a valid image
- **THEN** the upload is accepted

#### Scenario: Race for the last slot
- **WHEN** a user with 19 accepted uploads in the last 60 minutes sends two valid uploads at the same time
- **THEN** exactly one is accepted, and the other gets 429 with `rate_limited`

### Requirement: Bounded concurrent decoding
The api SHALL decode at most 2 images at a time. An upload that cannot start decoding within 10 seconds SHALL get 503 with reason `busy`, with nothing stored and the rejection audited.

#### Scenario: Decoders busy
- **WHEN** 2 decodes are in progress and neither finishes within 10 seconds of a further valid upload arriving
- **THEN** that upload gets 503 with error code `busy`, nothing is stored, and the rejection is audited

### Requirement: Upload audit records
Each upload request from a signed-in user that passes the CSRF check SHALL write exactly one upload audit record. It holds the Discord ID, the outcome (`accepted`, `rejected` or `failed`), the reason code unless accepted, the original's SHA-256 and byte size when its bytes were read, the sticker's public ID when accepted, and the time. It SHALL NOT contain an IP address.

#### Scenario: Accepted upload
- **WHEN** a signed-in user's upload is accepted
- **THEN** exactly one audit record holds their Discord ID, outcome `accepted`, no reason, the SHA-256 and byte size of the original file, and the sticker's public ID

#### Scenario: Rejected upload
- **WHEN** a signed-in user's upload is rejected after its bytes were read
- **THEN** exactly one audit record holds outcome `rejected`, the reason code, and the original's SHA-256 and byte size, and no public ID

#### Scenario: Requests that are not audited
- **WHEN** an upload is refused because the client is signed out, because the CSRF token is missing, or by the gateway's size limit
- **THEN** no upload audit record is written

### Requirement: Accepted means stored and audited
An upload SHALL be reported as accepted only once its PNG is in storage and its sticker record and accepted audit record are committed together. If storing or recording fails, the response SHALL be 500 with reason `error`, no sticker record SHALL remain, and an audit record with outcome `failed` SHALL be written when the database is reachable.

#### Scenario: Storage write fails
- **WHEN** storage rejects the PNG write during an upload
- **THEN** the response is 500 with error code `error`, no sticker record exists for it, and an audit record with outcome `failed` and reason `error` is written

#### Scenario: Every sticker record has its object
- **WHEN** a sticker record exists
- **THEN** its link serves its PNG

### Requirement: Upload limits endpoint
`GET /api/stickers/limits` SHALL return to a signed-in user JSON with `acceptedTypes`, `maxBytes`, `uploadsPerHour` and `stickerSize`, taken from the same settings the upload checks enforce. `acceptedTypes` SHALL list `image/jpeg` before `image/png`. A signed-out request SHALL get 401 JSON.

#### Scenario: Default limits
- **WHEN** a signed-in user requests `GET /api/stickers/limits` with the default settings
- **THEN** the response is `{"acceptedTypes":["image/jpeg","image/png"],"maxBytes":10485760,"uploadsPerHour":20,"stickerSize":160}`

#### Scenario: Values follow the settings
- **WHEN** the api runs with a different size limit, hourly limit or sticker size
- **THEN** the endpoint reports the new values, and uploads are checked against them

#### Scenario: Signed out
- **WHEN** a signed-out client requests `GET /api/stickers/limits`
- **THEN** the response is 401 with a JSON body

### Requirement: Upload form
When signed in, the home page SHALL show an upload form stating the accepted types, the size limit, the hourly limit, and "Animated images become a still image." Every type and number SHALL come from the limits endpoint; none SHALL be written into the page. The file picker SHALL accept the types in the endpoint's order. If the limits can't be loaded, the form SHALL NOT be shown.

#### Scenario: Form shows the server's limits
- **WHEN** a signed-in user opens the home page with the default settings
- **THEN** the form states JPEG and PNG, 10 MB and 20 uploads per hour, and "Animated images become a still image."

#### Scenario: Limits unavailable
- **WHEN** `GET /api/stickers/limits` fails
- **THEN** no upload form is shown, and the page says uploads are unavailable

### Requirement: Browser-side pre-check
The form SHALL refuse, without sending it, a file larger than `maxBytes`, showing the size-limit message, or a file whose browser-reported type is set and not in `acceptedTypes`, showing the type message. These checks are a convenience only: the server's checks SHALL decide every upload that is sent.

#### Scenario: File too large
- **WHEN** a signed-in user picks a file larger than `maxBytes`
- **THEN** the form shows the size-limit message and sends no request

#### Scenario: Unknown browser type
- **WHEN** the browser reports no type for the chosen file
- **THEN** the form sends it, and the server decides

### Requirement: Upload results shown as fixed text
After an accepted upload the form SHALL show the sticker's link with a copy button. A rejection SHALL show a fixed message for its reason code. Any 413 response SHALL show the size-limit message whatever its body, and any other response SHALL show a generic message. The page SHALL NOT render text from a response body or the file name.

#### Scenario: Accepted
- **WHEN** an upload is accepted
- **THEN** the form shows the sticker's full link and a button that copies it

#### Scenario: Gateway's own 413
- **WHEN** an upload gets a 413 response whose body is the gateway's HTML page
- **THEN** the form shows the size-limit message

#### Scenario: Hourly limit reached
- **WHEN** an upload gets 429 with `rate_limited`
- **THEN** the form says the hourly limit, from the limits endpoint, has been reached and to try again later

#### Scenario: Unrecognized response
- **WHEN** an upload gets a status or error code the page does not know
- **THEN** the form shows the generic failure message

### Requirement: Stickers served publicly
`GET /s/<id>.png` SHALL be public. For an existing sticker it SHALL respond 200 with the stored PNG, `Content-Type: image/png` exactly, its `Content-Length`, and `Cache-Control: public, max-age=86400`, and with no `Set-Cookie`, `Pragma` or `Expires` header. `HEAD` SHALL return the same status and headers with no body.

#### Scenario: Existing sticker
- **WHEN** anyone requests `GET /s/<id>.png` for an existing sticker
- **THEN** the response is 200 with exactly those headers, and its body is the stored PNG

#### Scenario: Signed-in viewer
- **WHEN** a signed-in user requests an existing sticker
- **THEN** the response sets no cookie, and the user is still signed in afterwards

#### Scenario: HEAD request
- **WHEN** anyone sends `HEAD /s/<id>.png` for an existing sticker
- **THEN** the response is 200 with the same headers and no body

### Requirement: Unknown and malformed sticker IDs look the same
A request under `/s/` that names no existing sticker SHALL get the api's 404 JSON, the same for a malformed ID as for a well-formed unknown one apart from the echoed path and the timestamp. An ID that is not 22 base64url characters in canonical form SHALL be rejected without a database lookup.

#### Scenario: Unknown well-formed ID
- **WHEN** anyone requests `/s/<id>.png` for a well-formed ID that no sticker has
- **THEN** the response is 404 with the api's JSON error body

#### Scenario: Malformed IDs
- **WHEN** anyone requests IDs of 21 or 23 characters, IDs containing `+` or `/`, an ID whose last character is not canonical, a `.jpg` extension, or an extra path segment
- **THEN** each response is 404 with a JSON body whose fields, apart from `path` and `timestamp`, equal those for an unknown well-formed ID

### Requirement: Stickers show sticker-sized in Discord
The configured sticker size S SHALL be one at which a sticker link pasted into a Discord message displays at about the size of a Discord sticker. S SHALL be chosen by pasting test images into Discord, and changing it SHALL need no code change.

#### Scenario: Pasted next to a sticker
- **WHEN** the link of an S × S sticker is pasted into a Discord channel next to a server sticker
- **THEN** Discord displays the image at about the same size as the sticker, on desktop and on a phone
