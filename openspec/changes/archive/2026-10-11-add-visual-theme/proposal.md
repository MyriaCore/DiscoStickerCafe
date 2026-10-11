# Proposal

## Why

Upload is live (PR #6), and the second test round starts at 6 pm (7 pm at the latest) whether or not the theme is live. The PM approved a pastel "Lavender café" theme brief with an original logo and a home-page splash, so testers land on a site that looks finished. It is time-boxed to about 1.5 hours, including the gateway redeploy, and is the first work to cut (decisions 2026-10-11).

## What Changes

- **Palette:** the brief's 12 color tokens, applied to the home page, the upload form and the sticker-link result in a Material style:
  - a header bar;
  - white cards on a cream page;
  - rounded filled and outlined buttons;
  - outlined inputs;
  - visible focus rings;
  - the system font stack.
- **Logo:** the PM-approved `discosticker-logo.svg` is committed byte-identical in `frontend/public/`. It's shown in the header and the splash with `<img>`, and declared as the browser icon next to the existing `favicon.ico`.
- **Splash:** a section on the home page in every sign-in state, reading:
  - headline: "Your pics, sticker-sized." (it becomes the page's `<h1>`);
  - line: "Upload a pic and get a link that shows up sticker-sized in Discord."

  The line is the PM's revision of the brief's line and names no file types. "DiscoSticker.cafe" stays in the page title and moves into the header.
- **Light only:** the default Next.js dark-mode styles are removed, so the page is light whatever the operating system prefers.
- **Contrast:** every text and control color pairing meets WCAG AA. design.md records each ratio.

## Out of scope

- Animations, dark mode, and extra pages.
- **Behavior changes.** These all keep their behavior and wording:
  - sign-in and sign-out;
  - the upload form's types and numbers, still from `GET /api/stickers/limits`;
  - the browser-side checks;
  - the reason-code messages, and "Animated images become a still image.".

  `lib/session.ts`, `lib/stickers.ts` and `lib/cookies.ts` are unchanged.
- A component library (MUI or similar), web fonts, CSS frameworks, and any new npm dependency.
- A Discord logo or mascot.
- An Apple touch icon, PNG icon sizes, and a web app manifest.
- Changes to the api, the gateway configuration, compose, secrets or `docs/deploy.md`.
- Editing `CLAUDE.md`, `openspec/config.yaml` or `docs/decisions.md`. Decision candidates are listed in design.md for the PM session.

## Capabilities

### New Capabilities

- `visual-theme`: the site's palette and light-only appearance; color contrast; the logo file, where it's shown and the browser icon; the home-page splash; Material-style controls; loading nothing from other origins; and the rule that the theme changes appearance only.

### Modified Capabilities

None. The sticker-upload "Upload form" requirement stays as written: its rule that no type or number is written into the page still holds, because the splash line names neither.

## Impact

- **frontend:**
  - `public/discosticker-logo.svg` is new, and is the first file in `public/`;
  - `app/globals.css` is rewritten;
  - `app/layout.tsx` gets the icon metadata and the header;
  - `app/page.tsx` gets the splash and class names.

  `lib/` is unchanged. There are no new dependencies, and `package.json` and `package-lock.json` are unchanged.
- **gateway:** no configuration change. The frontend is built into the gateway image, so the routine Redeploy rebuilds and recreates the gateway.
- **api, compose, secrets:** unchanged.
- **Production:** redeploy from `feature/theme`.

## Security impact

- **The project's security constraints are unaffected:** uploads, rate limits, audit records, public IDs, no CORS, login checks, bans and secrets. No api, gateway or session code changes.
- **A new static file on our origin: an SVG.**
  - It was scanned and contains none of these: script, event-handler attributes, `javascript:` URLs, `<foreignObject>`, `<image>`, external references, `@import` or `url()`.
  - It is committed byte-identical, and its SHA-256 is checked at every step from the download to production.
  - The page shows it only through `<img>`, where an SVG can't run script or load anything. It is never inlined: there is no `<svg>` markup in the app and no `dangerouslySetInnerHTML`.
- **No third-party requests, at build or run time:** no web fonts, no CDNs, no `next/font/google`, and no new packages.
- **Headers unchanged:** the four security headers still come from nginx at the server level and reach the new static files. Responses under `/s/` are unchanged.
- **A future Content-Security-Policy:** the theme adds no inline styles and no runtime style injection, so it adds nothing that policy would have to allow.
