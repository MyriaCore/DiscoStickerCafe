# Discord paste test

Test images for choosing the standard sticker size (`app.upload.sticker-size`), from the `add-sticker-upload` change (task 1.2, design D16). Discord's docs don't say what size a pasted image link is drawn at, so this test finds out.

Each image has its size printed inside and a 1 px border. All are opaque except `160-transparent.png`.

## URLs

Paste each one as its own message. They work once `feature/upload` is pushed to GitHub.

https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/32.png
https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/64.png
https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/128.png
https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/160.png
https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/240.png
https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/320.png
https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/480.png
https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/320x160.png
https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/160x320.png
https://raw.githubusercontent.com/MyriaCore/DiscoStickerCafe/feature/upload/docs/paste-test/160-transparent.png

## Procedure

1. Push `feature/upload` (PowerShell): `git push -u origin feature/upload`.
2. In a private Discord channel, post a real server sticker.
3. Paste each URL above as its own message. Do it on desktop with Discord's zoom at 100%, and again on a phone.
4. For each image, note:
   - whether it looks smaller than, the same size as, or larger than the sticker;
   - for 32 and 64, whether Discord draws them at their natural size or enlarges them;
   - for the two rectangles, how they fit (shrunk, cropped or shown whole).
5. Record the results in the Notes under task 1.2 in `openspec/changes/add-sticker-upload/tasks.md`. The square that matches the sticker is the candidate for S, which the PM session approves (task 1.3).
