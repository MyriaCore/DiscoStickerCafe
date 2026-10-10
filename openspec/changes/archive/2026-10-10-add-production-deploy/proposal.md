# Proposal

## Why

The walking skeleton is merged but runs only on a laptop. The decision log says it deploys to discosticker.cafe as soon as it merges, before login, so that hosting problems (DNS, TLS, firewalls, reboots, renewal) show up now instead of on Sunday night. This change puts the same compose stack on the new Vultr instance behind HTTPS and writes down how to run and maintain it.

## What Changes

- Add `compose.prod.yaml`, an override used only on the server:
  - The gateway publishes 80 and 443 instead of `127.0.0.1:8080`, using `!override`.
  - The gateway mounts the Let's Encrypt directory and the ACME webroot read-only, and gets a production healthcheck.
  - Long-running services restart after a reboot; the one-shot bucket-init does not.
- Split the nginx config so local and production share one routes include:
  - The local server block keeps listening on 8080.
  - Production server blocks:
    - Port 80 serves ACME HTTP-01 challenges and redirects everything else to https://discosticker.cafe.
    - Port 443 serves TLS for the apex.
    - www.discosticker.cafe redirects to the apex.
    - Unknown names and bare-IP connections are refused.
  - A fixed set of security headers is sent on every HTTPS response.
- Add `docs/deploy.md`, a runbook for the server. It covers host setup, the firewall check, certificate issuance and renewal, creating production `secrets.env`, going live, smoke checks, reboot recovery and redeploys. Server commands are bash; commands on the maintainer's Windows machine are PowerShell 5.1.
- Set up the host (manual `[user]` work, following the runbook):
  - Docker Engine from Docker's apt repository.
  - A non-root deploy user and key-only SSH with root login disabled.
  - unattended-upgrades for security updates.
  - certbot from Debian with a deploy hook that reloads the gateway.
- Decide production secret handling: `secrets.env` is created by hand on the server. The matching rule wording in `openspec/config.yaml` and `CLAUDE.md` changes only after the PM session approves it, using wording the maintainer supplies.

## Out of scope

- AWS S3 and any AWS account work. Production storage stays SeaweedFS on the instance.
- A container registry, CI/CD, and building images anywhere but on the server.
- Monitoring and alerting, including certificate-expiry alerts.
- Backups and snapshots of the Postgres and SeaweedFS volumes. They come before uploads go live, in a later change.
- A swapfile.
- Discord OAuth, its redirect URIs, and any application code change in `api/` or `frontend/`.
- A Content-Security-Policy header.
- The IPv6 client-address fix (IPv6 on the docker `edge` network plus `listen [::]` in nginx). It is recorded as a known limitation in design.md and becomes the first task of the login change.
- The existing myriaco.re server. Nothing on it changes.
- DNS changes. The A, AAAA and CAA records are already set and are only verified here.

## Capabilities

### New Capabilities

- `production-hosting`: how the production instance runs the stack and stays healthy. Covers reboot recovery, which ports are reachable from the internet, IPv4 and IPv6 reachability, automatic certificate renewal with a gateway reload, key-only SSH, automatic security updates, and where production secrets live.

### Modified Capabilities

- `gateway-routing`:
  - "Only the gateway is reachable from the host" now covers production: the gateway publishes 80 and 443 there.
  - "Forwarded request headers" gains a production scenario (https and 443).
  - New requirements: plain HTTP redirects to HTTPS except ACME challenges; TLS for the apex and www with unknown names refused; www redirects to the apex; security headers on every HTTPS response.

## Security impact

- **Exposure:** for the first time the stack is reachable from the internet. Only the gateway publishes ports (80 and 443). Postgres, SeaweedFS and the api stay on internal networks. The Vultr firewall group is default-deny and allows 22 only from the maintainer's home IPv4. Docker's published ports bypass host firewalls such as ufw, so the Vultr group, not ufw, is what keeps everything else closed. A smoke check probes the ports from outside to prove it.
- **TLS:** HTTPS with Let's Encrypt certificates. CAA allows only letsencrypt.org to issue. HSTS starts at a short `max-age=300`, without `includeSubDomains` or `preload`, so a mistake can be backed out quickly.
- **Response headers:** `X-Content-Type-Options: nosniff` matters for `/s/*`, which will serve images derived from user uploads. `Referrer-Policy: strict-origin-when-cross-origin` keeps sticker URLs out of the Referer header sent to other sites. `X-Frame-Options: DENY` blocks framing.
- **Secrets:** this change sets production secret handling, which the project context currently calls undecided. Values are generated on the server and never printed, committed, built into images, or seen by Claude Code. The rule text in `openspec/config.yaml` and `CLAUDE.md` changes only after PM approval (task 4.5).
- **Host:** key-only SSH with root login disabled, and automatic security updates. Membership in the `docker` group gives the deploy user root-equivalent access. This is accepted and recorded in design.md.
- **Upload, audit, ban, rate-limit, public-ID and CORS constraints:** no effect. No application code changes, and the browser still talks only to nginx on one origin.

## Impact

- New files:
  - `compose.prod.yaml`
  - `gateway/routes.conf`
  - `gateway/security_headers.conf`
  - `gateway/conf.d/local.conf`
  - `gateway/prod/discosticker.conf`
  - `docs/deploy.md`
- Changed files: `gateway/nginx.conf` (the server block moves out to an include) and `gateway/Dockerfile` (copies the new files). Local behavior and the local commands in `CLAUDE.md` stay the same.
- After PM approval only: `openspec/config.yaml` and `CLAUDE.md`, for the production secrets rule.
- Server (Debian 13, set up by the maintainer):
  - Docker Engine and the Compose plugin
  - certbot 4.0.0 from Debian
  - unattended-upgrades
  - the `deploy` user
  - `/srv/discosticker`, `/var/www/certbot`, and `/etc/letsencrypt` with its deploy hook
- External: Let's Encrypt (production and staging ACME endpoints). DNS at GoDaddy is unchanged.
