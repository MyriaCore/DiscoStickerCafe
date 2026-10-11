package cafe.discosticker.api.stickers.image;

import java.awt.image.BufferedImage;

/** Decodes an image's pixels. Called only after the header checks pass. */
@FunctionalInterface
interface Decoder {

	/**
	 * @throws ImageRejectedException with {@link ImageRejectedException.Reason#UNREADABLE} when the
	 * image is truncated or corrupt
	 */
	BufferedImage decode(ImageType type, byte[] bytes);

}
