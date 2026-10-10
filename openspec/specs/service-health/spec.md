# service-health Specification

## Purpose

Gives operators, container healthchecks, and later changes one endpoint that reports whether the backend is up and can reach its database.

## Requirements

### Requirement: Health endpoint reports status
The backend SHALL answer `GET /api/health` with a JSON body that contains a top-level `status` field. It SHALL respond 200 with `"status":"UP"` when the backend and its database are healthy.

#### Scenario: Healthy backend
- **WHEN** the backend is running with a reachable database
- **AND** a client requests `GET /api/health`
- **THEN** the response is 200 and the body's `status` is `UP`

#### Scenario: Through the gateway
- **WHEN** the full stack is running
- **AND** a client requests `GET /api/health` from the gateway
- **THEN** the response is 200 and the body's `status` is `UP`

### Requirement: Health endpoint exposes no details
The health response SHALL NOT include component details such as database product, version, or connection information.

#### Scenario: Minimal body
- **WHEN** a client requests `GET /api/health`
- **THEN** the body contains `status` and no `components` or `details` fields
