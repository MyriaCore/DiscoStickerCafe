package cafe.discosticker.api.stickers.image;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import cafe.discosticker.api.stickers.image.ImageRejectedException.Reason;

/**
 * What an image's header says, read without decoding any pixels (design D5).
 *
 * @param orientation the EXIF orientation (1–8) of a JPEG; 1 for PNG and when absent or malformed
 */
public record ImageHeader(ImageType type, int width, int height, int orientation) {

	private static final String JPEG_NATIVE_FORMAT = "javax_imageio_jpeg_image_1.0";

	private static final String APP1 = "225";

	private static final byte[] EXIF = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);

	/**
	 * Reads the header and applies the dimension caps. For a JPEG it also reads the frame's
	 * component count (4 means CMYK or YCCK) and the EXIF orientation, from marker segments only.
	 * @throws ImageRejectedException {@code DIMENSIONS_TOO_LARGE}, {@code UNSUPPORTED_COLOR} or
	 * {@code UNREADABLE}
	 */
	public static ImageHeader read(ImageType type, byte[] bytes, int maxSide, long maxPixels) {
		ImageReader reader = ImageCodecs.reader(type);
		try (ImageInputStream in = ImageCodecs.input(bytes)) {
			reader.setInput(in, true, false);
			int width = reader.getWidth(0);
			int height = reader.getHeight(0);
			if (width > maxSide || height > maxSide || (long) width * height > maxPixels) {
				throw new ImageRejectedException(Reason.DIMENSIONS_TOO_LARGE);
			}
			int orientation = 1;
			if (type == ImageType.JPEG) {
				IIOMetadataNode root = (IIOMetadataNode) reader.getImageMetadata(0).getAsTree(JPEG_NATIVE_FORMAT);
				int components = frameComponents(root);
				if (components != 1 && components != 3) {
					throw new ImageRejectedException(Reason.UNSUPPORTED_COLOR);
				}
				orientation = ExifOrientation.fromApp1(exifPayload(root));
			}
			return new ImageHeader(type, width, height, orientation);
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

	private static int frameComponents(IIOMetadataNode root) {
		NodeList frames = root.getElementsByTagName("sof");
		if (frames.getLength() != 1) {
			throw new ImageRejectedException(Reason.UNREADABLE);
		}
		return Integer.parseInt(((IIOMetadataNode) frames.item(0)).getAttribute("numFrameComponents"));
	}

	/** The payload of the first APP1 segment that holds EXIF, or null. */
	private static byte[] exifPayload(IIOMetadataNode root) {
		NodeList unknown = root.getElementsByTagName("unknown");
		for (int i = 0; i < unknown.getLength(); i++) {
			Node node = unknown.item(i);
			if (node instanceof IIOMetadataNode segment && APP1.equals(segment.getAttribute("MarkerTag"))
					&& segment.getUserObject() instanceof byte[] payload && payload.length >= EXIF.length
					&& Arrays.equals(payload, 0, EXIF.length, EXIF, 0, EXIF.length)) {
				return payload;
			}
		}
		return null;
	}

}
