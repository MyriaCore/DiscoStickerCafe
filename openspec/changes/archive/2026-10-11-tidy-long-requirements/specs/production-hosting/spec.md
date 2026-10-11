# Spec Delta

## MODIFIED Requirements

### Requirement: Production secrets live only on the server
Production secret values SHALL exist only in `secrets.env` in the deployment checkout on the server, owned by the deploy user with mode 600, ignored by git, and listing the same names as `secrets.env.example`. Values SHALL be generated on the server; a third-party-issued secret (the Discord client secret) SHALL instead be typed in on the server without echo and never stored on the maintainer's machine. No value SHALL appear in the repository, images, logs, or Claude Code sessions.

#### Scenario: File ownership and names
- **WHEN** the production `secrets.env` is inspected without printing its values
- **THEN** it is owned by the deploy user with mode 600, git reports it as ignored, and its variable names match `secrets.env.example` exactly

#### Scenario: Third-party secret entered without echo
- **WHEN** the maintainer adds the production Discord client secret following the runbook
- **THEN** the value is read from a prompt that does not echo it, appended to `secrets.env` on the server without being printed, and not written to any file on the maintainer's machine
