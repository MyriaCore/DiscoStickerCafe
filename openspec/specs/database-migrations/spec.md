# database-migrations Specification

## Purpose

Defines how the backend's database schema evolves. Versioned migrations are applied automatically at backend startup, so every environment's schema comes from the repository alone.

## Requirements

### Requirement: Migrations applied at startup
The backend SHALL apply pending versioned database migrations when it starts, before it serves requests. Startup SHALL fail if the database is unreachable or a migration fails.

#### Scenario: Startup with no migrations
- **WHEN** the backend starts against an empty Postgres database, with no migrations applied yet
- **THEN** startup succeeds, every migration in the repository (including those that create the users, login audit, stickers and upload audit tables) is applied, and the migration tool reports no pending migrations

#### Scenario: No session tables
- **WHEN** the migrations have been applied
- **THEN** the database contains no HTTP-session tables, because sessions are kept in the backend's memory
