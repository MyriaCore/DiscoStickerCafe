package cafe.discosticker.api.stickers.image;

import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import cafe.discosticker.api.stickers.image.ImageRejectedException.Reason;

/**
 * Decodes with the JDK's own reader for the detected type. Any decoder warning means unreadable:
 * libjpeg reports truncated or corrupt data only as a warning, and would otherwise return an image
 * with a gray area.
 */
final class ImageIoDecoder implements Decoder {

	@Override
	public BufferedImage decode(ImageType type, byte[] bytes) {
		ImageReader reader = ImageCodecs.reader(type);
		boolean[] warned = { false };
		reader.addIIOReadWarningListener((source, warning) -> warned[0] = true);
		try (ImageInputStream in = ImageCodecs.input(bytes)) {
			reader.setInput(in, true, true);
			BufferedImage image = reader.read(0);
			if (warned[0]) {
				throw new ImageRejectedException(Reason.UNREADABLE);
			}
			return image;
		}
		catch (ImageRejectedException ex) {
			throw ex;
		}
		catch (IOException | RuntimeException ex) {
			throw new ImageRejectedException(Reason.UNREADABLE, ex);
		}
		finally {
			reader.dispose();
		}
	}

}
