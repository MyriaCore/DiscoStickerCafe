# Spec Delta

## Purpose

Gives the site its approved look: the "Lavender café" palette, the original logo, the home-page splash and Material-style controls. The theme stays readable for everyone, loads nothing from other origins, and changes only appearance, never behavior.

## ADDED Requirements

### Requirement: Approved palette
The site's styles SHALL use only the approved palette:
- cream `#FFF7EE` for the page;
- white `#FFFFFF` for surfaces;
- plum `#3B2147` and plum-muted `#6A5277` for text;
- lavender-strong `#6E55C4` for primary controls and links;
- lavender `#C9B8F0` and peach `#FFCBAE` for fills;
- error `#A3324A` and success `#2F6B4F`;
- lavender-mid `#9D86DE`, peach-strong `#F2906B` and blush `#FFB899`, for decoration only.

Shadows SHALL use only plum with transparency.

#### Scenario: Palette on the home page
- **WHEN** anyone opens the home page
- **THEN** the page background is cream, cards are white, and text is plum or plum-muted
- **AND** no text, background or border uses a color outside the palette

### Requirement: Light appearance only
The site SHALL show the same light theme, including its native form controls, whatever color scheme the browser or operating system prefers.

#### Scenario: Visitor prefers dark mode
- **WHEN** a visitor whose operating system prefers a dark color scheme opens the home page
- **THEN** the page shows the cream background, plum text and light form controls

### Requirement: Readable color contrast
Every text color SHALL have a WCAG 2 contrast ratio of at least 4.5:1 against its background, including on disabled controls. Every focus indicator, and every boundary that identifies a control, SHALL have at least 3:1 against its adjacent colors. The decorative colors (lavender-mid, peach-strong, blush) SHALL NOT be used for text.

#### Scenario: Text contrast
- **WHEN** any text is shown, including headings, hints, button labels, messages and the sticker link
- **THEN** its contrast ratio against its background is at least 4.5:1

#### Scenario: Focus indicator contrast
- **WHEN** an interactive control has keyboard focus
- **THEN** it shows a focus indicator with a contrast ratio of at least 3:1 against the colors next to it

#### Scenario: Input boundary contrast
- **WHEN** a text input is shown
- **THEN** its border has a contrast ratio of at least 3:1 against its background

### Requirement: Logo file
The site SHALL serve the PM-approved logo from its own origin at `/discosticker-logo.svg`, byte-identical to the approved file (SHA-256 `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4`). The file SHALL contain no script, no event-handler attributes, no `javascript:` URLs, no `<foreignObject>`, no embedded images and no references to other resources.

#### Scenario: Logo served unchanged
- **WHEN** `/discosticker-logo.svg` is requested
- **THEN** the response is 200 with `Content-Type: image/svg+xml`
- **AND** the body's SHA-256 is `d5d773755c71a9136d56e9f173d4e32bfcaaa3e07e329cd857e1e419e02e25b4`

#### Scenario: Logo carries the security headers in production
- **WHEN** `https://discosticker.cafe/discosticker-logo.svg` is requested
- **THEN** the response carries each of these exactly once:
  - `Strict-Transport-Security: max-age=300`;
  - `X-Content-Type-Options: nosniff`;
  - `Referrer-Policy: strict-origin-when-cross-origin`;
  - `X-Frame-Options: DENY`.

### Requirement: Logo shown only as an image
The home page SHALL show the logo in the header and in the splash. Each SHALL be an image reference to `/discosticker-logo.svg`. The logo's SVG markup SHALL NOT be inlined into the page by any means.

#### Scenario: Logo in the header and the splash
- **WHEN** anyone opens the home page, in any sign-in state
- **THEN** the header shows the logo next to "DiscoSticker.cafe", and the splash shows the logo

#### Scenario: Logo never inlined
- **WHEN** the page's source is inspected
- **THEN** it contains no inline `<svg>` markup
- **AND** the logo appears only as image references to `/discosticker-logo.svg`

### Requirement: Browser icon
The home page SHALL declare the logo as its browser icon, with type `image/svg+xml`. It SHALL keep the existing `/favicon.ico` as the fallback for browsers that don't use SVG icons.

#### Scenario: Icon links
- **WHEN** the home page's HTML is loaded
- **THEN** it declares exactly two icons: `/favicon.ico` with type `image/x-icon` first, then `/discosticker-logo.svg` with type `image/svg+xml`

#### Scenario: Icon in a browser tab
- **WHEN** the home page is open in a current Chrome, Edge or Firefox
- **THEN** the tab shows the logo

#### Scenario: Fallback icon served with the security headers
- **WHEN** `https://discosticker.cafe/favicon.ico` is requested
- **THEN** the response is 200 with `Content-Type: image/x-icon`, and carries each of the four security headers exactly once

### Requirement: Home-page splash
The home page SHALL show a splash section in every sign-in state, above the sign-in controls. Its main heading SHALL read exactly "Your pics, sticker-sized.", followed by the line exactly "Upload a pic and get a link that shows up sticker-sized in Discord." The splash SHALL name no file types and no limits.

#### Scenario: Signed-out visitor
- **WHEN** a signed-out visitor opens the home page
- **THEN** the splash shows the heading "Your pics, sticker-sized." and the line "Upload a pic and get a link that shows up sticker-sized in Discord.", exactly, above the sign-in link

#### Scenario: Signed-in user
- **WHEN** a signed-in user opens the home page
- **THEN** the same splash, with the same text, is shown above the upload form

### Requirement: Material-style controls
The home page SHALL show:
- a header bar;
- the upload form on a white card;
- the sticker link in a distinct result panel;
- each primary action (Sign in with Discord, Upload, Copy) as a rounded, filled lavender-strong button with white text;
- a visible focus ring on every interactive control.

#### Scenario: Signed-in layout
- **WHEN** a signed-in user opens the home page
- **THEN** the upload form is on a white card on the cream page
- **AND** Upload is a filled lavender-strong button with white text

#### Scenario: Keyboard focus
- **WHEN** a user moves through the page with the Tab key
- **THEN** each focused control shows a visible focus ring

### Requirement: No third-party requests
The frontend SHALL load every resource from the site's own origin. Neither the build nor the page SHALL fetch fonts, stylesheets, scripts or images from another origin. Text SHALL use the platform's system fonts.

#### Scenario: Page load
- **WHEN** the home page loads in a browser
- **THEN** every request goes to the site's own origin

#### Scenario: Build
- **WHEN** the frontend is built
- **THEN** no font or stylesheet is downloaded, and the static export references no other origin

### Requirement: Appearance-only change
The theme SHALL NOT change behavior. Each of these SHALL behave and read as before:
- sign-in and sign-out;
- the upload form's types and numbers from the limits endpoint;
- the browser-side checks;
- every fixed message;
- "Animated images become a still image.".

The theme SHALL NOT change any server response, including the gateway's security headers and sticker responses.

#### Scenario: Form text from the limits endpoint
- **WHEN** a signed-in user opens the home page with the default settings
- **THEN** the form states JPEG and PNG, 10 MB and 20 uploads per hour, as returned by `GET /api/stickers/limits`
- **AND** "Animated images become a still image." appears exactly once

#### Scenario: Refusal messages unchanged
- **WHEN** an upload is refused, by the browser-side check or by the api
- **THEN** the message shown is the same fixed text for that reason as before the theme, shown in the error style

#### Scenario: Sticker link result
- **WHEN** an upload is accepted
- **THEN** the sticker link is shown in the result panel with a Copy button that copies it

#### Scenario: Sign-in and sign-out unchanged
- **WHEN** a signed-out visitor chooses "Sign in with Discord", or a signed-in user chooses "Sign out"
- **THEN** the browser navigates to `/oauth2/authorization/discord`, or sends `POST /api/logout` with the `X-XSRF-TOKEN` header, as before

#### Scenario: Security headers unchanged
- **WHEN** `/`, `/api/health` or `/nope/` is requested over HTTPS in production
- **THEN** each of the four security headers appears exactly once, and HSTS is exactly `max-age=300`

#### Scenario: Sticker responses unchanged
- **WHEN** an existing sticker's `/s/<id>.png` is requested in production after the theme is deployed
- **THEN** the response is 200 with `Content-Type: image/png` and `Cache-Control: public, max-age=86400`
- **AND** it carries `X-Content-Type-Options: nosniff` exactly once, and no `Set-Cookie`
