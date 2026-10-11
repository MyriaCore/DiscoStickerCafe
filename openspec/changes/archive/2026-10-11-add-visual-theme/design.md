# Design

## Context

See proposal.md for why; the requirements are in `specs/visual-theme/spec.md`. Checked during explore on `feature/theme` at `b44da79` (= `origin/master`):

- **Frontend:**
  - `app/page.tsx` is one client component, `Home`, with four session states: `loading`, `signed-out`, `signed-in` and `unavailable`, plus a `role="alert"` notice.
  - The upload form and the sticker-link result render inside `StickerUpload`, which is shown only when signed in.
  - Every fixed message lives in `lib/session.ts` (`loginMessage`) and `lib/stickers.ts` (`precheck`, `uploadMessage`), except the page's own notices and labels in `page.tsx`.
- **`app/globals.css` is Next's scaffold.**
  - It has two `prefers-color-scheme: dark` blocks, with Arial as the font.
  - `app/layout.tsx` sets only `title` and `description`.
- **Favicon:**
  - `app/favicon.ico` (16/32/48 px, from the walking-skeleton commit `c12cf20`) is emitted as `<link rel="icon" href="/favicon.ico?favicon.<hash>.ico" sizes="48x48" type="image/x-icon"/>`.
  - There is no `public/` folder.
  - The current export references no other origin.
- **Next 16.4.0's icon handling:**
  - `app/favicon.ico` is always emitted first (`next/dist/lib/metadata/resolve-metadata-parallel.js:248`).
  - File-based `app/icon.*` icons are used only when `metadata.icons` is unset (`:318`).
- **Gateway:**
  - `nginx:1.30.5-alpine`'s `mime.types` maps `svg` to `image/svg+xml` and `ico` to `image/x-icon`.
  - In production, `gateway/prod/discosticker.conf` includes `security_headers.conf` at server level. `routes.conf`'s `location /` sets no headers, so static files inherit all four.
  - The local server has no security headers.
  - `.dockerignore` doesn't exclude `public/` or `.svg`, and the gateway image builds the frontend.
- **Logo file:**
  - The PM-approved logo's SHA-256 is `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4`.
  - Its line endings are LF only, and its one non-ASCII character is the UTF-8 `é` in its `aria-label`.
  - `.gitattributes` gives `.svg` files `text=auto eol=lf`, a no-op for this file.
- **No frontend test runner** (decision 2026-10-10). The frontend's checks are the type check, the build, greps, and looks in a browser.

## Goals / Non-Goals

**Goals:**
- The brief's look, applied within a 60-minute apply, leaving about 30 minutes for review and deploy.
- Logo bytes provably unchanged from the PM's file to the bytes production serves.
- Zero behavior change, made visible by checks rather than by review alone.

**Non-Goals:**
- Material's full spec: ripple, motion, typography scale and density.
- Any refactor of `page.tsx` beyond adding wrappers and class names.
- Styling pages other than the home page. There are none, apart from the static 404, which keeps its default.

## Decisions

### D1. Plain CSS with the brief's tokens, no component library

`app/globals.css` holds:
- the 12 tokens as custom properties on `:root`, named and valued exactly as in the brief;
- a few class names used by `layout.tsx` and `page.tsx`.

The Material style is built from:
- white cards with a soft, plum-tinted layered shadow;
- rounded filled buttons (white on lavender-strong);
- outlined buttons (lavender-strong text and border);
- outlined inputs with a plum-muted border;
- a `:focus-visible` ring: lavender-strong, 3 px, offset 2 px;
- an 8 px spacing grid;
- the system font stack.

The brief requires no font.

| | Plain CSS + tokens | MUI (`@mui/material`) |
|---|---|---|
| New dependencies | 0 | `@mui/material`, `@emotion/react`, `@emotion/styled`, `@mui/material-nextjs`, plus their dependencies |
| Added client JS | 0 | roughly 80–120 KB gzipped for Button, TextField, Card and Alert plus Emotion (estimate, not measured) |
| Runtime | none; one static stylesheet | Emotion injects `<style>` tags at runtime, which would need `'unsafe-inline'` under a future CSP (a static export can't use nonces) |
| Static-export fit | trivial | needs `AppRouterCacheProvider`; ripple and transitions must be turned off (animations are out of scope) |
| Apply time | about 35 of the 60 minutes | 60+ minutes; it also rewrites the form's JSX into MUI components, which risks wording changes |

*Alternatives:* MUI, as in the table; Material Web (`@material/web`), which brings Lit and web components and is in maintenance mode; CSS Modules, which isn't worth it for one page.

*Trade-off:* the result is "Material-style", not spec-complete Material.

### D2. The logo: one byte-identical file, shown only with `<img>`

- **Copy:** `cp` from `~/Downloads` to `frontend/public/discosticker-logo.svg`, with no editor and no formatter.
- **Hash checks:** the full SHA-256 is checked on:
  - the repo copy;
  - `frontend/out/`;
  - the committed blob;
  - the server checkout;
  - the local stack's response;
  - production's response.
- **Shown with `<img src="/discosticker-logo.svg">`** in the header (`layout.tsx`) and the splash (`page.tsx`).
  - Sizes are set in CSS, never as TSX attributes.
  - Both have `alt=""`: the header's logo sits next to the "DiscoSticker.cafe" wordmark, and the splash's sits next to the headline, so the logo adds no information for screen readers.
- **Not used:**
  - a CSS `background-image`, which is fine too but adds a `url()` to the stylesheet;
  - inlining the SVG, which is forbidden.
- **Lint:** there is no ESLint setup, so `@next/next/no-img-element` doesn't apply. `next/image` isn't used: the static export has no image optimization.

### D3. The browser icon: `metadata.icons` plus the existing `favicon.ico`

`layout.tsx` adds `icons: { icon: [{ url: "/discosticker-logo.svg", type: "image/svg+xml", sizes: "any" }] }`.

Next always emits `favicon.ico` first, with `sizes="48x48"`, then the SVG link. Chrome, Edge and Firefox pick the SVG. Browsers that don't take SVG icons use the `.ico`.

*Alternatives:*
- **`app/icon.svg`:** no code, but a second copy of the logo to hash. Next also drops it silently once anyone sets `metadata.icons`.
- **Generating PNG or ICO sizes:** this needs a rasterizer and a derived binary. It doesn't fit the time box.

*Trade-off:* Safari's SVG favicon support is unconfirmed (sources conflict). Safari may show the old scaffold icon, not the cup.

This is the first item to cut (see "Cut order").

### D4. Page structure

```
+------------------------------------------------+
| [logo] DiscoSticker.cafe   (header, layout.tsx)|
+------------------------------------------------+
|  [logo]  Your pics, sticker-sized.      <h1>   |
|          Upload a pic and get a link ...       |
|  [notice, role=alert]                (if any)  |
|  out: [ Sign in with Discord ]  (filled <a>)   |
|  in:  Signed in as X.  [ Sign out ] (outlined) |
|  +-- card: Make a sticker <h2> -------------+  |
|  | limits line / Animated images ...        |  |
|  | [Choose file]  [ Upload ]                |  |
|  | [error alert]                            |  |
|  | +-- peach: link [input......] [Copy] -+  |  |
+------------------------------------------------+
```

- **Header** (`layout.tsx`):
  - the logo and a "DiscoSticker.cafe" wordmark, which isn't a heading;
  - `<title>` and `description` are unchanged.
- **Splash** (`page.tsx`):
  - the same splash in every state, placed before the notice and the session controls;
  - its headline is the page's only `<h1>`;
  - the old `<h1>DiscoSticker.cafe</h1>` is removed, since the header carries the name;
  - "Make a sticker" stays an `<h2>`;
  - no spec pins the heading text.
- **Markup-only changes:** `className` attributes and wrapper elements. Not changed:
  - state, effects, handlers, conditions, `lib/` imports and strings;
  - existing JSX text lines, which aren't reflowed, so whitespace and wording stay identical;
  - Sign in, which stays `<a href="/oauth2/authorization/discord">` (a full navigation);
  - the file input, which stays native and is styled through `::file-selector-button`;
  - the read-only link input, whose `size` attribute stays; CSS caps its width at 100%.
- **Two-column splash:** logo beside the text on wide screens, stacked on narrow ones. The stacked layout is the fallback (see "Cut order").

*Trade-off:* on a phone, signed-in users scroll past the splash. A compact splash for signed-in users would need state-dependent markup.

### D5. Contrast, and the rules it sets

Computed with the WCAG 2.x relative-luminance formula. Every ratio the brief publishes matches.

**Text: needs 4.5:1**

| Text on background | Used for | Ratio |
|---|---|---|
| plum on cream | splash headline, page text | 13.24 |
| plum on surface | header wordmark, card text, input text | 14.05 |
| plum-muted on cream | splash line, "Checking sign-in…" | 6.41 |
| plum-muted on surface | limits line, "Animated images become a still image." | 6.80 |
| white on lavender-strong | filled buttons: Sign in with Discord, Upload, Copy | 5.59 |
| lavender-strong on cream | outlined Sign out (label) | 5.26 |
| lavender-strong on surface | outlined file-picker button (label) | 5.59 |
| plum on lavender | disabled Upload (inactive, so exempt, but still readable) | 7.76 |
| plum on peach | "Your sticker link:" in the result panel | 9.64 |
| error on cream | page notices (login codes, sign-out failure, session ended) | 6.36 |
| error on surface | upload messages inside the card | 6.75 |

**Non-text: needs 3:1**

| Element on background | Ratio |
|---|---|
| focus ring (lavender-strong, 2 px offset) on cream / surface / peach | 5.26 / 5.59 / 3.83 |
| filled button edge (lavender-strong) on cream / surface / peach | 5.26 / 5.59 / 3.83 |
| outlined button border (lavender-strong) on cream / surface | 5.26 / 5.59 |
| input border (plum-muted) on surface / peach | 6.80 / 4.67 |
| error alert's left border (error) on cream / surface | 6.36 / 6.75 |
| result panel accent border (success) on peach | 4.32 |

**Failing pairings, and the rule each sets**

| Pairing | Ratio | Rule |
|---|---|---|
| lavender-strong text on peach | 3.83 | No links or outlined buttons in the result panel; Copy is a filled button. |
| success text on peach | 4.32 | No green text in the result panel. |
| plum-muted on lavender | 3.76 | No muted text on lavender fills. |
| lavender-mid on cream / surface | 2.87 / 3.04 | Decorative only (dividers, the card's hairline). Input borders use plum-muted. |
| peach-strong on cream | 2.20 | Decorative only. |
| lavender on surface / cream | 1.81 / 1.71 | Fill only, never a control's only boundary. |
| peach on surface / cream | 1.46 / 1.37 | Fill only. |
| surface on cream | 1.06 | Card edges are decorative and carried by the shadow; cards aren't controls. |

**Rules:**
- **New pairings:** any pairing not in these tables gets its ratio computed and recorded in tasks.md notes before it's used. It must pass AA: 4.5:1 for text, 3:1 for non-text.
- **Hover:** changes only elevation (the shadow), never a color, so it adds no pairing.
- **Colors in CSS:** only through the `--ds-*` tokens, except shadows, which use plum with alpha.

### D6. Light only

Remove both `prefers-color-scheme: dark` blocks from the scaffold CSS, and set `color-scheme: light` on `:root`, so native inputs and the file-picker button render light even when the OS prefers dark.

*Why:* dark mode is out of scope. Keeping the scaffold's dark block would put plum text on near-black.

### D7. Splash copy

- **Headline:** "Your pics, sticker-sized." (from the brief).
- **Line:** "Upload a pic and get a link that shows up sticker-sized in Discord." (the PM's revision; it replaces the brief's line).

The line names no file types or limits. As a result:
- the sticker-upload rule that no type or number is written into the page still holds;
- next weekend's WebP change won't need to touch the splash.

The copy is fixed text in `page.tsx`, and checks compare it character for character.

### D8. Verification without a frontend test runner

There's no frontend test runner (decision 2026-10-10), so the "tests" are:
- **1.1 baseline:** the type check, the build and the existing greps, run before any edit, so a later failure is known to be new;
- **checks fixed before implementation** in tasks.md, each run as its task completes:
  - the existing greps from add-sticker-upload 9.1 (no literal limits, no `dangerouslySetInnerHTML`, the animated-image note exactly once);
  - a fixed-string loop over every existing UI string in `page.tsx`;
  - `git diff --exit-code` on `lib/`, `package.json` and `package-lock.json`;
  - the no-inline-`<svg>` and `<img>`-count greps;
  - the hash checks;
  - the build-output checks;
- **3.3:** a `[user]` look at the page in a browser;
- **4.2:** production checks.

CLAUDE.md requires every command in its Commands section to pass before a task is reported done. Claude therefore reports 1.1–2.3 done only after 3.1 has run them all on the finished tree. The api doesn't change, so `./mvnw -B verify` runs once, in the background, during 3.1.

### D9. Deploy

The routine Redeploy in `docs/deploy.md`, from `feature/theme`:
1. push, and take the commit hash from GitHub with `git ls-remote` on Windows;
2. on the server: fetch, switch, `--ff-only` pull, and compare the hash;
3. `up --build`, which rebuilds the gateway image with the new frontend (the api is cached);
4. recreate the gateway;
5. check `conf.d` and `nginx -t`.

No gateway configuration, secrets or compose changes. After the PR merges, switching the server to `master` is routine and not a task.

## Threats considered

This change doesn't touch auth, sessions, uploads or storage. These are the threats it does raise.

| # | Threat | Handling | Verified by |
|---|---|---|---|
| T1 | **Active content in an SVG on our origin.** An SVG opened directly runs as a document with our origin's privileges. Its script could make same-origin api calls with the visitor's session. | The file was scanned before commit, and the scan is re-run on the repo copy: no `<script>`, `on*=`, `javascript:`, `<foreignObject>`, `<image>`, `<style>`, `@import`, `url(` or `data:`, and every `href` is a `#fragment`. It's shown only via `<img>`, where an SVG runs no script and loads nothing. It's never inlined. nginx serves it as `image/svg+xml` with `nosniff` and `X-Frame-Options: DENY`. | 1.2; 2.3 and 3.1 (no `<svg>`, `<img>` count); 4.2 (headers) |
| T2 | **The logo altered between PM approval and production:** an editor re-save, line endings, a formatter, a later commit. | A byte copy, then the full SHA-256 checked on: the download, the repo copy, `frontend/out`, the blob git will commit, the committed blob, the server checkout, the local response and the production response. | 1.2; 2.2; 3.1; 3.2; 4.1; 4.2 |
| T3 | **Third-party requests:** font or CSS CDNs, `next/font/google`, or a component library's assets. They would leak visitors' IPs and referrers and widen the supply chain. | System fonts and no new dependencies (`package.json` and the lock file unchanged). The CSS has no `@import`, `url()` or `@font-face`. The built HTML references no other origin. The `[user]` Network-tab check confirms it. | 2.1; 2.3; 3.1; 3.3 |
| T4 | **A future CSP** (next weekend) breaking the theme, or forcing `'unsafe-inline'` for styles. | One static stylesheet, no `style` attributes, no runtime style injection, and a same-origin logo (`img-src 'self'` covers it). Next's own inline scripts are a separate question for that change. | 2.1–2.3 (review); D1 |
| T5 | **Behavior regressions from restyling:** changed wording, broken sign-in or sign-out, hard-coded limits, a duplicated animated note, reflowed JSX text. | `lib/` unchanged; `page.tsx` edits limited to class names and wrappers; the fixed-string loop; the existing greps; the local look; a sign-in and an upload in production. | 2.3; 3.1; 3.3; 4.2 |
| T6 | **Serving regressions:** new static files without security headers, or changes under `/s/`. | No gateway change; `location /` inherits the server-level headers. The production header check runs on five paths, plus the `/s/` header check. | 4.2 |

## Risks / Trade-offs

- **[Risk]** The scaffold's dark-mode CSS survives and flips the page for dark-mode users. → **Mitigation:** removed in 2.1, with a grep that it's gone, and a dark-mode look in 3.3.
- **[Risk]** Pixel sizes written in TSX trip the literal-limits grep (`\b20\b|\b160\b` over `.ts`/`.tsx`). → **Mitigation:** every size lives in CSS (D2), and the grep runs in 2.3 and 3.1.
- **[Risk]** A new pairing (hover, disabled, alert) misses AA. → **Mitigation:** the D5 tables, and the rule to compute and record any new pairing before use. Hover changes elevation only.
- **[Risk]** Safari shows the old favicon. → **Accepted:** see D3.
- **[Risk]** The splash pushes the form down on phones. → **Accepted:** see D4. The 360 px check in 3.3 catches overflow.
- **[Risk]** Local checks can't see the security headers, because the local server has none. → **Mitigation:** headers are checked in production only (4.2), on five paths.
- **[Risk]** The gateway build on the server runs out of memory (it builds the frontend). → **Mitigation:** the runbook's one-image-at-a-time fallback.
- **[Trade-off]** Material-style, not full Material (D1); one global stylesheet rather than modules (D1).

## Cut order

The PM set this order. Cut from the top while time runs short:

1. **The browser icon.** Drop `metadata.icons` and keep the current `favicon.ico`.
   - 2.2's icon check then expects one link (`/favicon.ico`).
   - 3.2's and 4.2's icon-link checks expect the same single link.
   - Before archive, remove the "Browser icon" requirement from `specs/visual-theme/spec.md`.
2. **Result-panel polish.** Use a plain panel: peach fill with plum text and the filled Copy button, no accent border.
3. **The two-column splash.** Stack the logo above the text at every width.
4. **Hover and elevation polish.** Keep one card shadow.
5. **The outlined button variant.** Sign out and the file-picker button fall back to a simpler style that still meets D5's ratios.

**Never cut:**
- the header and splash logos;
- the exact splash text;
- the palette, contrast and focus rings;
- the hash, grep and production checks.

**Recommendation (not a recorded decision):** if apply isn't green about 15 minutes past its 60-minute budget, don't deploy the theme. decisions.md:117 already says round 2 starts by 7 pm (planned for 6 pm) whether or not the theme is live, so an unthemed round 2 is acceptable. A half-finished theme deployed under pressure isn't.

## Migration Plan

- **Deploy:** D9 and tasks 4.1–4.2. Only the gateway image changes. There are no data or configuration migrations.
- **Rollback:** `docs/deploy.md` "Roll back":
  1. `git checkout <previous-commit>` (`b44da79`, or `master`'s head before the merge);
  2. `$Prod up --build -d --wait --wait-timeout 900`;
  3. `$Prod up -d --force-recreate --no-deps --wait gateway`.

  Nothing else needs undoing.

## Decision candidates for the PM session

One line each. This change does not add them to `docs/decisions.md`.

- The theme is plain CSS: the brief's 12 tokens as custom properties in `globals.css`, with no component library and no new dependency.
- The logo is committed byte-identical in `frontend/public/`, and its full SHA-256 is checked from the download to the bytes production serves.
- The logo is shown only with `<img>` and is never inlined; the app contains no `<svg>` markup.
- The browser icon is the SVG logo via `metadata.icons`, with the existing `favicon.ico` kept as the fallback; Safari may show the old icon.
- The splash shows in every sign-in state, above the session controls, and its headline is the page's only `<h1>`; "DiscoSticker.cafe" moves to the header and stays the page title.
- The splash line is "Upload a pic and get a link that shows up sticker-sized in Discord." (the PM's revision of the brief), so the page names no file types outside the form.
- The site is light-only: the scaffold's dark-mode CSS is removed, and `color-scheme` is `light`.
- Text pairings are at least 4.5:1, and focus rings and control boundaries at least 3:1. Lavender-mid, peach-strong and blush are decorative only, and there's no lavender-strong or success text on peach.
- Hover changes elevation only, never color.
- Cut order: the browser icon, result-panel polish, the two-column splash, hover and elevation polish, then the outlined buttons.
- Recommendation: if apply isn't green about 15 minutes past budget, the theme isn't deployed for round 2.
