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

Debian's security updates and stable point-release updates install automatically: the allowed origins are `label=Debian` and `label=Debian-Security` for trixie, plus `trixie-security`. Docker's repository is not an allowed origin. Docker Engine comes from Docker's own repository, so update it by hand (`sudo apt update`, then `sudo apt upgrade`), when you can watch the stack restart.

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
# Quiet on success: certbot labels any stderr from a hook as "error output".
set -euo pipefail
cid=$(docker ps -q --filter label=com.docker.compose.project=discosticker --filter label=com.docker.compose.service=gateway)
if [ -z "$cid" ]; then
  echo "reload-gateway: gateway container is not running" >&2
  exit 1
fi
docker exec "$cid" nginx -t -q
docker exec "$cid" nginx -s reload 2>&1
EOF
sudo chmod 755 /etc/letsencrypt/renewal-hooks/deploy/reload-gateway
sudo stat -c '%U %a' /etc/letsencrypt/renewal-hooks/deploy/reload-gateway     # root 755
sudo bash -n /etc/letsencrypt/renewal-hooks/deploy/reload-gateway; echo $?    # 0
```

The hook runs for real only once the stack is up. "Renewal" covers the end-to-end test.

The hook is quiet on success. `nginx -t -q` prints only real errors, and the reload notice goes to stdout. So certbot reports "Hook 'deploy-hook' ran with output" with the reload notice, not "ran with error output". "error output" after a renewal therefore means something actually went wrong. Look in `/var/log/letsencrypt/letsencrypt.log`.

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

The names must match `secrets.env.example`. Values we can generate are generated on the server. Values a third party issues (the Discord client secret) are typed in on the server without echo. Neither kind is ever printed. If a later change adds a secret, add a line to the matching step.

**Generated values.** This only creates the file if it doesn't exist yet:

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
```

**Third-party secrets.** `DISCORD_CLIENT_SECRET` comes from the production Discord application (DiscoSticker.cafe). In the Discord Developer Portal, open the application's OAuth2 page, then reset or reveal the client secret and copy it. Never use the dev application's secret here. On the server:

```bash
cd /srv/discosticker
if cut -d= -f1 secrets.env | grep -qx DISCORD_CLIENT_SECRET; then echo "DISCORD_CLIENT_SECRET already set" >&2; else
  IFS= read -rs -p 'DISCORD_CLIENT_SECRET (prod app): ' v; echo
  printf 'DISCORD_CLIENT_SECRET=%s\n' "$v" >> secrets.env; unset v
fi
```

`read -s` doesn't echo, and the value never enters shell history. Appending keeps the file's mode 600. At the prompt, paste **once** (Ctrl+Shift+V or a single Ctrl+V, not also a right-click) and press Enter. Nothing shows, so a doubled paste goes unnoticed; the length check below catches it. Then clear the clipboard on Windows. If clipboard history is on, also clear it (Settings → System → Clipboard → Clear clipboard data):

```powershell
Set-Clipboard -Value ' '
```

The secret is never written to a file on Windows. To replace it (for example after resetting it in the portal), delete its line with `sed -i '/^DISCORD_CLIENT_SECRET\b/d' secrets.env` (sed keeps the file's owner and mode) and run the step again.

**Check** (names, owner, mode and value lengths only, never values):

```bash
stat -c '%U:%G %a' secrets.env                                                     # deploy:deploy 600
diff <(cut -d= -f1 secrets.env) <(grep -Ev '^(#|$)' secrets.env.example | cut -d= -f1); echo $?   # 0 only
git check-ignore -q secrets.env; echo $?                                           # 0
awk -F= '{print $1, length($0)-length($1)-1}' secrets.env                          # value lengths
```

Expected lengths: `POSTGRES_PASSWORD 64`, `S3_ACCESS_KEY 32`, `S3_SECRET_KEY 64` (hex from `openssl rand`), and `DISCORD_CLIENT_SECRET 32` (Discord's client secret). A Discord length of 64 means the secret was pasted twice. On 2026-10-11 a doubled paste was caught exactly this way. Delete the line and enter it again.

Don't `cat` the file. `POSTGRES_PASSWORD` is applied only when the Postgres volume is first created; changing it later needs `ALTER ROLE` inside Postgres as well.

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

# api prefixes, signed out: /api/me, /api/stickers/limits, /oauth2/x and /login/oauth2/x 401; /s/x and an unknown
# sticker ID 404; each application/json
foreach ($p in '/api/me','/api/stickers/limits','/oauth2/x','/login/oauth2/x','/s/x','/s/AAAAAAAAAAAAAAAAAAAAAA.png') { curl.exe -sS -o NUL -w "$p %{http_code} %{content_type}\n" "https://discosticker.cafe$p" }

# Upload size limit, signed out: "big /api/stickers 413 text/html", "mid /api/stickers 403 application/json",
# "two /api/logout 413 text/html". Only /api/stickers accepts more than 1m: 11.5 MiB is refused by nginx,
# 10.5 MiB reaches the api (its CSRF 403, not a 502), and 2 MiB elsewhere is still refused by nginx.
$d = Join-Path $env:TEMP 'upload-limit'; New-Item -ItemType Directory -Force $d | Out-Null
foreach ($c in @(@('big',12058624,'/api/stickers'), @('mid',11010048,'/api/stickers'), @('two',2097152,'/api/logout'))) {
  $f = Join-Path $d $c[0]; [IO.File]::WriteAllBytes($f, (New-Object byte[] $c[1]))
  curl.exe -sS -o NUL -w "$($c[0]) $($c[2]) %{http_code} %{content_type}\n" -H 'Content-Type: application/octet-stream' --data-binary "@$f" "https://discosticker.cafe$($c[2])"
}

# Sign-in redirect and session cookie: all nine lines True. The redirect goes to Discord with the
# production client ID, identify scope and redirect URI; the cookie is __Host-SESSION with each
# attribute checked separately.
$r = curl.exe -sS -D - -o NUL https://discosticker.cafe/oauth2/authorization/discord | ForEach-Object { $_.TrimEnd() }
$loc = [uri]::UnescapeDataString(($r | Select-String -Pattern '^location: (.+)$').Matches[0].Groups[1].Value)
'authorize      ' + ($loc -match '^https://discord\.com/oauth2/authorize\?')
'redirect_uri   ' + ($loc -match '[?&]redirect_uri=https://discosticker\.cafe/login/oauth2/code/discord(&|$)')
'scope          ' + ($loc -match '[?&]scope=identify(&|$)')
'client_id      ' + ($loc -match '[?&]client_id=1558633777062875269(&|$)')
$c = ($r | Select-String -Pattern '^set-cookie: __Host-SESSION=').Line
foreach ($a in 'Path=/','Secure','HttpOnly','SameSite=Lax') { '{0,-15}{1}' -f $a, ($c -match (';\s*' + [regex]::Escape($a) + '(;|$)')) }
'no Domain/Max-Age/Expires ' + (($null -ne $c) -and ($c -notmatch ';\s*(Domain|Max-Age|Expires)='))

# Static root and unknown path: / 200, /nope/ 404
foreach ($p in '/','/nope/') { curl.exe -sS -o NUL -w "$p %{http_code}\n" "https://discosticker.cafe$p" }

# Security headers: 1 on all 15 lines. nginx is their only source, so each header appears
# exactly once per path, including /api/health (proxied), and HSTS is exactly max-age=300.
$names = 'strict-transport-security','x-content-type-options','referrer-policy','x-frame-options'
foreach ($p in '/','/api/health','/nope/') {
  $h = curl.exe -sI "https://discosticker.cafe$p" | ForEach-Object { $_.TrimEnd() }
  foreach ($n in $names) { '{0,-12} {1,-26} {2}' -f $p, $n, @($h | Select-String -Pattern "^${n}:").Count }
  '{0,-12} {1,-26} {2}' -f $p, 'hsts = max-age=300', @($h | Select-String -Pattern '^strict-transport-security: max-age=300$').Count
}
```

After an upload, check one sticker link's headers (replace `<id>` with the ID from its link). Expect status 200, `content-type: image/png`, a `content-length`, `cache-control: public, max-age=86400` and `x-content-type-options: nosniff` once each, and no `set-cookie`:

```powershell
curl.exe -sSI "https://discosticker.cafe/s/<id>.png" | Select-String -Pattern '^(HTTP|content-type|content-length|cache-control|set-cookie|x-content-type-options):?'
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

A change deploys from its feature branch before the PR merges. **After the merge, switch the server to `master`** with the same steps: this is routine, not a task in any change. Below, `<branch>` is the feature branch (for example `feature/login`) or `master`.

First push the branch, then take GitHub's hash for it on Windows. That's the commit the server must end up on:

```powershell
git ls-remote origin refs/heads/<branch>
```

On the server, check out that commit:

```bash
cd /srv/discosticker
Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'
git fetch origin
git switch <branch>
git pull --ff-only
git rev-parse HEAD              # must equal the hash from ls-remote; if not, stop
```

**If the change adds a secret** (a new name in `secrets.env.example`), add it to `secrets.env` now, before `up`: a generated one with `openssl rand`, a third-party one as in "Create secrets.env". Then run the name check there. Every secret is required, so `up` stops at a missing one without touching the running containers.

Then build, start and recreate the gateway (the branch changed, see below):

```bash
$Prod up --build -d --wait --wait-timeout 900; echo "exit=$?"
$Prod up -d --force-recreate --no-deps --wait gateway
$Prod exec gateway ls /etc/nginx/conf.d          # must list discosticker.conf
$Prod exec gateway nginx -t -q; echo "nginx-t=$?"
```

Then run "Smoke checks".

**Always compare against GitHub's hash, taken from Windows.** Never compare against the server's own `origin/master`: it's only as fresh as the server's last fetch. On 2026-10-10, a pull ran before GitHub's `master` had the deploy merge. `git switch master` then checked out the pre-deploy master and deleted `compose.prod.yaml` and `gateway/prod/` from the checkout. `HEAD == origin/master` still passed, because both were stale.

**After any `git switch` or `git checkout` on the server, recreate the gateway:**

```bash
$Prod up -d --force-recreate --no-deps --wait gateway
$Prod exec gateway ls /etc/nginx/conf.d          # must list discosticker.conf
$Prod exec gateway nginx -t -q; echo "nginx-t=$?"
```

Why: the gateway mounts the `gateway/prod/` directory. If a checkout removes that directory and later recreates it, the running container keeps pointing at the deleted, empty one. nginx carries on with the config it already loaded, so the site still looks fine, but the next reload or restart (including the renewal hook's) would start nginx with no server blocks. Recreating the container mounts the current directory. A plain `up` doesn't do this, because it only recreates containers whose image or settings changed.

If the build runs out of memory, build one image at a time, then start:

```bash
$Prod build api
$Prod build gateway
$Prod up -d --wait --wait-timeout 900
```

**The upload size limit** (`client_max_body_size` in `location = /api/stickers`) lives in `gateway/routes.conf`, which is built into the gateway image, not mounted like `gateway/prod/`. Changing it needs a gateway rebuild (`$Prod up --build -d --wait gateway`); a reload doesn't pick it up. Keep it above the api's `app.upload.max-file-size` and below `server.tomcat.max-swallow-size` (both in `api/src/main/resources/application.yml`).

**A change to `gateway/prod/` only** needs no rebuild. The directory is mounted into the gateway, so pull, check that the container sees the new file, and reload:

```bash
git fetch origin
git pull --ff-only
$Prod exec gateway ls /etc/nginx/conf.d          # must list discosticker.conf; if not, recreate the gateway (above)
$Prod exec gateway nginx -t -q; echo "nginx-t=$?"
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
$Prod up -d --force-recreate --no-deps --wait gateway
```

Then, once the fix is merged, `git fetch origin`, `git switch master` and `git pull --ff-only`, check the hash against GitHub's, and recreate the gateway again. Don't roll back to a commit from before the deploy merge: it has no `compose.prod.yaml` or `gateway/prod/`.

## Sign-in and bans

Sign-in uses two Discord applications, each with exactly one redirect URI registered:
- **DiscoSticker (dev)**, for the local stack: http://localhost:8080/login/oauth2/code/discord
- **DiscoSticker.cafe**, for production: https://discosticker.cafe/login/oauth2/code/discord

Their client IDs are public and set in `compose.yaml` and `compose.prod.yaml`. Each secret lives only in its own environment's `secrets.env`. Locally, browse to http://localhost:8080, not 127.0.0.1:8080: the redirect URI is built from the address in the browser, and Discord accepts only the registered one.

**Ban an account** by its Discord user ID (in Discord: Developer Mode, then right-click the user, then Copy User ID). This also works for an account that has never signed in:

```bash
$Prod exec postgres psql -U discosticker -d discosticker -c "insert into users (discord_id, banned_at) values (<id>, now()) on conflict (discord_id) do update set banned_at = now()"
```

A ban takes effect at the account's next login: sign-in checks it before any session exists, and a rejected attempt is audited as `rejected | banned`. A ban also blocks uploads at once, even from a session that already exists: each upload checks it, and a refused upload is audited in `upload_audit` as `rejected | banned`. Other signed-in endpoints (`/api/me`, sign-out) keep working until the session ends (8 hours idle). To end it now, restart the api. Sessions are kept in memory, so this signs **everyone** out:

```bash
$Prod restart api
```

**Unban:**

```bash
$Prod exec postgres psql -U discosticker -d discosticker -c "update users set banned_at = null where discord_id = <id>"
```

**Recent sign-ins** (identified attempts only; failures before Discord confirmed an identity are WARN lines in the api log, not audit rows):

```bash
$Prod exec postgres psql -U discosticker -d discosticker -c "select occurred_at, discord_id, outcome, reason from login_audit order by id desc limit 20"
$Prod logs --since 24h api | grep 'Login failed before'
```

**Known limitation:** audit records hold no visitor IP addresses, but nginx's access log still does, in the gateway's Docker container log. That's IPv4 addresses only, because IPv6 visitors currently appear as the Docker bridge address. The log uses the default `json-file` driver with no rotation configured. Rotation and retention come next weekend, with the IP-storage work.

## Uploads

Stickers are re-encoded PNGs in the private `stickers` bucket, one row each in `stickers`. Every upload attempt by a signed-in user that passed the CSRF check has one row in `upload_audit`: `accepted`, `rejected` with a fixed reason, or `failed | error` when storing it went wrong. Uploads refused earlier (signed out, CSRF, nginx's 413 over 11 MiB) leave no row. The api logs one `Upload <outcome> [<reason>] in <n> ms` line per attempt, never the file name, Discord ID or IP.

**Recent uploads:**

```bash
$Prod exec postgres psql -U discosticker -d discosticker -c "select occurred_at, discord_id, outcome, reason, original_bytes, sticker_public_id from upload_audit order by id desc limit 20"
$Prod logs --since 24h api | grep -E 'Upload (accepted|rejected|failed)'
```

**Orphan check.** The api writes a sticker's object before committing its row and deletes the object if the commit fails, so a row always has its object. An orphan object (stored, with no row) can only be left if the api dies between the two steps. Orphans are unreachable, because `/s/` serves only stickers that have a row. Compare the bucket with the table:

```bash
$Prod run --rm --no-deps --entrypoint aws bucket-init s3 ls s3://stickers --endpoint-url http://seaweedfs:8333 | awk '{print $4}' | sort > /tmp/objects.txt
$Prod exec -T postgres psql -U discosticker -d discosticker -At -c "select object_key from stickers" | sort > /tmp/rows.txt
comm -23 /tmp/objects.txt /tmp/rows.txt   # orphan objects: in storage with no row (safe to delete)
comm -13 /tmp/objects.txt /tmp/rows.txt   # rows without an object: must print nothing
```

Delete an orphan with `$Prod run --rm --no-deps --entrypoint aws bucket-init s3 rm s3://stickers/<key> --endpoint-url http://seaweedfs:8333`. A row without an object should never happen; if the second line prints anything, stop and investigate before deleting anything. An automatic sweep comes with ban-and-purge.

### Take down a sticker

Until owner delete and ban-and-purge arrive next weekend, a sticker is taken down by hand. **Delete the row first, then the object.** Deleting the row takes the sticker offline in one atomic statement, and from then on its link gets the same 404 as an unknown ID. If the object delete fails afterwards, what's left is an orphan: unreachable, and listed by the orphan check above. Deleting the object first would leave a row with no object between the two steps.

**0. Find the sticker's ID** (Windows). If you have the link, the ID is the part between `/s/` and `.png`. Otherwise, list every sticker:

- **When to use it:** the query is read-only, so it's also handy any time you want to see what an upload or a test did to the table.
- **Pasting:** it's one pipeline with no here-string, so it pastes into the console as is, even from an indented block.
- **Quoting:** the SQL travels over stdin, which avoids PowerShell 5.1's quoting of arguments to `ssh`. Inside the single-quoted string, `''` is an escaped `'`.
- **Output:** each row has the sticker's public link and its owner's Discord ID, and the total comes last.

```powershell
'select s.public_id,
        ''https://discosticker.cafe/s/'' || s.public_id || ''.png'' as url,
        u.discord_id, s.width, s.height, s.png_bytes, s.created_at
 from stickers s join users u on u.id = s.owner_id
 order by s.created_at;
 select count(*) as total from stickers;' | ssh discosticker "cd /srv/discosticker; docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env exec -T postgres psql -U discosticker -d discosticker"
```

For the local stack, replace everything after the `|` with `docker compose --env-file secrets.env exec -T postgres psql -U discosticker -d discosticker`.

The server parts of steps 1 to 3 run in one SSH session, because they share `$ID`.

**1. Set the ID and check its format** before it reaches any SQL, so a typo or a stray quote can't. A bad ID prints `Not a sticker ID: stop` and leaves you in the shell. Steps 2 and 3 repeat the check, so their SQL never runs with a bad ID:

```bash
cd /srv/discosticker
Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'
ID='<id>'
if [[ $ID =~ ^[A-Za-z0-9_-]{21}[AQgw]$ ]]; then echo "ID ok"; else echo "Not a sticker ID: stop"; fi
```

**2. Look up, confirm, delete.** One block shows the sticker, asks, and deletes only what it showed:

- **What it shows:** the sticker's link, its owner's Discord ID and when it was uploaded. Open the link and check it's the sticker you mean. To ban the owner as well, use the Discord ID with "Ban an account" under "Sign-in and bans".
- **The answer:** only the exact answer `yes` deletes. Anything else, including an empty answer, prints `Stopped: nothing deleted`.
- **On `yes`:** it deletes the row, which takes the sticker offline, then the object, only if the returned key is exactly `<id>.png`. `-T` and `-q` make `KEY` exactly the returned key, with no terminal carriage return and no `DELETE 1` status line.
- **Pasting:** paste the block on its own, without the lines after it, because the question reads the next line you enter.

```bash
if [[ $ID =~ ^[A-Za-z0-9_-]{21}[AQgw]$ ]]; then
  ROW=$($Prod exec -T postgres psql -U discosticker -d discosticker -Atq -c "select u.discord_id, to_char(s.created_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS') from stickers s join users u on u.id = s.owner_id where s.public_id = '$ID'")
  if [[ -z $ROW ]]; then
    echo "No sticker with that ID: stop"
  else
    IFS='|' read -r OWNER UPLOADED <<< "$ROW"
    echo "Link:     https://discosticker.cafe/s/$ID.png"
    echo "Owner:    $OWNER (Discord ID)"
    echo "Uploaded: $UPLOADED UTC"
    IFS= read -r -p "Type yes to delete it, anything else stops: " ANSWER
    if [[ $ANSWER == yes ]]; then
      KEY=$($Prod exec -T postgres psql -U discosticker -d discosticker -Atq -c "delete from stickers where public_id = '$ID' returning object_key")
      echo "Returned object key: ${KEY:-none, so no row was deleted}"
      if [[ $KEY == "$ID.png" ]]; then
        $Prod run --rm --no-deps --entrypoint aws bucket-init s3 rm "s3://stickers/$KEY" --endpoint-url http://seaweedfs:8333
      else
        echo "Object not deleted: key '${KEY:-none}' isn't '$ID.png'"
      fi
    else
      echo "Stopped: nothing deleted"
    fi
  fi
else
  echo "Not a sticker ID: stop"
fi
```

- **Success:** after `yes`, it prints `delete: s3://stickers/<id>.png`.
- **"No sticker with that ID: stop":** the ID is wrong, or the sticker is already gone.
- **"Object not deleted":** no row was deleted, or the key isn't the usual one. Stop, and run the orphan check before deleting anything.
- **If `s3 rm` fails:** the sticker is still offline. Its object is now an orphan; delete it as shown under the orphan check.

**3. Verify.** From Windows, the link must print `404 application/json`. A live sticker prints `200 image/png`, so run this before step 2 too to see the change:

```powershell
curl.exe -sS -o NUL -w "%{http_code} %{content_type}" https://discosticker.cafe/s/<id>.png
```

On the server, both `comm` lines of the orphan check above must print nothing. Then check the audit row and the sticker row:

```bash
if [[ $ID =~ ^[A-Za-z0-9_-]{21}[AQgw]$ ]]; then
  $Prod exec postgres psql -U discosticker -d discosticker -c "select outcome, sticker_public_id from upload_audit where sticker_public_id = '$ID'"   # still one accepted row
  $Prod exec postgres psql -U discosticker -d discosticker -At -c "select count(*) from stickers where public_id = '$ID'"   # 0
else
  echo "Not a sticker ID: stop"
fi
```

**Afterwards:**
- **Ask the poster to delete their Discord message.** Browsers that already loaded the sticker can keep showing it for up to a day (`Cache-Control: public, max-age=86400`). Discord's media proxy is outside our control and may keep a copy for a similar time.
- **Audit:** the sticker's `upload_audit` row stays. It records who uploaded which SHA-256, and when, and it holds no image data.
- **The takedown itself isn't audited** this weekend. Audited purges come with ban-and-purge.
- **Rate limit:** a takedown doesn't free a rate-limit slot. The hourly limit counts accepted `upload_audit` rows, not stickers.

## Lockout recovery

**The home IPv4 changed** (SSH hangs). ufw on the host allows 22 from anywhere; only the Vultr firewall group limits SSH to `<home-ipv4>`. In the Vultr console, edit the group's IPv4 22/tcp rule to the new `<home-ipv4>/32`. No server login is needed.

**The SSH key is lost or broken.** Open the instance's web console in the Vultr dashboard. It's a local terminal login, not sshd, so passwords work. Log in as `deploy` with its password, then fix `/home/deploy/.ssh/authorized_keys`, which must be mode 600 and owned by `deploy`.

**sshd won't start after a config change.** Use the same web console, fix or remove `/etc/ssh/sshd_config.d/00-hardening.conf`, then:

```bash
sudo sshd -t && sudo systemctl restart ssh
```
