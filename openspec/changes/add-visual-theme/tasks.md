# Tasks

Conventions for every task:
- **`[user]` marks a manual step for the maintainer.** Claude Code never connects to the server, never runs commands on it, and never creates, reads or prints any `secrets.env`.
- **Where each kind of command runs:**
  - **Claude's commands:** Git Bash from the repo root (CLAUDE.md), with `MSYS_NO_PATHCONV=1` for `docker` commands that pass container paths.
  - **The maintainer's Windows commands:** **Windows PowerShell 5.1** from the repo root, using `curl.exe` (never `curl`), `-o NUL`, and no `&&`.
  - **Server commands:** **bash**, over SSH as `deploy`, from `/srv/discosticker`, with `Prod='docker compose -f compose.yaml -f compose.prod.yaml --env-file secrets.env'` defined.
- Never run `docker compose config` with `secrets.env`, and never run `docker inspect` on postgres, seaweedfs, bucket-init or api.
- **CLAUDE.md commands:** every command in CLAUDE.md's Commands section must pass before a Claude task is reported done. Claude therefore reports 1.1–2.3 done only after 3.1 has run them all on the finished tree (design D8). Each task's own Verify runs when that task's work is done.
- **Test-first:** there's no frontend test runner (decision 2026-10-10). The checks below are fixed before any edit, and 1.1 runs them on the unchanged tree.
- **The logo's SHA-256** is `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4`. PowerShell's `Get-FileHash` prints it in uppercase: `D5D773755C71A9136D56E9F173D4E32BFCAAA3E07E329CD857E1E419E02E25B4`. Every logo check compares the full value.
- **New color pairings:** before any pairing that isn't in design D5's tables is used, compute its WCAG ratio and record it in the Notes of the task that uses it. It must pass AA: 4.5:1 for text, 3:1 for non-text.
- **Time budget:** the apply is about 61 minutes:

  | Task | Minutes |
  |---|---|
  | 1.1 | 2 |
  | 1.2 | 3 |
  | 2.1 | 20 |
  | 2.2 | 7 |
  | 2.3 | 13 |
  | 3.1 | 8 |
  | 3.2 | 3 |
  | 3.3 | 5 |

  Section 4 uses the 30-minute review and deploy window.
- **Cut order:** see design "Cut order". If the browser icon is cut, the icon-link checks in 2.2, 3.2 and 4.2 expect one line, the `/favicon.ico` link.
  - PM decisions (2026-10-11): items 1 and 2 were cut during apply, then restored when the PM kept the browser icon and extended the box to about 4:50 pm. Both are in, as planned.
- **The existing frontend greps** (from add-sticker-upload task 9.1), Git Bash:
  ```bash
  grep -rnE --include='*.ts' --include='*.tsx' '10 ?MB|10485760|\b20\b|\b160\b' frontend/app frontend/lib   # prints nothing
  grep -rn 'dangerouslySetInnerHTML' frontend/app frontend/lib                                             # prints nothing
  grep -c 'Animated images become a still image.' frontend/app/page.tsx                                     # prints 1
  ```
- **The logo-reference greps**, Git Bash:
  ```bash
  grep -rc '<svg' frontend/app | awk -F: '{s+=$NF} END {print s}'   # total: 0, the logo is never inlined
  grep -rho 'src="/discosticker-logo.svg"' frontend/app | wc -l     # 2 once the theme is in: header (layout.tsx) and splash (page.tsx)
  ```
- **The fixed-string check**, Git Bash. Every existing string in `page.tsx` stays, exactly once, so it must print `1` at the start of all 17 lines:
  ```bash
  for s in 'Checking sign-in…' 'Sign in with Discord' 'href="/oauth2/authorization/discord"' 'Signed in as' \
    "Sign-out didn't complete. Please try again." 'Your session has ended. Please sign in again.' \
    'Sign-in is unavailable right now. Please try again later.' 'Loading the upload form…' \
    'Uploads are unavailable right now. Please try again later.' 'Make a sticker' \
    '{typeNames(loaded)} images up to {megabytes(loaded)} MB, {loaded.uploadsPerHour} uploads per hour. Images' \
    'larger than {size} × {size} pixels are shrunk to fit.' 'Your sticker link:' \
    '"Uploading…"' '"Upload"' '"Copied"' '"Copy"'; do
    printf '%s %s\n' "$(grep -cF -- "$s" frontend/app/page.tsx)" "$s"
  done
  ```
- **The line-survival check**, Git Bash. It must print nothing: every line of `master`'s `page.tsx`, except the old `<h1>`, survives once `className` attributes and indentation are stripped. So `className` is added as ` className="…"` (one space before it) to existing tags, and an existing tag's name and other attributes never change.
  ```bash
  old=$(mktemp); new=$(mktemp)
  git show master:frontend/app/page.tsx | sed -E 's/^[[:space:]]+//' | grep -vxF '<h1>DiscoSticker.cafe</h1>' > "$old"
  sed -E 's/ className="[^"]*"//g; s/^[[:space:]]+//' frontend/app/page.tsx > "$new"
  while IFS= read -r l; do grep -qxF -- "$l" "$new" || printf 'missing: %s\n' "$l"; done < "$old"; rm -f "$old" "$new"
  ```
- **The production header check**, PowerShell 5.1. It must print `1` on all 25 lines:
  ```powershell
  $names = 'strict-transport-security','x-content-type-options','referrer-policy','x-frame-options'
  foreach ($p in '/','/api/health','/nope/','/discosticker-logo.svg','/favicon.ico') {
    $h = curl.exe -sI "https://discosticker.cafe$p" | ForEach-Object { $_.TrimEnd() }
    foreach ($n in $names) { '{0,-24} {1,-26} {2}' -f $p, $n, @($h | Select-String -Pattern "^${n}:").Count }
    '{0,-24} {1,-26} {2}' -f $p, 'hsts = max-age=300', @($h | Select-String -Pattern '^strict-transport-security: max-age=300$').Count
  }
  ```

## 1. Baseline and logo

- [x] 1.1 Baseline (~2 min). Before any edit, run the frontend build and the fixed checks on the unchanged tree, so any later failure is known to be new (design D8).

  Verify:
  - `cd frontend && npm ci && npm run typecheck && npm run build` exits 0.
  - The existing frontend greps print nothing, nothing and `1`.
  - The fixed-string check prints `1` at the start of all 17 lines.
  - The logo-reference greps print `0` and `0`.

  Notes (2026-10-11, Claude):
  - On `feature/theme` at `b44da79`, the same commit as `master`, with no frontend diff from `master`.
  - `npm ci`, `npm run typecheck` and `npm run build` succeeded.
  - The existing greps printed nothing, nothing and `1`; the fixed-string check printed `1` on all 17 lines; the logo-reference greps printed `0` and `0`.
  - The line-survival check printed nothing on the unchanged tree.
  - Negative test on a scratch copy: adding `disabled` to the Sign out button was reported as `missing: <button type="button" onClick={handleSignOut}>`, while a `<section>` that only gained ` className="card"` passed.

- [x] 1.2 Copy the logo byte for byte (~3 min; design D2, T1, T2). Run `mkdir -p frontend/public && cp ~/Downloads/discosticker-logo.svg frontend/public/discosticker-logo.svg`, never opening the file in an editor or formatter. Then re-run the content scan on the copy.

  Verify:
  - `sha256sum ~/Downloads/discosticker-logo.svg frontend/public/discosticker-logo.svg` prints `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4` on both lines.
  - `[ "$(git hash-object frontend/public/discosticker-logo.svg)" = "$(git hash-object --no-filters frontend/public/discosticker-logo.svg)" ] && echo same-blob` prints `same-blob`: git's line-ending handling leaves the committed bytes unchanged.
  - `grep -ciE '<script|\bon[a-z]+[[:space:]]*=|javascript:|<foreignObject|<image|<style|@import|url\(|data:|<!ENTITY' frontend/public/discosticker-logo.svg` prints `0`.
  - `grep -oE '(xlink:)?href="[^"]*"' frontend/public/discosticker-logo.svg | grep -vc '="#'` prints `0`: every `href` is a fragment.

  Notes (2026-10-11, Claude):
  - Copied with `cp`, 3337 bytes; the file was never opened in an editor.
  - `sha256sum` printed `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4` for both the download and the copy.
  - The blob check printed `same-blob`, and the content scan and non-fragment `href` check each printed `0`.

## 2. Theme

- [x] 2.1 Rewrite `frontend/app/globals.css` (~20 min; design D1, D5, D6). It holds:
  - the 12 `--ds-*` tokens on `:root`, named and valued exactly as in the brief;
  - no `prefers-color-scheme` block, and `color-scheme: light`;
  - a system font stack;
  - styles for:
    - the header;
    - the splash: two-column on wide screens, stacked on narrow ones;
    - the card;
    - filled, outlined and disabled buttons, and `::file-selector-button`;
    - the text input and alerts;
    - the peach result panel, with a success accent border;
    - a `:focus-visible` ring.

  Rules:
  - Hover changes elevation only.
  - Every size lives in CSS.
  - Colors come only through the tokens: white is `var(--ds-surface)`. Shadows are written as `rgba(59, 33, 71, <alpha>)`, with commas.
  - Before using any pairing that isn't in design D5's tables, record it with its computed ratio in this task's Notes.

  Verify:
  - `grep -c 'prefers-color-scheme' frontend/app/globals.css` prints `0`.
  - `grep -ciE '@import|url\(|@font-face|https?://' frontend/app/globals.css` prints `0`.
  - This loop prints `1` at the start of all 12 lines:
    ```bash
    for t in 'cream: #FFF7EE' 'surface: #FFFFFF' 'plum: #3B2147' 'plum-muted: #6A5277' 'lavender-strong: #6E55C4' \
      'lavender: #C9B8F0' 'lavender-mid: #9D86DE' 'peach: #FFCBAE' 'peach-strong: #F2906B' 'blush: #FFB899' \
      'error: #A3324A' 'success: #2F6B4F'; do
      printf '%s %s\n' "$(grep -cF -- "--ds-$t;" frontend/app/globals.css)" "$t"
    done
    ```
  - `grep -cE '#[0-9A-Fa-f]{3,8}\b' frontend/app/globals.css` prints `12`: hex colors appear only on the token lines.
  - `grep -oE 'rgba?\([^)]*\)' frontend/app/globals.css | grep -vcE '^rgba?\(59, ?33, ?71'` prints `0`: any `rgb()` or `rgba()` is plum.
  - `grep -nwiE 'white|black' frontend/app/globals.css | grep -v white-space` prints nothing.
  - `cd frontend && npm run typecheck && npm run build` exits 0.

  Notes (2026-10-11, Claude):
  - No new color pairing: every pairing used is in design D5's tables.
    - Text: plum and plum-muted on cream (page text, statuses, splash line); plum and plum-muted on surface (header, card, inputs, hints); surface on lavender-strong (filled buttons); lavender-strong on cream (outlined Sign out, transparent background); lavender-strong on surface (the file-picker button); plum on lavender (disabled Upload); plum on peach (the result label); error on surface (alerts, which have a surface background on the page and in the card).
    - Non-text: the focus ring, the filled and outlined button edges, the input border (plum-muted), and the alert's error left border.
  - The result panel has a 4 px success left border on peach (non-text, 4.32:1, in design D5's tables).
  - The shadows are `--elevation-1` and `--elevation-2`, named outside `--ds-*` so the brief's 12 tokens stay exactly 12. They are written as `rgba(59, 33, 71, <alpha>)`.
  - Hover only raises the shadow.
  - The scaffold's `overflow-x: hidden` on `html, body` is gone, so 3.3's 360 px check can actually see any overflow.
  - Checks:
    - `prefers-color-scheme` 0;
    - `@import`/`url(`/`@font-face`/http 0;
    - the token loop `1` on all 12 lines;
    - hex lines 12;
    - non-plum `rgb()` 0;
    - the white/black grep printed nothing;
    - type check and build exit 0.

- [x] 2.2 Update `frontend/app/layout.tsx` (~7 min; design D2, D3, D4).
  - Add `icons: { icon: [{ url: "/discosticker-logo.svg", type: "image/svg+xml", sizes: "any" }] }` to `metadata`, leaving `title` and `description` unchanged.
  - Add the header: `<img src="/discosticker-logo.svg" alt="">` and the "DiscoSticker.cafe" wordmark, which isn't a heading.

  Verify:
  - `cd frontend && npm run typecheck && npm run build` exits 0.
  - `grep -oE '<link rel="icon"[^>]*>' frontend/out/index.html` prints exactly two lines:
    - first, one containing `href="/favicon.ico` and `type="image/x-icon"`;
    - then one containing `href="/discosticker-logo.svg"` and `type="image/svg+xml"`.

    If the icon is cut, it prints one line, the `/favicon.ico` link.
  - `grep -c '<title>DiscoSticker.cafe</title>' frontend/out/index.html` prints `1`.
  - `sha256sum frontend/out/discosticker-logo.svg` prints `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4`.

  Notes (2026-10-11, Claude):
  - The header holds `<img className="site-header-logo" src="/discosticker-logo.svg" alt="" />` and `<span className="site-header-name">DiscoSticker.cafe</span>`, before `{children}` in `<body>`.
  - `metadata` adds only `icons`, restored after the PM undid the cut; `title` and `description` are unchanged.
  - The type check and build exit 0. The icon grep printed two lines: `<link rel="icon" href="/favicon.ico?favicon.117ezoe8m31dk.ico" sizes="48x48" type="image/x-icon"/>`, then `<link rel="icon" href="/discosticker-logo.svg" type="image/svg+xml" sizes="any"/>`.
  - `app/favicon.ico` is byte-identical to create-next-app 16.4.0's default (SHA-256 `c28fdd2a4f31e2dc64f653962286da5c82a4cdfc518b242d32812c624e9a19a4`, matched against the cached package). It's left unchanged; browsers without SVG icons, possibly Safari, show it.
  - The title grep printed `1`, and `frontend/out/discosticker-logo.svg` hashes to `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4`.
  - Re-run (2026-10-11, Claude), after the PM undid both cuts. I re-checked rather than relying on the notes above.
    - 2.1's checks: tokens `1` on all 12 lines; hex lines 12; non-plum `rgb()` 0; white/black nothing; `prefers-color-scheme` 0; `@import`/`url(`/`@font-face`/http 0. `.result` has `border-left: 4px solid var(--ds-success)` again.
    - 2.2's icon grep printed the two lines above, favicon first.
    - 2.3's checks: existing greps nothing, nothing and `1`; fixed strings `1` on all 17 lines; line-survival nothing; splash `1`, `1` and `splash-in-html`; logo references `0` and `2`; the `lib/` and package diff exit 0.
    - The type check and build exit 0.
    - `favicon.ico` was checked independently. `npm pack create-next-app@16.4.0 --offline` from the local npm cache, unpacked in a scratch folder: all 8 `dist/templates/*/*/app/favicon.ico` hash to `c28fdd2a4f31e2dc64f653962286da5c82a4cdfc518b242d32812c624e9a19a4`, the same as ours, which is unchanged since `c12cf20`.

- [x] 2.3 Update `frontend/app/page.tsx` (~13 min; design D4, D7).
  - Add the splash before the notice:
    - the logo, as `<img src="/discosticker-logo.svg" alt="">`;
    - `<h1>Your pics, sticker-sized.</h1>`;
    - the line "Upload a pic and get a link that shows up sticker-sized in Discord.".
  - Remove `<h1>DiscoSticker.cafe</h1>`.
  - Otherwise, add only `className` attributes and wrapper elements. Add `className` as ` className="…"` (one space before it) to existing tags, and never change an existing tag's name or other attributes.
  - Don't change state, effects, handlers, conditions, imports or any existing string, and don't reflow existing JSX text.
  - Sign in stays `<a href="/oauth2/authorization/discord">`, and the file input stays native.

  Verify:
  - `cd frontend && npm run typecheck && npm run build` exits 0.
  - The existing frontend greps print nothing, nothing and `1`.
  - The fixed-string check prints `1` at the start of all 17 lines.
  - The line-survival check prints nothing.
  - `grep -cF 'Your pics, sticker-sized.' frontend/app/page.tsx` prints `1`.
  - `grep -cF 'Upload a pic and get a link that shows up sticker-sized in Discord.' frontend/app/page.tsx` prints `1`.
  - `grep -qF 'Upload a pic and get a link that shows up sticker-sized in Discord.' frontend/out/index.html && echo splash-in-html` prints `splash-in-html`.
  - The logo-reference greps print `0` and `2`: `grep -rc '<svg' frontend/app` totals 0, and `grep -rho 'src="/discosticker-logo.svg"' frontend/app | wc -l` prints `2`.
  - `git diff --exit-code master -- frontend/lib frontend/package.json frontend/package-lock.json` exits 0.

  Notes (2026-10-11, Claude):
  - The splash is a `<section className="splash">` holding the logo `<img>` and a `<div>` with the `<h1>` and `<p className="splash-line">`. It sits first in `<main className="page">`, before the notice.
  - `<h1>DiscoSticker.cafe</h1>` is removed.
  - Every other change is a ` className="…"` added to an existing tag:
    - `status` on the loading, unavailable and limits-failure paragraphs;
    - `session` on the sign-in and signed-in rows;
    - `button button-filled` on Sign in, Upload and Copy;
    - `button button-outlined` on Sign out;
    - `alert` on both `role="alert"` paragraphs;
    - `card` on the upload `<section>`;
    - `hint` on the two hint paragraphs;
    - `upload-form`, `result`, `result-label` and `result-link`.
  - On the multi-line file `<input>`, `className="file-input"` is its own attribute line, like the tag's other attributes.
  - `git diff` shows no change to state, effects, handlers, conditions, imports or strings.
  - Checks:
    - the type check and build exit 0;
    - the existing greps printed nothing, nothing and `1`;
    - the fixed-string check printed `1` on all 17 lines;
    - the line-survival check printed nothing;
    - the splash greps printed `1`, `1` and `splash-in-html`;
    - the logo-reference greps printed `0` and `2`;
    - the `git diff --exit-code` on `lib/` and the package files exited 0.

## 3. Checks on the finished tree

- [x] 3.1 Run every command in CLAUDE.md's Commands section (~8 min).
  - Start `./mvnw -B verify` first, in the background; the api is unchanged.
  - Then run the build-output checks below.
  - After CLAUDE.md's `down`, start the stack again (`docker compose --env-file secrets.env up -d --wait`) for 3.2 and 3.3.
  - Then report 1.1–2.3 done (design D8).

  Verify:
  - Every CLAUDE.md command exits 0, and the health check prints `"status":"UP"`.
  - `grep -oE '(src|href)="https?://' frontend/out/index.html` prints nothing.
  - `grep -l -- '--ds-cream' frontend/out/_next/static/chunks/*.css` prints exactly one file, so the CSS greps here can't pass with nothing checked.
  - `grep -lE '@import|url\(https?:|@font-face' frontend/out/_next/static/chunks/*.css` prints nothing.
  - `grep -rnE 'next/font|fonts\.googleapis|fonts\.gstatic' frontend/app frontend/lib frontend/next.config.ts` prints nothing.
  - The existing frontend greps print nothing, nothing and `1`.
  - The line-survival check prints nothing.
  - The logo-reference greps print `0` and `2`: `grep -rc '<svg' frontend/app` totals 0, and `grep -rho 'src="/discosticker-logo.svg"' frontend/app | wc -l` prints `2`.
  - `sha256sum frontend/public/discosticker-logo.svg frontend/out/discosticker-logo.svg` prints `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4` on both lines.

  Notes (2026-10-11, Claude):
  - CLAUDE.md commands, all exit 0:
    - `./mvnw -B verify`: 54 unit and 87 integration tests, 0 failures, `BUILD SUCCESS`. The one `ERROR` log line comes from the integration test that exercises the storage-failure path.
    - frontend `npm ci && npm run typecheck && npm run build`;
    - `docker compose --env-file secrets.env.example config --quiet`;
    - `up --build -d --wait`, with every service healthy;
    - health `{"groups":["liveness","readiness"],"status":"UP"}`;
    - `down`.
  - The stack was then started again (`up -d --wait`, all healthy) for 3.2 and 3.3. `down` kept the volumes, so existing stickers remain.
  - Build-output checks:
    - no `http(s)` `src` or `href` in `index.html`;
    - `--ds-cream` found in exactly one stylesheet, `frontend/out/_next/static/chunks/0bw-is530oml_.css`, which has no `@import`, `url(http` or `@font-face`;
    - no `next/font` or Google Fonts references.
  - The existing greps printed nothing, nothing and `1`; the line-survival check printed nothing; the logo-reference greps printed `0` and `2`.
  - `frontend/public` and `frontend/out` both hash to `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4`.
  - Re-run (2026-10-11, Claude), after the cuts were undone:
    - every CLAUDE.md command exits 0: `./mvnw -B verify` (54 unit and 87 integration tests, 0 failures, `BUILD SUCCESS`), frontend `npm ci && npm run typecheck && npm run build`, the compose config check, `up --build -d --wait` (all healthy), health `"status":"UP"`, and `down`;
    - the stack was started again for 3.2 and 3.3;
    - build-output checks are unchanged in result: `--ds-cream` is in exactly one stylesheet, now `frontend/out/_next/static/chunks/0_sl7nxl5t72g.css`;
    - the line-survival check printed nothing, the logo references printed `0` and `2`, and both logo hashes are `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4`.

- [x] 3.2 Check what the running local stack serves (~3 min).

  Verify:
  - `for p in /discosticker-logo.svg /favicon.ico; do curl -sS -o /dev/null -w '%{url} %{http_code} %{content_type}\n' "http://localhost:8080$p"; done` prints `http://localhost:8080/discosticker-logo.svg 200 image/svg+xml` and `http://localhost:8080/favicon.ico 200 image/x-icon`.
  - `curl -sS http://localhost:8080/discosticker-logo.svg | sha256sum` prints `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4  -`.
  - `curl -sS http://localhost:8080/ | grep -oE '<link rel="icon"[^>]*>'` prints the same lines as in 2.2.
  - `curl -sS http://localhost:8080/ | grep -qF 'Upload a pic and get a link that shows up sticker-sized in Discord.' && echo splash` prints `splash`.
  - `curl -sS -D - -o /dev/null http://localhost:8080/discosticker-logo.svg | grep -ciE '^(strict-transport-security|x-content-type-options|referrer-policy|x-frame-options):'` prints `0`. The local server has no security headers; 4.2 checks them in production.

  Notes (2026-10-11, Claude):
  - The first Verify line originally used `-w "$p …"`. Git Bash rewrote that `/`-leading argument into a Windows path, and `MSYS_NO_PATHCONV=1` then broke `-o /dev/null` (curl exit 23). I replaced it with the `%{url}` form from add-sticker-upload 10.1, which printed `http://localhost:8080/discosticker-logo.svg 200 image/svg+xml` and `http://localhost:8080/favicon.ico 200 image/x-icon`. The status codes and types were right in every run.
  - The served logo hashed to `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4`.
  - Re-run (2026-10-11, Claude) on the rebuilt stack after the cuts were undone:
    - `http://localhost:8080/discosticker-logo.svg 200 image/svg+xml` and `http://localhost:8080/favicon.ico 200 image/x-icon`;
    - the served logo hash is `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4`;
    - the icon grep printed `<link rel="icon" href="/favicon.ico?favicon.117ezoe8m31dk.ico" sizes="48x48" type="image/x-icon"/>`, then `<link rel="icon" href="/discosticker-logo.svg" type="image/svg+xml" sizes="any"/>`;
    - the served stylesheet `/_next/static/chunks/0_sl7nxl5t72g.css` contains `border-left:4px solid var(--ds-success)`.
  - The splash check printed `splash`, and the local header check printed `0`.

- [x] 3.3 [user] Look at the page at http://localhost:8080 in Chrome or Edge, signed in with the dev application (~5 min). There are no commands.

  Verify:
  1. Signed out and signed in, the splash reads exactly "Your pics, sticker-sized." and, below it, exactly "Upload a pic and get a link that shows up sticker-sized in Discord.".
  2. The logo shows in the header, in the splash and in the browser tab.
  3. Signed out, "Sign in with Discord" is a filled button and signs in as before. Signed in, "Signed in as <name>." shows with Sign out, which signs out as before.
  4. The form is on a white card. Its types and numbers match http://localhost:8080/api/stickers/limits opened in the same browser, and "Animated images become a still image." shows once.
  5. These messages appear in the error style, with the same wording as before:
     - a `.txt` file renamed to `.png` shows "Only JPEG and PNG images can be uploaded.";
     - a file over 10 MB shows "That file is over 10 MB.".
  6. An upload shows its link in the result panel, and Copy puts it on the clipboard.
  7. Tabbing through the page shows a visible focus ring on every control.
  8. With DevTools' Network tab open and the cache disabled, a reload requests only from localhost:8080.
  9. At 360 px wide (DevTools device toolbar), there's no horizontal scroll and the link input fits.
  10. With Windows set to dark mode, the page stays light, including the file picker.

  Notes (2026-10-11, reported by the user):
  - Passed on all seven checks, including the logo in the tab, phone width (375 px), focus rings, and unchanged error wording.
  - The report names seven checks and a 375 px width, while this list has ten items and says 360 px. Recorded as reported.

## 4. Production

- [ ] 4.1 [user] Commit, push and deploy `feature/theme`.
  1. **Windows (PowerShell 5.1):**
     - run `git status -sb` and `git log --oneline -3` first, and check nothing is unexpected;
     - commit the change;
     - `git push -u origin feature/theme`;
     - `git ls-remote origin refs/heads/feature/theme` for GitHub's hash.
  2. **Server (bash):** run `docs/deploy.md` "Redeploy" with `<branch>` set to `feature/theme`, then `sha256sum frontend/public/discosticker-logo.svg`.

  Verify:
  - **Windows:** `cmd /c "git cat-file blob HEAD:frontend/public/discosticker-logo.svg > %TEMP%\ds-blob.svg"; (Get-FileHash "$env:TEMP\ds-blob.svg").Hash` prints `D5D773755C71A9136D56E9F173D4E32BFCAAA3E07E329CD857E1E419E02E25B4`. cmd's redirection keeps the bytes as they are; PowerShell 5.1's `>` would re-encode them.
  - **Server:**
    - `git rev-parse HEAD` equals the `ls-remote` hash.
    - `sha256sum frontend/public/discosticker-logo.svg` prints `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4  frontend/public/discosticker-logo.svg`.
    - `$Prod up --build -d --wait --wait-timeout 900; echo "exit=$?"` prints `exit=0`.
    - After `$Prod up -d --force-recreate --no-deps --wait gateway`, `$Prod exec gateway ls /etc/nginx/conf.d` lists `discosticker.conf`.
    - `$Prod exec gateway nginx -t -q; echo "nginx-t=$?"` prints `nginx-t=0`.

- [ ] 4.2 [user] Production checks (Windows PowerShell 5.1).
  1. Run the production header check (Conventions).
  2. `foreach ($p in '/discosticker-logo.svg','/favicon.ico') { curl.exe -sS -o NUL -w "$p %{http_code} %{content_type}\n" "https://discosticker.cafe$p" }`
  3. `curl.exe -sS -o "$env:TEMP\ds-logo.svg" https://discosticker.cafe/discosticker-logo.svg; (Get-FileHash "$env:TEMP\ds-logo.svg").Hash`
  4. `$html = curl.exe -sS https://discosticker.cafe/ | Out-String; [regex]::Matches($html, '<link rel="icon"[^>]*>') | ForEach-Object Value; $html.Contains('Your pics, sticker-sized.'); $html.Contains('Upload a pic and get a link that shows up sticker-sized in Discord.')`
  5. With an existing sticker's ID in place of `<id>`: `curl.exe -sSI "https://discosticker.cafe/s/<id>.png" | Select-String -Pattern '^(HTTP|content-type|content-length|cache-control|set-cookie|x-content-type-options):?'`
  6. In a browser at https://discosticker.cafe: look at the tab icon, sign in, upload one image, and open its link.

  Verify:
  1. Prints `1` on all 25 lines: the four headers once each on `/`, `/api/health`, `/nope/`, `/discosticker-logo.svg` and `/favicon.ico`, and HSTS exactly `max-age=300`.
  2. Prints `/discosticker-logo.svg 200 image/svg+xml` and `/favicon.ico 200 image/x-icon`.
  3. Prints `D5D773755C71A9136D56E9F173D4E32BFCAAA3E07E329CD857E1E419E02E25B4`.
  4. Prints the icon links as in 2.2 (one line if the icon was cut), then `True` and `True`.
  5. Shows:
     - `HTTP/2 200`;
     - `content-type: image/png`;
     - a `content-length`;
     - `cache-control: public, max-age=86400`;
     - `x-content-type-options: nosniff` exactly once;
     - no `set-cookie` line.
  6. The tab shows the logo (in Chrome, Edge or Firefox), sign-in and the upload work, and the link opens the PNG.

## Workflow follow-up

- Open the PR from `feature/theme` to `master`. After it merges, switch the server to `master` per `docs/deploy.md` "Redeploy". This is routine, not a task: compare with GitHub's hash, rebuild, and recreate the gateway.
- Archive with `/opsx:archive` after review.
