# DiscoSticker.cafe

Turn any image into a Discord sticker.

Normally you can only send the stickers a server has added. DiscoSticker.cafe gets around that: sign in with Discord, upload an image, and you get a short link to a downscaled copy. Paste the link into any chat, and Discord's embed shows it at roughly the size of a real sticker, so any image can be used like one.

## How it works

1. Sign in with your Discord account.
2. Upload a PNG or JPEG.
3. The server checks the file, downscales it to sticker size, and re-encodes it as a fresh PNG.
4. You get an unguessable link to the result. Paste it into Discord.

## Built with security in mind

The upload pipeline is designed to treat every file as untrusted:

- File types are detected from the file's contents, not its name.
- Sizes are limited at the gateway and again in the app.
- Every image is re-encoded before it is served, so the original upload is never stored or served.
- Uploads are rate-limited per user, and logins and uploads are audit-logged.
- Sticker links are 128-bit random IDs that can't be guessed or enumerated.

## Status

Work in progress. The walking skeleton (gateway, api, database, storage and the static frontend) is in place; Discord login and uploads are next.

**Stack:** Next.js (static export), Spring Boot + Spring Security, Postgres, S3-compatible object storage, nginx and docker compose.
