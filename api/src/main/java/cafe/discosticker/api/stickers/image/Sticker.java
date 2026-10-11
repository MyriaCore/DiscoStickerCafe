package cafe.discosticker.api.stickers.image;

/** A re-encoded sticker: PNG bytes with no metadata, and the upright dimensions. */
public record Sticker(byte[] png, int width, int height) {
}
