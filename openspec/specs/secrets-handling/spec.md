# secrets-handling Specification

## Purpose

Keeps secret values out of the public repository, Docker images, and logs. Each service receives only the secrets it needs, and the stack refuses to start without them instead of running with weaker defaults.

## Requirements

### Requirement: Secrets come only from the gitignored secrets file
Secret values SHALL be supplied only through `secrets.env`, which git SHALL ignore. Source code, application configuration, compose files, Dockerfiles, tests, and OpenSpec artifacts SHALL refer to secrets by variable name only.

#### Scenario: Secrets file is ignored
- **WHEN** `git check-ignore secrets.env` runs in the repository
- **THEN** it reports the file as ignored

### Requirement: Example file lists every secret name
The repository SHALL contain a committed `secrets.env.example` that lists every secret variable the stack requires, with placeholder values only.

#### Scenario: Example file is enough to validate the stack definition
- **WHEN** the compose configuration is validated using `secrets.env.example` as its environment file
- **THEN** validation succeeds with no missing-variable errors

### Requirement: Each service receives only the secrets it needs
Each container SHALL receive only the secret variables it needs. In particular, the database SHALL NOT receive the object-storage keys, and the gateway SHALL receive no secrets.

#### Scenario: Database environment excludes storage keys
- **WHEN** the compose file is read
- **THEN** the database service's environment names no object-storage key variable, and the gateway service's environment names no secret variable

### Requirement: Missing secrets stop startup
The stack SHALL refuse to start when a required secret variable is unset or empty. It SHALL NOT start a service with a default or empty secret.

#### Scenario: Required secret missing
- **WHEN** the compose configuration is validated with an environment file that leaves out a required secret
- **THEN** validation fails and names the missing variable

### Requirement: Secrets excluded from Docker build contexts
Docker builds whose context is the repository root SHALL exclude `secrets.env` and any other `secrets*.env` file.

#### Scenario: Build context check
- **WHEN** an image is built from the repository root that copies the whole build context and checks for `secrets.env`
- **THEN** the file is absent from the build context
