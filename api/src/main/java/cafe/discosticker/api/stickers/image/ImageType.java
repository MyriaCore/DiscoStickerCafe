package cafe.discosticker.api.stickers.image;

import java.util.Arrays;
import java.util.Optional;

/**
 * The image types uploads accept, in the order the upload form offers them: JPEG first, because
 * Safari converts a HEIC photo to the first type a file picker accepts.
 */
public enum ImageType {

	JPEG("image/jpeg", "jpeg"),

	PNG("image/png", "png");

	private final String mediaType;

	private final String formatName;

	ImageType(String mediaType, String formatName) {
		this.mediaType = mediaType;
		this.formatName = formatName;
	}

	public String mediaType() {
		return this.mediaType;
	}

	/** The ImageIO format name whose reader decodes this type. */
	public String formatName() {
		return this.formatName;
	}

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] JPEG_START = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	/** The type from the file's leading bytes only; the name and declared Content-Type are never consulted. */
	public static Optional<ImageType> detect(byte[] bytes) {
		if (startsWith(bytes, PNG_SIGNATURE)) {
			return Optional.of(PNG);
		}
		if (startsWith(bytes, JPEG_START)) {
			return Optional.of(JPEG);
		}
		return Optional.empty();
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
	}

}
