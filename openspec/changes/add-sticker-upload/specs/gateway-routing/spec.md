# Spec Delta

## ADDED Requirements

### Requirement: Upload request size limit
The gateway SHALL accept request bodies of up to 11 MiB on exactly the path `/api/stickers` and SHALL respond with its own 413 to a larger one there, without proxying it. Every other path SHALL keep a limit of 1 MiB. The upload limit SHALL stay above the backend's own upload request limit, so that a file just over the backend's 10 MiB limit reaches the backend and is rejected there.

#### Scenario: Over the upload limit
- **WHEN** a client sends a request with an 11.5 MiB body to `POST /api/stickers` through the gateway
- **THEN** the gateway responds 413 with its own HTML error page, and the request never reaches the backend

#### Scenario: Just over the backend's limit
- **WHEN** a client sends a request with a 10.5 MiB body to `POST /api/stickers` through the gateway
- **THEN** the response comes from the backend (a JSON body), not the gateway's 413 page

#### Scenario: Other paths keep the default
- **WHEN** a client sends a request with a 2 MiB body to `POST /api/logout` through the gateway
- **THEN** the gateway responds 413 with its own HTML error page

### Requirement: Request bodies buffered only in memory
The gateway SHALL buffer client request bodies that exceed its in-memory buffer in a temporary directory backed by memory (tmpfs) with a size limit, never on persistent disk, locally and in production.

#### Scenario: Body temp directory is tmpfs
- **WHEN** the stack is running, locally or in production
- **THEN** the gateway's client-body temporary directory, as compiled into nginx, is a tmpfs mount with a size limit

### Requirement: Sticker responses pass through intact
For requests under `/s/`, the gateway SHALL pass on the backend's `Content-Type`, `Content-Length` and `Cache-Control` unchanged and SHALL add no caching headers of its own. In production it SHALL add the four security headers, as for every HTTPS response, including `X-Content-Type-Options: nosniff`.

#### Scenario: Sticker through the local gateway
- **WHEN** a client requests an existing sticker at `http://localhost:8080/s/<id>.png`
- **THEN** the response has `Content-Type: image/png`, the PNG's `Content-Length`, `Cache-Control: public, max-age=86400`, and no `Set-Cookie`

#### Scenario: Sticker in production
- **WHEN** a client requests an existing sticker at `https://discosticker.cafe/s/<id>.png`
- **THEN** the response has `Content-Type: image/png` and `Cache-Control: public, max-age=86400`, and each of the four security headers appears exactly once
