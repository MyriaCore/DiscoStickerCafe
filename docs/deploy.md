# Production deploy runbook

How discosticker.cafe runs on its Vultr instance (Debian 13), and how to set it up, renew it, recover it and redeploy it. The design and the reasons behind each step are in `openspec/changes/add-production-deploy/design.md`; after archive they are in `openspec/specs/production-hosting` and `docs/decisions.md`.

Conventions:
- **The maintainer runs every step in this file.** Claude Code never connects to the server and never sees production secrets.
- `bash` blocks run **on the server** over SSH. They run as `deploy` unless marked "as root" (only before the `deploy` user exists).
- `powershell` blocks run **on the maintainer's Windows machine** in Windows PowerShell 5.1, from the repo root. Use `curl.exe`, never `curl`, which is an alias for `Invoke-WebRequest`.
- Server compose commands run from `/srv/discosticker` through one variable:
  ```bash
  cd /srv/discosticker
  Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'
  ```
  To have it in every session, add both lines to `~/.bashrc`.
- Never run compose's `config` subcommand with `secrets.env`, and never run `docker inspect` on postgres, seaweedfs, bucket-init or api. Both print secret values. Validate compose files on Windows with `secrets.env.example` instead.
- Placeholders such as `<home-ipv4>` stand for values that are never committed.

Server addresses (public, from DNS): IPv4 `207.148.19.168`, IPv6 `2001:19f0:1000:f32f:5400:6ff:fed6:d61a`.

## SSH access

Connect through a host alias. Don't use `ssh deploy@discosticker.cafe`: the name has an AAAA record, so ssh tries IPv6 first. Port 22 is closed on IPv6 (the Vultr firewall allows SSH only from `<home-ipv4>`), so the connection hangs until it times out. The alias pins IPv4 with `AddressFamily inet`.

Add the entry once, on Windows:

```powershell
$entry = @'

Host discosticker
    HostName 207.148.19.168
    AddressFamily inet
    User deploy
'@
Add-Content -Path "$HOME\.ssh\config" -Value $entry -Encoding ascii
```

Then `ssh discosticker` logs in as `deploy`, and `ssh root@discosticker` would try root (refused once SSH is hardened). Add an `IdentityFile` line if your key isn't one of ssh's default names.

## Firewall

Two layers filter inbound traffic.

**1. The Vultr firewall group** (Vultr console). It is default-deny, and the rules are per address family:

| Family | Port | Source |
|---|---|---|
| IPv4 | 22/tcp | `<home-ipv4>/32` |
| IPv4 | 80/tcp | `0.0.0.0/0` |
| IPv4 | 443/tcp | `0.0.0.0/0` |
| IPv6 | 80/tcp | `::/0` |
| IPv6 | 443/tcp | `::/0` |

Nothing else. There is no IPv6 rule for 22, on purpose.

**2. ufw on the host.** Vultr's Debian 13 image ships with ufw active (default-deny incoming, only 22 allowed). That is why 80 and 443 timed out before deploy (task 2.2). ufw stays on and allows 22, 80 and 443 on IPv4 and IPv6 (task 2.3):

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
grep '^IPV6=' /etc/default/ufw     # expect IPV6=yes
sudo ufw status verbose            # 22, 80, 443 ALLOW IN from Anywhere and Anywhere (v6)
```

Why each ufw rule matters:
- **The IPv6 rules for 80 and 443 are required.** The docker `edge` network is IPv4-only, so IPv6 visitors reach the gateway through `docker-proxy`, a process listening on the host. That traffic passes through ufw's INPUT rules. Without these rules, IPv6 visitors are dropped, and so is Let's Encrypt, which validates over IPv6 when an AAAA record exists.
- **The IPv4 rules for 80 and 443 are redundant for Docker's ports.** Docker DNATs IPv4 published ports in its own iptables rules, before ufw sees them, so IPv4 visitors would get through either way. The rules stay so that `ufw status` matches what is reachable, and so that a non-Docker listener (like the temporary test server in task 2.2) behaves the same.
- **The rule for 22 protects sshd,** which listens on the host. It backs up the Vultr rule.

**The port probe** (Windows). It checks what is reachable from outside:

```powershell
foreach ($ip in '207.148.19.168','2001:19f0:1000:f32f:5400:6ff:fed6:d61a') { foreach ($p in 22,80,443,5432,8080,8333,23646) { $c = New-Object Net.Sockets.TcpClient ([Net.IPAddress]::Parse($ip)).AddressFamily; $r = try { if ($c.ConnectAsync($ip,$p).Wait(3000)) {'open'} else {'closed'} } catch {'closed'}; $c.Dispose(); '{0,-40} {1,-6} {2}' -f $ip,$p,$r } }
```

Expected once the site is live, from home:
- IPv4: 22, 80 and 443 `open`.
- IPv6: 80 and 443 `open`.
- Everything else `closed`, including IPv6 port 22, and 5432, 8080, 8333 and 23646 on both addresses.

"closed" doesn't tell a filtered port from one with nothing listening. The server's RST replies never reach the maintainer's machine (seen with tcpdump in task 2.2), so both cases look like a timeout. To test a filter rule on a port with no listener, start a temporary listener first (as in task 2.2).

## Host setup

### Deploy user (as root)

```bash
command -v sudo >/dev/null || apt install -y sudo
adduser --gecos '' deploy                # set a password: used for sudo and the Vultr console
usermod -aG sudo deploy
install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
install -m 600 -o deploy -g deploy /dev/null /home/deploy/.ssh/authorized_keys
```

Then add your public key to `deploy` from Windows. Don't copy root's `authorized_keys`: Vultr may not install a key at creation (on this instance both files were empty, and root logged in with a password), so the copy would silently give `deploy` no key at all. This uses root's password login, which still works until "Key-only SSH, no root login" below. Change the file name if your key isn't `id_ed25519.pub`:

```powershell
Get-Content "$HOME\.ssh\id_ed25519.pub" | ssh root@discosticker "cat >> /home/deploy/.ssh/authorized_keys"
```

Check from Windows, after adding the alias in "SSH access". `PasswordAuthentication=no` proves the login used the key, not a password:

```powershell
ssh -o PasswordAuthentication=no discosticker id
```

Expect `uid=…(deploy)`, with `sudo` among the groups. From here on, log in as `deploy`.

### Docker Engine and Compose

These steps are from docs.docker.com/engine/install/debian (Debian 13 trixie is supported).

```bash
sudo apt update
sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/debian
Suites: $(. /etc/os-release && echo "$VERSION_CODENAME")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker deploy
```

Log out and back in so the `docker` group applies, then check:

```bash
docker run --rm hello-world | grep -F 'Hello from Docker!'
docker compose version             # v2.24.4 or newer (compose.prod.yaml uses !override)
```

Membership in `docker` is root-equivalent. That's accepted: the only way in is key-only SSH as `deploy`.

### Key-only SSH, no root login

Keep your current SSH session open until the checks below pass, so a mistake can't lock you out.

```bash
sudo tee /etc/ssh/sshd_config.d/00-hardening.conf >/dev/null <<'EOF'
# Key-only SSH and no root login. Named 00- because sshd keeps the first value it reads,
# and later drop-ins (such as 50-cloud-init.conf) may turn passwords back on.
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
EOF
sudo sshd -t && sudo systemctl reload ssh
sudo sshd -T | grep -E '^(permitrootlogin|passwordauthentication|kbdinteractiveauthentication) '
```

All three lines must end in `no`. Then, from a new Windows terminal:

```powershell
ssh -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive discosticker true
ssh root@discosticker true
```

Both must print `Permission denied (publickey).`

### Automatic security updates

```bash
sudo apt install -y unattended-upgrades apt-listchanges
sudo dpkg-reconfigure -plow unattended-upgrades       # answer Yes
cat /etc/apt/apt.conf.d/20auto-upgrades               # both values "1"
systemctl list-timers apt-daily-upgrade.timer         # shows a NEXT time
sudo unattended-upgrade --dry-run -d 2>&1 | grep -i 'allowed origins'   # includes Debian-Security
```

Only Debian security updates install automatically. Docker Engine comes from Docker's own repository, so update it by hand (`sudo apt update`, then `sudo apt upgrade`), when you can watch the stack restart.

## Certificates

The certificate comes from certbot (Debian's package) using the webroot method. The first issuance happens before the stack runs, through a throwaway nginx container. Every renewal after that goes through the running gateway, which serves `/.well-known/acme-challenge/` from the same webroot, so there's no downtime.

### Install certbot and the webroot

```bash
sudo apt install -y certbot
sudo install -d -m 755 /var/www/certbot
certbot --version                        # certbot 4.0.0
systemctl list-timers certbot.timer      # shows a NEXT time (runs twice a day)
stat -c '%U %a' /var/www/certbot         # root 755
```

### First issuance

This needs Docker installed and port 80 open in ufw on IPv4 and IPv6 (see "Firewall").

1. Start the bootstrap container, which serves the webroot on port 80:

```bash
docker run -d --name acme-bootstrap -p 80:80 -v /var/www/certbot:/usr/share/nginx/html:ro nginx:1.30.5-alpine
```

2. **Before any certbot request,** check that port 80 answers over both IPv4 and IPv6. Let's Encrypt validates over IPv6 when an AAAA record exists, and failed validations count against its rate limit (5 per name per hour). From Windows:

```powershell
foreach ($f in '-4','-6') { curl.exe $f -sS -o NUL -w "$f %{http_code}\n" --max-time 5 http://discosticker.cafe/ }
```

Each family must print a 3-digit status. `403` is normal, because the webroot is empty. `000` or a timeout means stop: fix that address family's path (Vultr rules, ufw) before going on.

3. Dry run against Let's Encrypt's staging server (nothing is saved):

```bash
sudo certbot certonly --webroot -w /var/www/certbot -d discosticker.cafe -d www.discosticker.cafe --register-unsafely-without-email --agree-tos --dry-run
```

No email address goes to Let's Encrypt: it stopped sending expiry emails in 2025, and renewal is automatic.

4. If the dry run succeeded, issue the real certificate with the same command minus `--dry-run`:

```bash
sudo certbot certonly --webroot -w /var/www/certbot -d discosticker.cafe -d www.discosticker.cafe --register-unsafely-without-email --agree-tos
```

5. Remove the bootstrap container and check the result:

```bash
docker rm -f acme-bootstrap
sudo certbot certificates                 # Domains: discosticker.cafe www.discosticker.cafe, VALID: 89 days
sudo grep '^authenticator' /etc/letsencrypt/renewal/discosticker.cafe.conf    # authenticator = webroot
docker ps -aq --filter name=acme-bootstrap                                    # prints nothing
```

### Deploy hook: reload the gateway after each renewal

certbot runs every script in `/etc/letsencrypt/renewal-hooks/deploy/` after a successful renewal. This one reloads nginx in the gateway container. It uses `docker exec`, found by compose labels, not `docker compose`: compose interpolates the required secrets, so it would need `secrets.env`.

```bash
sudo install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
sudo tee /etc/letsencrypt/renewal-hooks/deploy/reload-gateway >/dev/null <<'EOF'
#!/bin/bash
# Reloads the production gateway after certbot renews a certificate.
# Uses docker exec, not docker compose: compose would need secrets.env to interpolate.
set -euo pipefail
cid=$(docker ps -q --filter label=com.docker.compose.project=discosticker --filter label=com.docker.compose.service=gateway)
if [ -z "$cid" ]; then
  echo "reload-gateway: gateway container is not running" >&2
  exit 1
fi
docker exec "$cid" nginx -t
docker exec "$cid" nginx -s reload
EOF
sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-gateway
sudo stat -c '%U %a' /etc/letsencrypt/renewal-hooks/deploy/reload-gateway     # root 755
sudo bash -n /etc/letsencrypt/renewal-hooks/deploy/reload-gateway; echo $?    # 0
```

The hook runs for real only once the stack is up. "Renewal" covers the end-to-end test.

## Go live

### Push the branch (Windows)

The server clones from GitHub, so the deploy commits must be pushed first:

```powershell
git push -u origin feature/deploy
git ls-remote origin refs/heads/feature/deploy
git rev-parse feature/deploy
```

The two hashes must match.

### Clone

```bash
command -v git >/dev/null || sudo apt install -y git
sudo install -d -o deploy -g deploy /srv/discosticker
git clone --branch feature/deploy https://github.com/MyriaCore/DiscoStickerCafe.git /srv/discosticker
git -C /srv/discosticker rev-parse HEAD                # same hash as the push
git -C /srv/discosticker branch --show-current         # feature/deploy
stat -c '%U' /srv/discosticker                         # deploy
```

After the PR merges, switch to `master` (see "Redeploy").

### Create secrets.env

Generate the values on the server, without printing them. This only creates the file if it doesn't exist yet. The names must match `secrets.env.example`; if a later change adds a secret, add a line here too.

```bash
cd /srv/discosticker
command -v openssl >/dev/null || sudo apt install -y openssl
if [ -e secrets.env ]; then
  echo "secrets.env already exists; not overwriting" >&2
else
  (
    umask 077
    {
      printf 'POSTGRES_PASSWORD=%s\n' "$(openssl rand -hex 32)"
      printf 'S3_ACCESS_KEY=%s\n' "$(openssl rand -hex 16)"
      printf 'S3_SECRET_KEY=%s\n' "$(openssl rand -hex 32)"
    } > secrets.env
  )
fi
stat -c '%U:%G %a' secrets.env                                                     # deploy:deploy 600
diff <(cut -d= -f1 secrets.env) <(grep -Ev '^(#|$)' secrets.env.example | cut -d= -f1); echo $?   # 0 only
git check-ignore -q secrets.env; echo $?                                           # 0
```

The checks print names, owner and mode, never values. Don't `cat` the file. `POSTGRES_PASSWORD` is applied only when the Postgres volume is first created; changing it later needs `ALTER ROLE` inside Postgres as well.

### Build and start

```bash
cd /srv/discosticker
Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'
$Prod up --build -d --wait --wait-timeout 900; echo "exit=$?"
$Prod ps -a --format '{{.Service}} | {{.Status}} | {{.Ports}}'
```

Expect:
- `exit=0`.
- gateway, api, postgres and seaweedfs `(healthy)`.
- bucket-init `Exited (0)`.
- Ports only on the gateway, and only 80 and 443 (on `0.0.0.0` and `[::]`).

If the build runs out of memory (the host has 4 GB and BuildKit builds the api and gateway in parallel), build them one at a time and start again:

```bash
$Prod build api
$Prod build gateway
$Prod up -d --wait --wait-timeout 900
```

## Smoke checks

Run these from Windows once the stack is up. Each line says what it must print.

```powershell
# DNS: one A and one AAAA per resolver, CAA for Let's Encrypt, www is a CNAME to the apex
foreach ($r in '8.8.8.8','1.1.1.1') { @(Resolve-DnsName discosticker.cafe -Type A -Server $r -DnsOnly | Where-Object Section -eq 'Answer').IPAddress; @(Resolve-DnsName discosticker.cafe -Type AAAA -Server $r -DnsOnly | Where-Object Section -eq 'Answer').IPAddress }; (Invoke-RestMethod 'https://dns.google/resolve?name=discosticker.cafe&type=CAA').Answer.data; (Resolve-DnsName www.discosticker.cafe -Type CNAME -Server 8.8.8.8 -DnsOnly | Where-Object Section -eq 'Answer').NameHost

# Redirects: 301 to https://discosticker.cafe/... for the first three, 404 with no redirect for the ACME path
foreach ($u in 'http://discosticker.cafe/api/health','http://www.discosticker.cafe/x','https://www.discosticker.cafe/x','http://discosticker.cafe/.well-known/acme-challenge/x') { curl.exe -sS -o NUL -w "$u -> %{http_code} %{redirect_url}\n" $u }

# Unknown Host on port 80: connection closed (444), so a non-zero exit
curl.exe -s -o NUL -H "Host: example.com" http://207.148.19.168/; "exit=$LASTEXITCODE"

# Certificate: no exception, issuer O=Let's Encrypt, about 89 days left, SANs discosticker.cafe and www.discosticker.cafe
foreach ($h in 'discosticker.cafe','www.discosticker.cafe') { $t = New-Object Net.Sockets.TcpClient($h,443); $s = New-Object Net.Security.SslStream($t.GetStream()); $s.AuthenticateAsClient($h); $x = New-Object Security.Cryptography.X509Certificates.X509Certificate2 $s.RemoteCertificate; $h; $x.Issuer; $x.NotAfter; ($x.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.17' }).Format($false); $t.Dispose() }

# Bare-IP TLS: handshake refused, non-zero exit
curl.exe -k -sS -o NUL https://207.148.19.168/; "exit=$LASTEXITCODE"

# Health over both address families: UP, then two bodies containing "status":"UP"
(Invoke-RestMethod https://discosticker.cafe/api/health).status
curl.exe -4 -fsS https://discosticker.cafe/api/health
curl.exe -6 -fsS https://discosticker.cafe/api/health

# api prefixes: 404 application/json each
foreach ($p in '/oauth2/x','/login/oauth2/x','/s/x') { curl.exe -sS -o NUL -w "$p %{http_code} %{content_type}\n" "https://discosticker.cafe$p" }

# Static root and unknown path: / 200, /nope/ 404
foreach ($p in '/','/nope/') { curl.exe -sS -o NUL -w "$p %{http_code}\n" "https://discosticker.cafe$p" }

# Security headers: four lines per path (HSTS exactly max-age=300)
foreach ($p in '/','/api/health','/nope/') { "== $p"; curl.exe -sI "https://discosticker.cafe$p" | Select-String -Pattern '^(strict-transport-security|x-content-type-options|referrer-policy|x-frame-options):' }
```

Then run the port probe from "Firewall", and check on the server:

```bash
sudo ss -tlnp                                   # only sshd on 22 and docker-proxy on 80 and 443
$Prod ps --format '{{.Service}} {{.Ports}}'     # ports only on the gateway
```

## Renewal

`certbot.timer` runs `certbot renew` twice a day. A certificate renews once less than a third of its lifetime is left (about day 60 of 90). Renewal goes through the gateway's port-80 ACME location, then the deploy hook reloads nginx. Nothing goes offline.

Test it end to end, with the stack running:

```bash
sudo certbot renew --dry-run --run-deploy-hooks          # "Congratulations, all simulated renewals succeeded", no hook error
cd /srv/discosticker
$Prod logs --since 5m gateway | grep -i reconfiguring    # nginx reloaded
```

`--dry-run` skips deploy hooks unless `--run-deploy-hooks` is given. Run this check after any change to the gateway, the hook, or the firewall.

To check on renewals later:

```bash
sudo certbot certificates                       # days left
systemctl list-timers certbot.timer             # next run
sudo tail -n 50 /var/log/letsencrypt/letsencrypt.log
```

There's no monitoring yet, so glance at `certbot certificates` now and then. A failed renewal shows only in that log and in the gateway's health status.

## Reboot

Long-running services have `restart: unless-stopped`, so the stack comes back on its own after a reboot. During the first minute, api and gateway may restart a few times until postgres and api are up. bucket-init does not run again.

```bash
sudo reboot
```

Don't log in again. From Windows, wait up to six minutes for the site to come back:

```powershell
$d = (Get-Date).AddMinutes(6); do { try { $st = (Invoke-RestMethod https://discosticker.cafe/api/health -TimeoutSec 5).status } catch { $st = $null; Start-Sleep 10 } } until ($st -eq 'UP' -or (Get-Date) -gt $d); $st
```

It must print `UP`.

## Redeploy

After a merge to `master`:

```bash
cd /srv/discosticker
Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'
git switch master               # only once, the first time after the deploy PR merges
git pull --ff-only
$Prod up --build -d --wait --wait-timeout 900; echo "exit=$?"
```

If the build runs out of memory, build one image at a time, then start:

```bash
$Prod build api
$Prod build gateway
$Prod up -d --wait --wait-timeout 900
```

**A change to `gateway/prod/` only** needs no rebuild. The directory is mounted into the gateway, so pull and reload:

```bash
git pull --ff-only
$Prod exec gateway nginx -t
$Prod exec gateway nginx -s reload
```

**Disk space:** every `--build` adds to Docker's build cache. When `df -h /` shows the root filesystem above 80%:

```bash
df -h /
docker builder prune
```

**Roll back** to an earlier version:

```bash
git checkout <previous-commit>
$Prod up --build -d --wait --wait-timeout 900
```

Then `git switch master` again once the fix is merged.

## Lockout recovery

**The home IPv4 changed** (SSH hangs). ufw on the host allows 22 from anywhere; only the Vultr firewall group limits SSH to `<home-ipv4>`. In the Vultr console, edit the group's IPv4 22/tcp rule to the new `<home-ipv4>/32`. No server login is needed.

**The SSH key is lost or broken.** Open the instance's web console in the Vultr dashboard. It's a local terminal login, not sshd, so passwords work. Log in as `deploy` with its password, then fix `/home/deploy/.ssh/authorized_keys`, which must be mode 600 and owned by `deploy`.

**sshd won't start after a config change.** Use the same web console, fix or remove `/etc/ssh/sshd_config.d/00-hardening.conf`, then:

```bash
sudo sshd -t && sudo systemctl restart ssh
```
