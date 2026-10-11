# Spec Delta

## MODIFIED Requirements

### Requirement: Production secrets live only on the server
Production secret values SHALL exist only in `secrets.env` in the deployment checkout on the server. The file SHALL be owned by the deploy user with mode 600, SHALL be ignored by git, and SHALL list the same variable names as the committed `secrets.env.example`. Values SHALL be generated on the server, except third-party-issued secrets (the Discord client secret), which SHALL be typed in by hand on the server without being echoed and SHALL NOT be stored on the maintainer's machine. No value SHALL appear in the repository, images, logs, or Claude Code sessions.

#### Scenario: File ownership and names
- **WHEN** the production `secrets.env` is inspected without printing its values
- **THEN** it is owned by the deploy user with mode 600, git reports it as ignored, and its variable names match `secrets.env.example` exactly

#### Scenario: Third-party secret entered without echo
- **WHEN** the maintainer adds the production Discord client secret following the runbook
- **THEN** the value is read from a prompt that does not echo it, appended to `secrets.env` on the server without being printed, and not written to any file on the maintainer's machine
