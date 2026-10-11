package cafe.discosticker.api.stickers.image;

import java.io.ByteArrayInputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * The only way the pipeline reaches ImageIO. It picks the JDK's own reader for exactly the detected
 * type (never {@code ImageIO.read}, which would accept any registered format) and reads from
 * memory, never through ImageIO's temp-file cache.
 */
final class ImageCodecs {

	private static final String JDK_PLUGINS = "com.sun.imageio.plugins.";

	private ImageCodecs() {
	}

	static ImageReader reader(ImageType type) {
		return jdkOwn(ImageIO.getImageReadersByFormatName(type.formatName()), type.formatName());
	}

	static ImageWriter pngWriter() {
		return jdkOwn(ImageIO.getImageWritersByFormatName("png"), "png");
	}

	/** The upload's bytes as an in-memory stream; nothing is copied to disk. */
	static ImageInputStream input(byte[] bytes) {
		return new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes));
	}

	private static <T> T jdkOwn(Iterator<T> candidates, String format) {
		while (candidates.hasNext()) {
			T candidate = candidates.next();
			if (candidate.getClass().getName().startsWith(JDK_PLUGINS)) {
				return candidate;
			}
		}
		throw new IllegalStateException("No JDK ImageIO plugin for " + format);
	}

}
