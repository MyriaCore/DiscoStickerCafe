# production-hosting Specification

## Purpose

Defines how the production instance runs the stack and keeps it reachable, patched, and certified. Covers reboot recovery, internet-facing ports, IPv4 and IPv6 reachability, certificate renewal, SSH access, security updates, and where production secrets live.

## Requirements

### Requirement: Stack recovers after a reboot
After the production server reboots, the gateway, backend, database, and object storage SHALL start again on their own and the site SHALL become healthy with no manual steps. The one-shot bucket-creation step SHALL NOT restart.

#### Scenario: Reboot
- **WHEN** the server is rebooted
- **THEN** within six minutes `GET https://discosticker.cafe/api/health` returns 200 with `"status":"UP"`, and no one has logged in to the server

#### Scenario: One-shot step stays one-shot
- **WHEN** the production compose configuration is read
- **THEN** the gateway, backend, database, and object storage restart unless stopped, and the bucket-creation step never restarts

### Requirement: Only SSH, HTTP, and HTTPS reachable from the internet
From the internet, the production server SHALL accept connections only on TCP 80 and 443 (IPv4 and IPv6) and on TCP 22. Port 22 SHALL be reachable only from the maintainer's home IPv4 address, and not over IPv6. Every other port SHALL be unreachable, including the database, object storage, backend, and storage admin ports.

#### Scenario: Probe from the maintainer's home
- **WHEN** TCP connections are attempted from the maintainer's home network to ports 22, 80, 443, 5432, 8080, 8333, and 23646 on the server's IPv4 and IPv6 addresses
- **THEN** 80 and 443 connect on both addresses, 22 connects on IPv4 only, and every other port fails on both

### Requirement: Site reachable over IPv4 and IPv6
The site SHALL be served over both IPv4 and IPv6 at discosticker.cafe. Public DNS SHALL return exactly one A and one AAAA record for the apex, both pointing at the production server, and a CAA record that allows only letsencrypt.org to issue.

#### Scenario: DNS records
- **WHEN** a public resolver is queried for discosticker.cafe
- **THEN** it returns one A record and one AAAA record for the production server, and the CAA record `0 issue "letsencrypt.org"`

#### Scenario: Health over each address family
- **WHEN** a client requests `GET https://discosticker.cafe/api/health` over IPv4 and again over IPv6
- **THEN** both responses are 200 with `"status":"UP"`

### Requirement: Certificates renew automatically without downtime
The production certificate SHALL be renewed automatically before it expires, while the gateway keeps serving traffic. After each successful renewal, the gateway SHALL reload so that it serves the new certificate.

#### Scenario: Renewal is scheduled
- **WHEN** the server's scheduled tasks are listed
- **THEN** a certificate renewal task is scheduled to run at least daily

#### Scenario: Simulated renewal through the running gateway
- **WHEN** a simulated renewal runs against the Let's Encrypt staging server with post-renewal hooks enabled, while the stack is running
- **THEN** the renewal succeeds, the gateway's configuration test passes, and the gateway reloads

### Requirement: Key-only SSH without root login
The production server's SSH daemon SHALL accept only public-key authentication and SHALL refuse logins as root.

#### Scenario: Effective SSH settings
- **WHEN** the SSH daemon's effective configuration is printed
- **THEN** root login, password authentication, and keyboard-interactive authentication are all off

#### Scenario: Password and root logins refused
- **WHEN** a client tries to log in with a password, or as root with a valid key
- **THEN** the server refuses with `Permission denied (publickey)`

### Requirement: Security updates install automatically
The production server SHALL install Debian security updates automatically.

#### Scenario: Automatic upgrades enabled
- **WHEN** the server's automatic-upgrade settings and timers are inspected
- **THEN** unattended upgrades are enabled, the upgrade timer is scheduled, and the allowed origins include Debian's security archive

### Requirement: Production secrets live only on the server
Production secret values SHALL exist only in `secrets.env` in the deployment checkout on the server. The file SHALL be owned by the deploy user with mode 600, SHALL be ignored by git, and SHALL list the same variable names as the committed `secrets.env.example`. Values SHALL be generated on the server, except third-party-issued secrets (the Discord client secret), which SHALL be typed in by hand on the server without being echoed and SHALL NOT be stored on the maintainer's machine. No value SHALL appear in the repository, images, logs, or Claude Code sessions.

#### Scenario: File ownership and names
- **WHEN** the production `secrets.env` is inspected without printing its values
- **THEN** it is owned by the deploy user with mode 600, git reports it as ignored, and its variable names match `secrets.env.example` exactly

#### Scenario: Third-party secret entered without echo
- **WHEN** the maintainer adds the production Discord client secret following the runbook
- **THEN** the value is read from a prompt that does not echo it, appended to `secrets.env` on the server without being printed, and not written to any file on the maintainer's machine
