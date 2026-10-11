# Spec Delta

## MODIFIED Requirements

### Requirement: Api path prefixes proxied to the backend
The gateway SHALL forward every request whose path starts with `/api/`, `/oauth2/`, `/login/oauth2/`, or `/s/` to the backend, whether or not the backend handles that path yet. Static-file rules SHALL NOT take precedence over these prefixes.

#### Scenario: Health path reaches the backend
- **WHEN** a client requests `GET /api/health` from the gateway
- **THEN** the response comes from the backend

#### Scenario: Unhandled prefixed paths reach the backend
- **WHEN** a client with no session requests `GET /oauth2/x`, `GET /login/oauth2/x`, and `GET /s/x` from the gateway
- **THEN** the first two responses are the backend's 401 response (JSON), the third is the backend's 404 response (JSON), and none is the gateway's own 404 page

### Requirement: Security headers on every HTTPS response
In production, every HTTPS response from the gateway SHALL carry `Strict-Transport-Security: max-age=300`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, and `X-Frame-Options: DENY`. This SHALL hold for static files, responses proxied from the backend, and error responses. `Strict-Transport-Security` SHALL NOT include `includeSubDomains` or `preload`. The gateway SHALL be the only source of these four headers: it SHALL NOT pass on copies sent by the backend, locally or in production.

#### Scenario: Static page
- **WHEN** a client requests `GET https://discosticker.cafe/`
- **THEN** the response carries all four headers

#### Scenario: Proxied backend response
- **WHEN** a client requests `GET https://discosticker.cafe/api/health`
- **THEN** the response carries all four headers

#### Scenario: Error response
- **WHEN** a client requests `GET https://discosticker.cafe/nope/`
- **THEN** the response is 404 and carries all four headers

#### Scenario: Each header exactly once on proxied responses
- **WHEN** a client requests `GET https://discosticker.cafe/api/health`
- **THEN** each of the four headers appears exactly once, and `Strict-Transport-Security` is exactly `max-age=300`

#### Scenario: Backend copies not passed on locally
- **WHEN** the local stack is running and a client requests `GET http://localhost:8080/api/health` from the gateway
- **THEN** none of the four headers is present, even though the backend sends `X-Content-Type-Options` and `X-Frame-Options` itself
