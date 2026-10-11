# gateway-routing Specification

## Purpose

Serves the whole application from a single origin. The gateway serves the static frontend and forwards the api path prefixes to the backend, so the browser never talks to any other service.

## Requirements

### Requirement: Static frontend served at root
The gateway SHALL serve the frontend's static export for requests under `/` that do not match an api path prefix.

#### Scenario: Root page
- **WHEN** a client requests `GET /` from the gateway
- **THEN** the gateway responds 200 with the frontend's HTML page

### Requirement: Api path prefixes proxied to the backend
The gateway SHALL forward every request whose path starts with `/api/`, `/oauth2/`, `/login/oauth2/`, or `/s/` to the backend, whether or not the backend handles that path yet. Static-file rules SHALL NOT take precedence over these prefixes.

#### Scenario: Health path reaches the backend
- **WHEN** a client requests `GET /api/health` from the gateway
- **THEN** the response comes from the backend

#### Scenario: Unhandled prefixed paths reach the backend
- **WHEN** a client requests `GET /oauth2/x`, `GET /login/oauth2/x`, or `GET /s/x` from the gateway
- **THEN** each response is the backend's 404 response (JSON), not the gateway's own 404 page

### Requirement: Forwarded request headers
The gateway SHALL send `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-Port` describing the client-facing request on every proxied request. The backend SHALL use them when it builds absolute URLs.

#### Scenario: Gateway configuration sets forwarded headers
- **WHEN** the gateway's effective configuration is dumped
- **THEN** each proxied location sets `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-Port`

#### Scenario: Production requests are described as HTTPS on 443
- **WHEN** the production gateway proxies a request to the backend
- **THEN** the request reached the gateway over HTTPS on port 443, so the forwarded headers say `https` and `443`, because only the HTTPS server proxies to the backend

### Requirement: Unknown paths return 404
The gateway SHALL respond 404 to a non-api path that matches no file in the static export. It SHALL NOT fall back to serving the frontend's index page.

#### Scenario: Unknown frontend path
- **WHEN** a client requests `GET /nope/` from the gateway
- **THEN** the gateway responds 404

### Requirement: Only the gateway is reachable from the host
The stack SHALL publish host ports only for the gateway. Locally the gateway SHALL publish a single port bound to loopback. In production it SHALL publish 80 and 443 and nothing else. The backend, database, and object storage SHALL be reachable only on the internal network.

#### Scenario: Published ports
- **WHEN** the local stack is running and the containers' port mappings are listed
- **THEN** exactly one container, the gateway, has a host port mapping

#### Scenario: Published ports in production
- **WHEN** the production stack is running and the containers' port mappings are listed
- **THEN** only the gateway has host port mappings, and they are 80 and 443

### Requirement: Plain HTTP redirects to HTTPS in production
In production, the gateway SHALL answer every plain-HTTP request for discosticker.cafe or www.discosticker.cafe with a 301 redirect to the same path and query on `https://discosticker.cafe`, except ACME HTTP-01 challenge paths. The redirect target host SHALL be fixed, not taken from the request.

#### Scenario: HTTP request redirected
- **WHEN** a client requests `GET http://discosticker.cafe/api/health`
- **THEN** the gateway responds 301 with `Location: https://discosticker.cafe/api/health`

#### Scenario: HTTP request for www redirected to the apex
- **WHEN** a client requests `GET http://www.discosticker.cafe/x`
- **THEN** the gateway responds 301 with `Location: https://discosticker.cafe/x`

#### Scenario: ACME challenge path not redirected
- **WHEN** a client requests `GET http://discosticker.cafe/.well-known/acme-challenge/<token>`
- **THEN** the gateway serves the challenge file if it exists and responds 404 if it does not, and never redirects

### Requirement: TLS for the production names only
In production, the gateway SHALL serve HTTPS for discosticker.cafe and www.discosticker.cafe with one publicly trusted certificate that covers both names. It SHALL refuse the TLS handshake for any other server name, or when the client sends none (for example, a connection to the bare IP address).

#### Scenario: Trusted certificate for both names
- **WHEN** a client opens a TLS connection to discosticker.cafe or www.discosticker.cafe and validates the certificate against the system trust store
- **THEN** validation succeeds and the certificate's subject alternative names include both names

#### Scenario: Unknown name refused
- **WHEN** a client opens a TLS connection to the server's IP address without a matching server name
- **THEN** the gateway rejects the handshake and presents no certificate

### Requirement: www redirects to the apex
In production, the gateway SHALL answer every HTTPS request for www.discosticker.cafe with a 301 redirect to the same path and query on `https://discosticker.cafe`.

#### Scenario: HTTPS www request redirected
- **WHEN** a client requests `GET https://www.discosticker.cafe/x`
- **THEN** the gateway responds 301 with `Location: https://discosticker.cafe/x`

### Requirement: Security headers on every HTTPS response
In production, every HTTPS response from the gateway SHALL carry `Strict-Transport-Security: max-age=300`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, and `X-Frame-Options: DENY`. This SHALL hold for static files, responses proxied from the backend, and error responses. `Strict-Transport-Security` SHALL NOT include `includeSubDomains` or `preload`.

#### Scenario: Static page
- **WHEN** a client requests `GET https://discosticker.cafe/`
- **THEN** the response carries all four headers

#### Scenario: Proxied backend response
- **WHEN** a client requests `GET https://discosticker.cafe/api/health`
- **THEN** the response carries all four headers

#### Scenario: Error response
- **WHEN** a client requests `GET https://discosticker.cafe/nope/`
- **THEN** the response is 404 and carries all four headers
