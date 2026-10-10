# Tasks

Conventions for every task:
- `[user]` marks a manual step for the maintainer. Claude Code does not do it. Claude never connects to the server, never runs commands on it, and never creates, reads, or prints any `secrets.env`.
- **Two tracks run at the same time**, then join:
  - **User track (server):** sections 2, 3 and 5. From 3.1 on, the commands come from `docs/deploy.md` (task 4.1, which Claude writes first). Certificate issuance needs only port 80 and Docker, not the other repo changes.
  - **Claude track (repo):** section 4.
  - **Join:** section 6 (go live), then section 7 (smoke checks). Section 1 can run any time.
- Commands on the maintainer's Windows machine are **Windows PowerShell 5.1**, run from the repo root: `curl.exe` (never `curl`), `-o NUL`, no `&&`. Server commands are **bash**, run over SSH as `deploy` from `/srv/discosticker`, with `Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'` defined. Before 3.1 they run as root.
- Never run `docker compose config` with `secrets.env` (locally, on the server, or in the runbook), and never run `docker inspect` on postgres, seaweedfs, bucket-init, or api. Config checks pass `secrets.env.example`.
- No home IP address (IPv4 or IPv6) in any committed file. Use the placeholder `<home-ipv4>`.
- Write www.discosticker.cafe as plain text, never as a markdown link.
- **The port probe** (PowerShell; used in 2.2, 2.3 and 7.8):
  ```powershell
  foreach ($ip in '207.148.19.168','2001:19f0:1000:f32f:5400:6ff:fed6:d61a') { foreach ($p in 22,80,443,5432,8080,8333,23646) { $c = New-Object Net.Sockets.TcpClient ([Net.IPAddress]::Parse($ip)).AddressFamily; $r = try { if ($c.ConnectAsync($ip,$p).Wait(3000)) {'open'} else {'closed'} } catch {'closed'}; $c.Dispose(); '{0,-40} {1,-6} {2}' -f $ip,$p,$r } }
  ```

## 1. DNS (verify-only; Claude, any time)

- [ ] 1.1 Confirm the existing records on two public resolvers. No DNS changes; www stays a CNAME to the apex (design D10).
  Verify: `foreach ($r in '8.8.8.8','1.1.1.1') { @(Resolve-DnsName discosticker.cafe -Type A -Server $r -DnsOnly | Where-Object Section -eq 'Answer').IPAddress; @(Resolve-DnsName discosticker.cafe -Type AAAA -Server $r -DnsOnly | Where-Object Section -eq 'Answer').IPAddress }; (Invoke-RestMethod 'https://dns.google/resolve?name=discosticker.cafe&type=CAA').Answer.data; (Resolve-DnsName www.discosticker.cafe -Type CNAME -Server 8.8.8.8 -DnsOnly | Where-Object Section -eq 'Answer').NameHost` prints, for each resolver, exactly one `207.148.19.168` and one `2001:19f0:1000:f32f:5400:6ff:fed6:d61a`, then `0 issue "letsencrypt.org"`, then `discosticker.cafe`.

## 2. Instance and firewall (user track)

- [ ] 2.1 [user] In the Vultr console, confirm the instance: Debian 13 x64, IPv6 enabled, the firewall group attached, and its rules (v4 22 from `<home-ipv4>/32`; v4 80 and 443 from `0.0.0.0/0`; v6 80 and 443 from `::/0`; nothing else). This task does not verify the firewall; the outside probe in 7.8 does.
  Verify (server, as root): `cat /etc/debian_version; nproc; free -m | awk '/^Mem:/{print $2}'; ip -6 addr show scope global | grep inet6` prints `13.` followed by a minor version, `2`, a value of about 3800 or more, and a line containing `2001:19f0:1000:f32f:5400:6ff:fed6:d61a`.

- [x] 2.2 [user] Find out why ports 80 and 443 time out (design D11). On the server, as root:
  - Run `ss -tlnp`, `nft list ruleset`, and `command -v ufw && ufw status verbose`.
  - Start two temporary listeners: `timeout 180 python3 -m http.server 80 --bind :: &` and `timeout 180 python3 -m http.server 443 --bind :: &`. If `python3` is missing, run `apt install -y python3` first.
  - While they run, run the port probe from Windows.
  Verify: the task notes record the `nft list ruleset` and `ufw status` output (or "ufw not installed"), plus the probe's 80 and 443 results on both addresses with the listeners running. One branch of design D11 is chosen from them: the Vultr rules need fixing, a host firewall is active, or neither.
  Notes (2026-10-10, reported by the user):
  - **Cause:** ufw was active with default-deny incoming and only 22 allowed. That dropped 80 and 443.
  - **Branch chosen:** a host firewall is active.
  - tcpdump on the server showed incoming SYNs answered with RST while nothing listened, but the RST never reached the maintainer's machine. With no listener, a probe therefore times out on any port, so "closed" in the probe means either a timeout or a refusal.

- [x] 2.3 [user] Apply the branch chosen in 2.2:
  - **Vultr rules:** fix the firewall group to match 2.1.
  - **Host firewall active:** keep it and allow 22, 80 and 443 for IPv4 and IPv6. For ufw: `ufw allow 22/tcp`, `ufw allow 80/tcp`, `ufw allow 443/tcp`, with `IPV6=yes` in `/etc/default/ufw`.
  - **Neither:** change nothing; the Vultr group is the only filter.
  Tell Claude which branch applied, so 4.1 records it in the runbook's firewall section.
  Verify: with the 2.2 listeners started again, the port probe prints `open` for 80 and 443 on both addresses. If a host firewall is active, `ufw status verbose` (or `nft list ruleset | grep -E 'dport (22|80|443)'`) shows 22, 80 and 443 allowed for IPv4 and IPv6.
  Notes (2026-10-10, reported by the user):
  - **Applied:** the "host firewall active" branch. `ufw allow 80/tcp` and `ufw allow 443/tcp` were added to the existing 22 rule, and `ufw status` lists 22, 80 and 443 for IPv4 and IPv6.
  - **End to end:** a temporary `python3 -m http.server` on port 80 answered `ok` to `curl.exe` from the maintainer's machine. Which address family that request used wasn't recorded. The listener was then stopped and its folder removed.
  - **Not yet checked end to end:** port 443, and port 80 over IPv6 specifically. These are covered by the ufw rules so far. Port 80 over IPv6 is re-checked before 5.2, because Let's Encrypt validates over IPv6 when an AAAA record exists. Port 443 on both families is proven by 7.4 and 7.8.

## 3. Host setup (user track; commands from docs/deploy.md "Host setup")

- [x] 3.1 [user] Create the `deploy` user as root:
  - Install `sudo` if it's missing.
  - `adduser --gecos '' deploy`, setting a password for sudo and the Vultr console.
  - `usermod -aG sudo deploy`.
  - Create `/home/deploy/.ssh` (mode 700) with an empty `authorized_keys` (mode 600), both owned by deploy, then append the maintainer's public key from Windows as given in the runbook. Don't copy root's `authorized_keys`: Vultr may not install a key at creation.
  On Windows, add the `Host discosticker` entry to `$HOME\.ssh\config` (`HostName 207.148.19.168`, `AddressFamily inet`, `User deploy`) as given in the runbook.
  Verify (PowerShell): `ssh -o PasswordAuthentication=no discosticker id` prints `uid=` with `(deploy)` and lists `sudo` among the groups.
  Notes (2026-10-10, reported by the user): Vultr installed no SSH key at creation. Root's and deploy's `authorized_keys` were both empty, and root logged in with a password. The maintainer added their public key to deploy's `authorized_keys` directly. `ssh -o PasswordAuthentication=no discosticker id` works. Root's password login is still on until 3.3.

- [x] 3.2 [user] Install Docker Engine and the Compose plugin from Docker's apt repository, as in the runbook (docs.docker.com/engine/install/debian). Then `sudo usermod -aG docker deploy`, log out, and log in again.
  Verify (server): `docker run --rm hello-world | grep -F 'Hello from Docker!'; docker compose version` prints the hello line and a Compose version of v2.24.4 or newer.
  Notes (2026-10-10, reported by the user): `Hello from Docker!`, `Docker Compose version v5.6.0`, and `systemctl is-enabled docker` prints `enabled`.

- [x] 3.3 [user] Harden sshd with `/etc/ssh/sshd_config.d/00-hardening.conf` (`PermitRootLogin no`, `PasswordAuthentication no`, `KbdInteractiveAuthentication no`), then `sudo sshd -t` and `sudo systemctl reload ssh`. Keep the current SSH session open until the checks pass.
  Verify (server): `sudo sshd -T | grep -E '^(permitrootlogin|passwordauthentication|kbdinteractiveauthentication) '` prints three lines, each ending in `no`. Then (PowerShell) `ssh -o PubkeyAuthentication=no -o PreferredAuthentications=password,keyboard-interactive discosticker true` and `ssh root@discosticker true` each print `Permission denied (publickey).`
  Notes (2026-10-10, reported by the user): `sshd -T` shows `permitrootlogin no`, `passwordauthentication no` and `kbdinteractiveauthentication no`. `ssh -o PasswordAuthentication=no discosticker id` prints `uid=1001(deploy)` with groups `sudo` and `docker`. The forced-password attempt and `ssh root@discosticker true` each print `Permission denied (publickey).`

- [x] 3.4 [user] Install unattended-upgrades with `sudo apt install -y unattended-upgrades apt-listchanges` and enable it with `sudo dpkg-reconfigure -plow unattended-upgrades` (answer Yes).
  Verify (server): `cat /etc/apt/apt.conf.d/20auto-upgrades` shows `"1"` for both `Update-Package-Lists` and `Unattended-Upgrade`. `systemctl list-timers apt-daily-upgrade.timer` shows a NEXT time. `sudo unattended-upgrade --dry-run -d 2>&1 | grep -i 'allowed origins'` includes `Debian-Security`.
  Notes (2026-10-10, reported by the user):
  - `20auto-upgrades` has `"1"` for both settings.
  - Both timers have run and have a next run scheduled: `apt-daily-upgrade` at 2026-10-11 06:43 UTC and `apt-daily` at 16:45 UTC.
  - The allowed origins are `codename=trixie,label=Debian`, `codename=trixie,label=Debian-Security` and `codename=trixie-security,label=Debian-Security`. So stable point releases install automatically too, not only security fixes. design D15 and the runbook were corrected to match. No Docker origin is allowed.

## 4. Repo changes (Claude track)

- [x] 4.1 Write `docs/deploy.md`, the runbook, first, because the user track follows it from 3.1 on. It needs these `##` sections:
  - **SSH access:** the `~/.ssh/config` entry and why `AddressFamily inet` is needed.
  - **Firewall:** the Vultr rules with the `<home-ipv4>` placeholder, the branch applied in 2.3, and the port probe.
  - **Host setup:** sections 3.1–3.4.
  - **Certificates:** 5.1–5.3, including the hook script.
  - **Go live:** 6.2–6.4, including the `secrets.env` generator.
  - **Smoke checks:** section 7, in PowerShell.
  - **Renewal**
  - **Reboot**
  - **Redeploy:** `git pull`, `$Prod up --build -d --wait`, and the out-of-memory fallback `$Prod build api`, then `$Prod build gateway`. Also `docker builder prune` when `df -h /` passes 80%.
  - **Lockout recovery:** the Vultr console with `deploy`'s password.
  Every code block is labelled `bash` or `powershell`. Server commands are bash; Windows commands are PowerShell 5.1.
  Verify: run each check below; each prints nothing unless noted.
  - `$f = $null; Get-Content docs\deploy.md | ForEach-Object { if ($_ -match '^```(\w*)$') { if ($null -ne $f) { $f = $null } else { $f = $matches[1] } } elseif ($null -ne $f) { if ($_ -match '(\$Prod|docker compose)\b.*\sconfig\b' -or $_ -match 'docker inspect') { "config/inspect: $_" }; if ($f -eq 'powershell' -and ($_ -match '&&' -or $_ -match '(^|[\s(;])curl\s')) { "powershell: $_" }; if ($f -notin 'bash','powershell') { "unlabelled fence: $_" } } }`
  - `Select-String -Path docs\deploy.md -Pattern '(PASSWORD|KEY)=[^<\s$"%]'`
  - `Get-ChildItem -Recurse -File docs\deploy.md,openspec\changes\add-production-deploy | Select-String -Pattern '\]\([^)]*www\.discosticker|<https?://www\.discosticker'`
  - `Select-String -Path docs\deploy.md -SimpleMatch -Pattern '<home-ipv4>','AddressFamily inet','$Prod build api','$Prod build gateway','--run-deploy-hooks'` prints at least one line for each pattern.
  - [user] `git grep -n --untracked -F -e (curl.exe -4 -s https://api.ipify.org)` and `git grep -n --untracked -F -e (curl.exe -6 -s https://api64.ipify.org)` print nothing. This covers every tracked and new file (gitignored files excluded), including `docs\deploy.md` and `openspec\changes\add-production-deploy\`, at any depth.

- [x] 4.2 Move the routes into a shared include without changing local behavior (design D2):
  - `gateway/routes.conf` gets `root`, `index`, `absolute_redirect off`, the four `^~` proxy locations and `location /`, unchanged.
  - `gateway/conf.d/local.conf` gets `server { listen 8080; include /etc/nginx/routes.conf; }`.
  - `gateway/nginx.conf` includes `/etc/nginx/conf.d/*.conf`.
  - The Dockerfile copies the new files.
  First run the Verify checks against the current stack and record the results (all should pass), then refactor and run them again.
  Verify: every command in the CLAUDE.md Commands section passes. Then, with the local stack up:
  - `curl.exe -fsS http://localhost:8080/api/health` contains `"status":"UP"`.
  - `foreach ($p in '/oauth2/x','/login/oauth2/x','/s/x','/nope/') { curl.exe -sS -o NUL -w "$p %{http_code} %{content_type}\n" "http://localhost:8080$p" }` prints `404 application/json` for the three api paths and `404 text/html` for `/nope/`.
  - `(Select-String gateway\routes.conf -SimpleMatch -Pattern 'include /etc/nginx/proxy_headers.conf').Count` is `4`.

- [x] 4.3 Add `gateway/security_headers.conf` (design D9) and `gateway/prod/discosticker.conf` (design D3): port 80 with ACME and the redirect, the port-80 default server returning 444, 443 for the apex, 443 for www.discosticker.cafe redirecting to the apex, and the 443 default server with `ssl_reject_handshake on`. No `ssl_stapling`. The Dockerfile copies `security_headers.conf`. Test the config against a throwaway self-signed certificate:
  `docker compose --env-file secrets.env.example build gateway; $le = Join-Path $env:TEMP 'dsc-letsencrypt'; New-Item -ItemType Directory -Force "$le\live\discosticker.cafe" | Out-Null; docker run --rm -v "${le}:/out" nginx:1.30.5-alpine sh -ec 'apk add -q --no-cache openssl; openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=discosticker.cafe -keyout /out/live/discosticker.cafe/privkey.pem -out /out/live/discosticker.cafe/fullchain.pem'; docker run --rm --add-host api:127.0.0.1 -v "${le}:/etc/letsencrypt:ro" -v "${PWD}\gateway\prod:/etc/nginx/conf.d:ro" discosticker-gateway nginx -T > "$le\nginx-T.txt"`
  Verify:
  - The last command prints `nginx: configuration file /etc/nginx/nginx.conf test is successful`.
  - `Select-String "$le\nginx-T.txt" -Pattern '^\s*listen\s'` shows only port 80 and 443 listeners (none on 8080).
  - `Select-String "$le\nginx-T.txt" -Pattern 'ssl_stapling','return 301 https://\$host'` prints nothing.
  - `Select-String gateway\routes.conf -Pattern 'add_header'` prints nothing.
  - `(Select-String gateway\prod\discosticker.conf -SimpleMatch -Pattern 'include /etc/nginx/routes.conf').Count` is `1`.
  - `(Select-String gateway\prod\discosticker.conf -SimpleMatch -Pattern 'include /etc/nginx/security_headers.conf').Count` is `2`.
  - `Select-String gateway\security_headers.conf -Pattern 'always;'` prints the four header lines, with HSTS exactly `"max-age=300"`.

- [x] 4.4 Add `compose.prod.yaml` (design D4–D7):
  - On the gateway: `ports: !override ["80:80", "443:443"]`, plus read-only mounts `/etc/letsencrypt:/etc/letsencrypt`, `/var/www/certbot:/var/www/certbot`, and the directory `./gateway/prod:/etc/nginx/conf.d`.
  - The healthcheck `curl -fsS -o /dev/null --resolve discosticker.cafe:443:127.0.0.1 https://discosticker.cafe/`.
  - `restart: unless-stopped` on gateway, api, postgres and seaweedfs; `restart: "no"` on bucket-init.
  Verify:
  - `docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env.example config --quiet; "exit=$LASTEXITCODE"` prints `exit=0`.
  - `$c = docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env.example config --format json | Out-String | ConvertFrom-Json; $c.services.gateway.ports | Select-Object host_ip,published,target; $c.services.PSObject.Properties | ForEach-Object { '{0} restart={1} ports={2}' -f $_.Name, $_.Value.restart, ($_.Value.ports | Measure-Object).Count }; $c.services.gateway.volumes | Select-Object source,target,read_only; $c.services.gateway.healthcheck.test -join ' '` shows:
    - gateway ports exactly 80→80 and 443→443, with no host_ip and no 8080
    - `restart=unless-stopped` for api, gateway, postgres and seaweedfs, and `restart=no` for bucket-init
    - `ports=0` for every service but the gateway
    - three gateway volumes, all `read_only` True, the last with a source ending in `gateway\prod` (a directory)
    - the `--resolve discosticker.cafe:443:127.0.0.1` healthcheck
  - `docker compose --env-file secrets.env.example config --quiet; "exit=$LASTEXITCODE"` still prints `exit=0`, and the local gateway port is still `127.0.0.1:8080`.

- [x] 4.5 Apply the production-secrets rule wording to `openspec/config.yaml` (replacing "Production secret handling is undecided and is not in scope.") and `CLAUDE.md` (a new bullet under the secrets rule). **Blocked:** do this only after the maintainer confirms PM approval, and use the exact wording the maintainer provides; design.md's draft is only a proposal. Until then, leave this task unchecked. Go-live doesn't depend on it.
  Verify: `Select-String -Path openspec\config.yaml,CLAUDE.md -Pattern 'Production secret handling is undecided'` prints nothing. `Select-String -Path openspec\config.yaml,CLAUDE.md -SimpleMatch -Pattern '<first sentence of the approved wording>'` prints one line from each file.

## 5. Certificates (user track; commands from docs/deploy.md "Certificates")

- [x] 5.1 [user] Run `sudo apt install -y certbot` and `sudo install -d -m 755 /var/www/certbot`.
  Verify (server): `certbot --version` prints `certbot 4.0.0`. `systemctl list-timers certbot.timer` shows a NEXT time. `stat -c '%U %a' /var/www/certbot` prints `root 755`.
  Notes (2026-10-10, reported by the user): `certbot 4.0.0`, `certbot.timer` next run scheduled (2026-10-11 10:57 UTC), `/var/www/certbot` is `root 755`.

- [x] 5.2 [user] First issuance (design D7). Needs 2.3 (port 80 reachable on both address families) and 3.2 (Docker).
  - Start the bootstrap: `docker run -d --name acme-bootstrap -p 80:80 -v /var/www/certbot:/usr/share/nginx/html:ro nginx:1.30.5-alpine`.
  - Before the dry run, check port 80 over IPv6 while the bootstrap is running. 2.3 didn't prove it, and Let's Encrypt validates over IPv6 when an AAAA record exists. From Windows (PowerShell): `foreach ($f in '-4','-6') { curl.exe $f -sS -o NUL -w "$f %{http_code}\n" --max-time 5 http://discosticker.cafe/ }` prints an HTTP status for each family (any 3-digit code, such as 403 from the empty webroot). `000`, or a timeout, means stop: fix the IPv6 path before any certbot request.
  - Dry run: `sudo certbot certonly --webroot -w /var/www/certbot -d discosticker.cafe -d www.discosticker.cafe --register-unsafely-without-email --agree-tos --dry-run`. No email address goes to Let's Encrypt: it stopped sending expiry emails in 2025, and renewal is automatic (D7).
  - If the dry run succeeds, run the same command without `--dry-run`.
  - Remove the bootstrap: `docker rm -f acme-bootstrap`.
  Verify (server):
  - `sudo certbot certificates` shows `Certificate Name: discosticker.cafe`, `Domains: discosticker.cafe www.discosticker.cafe`, and `VALID: 89 days` (or 88).
  - `sudo grep '^authenticator' /etc/letsencrypt/renewal/discosticker.cafe.conf` prints `authenticator = webroot`.
  - `docker ps -aq --filter name=acme-bootstrap` prints nothing.
  Notes (2026-10-10, reported by the user):
  - **Port 80 check, with the bootstrap running:** `-4 403` and `-6 403`, so port 80 works end to end on both families. This closes the IPv6 gap left open in 2.3.
  - **Certbot:** the staging dry run succeeded, and the real issuance succeeded with no email registered.
  - **Certificate:** name `discosticker.cafe`, domains `discosticker.cafe www.discosticker.cafe`, ECDSA key, expires 2027-01-08 19:18 UTC (VALID: 89 days). `authenticator = webroot`.
  - The bootstrap container was removed, and the last check printed nothing.

- [ ] 5.3 [user] Install the deploy hook from the runbook as `/etc/letsencrypt/renewal-hooks/deploy/reload-gateway`, owned by root with mode 755 (design D8). 7.9 exercises it end to end.
  Verify (server): `sudo stat -c '%U %a' /etc/letsencrypt/renewal-hooks/deploy/reload-gateway` prints `root 755`, and `sudo bash -n /etc/letsencrypt/renewal-hooks/deploy/reload-gateway; echo $?` prints `0`.

## 6. Go live (join: needs sections 2, 3, 5 and 4.1–4.4)

- [ ] 6.1 [user] Commit the section 4 work on `feature/deploy` (or ask Claude to commit it), then push the branch so the server can clone it.
  Verify (PowerShell): `git ls-remote origin refs/heads/feature/deploy` prints the same hash as `git rev-parse feature/deploy`.

- [ ] 6.2 [user] Clone to `/srv/discosticker` as deploy on `feature/deploy`: install `git` if it's missing, run `sudo install -d -o deploy -g deploy /srv/discosticker`, then `git clone --branch feature/deploy https://github.com/MyriaCore/DiscoStickerCafe.git /srv/discosticker`.
  Verify (server): `git -C /srv/discosticker rev-parse HEAD` prints the hash from 6.1, `git -C /srv/discosticker branch --show-current` prints `feature/deploy`, and `stat -c '%U' /srv/discosticker` prints `deploy`.

- [ ] 6.3 [user] Create `/srv/discosticker/secrets.env` by hand with the runbook's generator (design D13): only if the file doesn't exist, under `umask 077`, with hex values from `openssl rand`, and printing nothing.
  Verify (server, from `/srv/discosticker`):
  - `stat -c '%U:%G %a' secrets.env` prints `deploy:deploy 600`.
  - `diff <(cut -d= -f1 secrets.env) <(grep -Ev '^(#|$)' secrets.env.example | cut -d= -f1); echo $?` prints only `0`.
  - `git check-ignore -q secrets.env; echo $?` prints `0`.

- [ ] 6.4 [user] Build and start: `$Prod up --build -d --wait --wait-timeout 900`. If the build runs out of memory, run `$Prod build api`, then `$Prod build gateway`, then the `up` command again.
  Verify (server): `$Prod up --build -d --wait --wait-timeout 900; echo "exit=$?"` prints `exit=0`. `$Prod ps -a --format '{{.Service}} | {{.Status}} | {{.Ports}}'` shows:
  - gateway, api, postgres and seaweedfs `(healthy)`
  - bucket-init `Exited (0)`
  - ports only on the gateway, and only 80 and 443 (on `0.0.0.0` and `[::]`)

## 7. Smoke checks (Claude from PowerShell unless marked [user])

- [ ] 7.1 DNS is unchanged after go-live.
  Verify: the 1.1 command prints the same values.

- [ ] 7.2 Plain HTTP redirects to HTTPS; www redirects to the apex; ACME paths and unknown hosts are not redirected.
  Verify:
  - `foreach ($u in 'http://discosticker.cafe/api/health','http://www.discosticker.cafe/x','https://www.discosticker.cafe/x','http://discosticker.cafe/.well-known/acme-challenge/x') { curl.exe -sS -o NUL -w "$u -> %{http_code} %{redirect_url}\n" $u }` prints, in order:
    - `301 https://discosticker.cafe/api/health`
    - `301 https://discosticker.cafe/x`
    - `301 https://discosticker.cafe/x`
    - `404` with an empty redirect URL
  - `curl.exe -s -o NUL -H "Host: example.com" http://207.148.19.168/; "exit=$LASTEXITCODE"` prints a non-zero exit (empty reply, from the 444).

- [ ] 7.3 The TLS certificate is valid and covers both names; bare-IP handshakes are refused.
  Verify:
  - `foreach ($h in 'discosticker.cafe','www.discosticker.cafe') { $t = New-Object Net.Sockets.TcpClient($h,443); $s = New-Object Net.Security.SslStream($t.GetStream()); $s.AuthenticateAsClient($h); $x = New-Object Security.Cryptography.X509Certificates.X509Certificate2 $s.RemoteCertificate; $h; $x.Issuer; $x.NotAfter; ($x.Extensions | Where-Object { $_.Oid.Value -eq '2.5.29.17' }).Format($false); $t.Dispose() }` throws no exception. For both names it prints an issuer containing `O=Let's Encrypt`, a NotAfter about 89 days out, and SANs of exactly `discosticker.cafe` and `www.discosticker.cafe`.
  - `curl.exe -k -sS -o NUL https://207.148.19.168/; "exit=$LASTEXITCODE"` prints a TLS handshake error and a non-zero exit.

- [ ] 7.4 Health over IPv4 and IPv6.
  Verify:
  - `(Invoke-RestMethod https://discosticker.cafe/api/health).status` prints `UP`.
  - `curl.exe -4 -fsS https://discosticker.cafe/api/health` and `curl.exe -6 -fsS https://discosticker.cafe/api/health` each print a body containing `"status":"UP"`.
  - Optional [user] second IPv6 check from the myriaco.re server: `curl -6 -fsS https://discosticker.cafe/api/health` contains `"status":"UP"`.

- [ ] 7.5 The api prefixes return the api's JSON 404.
  Verify: `foreach ($p in '/oauth2/x','/login/oauth2/x','/s/x') { curl.exe -sS -o NUL -w "$p %{http_code} %{content_type}\n" "https://discosticker.cafe$p" }` prints `404 application/json` for each path.

- [ ] 7.6 The static root is served, and an unknown path returns 404.
  Verify: `foreach ($p in '/','/nope/') { curl.exe -sS -o NUL -w "$p %{http_code}\n" "https://discosticker.cafe$p" }` prints `/ 200` and `/nope/ 404`.

- [ ] 7.7 Security headers are on static, proxied and error responses.
  Verify: `foreach ($p in '/','/api/health','/nope/') { "== $p"; curl.exe -sI "https://discosticker.cafe$p" | Select-String -Pattern '^(strict-transport-security|x-content-type-options|referrer-policy|x-frame-options):' }` prints four lines under each path:
  - `strict-transport-security: max-age=300` (no `includeSubDomains` or `preload`)
  - `x-content-type-options: nosniff`
  - `referrer-policy: strict-origin-when-cross-origin`
  - `x-frame-options: DENY`

- [ ] 7.8 Only 22, 80 and 443 are reachable from outside. This also verifies the firewall group rules from 2.1.
  Verify:
  - Run the port probe from the maintainer's home network. On `207.148.19.168`, 22, 80 and 443 are `open`. On `2001:19f0:1000:f32f:5400:6ff:fed6:d61a`, 80 and 443 are `open`. Every other line is `closed`, including IPv6 port 22, and 5432, 8080, 8333 and 23646 on both addresses.
  - [user] On the server, `sudo ss -tlnp` shows listeners only for sshd on 22 and docker-proxy on 80 and 443. `$Prod ps --format '{{.Service}} {{.Ports}}'` shows ports only on the gateway.

- [ ] 7.9 [user] Certificate renewal runs end to end through the running gateway, including the deploy hook.
  Verify (server): `sudo certbot renew --dry-run --run-deploy-hooks` prints `Congratulations, all simulated renewals succeeded` and no hook error. `$Prod logs --since 5m gateway | grep -i reconfiguring` shows the reload.

- [ ] 7.10 [user] Reboot recovery: run `sudo reboot` on the server and don't log in again. Claude then polls from Windows.
  Verify: `$d = (Get-Date).AddMinutes(6); do { try { $st = (Invoke-RestMethod https://discosticker.cafe/api/health -TimeoutSec 5).status } catch { $st = $null; Start-Sleep 10 } } until ($st -eq 'UP' -or (Get-Date) -gt $d); $st` prints `UP`.

- [ ] 7.11 [user] After the PR merges, switch the server to `master` with `git -C /srv/discosticker switch master` and `git -C /srv/discosticker pull --ff-only`.
  Verify (server): `git -C /srv/discosticker branch --show-current` prints `master`, and `git -C /srv/discosticker rev-parse HEAD` equals `git -C /srv/discosticker rev-parse origin/master`. Then Claude reruns the 7.4 checks, which still return `UP`.

## Workflow follow-up

- Open the PR from `feature/deploy` to `master` after section 7 passes up to 7.10. Merge it, then do 7.11.
- Take design.md's "Decision candidates for the PM session" to the PM session. `docs/decisions.md` is edited only when the maintainer asks.
- Archive the change (`/opsx:archive`) once 4.5 is done or explicitly deferred.
