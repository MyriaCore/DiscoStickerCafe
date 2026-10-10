# Design

## Context

See proposal.md for motivation and scope; the specs in this change define the required behavior.

Current state:
- The walking skeleton is merged on `master`.
- `compose.yaml` publishes only the gateway, on `127.0.0.1:8080:8080`, and sets no restart policies.
- `gateway/nginx.conf` holds one inline server block that listens on 8080 and contains the four `^~` api locations and the static `location /`.
- `gateway/proxy_headers.conf` sets `X-Forwarded-Proto $scheme` and `X-Forwarded-Port $server_port`, so these headers are correct only when nginx's listen port matches the client-facing port.
- The gateway image is built from the repo root with `nginx:1.30.5-alpine`.

Production state, set by the maintainer before this change:
- A Debian 13 (trixie) Vultr instance at 207.148.19.168 and 2001:19f0:1000:f32f:5400:6ff:fed6:d61a.
- A Vultr firewall group that allows 22 from the maintainer's home IPv4 only, and 80 and 443 from anywhere on IPv4 and IPv6.
- DNS at GoDaddy: one A record, one AAAA record, CAA `0 issue "letsencrypt.org"`, and www as a CNAME to the apex.

Planning checks (2026-10-10):
- **Vultr:** Debian 13 x64 (trixie) is offered (OS id 2625; source: the public `https://api.vultr.com/v2/os`). Firewall groups are default-deny for inbound traffic, and rules are per address family (v4 or v6). (Sources: Vultr's firewall troubleshooting doc and the `vultr_firewall_rule` `ip_version` parameter.)
- **Docker Engine** supports Debian 13 trixie from Docker's apt repository (docs.docker.com/engine/install/debian). The packages are `docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin`. Docker's docs say published ports bypass ufw and firewalld rules.
- **Port publishing:** ports publish on `0.0.0.0` and `[::]` by default. On an IPv4-only bridge network, host IPv6 ports reach the container's IPv4 address through the userland proxy, `docker-proxy` (docs.docker.com/engine/network/port-publishing).
- **Compose `!override`** replaces a list instead of merging it. It arrived in compose-go v2.0.0-rc.3, first used by Compose v2.24.4. Docker's apt repo ships a far newer Compose.
- **certbot** on trixie is 4.0.0 and ships `certbot.timer`.
  - Renewal reuses the authenticator recorded at first issuance.
  - Since 4.0.0, a certificate is due for renewal when less than 1/3 of its lifetime remains.
  - `--dry-run` uses staging and skips deploy hooks unless `--run-deploy-hooks` is given.
- **Let's Encrypt:**
  - Rate limits: 5 certificates per exact set of names per 7 days, and 5 failed validations per name per account per hour.
  - Certificates last 90 days by default. The default profile moves to 64 days on 2027-02-10 and 45 days on 2028-02-16; certbot's 1/3 rule follows on its own.
  - OCSP URLs were removed from certificates in May 2025, and the OCSP servers shut down in August 2025.
- **Probe from the maintainer's machine during planning:** 22 open. 80, 443, 5432, 8080, 8333 and 23646 timed out (dropped, not refused) on IPv4, and every probed port timed out on IPv6. Task 2.2 found the cause: ufw was active on the host with default-deny incoming and only 22 allowed, so it dropped 80 and 443. tcpdump on the server also showed SYNs to closed ports answered with RST, but those RSTs never reached the maintainer's machine. So from outside, a port with no listener times out instead of being refused, and the probe can't tell "dropped" from "nothing listening". Task 2.3 applied the host-firewall branch of D11.
- **The `nginx:1.30.5-alpine` image** contains `curl` and BusyBox `wget`, but not `openssl`.

## Goals / Non-Goals

**Goals:**
- The production stack is the local stack plus one override file. Routes are defined once and shared, so production and local can't drift.
- Every server step is a `[user]` step in `docs/deploy.md`, with an exact check. Claude Code never connects to the server and never handles production secrets.
- Certificate renewal and reboots need no human.

**Non-Goals:**
- No automation of server setup (no Ansible, no cloud-init user data). It's a one-off host, and the runbook is the record.
- No changes to how the api or frontend are built.
- No HTTP/3 or QUIC, and no tuning of TLS beyond nginx's defaults plus TLS 1.2 and 1.3 only.

## Decisions

### D1. Server layout and the deploy user
- `deploy` is a non-root user in `sudo` and `docker`. It has a password, used for sudo and for logging in on the Vultr web console.
- The checkout lives at `/srv/discosticker`, owned by `deploy`. Production `secrets.env` sits at its root, where `--env-file secrets.env` expects it.
- The ACME webroot is `/var/www/certbot` (root-owned, 755). Certificates are in `/etc/letsencrypt`, managed by certbot.
- Every server compose command runs from `/srv/discosticker` through one shell variable: `Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'`, then `$Prod up …`, `$Prod ps`, `$Prod logs …`.
- *Trade-off:* membership in `docker` is root-equivalent. Accepted, because the only way in is key-only SSH as `deploy`, from one IPv4 address. *Alternative:* `sudo docker …` everywhere. It's noisier and protects little, since `deploy` has sudo anyway.

### D2. One routes include, two sets of server blocks
- `gateway/routes.conf` (baked into the image at `/etc/nginx/routes.conf`) holds everything inside the server block today: `root`, `index`, `absolute_redirect off`, the four `^~` proxy locations, and the static `location /`.
- `gateway/nginx.conf` keeps the `http {}` settings and includes `/etc/nginx/conf.d/*.conf`.
- `gateway/conf.d/local.conf` is baked into `/etc/nginx/conf.d/`. It contains `server { listen 8080; include /etc/nginx/routes.conf; }`, so local behavior stays the same.
- `gateway/prod/discosticker.conf` holds the production server blocks (D3). compose.prod.yaml bind-mounts the **directory** `./gateway/prod` read-only over `/etc/nginx/conf.d`, hiding `local.conf`.
- Mount the directory, not the file: git replaces changed files with new inodes, and a single-file bind mount keeps pointing at the old one after `git pull`.
- *Trade-off:* production server config comes from the checkout rather than the image. A `git pull` plus a gateway reload applies a config change without a rebuild. *Alternative:* bake both files into the image and select one with a different `nginx -c` command. It keeps everything in the image, but needs a second top-level config.

### D3. Production server blocks
- **Port 80, `server_name discosticker.cafe www.discosticker.cafe`:**
  - `location ^~ /.well-known/acme-challenge/ { root /var/www/certbot; }`
  - Everything else returns `301 https://discosticker.cafe$request_uri`. The host is fixed, never `$host`.
- **Port 80 `default_server`:** `return 444` (close the connection) for any other Host header, including bare-IP requests.
- **Port 443, apex:** `listen 443 ssl`, `http2 on`, `ssl_protocols TLSv1.2 TLSv1.3`, and `ssl_certificate` and `ssl_certificate_key` from `/etc/letsencrypt/live/discosticker.cafe/`. It includes `security_headers.conf` (D9) and `routes.conf`. It is the only server that includes `routes.conf`, so it is the only one that proxies to the api.
- **Port 443, www.discosticker.cafe:** the same certificate, includes `security_headers.conf`, and returns `301 https://discosticker.cafe$request_uri`.
- **Port 443 `default_server`:** `ssl_reject_handshake on`. Unknown names and no-SNI clients get no certificate.
- **No `ssl_stapling` or `ssl_trusted_certificate`:** Let's Encrypt removed OCSP URLs from its certificates in May 2025 and shut down its OCSP servers in August 2025, so there is nothing to staple.
- nginx inside the container listens on IPv4 only, as it does locally (see Known limitations).

### D4. Ports and forwarded headers
- compose.prod.yaml sets `ports: !override ["80:80", "443:443"]` on the gateway. Without `!override`, Compose would merge the two lists and keep `127.0.0.1:8080:8080`.
- The container ports equal the public ports, so in the proxying server `$scheme` is `https` and `$server_port` is `443`. `proxy_headers.conf` doesn't change.
- *Alternative:* keep listening on 8080 and hardcode `X-Forwarded-Proto https` and `X-Forwarded-Port 443` in a production copy of the headers file. Rejected: it is a second copy that can drift.

### D5. Production gateway healthcheck
- `curl -fsS -o /dev/null --resolve discosticker.cafe:443:127.0.0.1 https://discosticker.cafe/`. It sends the right SNI, so the 443 default server doesn't reject it, and it checks the real certificate.
- The local healthcheck (`wget http://127.0.0.1:8080/`) would fail in production, because nothing listens on 8080 there.
- *Trade-off:* an expired certificate turns the gateway unhealthy. That is visible in `$Prod ps`, but it restarts nothing.

### D6. Restart policies
- `restart: unless-stopped` on gateway, api, postgres, and seaweedfs, in compose.prod.yaml only.
- `restart: "no"` on bucket-init. With `unless-stopped`, a one-shot container would rerun in a loop.
- On boot, Docker doesn't honor `depends_on`. The api may exit until postgres accepts connections, and the gateway may exit until the `api` hostname resolves. Both restart until they succeed. The reboot check (7.10) proves the stack settles.

### D7. Certificates: Debian certbot, webroot, bootstrap with a temporary nginx
- Install certbot from Debian (4.0.0). It brings `certbot.timer` (twice a day) and `/etc/letsencrypt/renewal-hooks/`.
- **First issuance** uses `--webroot -w /var/www/certbot` for both names. Before the stack runs, a throwaway `nginx:1.30.5-alpine` container publishes port 80 and serves `/var/www/certbot`. Run `--dry-run` (staging, nothing saved) first, then the real issuance, then remove the container.
- Because the authenticator recorded at first issuance is `webroot`, every renewal goes through the running gateway's port-80 ACME location, with no downtime and no reconfiguration.
- The gateway mounts `/etc/letsencrypt` read-only as a whole, because `live/` holds symlinks into `archive/`. It also mounts `/var/www/certbot` read-only. The nginx master runs as root, so it can read the 600 private keys.
- *Alternatives:*
  - `--standalone` with pre and post hooks that stop the gateway: a few seconds of downtime at every renewal.
  - Standalone first, then `certbot reconfigure --webroot`: one more step to get wrong.
  - The certbot container image: no scheduler, and it would need the Docker socket to reload nginx.

### D8. Deploy hook reloads the gateway through `docker exec`
- `/etc/letsencrypt/renewal-hooks/deploy/reload-gateway` is a root-owned, mode-755 bash script. Its content is in `docs/deploy.md`.
- It finds the container by the labels `com.docker.compose.project=discosticker` and `com.docker.compose.service=gateway`, then runs `docker exec <id> nginx -t` and `docker exec <id> nginx -s reload`. It exits non-zero if the gateway isn't running.
- It doesn't use `docker compose exec`: every compose command interpolates the `${VAR:?}` secrets and would need `secrets.env`.
- *Trade-off:* the hook depends on the compose project name `discosticker` (fixed by `name:` in compose.yaml).

### D9. Security headers at server level only
- `gateway/security_headers.conf` holds four `add_header … always;` lines: `Strict-Transport-Security "max-age=300"`, `X-Content-Type-Options "nosniff"`, `Referrer-Policy "strict-origin-when-cross-origin"`, and `X-Frame-Options "DENY"`.
- The production 443 servers include it at server level. `always` adds the headers to error responses and to responses proxied from the api.
- nginx drops inherited `add_header` lines in any block that defines its own, so `routes.conf` and its locations must contain no `add_header`. Task 4.3 checks this.
- HSTS starts at 300 seconds, without `includeSubDomains` or `preload`, and is raised in a later change after a stable week.
- The local server doesn't include the headers.
- No Content-Security-Policy this weekend.

### D10. www is served and redirected
- The certificate covers discosticker.cafe and www.discosticker.cafe, and www returns 301 to the apex on both ports.
- *Trade-off:* both names must validate at issuance. The CNAME already resolves, so without www in the certificate, https on www would show a certificate error. *Alternative:* delete the www CNAME and serve the apex only.

### D11. Host firewall: decided by task 2.2 (resolved: ufw stays, allowing 22, 80 and 443)
**Outcome (2026-10-10):** the host-firewall branch applied. ufw was active (default-deny incoming, only 22 allowed) and was dropping 80 and 443. It stays on, with `ufw allow 80/tcp` and `ufw allow 443/tcp` added to the existing 22 rule. `ufw status` lists all three for IPv4 and IPv6. The runbook's Firewall section records this.

The branches as planned, before 2.2 ran:
- **The firewall group rules are wrong or missing:** fix them in the Vultr console (v4 80 and 443 from `0.0.0.0/0`, v6 80 and 443 from `::/0`, v4 22 from `<home-ipv4>/32`, nothing else).
- **A host firewall is active (ufw or an nftables ruleset):** keep it, and allow 22, 80 and 443 on IPv4 and IPv6. It backs up the Vultr group for anything that listens on the host, such as sshd. IPv6 traffic to 80 and 443 also needs it: on the IPv4-only bridge, that traffic goes through `docker-proxy` on the host's INPUT chain, so a host firewall that doesn't allow it blocks IPv6 visitors. IPv4 published ports are DNATed by Docker and bypass the host firewall either way.
- **No host firewall is active:** the Vultr group is the only filter. Don't add ufw: it can't see Docker's IPv4 publishing, so it would claim to filter traffic that it doesn't.

The outside probe (7.8) proves the result. The ufw rules for 22, 80 and 443 have different jobs:
- **IPv6 80 and 443 are required.** IPv6 visitors reach the gateway through `docker-proxy` on the host, which goes through ufw's INPUT rules. Without them, IPv6 visitors and Let's Encrypt's IPv6 validation would be dropped.
- **IPv4 80 and 443 are redundant for Docker's ports,** because Docker DNATs IPv4 published ports before ufw sees them. They are kept so that `ufw status` matches what is actually reachable, and so that a non-Docker listener on 80, like the 2.2 test server, behaves the same.
- **22 (both families) protects sshd,** which listens on the host. It backs up the Vultr rule that allows only the home IPv4.

### D12. SSH
- The firewall group allows 22 from the maintainer's home IPv4 only (a recorded decision).
- `/etc/ssh/sshd_config.d/00-hardening.conf` sets `PermitRootLogin no`, `PasswordAuthentication no`, and `KbdInteractiveAuthentication no`. sshd keeps the first value it reads, and cloud images may ship a later `50-cloud-init.conf` that turns passwords on, so the `00-` prefix makes this file win.
- On the maintainer's machine, a `~/.ssh/config` entry `Host discosticker` sets `HostName 207.148.19.168`, `AddressFamily inet`, and `User deploy`. Plain `ssh deploy@discosticker.cafe` would try the AAAA address first, where 22 is closed, and hang.
- If the home IPv4 changes, the fallback is the Vultr web console (tty login with `deploy`'s password), then updating the firewall rule.

### D13. Production secrets
- `secrets.env` is created by hand on the server by `deploy`, in `/srv/discosticker`, and only if it doesn't exist yet. The runbook builds it under `umask 077` from `openssl rand -hex 32` for `POSTGRES_PASSWORD` and `S3_SECRET_KEY`, and `openssl rand -hex 16` for `S3_ACCESS_KEY`. It writes them without printing anything.
- Hex values avoid `$` interpolation, quoting and URL-encoding problems.
- Checks print names, owner and mode, never values.
- `POSTGRES_PASSWORD` takes effect only when the Postgres volume is first initialized. Changing it later needs a manual `ALTER ROLE` (not needed this weekend).
- The values are not copied off the server. If the server is lost, the data on it is lost too, since there are no backups yet. When backups arrive, they decide where a copy of the secrets lives.
- The runbook never uses `docker compose config` with `secrets.env`, on the server or locally, and never uses `docker inspect` on secret-holding services.

### D14. Deploying from the branch, then `master`
- The server clones the public repo over HTTPS and checks out `feature/deploy`. compose.prod.yaml and the nginx changes exist only there until the PR merges. The maintainer pushes the branch first (6.1).
- After the smoke checks pass and the PR merges, the server switches to `master` (7.11). The content is the same, so nothing restarts.
- Redeploy later: `git pull`, then `$Prod up --build -d --wait`. If the build runs out of memory, build the api and gateway images one at a time (`$Prod build api`, then `$Prod build gateway`) and run `up` again. No swapfile.

### D15. Updates
- unattended-upgrades with Debian's default origins installs security updates only.
- Docker's apt repository is not an allowed origin, so Docker Engine updates are manual. That avoids surprise daemon restarts. With D6, a daemon restart would recover anyway.

## Threats considered (TLS, host, SSH, secrets, ports, headers, renewal)

| # | Threat | Handling | Verified by |
|---|---|---|---|
| T1 | Certificate private key exposed. | The key is created by certbot on the server, root-only (600) under `archive/`. It's mounted read-only into the gateway only, and never enters the repo or an image. | 5.2 (`certbot certificates`); 4.4 (compose mounts are `:ro`). |
| T2 | The certificate expires unnoticed (no monitoring; Let's Encrypt no longer sends expiry emails). | `certbot.timer` runs twice a day. Renewal by webroot needs no downtime, and the deploy hook reloads the gateway. The gateway healthcheck fails on an expired certificate. | 5.1 (timer scheduled); 7.9 (dry run with hooks). Monitoring is out of scope (Risks). |
| T3 | Repeated failed issuance trips Let's Encrypt rate limits and blocks the launch. | Find out why 80/443 are dropped (2.2, 2.3) before any issuance, and dry-run against staging before the real request. | 2.3; 5.2. |
| T4 | Another CA issues a certificate for the domain. | CAA `0 issue "letsencrypt.org"`. | 1.1; 7.1. |
| T5 | Plain-HTTP use or a downgrade. | Every HTTP request gets 301 to https. HSTS is set (short at first). TLS 1.2 and 1.3 only. | 7.2; 7.7; 4.3 (`nginx -T`). |
| T6 | Host-header abuse: an open redirect through `$host`, or the site served under arbitrary names. | Redirects use a fixed host. Port-80 default server returns 444; port-443 default server rejects the handshake. | 4.3 (no `$host` in redirects); 7.2; 7.3. |
| T7 | Internal services reachable from the internet (Postgres 5432, SeaweedFS 8333 and 23646, api 8080). | Only the gateway publishes ports. The Vultr group is default-deny. | 4.4 (config: ports); 6.4 (`$Prod ps`); 7.8 (outside probe on IPv4 and IPv6). |
| T8 | Docker's published ports bypass a host firewall, so its rules give false assurance. | The Vultr group is the perimeter. A host firewall is kept only as a backup and must allow 22/80/443 on both families (D11). | 2.3; 7.8. |
| T9 | IPv6 traffic takes a different path (`docker-proxy` on INPUT) and is blocked or exposed differently. | Same rules on both families. The IPv6 probe and IPv6 health check. | 7.4; 7.8. |
| T10 | SSH brute force or credential stuffing. | 22 is reachable only from the home IPv4. Key-only, root login off, and the `00-` drop-in wins over cloud-init's. | 3.3; 7.8. |
| T11 | Locked out when the home IPv4 changes. | Vultr web console with `deploy`'s password, then update the firewall rule. | Runbook section; `deploy` has a password (3.1). |
| T12 | `deploy` is root-equivalent through the `docker` group. | Accepted (D1). The only entry is key-only SSH from one IPv4 address. | 3.1; 3.3. |
| T13 | Production secrets leak through the repo, an image, the terminal, or a Claude Code transcript. | Created on the server under `umask 077`, mode 600, never printed. `.dockerignore` already excludes `secrets*.env`. No `docker compose config` with `secrets.env` and no `docker inspect` on secret-holding services, anywhere. Claude Code never connects to the server. | 6.3 (owner, mode, names only, ignored); 4.1 (runbook has no `config` command and no secret-like values). |
| T14 | The renewal hook needs secrets, or fails silently. | The hook uses `docker exec` by label, so it needs no compose and no secrets. It exits non-zero when the gateway is missing, which certbot logs as a hook failure. | 5.3 (owner, mode, syntax); 7.9. |
| T15 | MIME sniffing turns an upload-derived image under `/s/*` into active content. | `X-Content-Type-Options: nosniff` on every HTTPS response, including proxied ones. | 7.7. |
| T16 | Sticker URLs (unguessable `/s/` IDs) leak to other sites through the Referer header. | `Referrer-Policy: strict-origin-when-cross-origin` sends only the origin cross-site. | 7.7. |
| T17 | Clickjacking. | `X-Frame-Options: DENY`. | 7.7. |
| T18 | An HSTS mistake locks visitors into a broken HTTPS setup for a long time. | `max-age=300`, with no `includeSubDomains` or `preload`. | 7.7 (exact header value). |
| T19 | The host goes unpatched. | unattended-upgrades for Debian security updates. Docker updates are manual (D15). | 3.4. |
| T20 | Stale nginx config after `git pull` (the bind mount still points at an old inode). | Mount the directory `gateway/prod/`, not the file (D2). | 4.4 (config shows a directory mount). |
| T21 | A tampered build input (repo or base images). | The clone is over HTTPS from the public GitHub repo; base images are pinned by tag. Digest pinning is accepted as out of scope for this weekend. | — |

## Known limitations

- **IPv6 client addresses are lost.** The docker `edge` network is IPv4-only, so IPv6 visitors reach nginx through `docker-proxy`, and nginx sees the bridge gateway address (172.x) instead of the client's address. That's harmless for the skeleton. It matters once the login change writes client addresses into audit records. **The fix is the first task of the login change:** `enable_ipv6: true` on `edge`, plus `listen [::]:80` and `listen [::]:443 ssl` in nginx, so Docker forwards IPv6 directly and keeps the source address.
- **No monitoring or alerting.** A failed renewal shows up only in certbot's log and the gateway's health status until the certificate expires.
- **No backups.** The Postgres and SeaweedFS volumes sit on the instance's one disk. Backups come before uploads go live.

## Risks / Trade-offs

- [Ports 80 and 443 are dropped today, cause unknown] → Tasks 2.2 and 2.3 run before any issuance. Issuance (5.2) doesn't start until 80 is reachable on both address families.
- [The build runs out of memory on 4 GB when api and gateway build in parallel] → The runbook's fallback builds them one at a time (D14). No swapfile.
- [Build cache fills the disk over many redeploys] → A runbook note: `docker builder prune` when `df -h /` passes 80%.
- [The api or gateway restart-loops on boot until their dependencies are up] → Expected (D6). The reboot check has a six-minute window.
- [`python3` is missing on the image, for the 2.2 listener] → The runbook falls back to `apt install -y python3`.
- [`sudo` or `git` is missing on the minimal image] → The runbook installs them in the step that first needs them.
- [Let's Encrypt shortens lifetimes in 2027 and 2028] → certbot 4.0's 1/3-lifetime rule and the twice-daily timer adapt with no change.
- [The PM session doesn't approve the secrets rule wording in time] → Task 4.5 stays unchecked. The deploy doesn't depend on it.

## Migration Plan

1. The two tracks in tasks.md run in parallel. The maintainer prepares the server and certificates (sections 2, 3 and 5), while Claude Code makes the repo changes (section 4). They meet at go-live (section 6), then run the smoke checks (section 7).
2. Rollback, from worst to mildest:
   - `$Prod down` takes the site offline. Volumes are kept.
   - `git checkout <previous commit>` and `$Prod up --build -d --wait` returns to an earlier version.
   - For an HSTS problem, the 300-second max-age means browsers forget the policy within five minutes of the header being removed.
3. Nothing to migrate: no production data exists yet.

## Proposed rule wording (for the PM session; not applied by this change until approved)

- `openspec/config.yaml`, replacing "Production secret handling is undecided and is not in scope.":
  > In production, secrets.env is created by hand on the server in the deploy checkout, owned by the deploy user with mode 600, with values generated on the server. It is never committed, copied into an image, or handled by Claude Code.
- `CLAUDE.md`, a new bullet after the secrets rule:
  > In production, the maintainer creates `secrets.env` by hand on the server (deploy user, mode 600, values generated there). Claude never connects to the server and never creates, reads, or handles production secrets; server steps are [user] tasks in `docs/deploy.md`.

Task 4.5 applies the wording the maintainer supplies after approval, which may differ from this draft.

## Decision candidates for the PM session

One line each. Not added to `docs/decisions.md` by this change.

- Production `secrets.env` is created by hand on the server (deploy user, mode 600, hex values from `openssl rand`) and never leaves it. *Why:* one server needs no secret store, and Claude never sees the values.
- certbot comes from Debian and renews by webroot through the running gateway; the first issuance uses a temporary nginx. *Why:* zero-downtime renewals with the packaged timer.
- Production and local nginx share one routes include; compose.prod.yaml mounts the production server blocks. *Why:* routes can't drift.
- www.discosticker.cafe is in the certificate and redirects to the apex. *Why:* the name already resolves.
- HSTS starts at `max-age=300`, with no includeSubDomains or preload. *Why:* a TLS mistake can be backed out in minutes.
- No CSP this weekend; production sends nosniff, a strict-origin referrer policy, and frame denial. *Why:* small, fixed, and enough for an image host.
- ufw, active on Vultr's Debian 13 image, stays on and allows 22, 80 and 443 on IPv4 and IPv6, behind the Vultr group. *Why:* IPv6 visitors reach docker-proxy through ufw, while Docker's IPv4 published ports bypass it.
- Long-running services restart `unless-stopped`; bucket-init stays one-shot. *Why:* the site comes back after a reboot with no manual steps.
- The deploy user is in the `docker` group. *Why:* root-equivalent, but reachable only by key from one IPv4 address.
- IPv6 client addresses are fixed in the login change, not now. *Why:* nothing records client addresses until login.
