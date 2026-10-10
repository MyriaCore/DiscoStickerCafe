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

### Requirement: Unknown paths return 404
The gateway SHALL respond 404 to a non-api path that matches no file in the static export. It SHALL NOT fall back to serving the frontend's index page.

#### Scenario: Unknown frontend path
- **WHEN** a client requests `GET /nope/` from the gateway
- **THEN** the gateway responds 404

### Requirement: Only the gateway is reachable from the host
The local stack SHALL publish a host port only for the gateway. The backend, database, and object storage SHALL be reachable only on the internal network.

#### Scenario: Published ports
- **WHEN** the stack is running and the containers' port mappings are listed
- **THEN** exactly one container, the gateway, has a host port mapping
