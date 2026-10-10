# Spec Delta

## Purpose

Defines how the backend's database schema evolves. Versioned migrations are applied automatically at backend startup, so every environment's schema comes from the repository alone.

## ADDED Requirements

### Requirement: Migrations applied at startup
The backend SHALL apply pending versioned database migrations when it starts, before it serves requests. Startup SHALL fail if the database is unreachable or a migration fails.

#### Scenario: Startup with no migrations
- **WHEN** the backend starts against an empty Postgres database and the repository contains no migrations
- **THEN** startup succeeds, the migration tool is connected to that database, and it reports no pending migrations
