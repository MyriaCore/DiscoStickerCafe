package cafe.discosticker.api.stickers.image;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Upload fixtures, generated in memory (design D17). Each method documents what it produces;
 * {@code TestImagesTest} checks those properties without using the pipeline under test.
 */
public final class TestImages {

	/** The JPEG metadata format whose {@code unknown} nodes carry APP1 (EXIF) segments. */
	static final String JPEG_NATIVE_FORMAT = "javax_imageio_jpeg_image_1.0";

	private TestImages() {
	}

	// ---- Plain images ----

	/** Opaque solid color, {@code TYPE_INT_RGB}. */
	public static BufferedImage solid(int width, int height, Color color) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(color);
		g.fillRect(0, 0, width, height);
		g.dispose();
		return image;
	}

	/** Opaque random noise from a fixed seed, {@code TYPE_INT_RGB}. */
	public static BufferedImage noise(int width, int height, long seed) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Random random = new Random(seed);
		int[] row = new int[width];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				row[x] = random.nextInt(0x1000000);
			}
			image.setRGB(0, y, width, 1, row, 0, width);
		}
		return image;
	}

	/** Alternating 1-pixel black and white columns, {@code TYPE_INT_RGB}. */
	public static BufferedImage stripes(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] row = new int[width];
		for (int x = 0; x < width; x++) {
			row[x] = (x % 2 == 0) ? 0x000000 : 0xFFFFFF;
		}
		for (int y = 0; y < height; y++) {
			image.setRGB(0, y, width, 1, row, 0, width);
		}
		return image;
	}

	/**
	 * An opaque red square, half the side, centered on a fully transparent background whose stored
	 * color is green ({@code 0x0000FF00}): shrinking without premultiplied alpha tints the edges green.
	 */
	public static BufferedImage redSquareOnTransparentGreen(int size) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		int[] pixels = new int[size * size];
		Arrays.fill(pixels, 0x0000FF00);
		int from = size / 4;
		int to = from + size / 2;
		for (int y = from; y < to; y++) {
			for (int x = from; x < to; x++) {
				pixels[y * size + x] = 0xFFFF0000;
			}
		}
		image.setRGB(0, 0, size, size, pixels, 0, size);
		return image;
	}

	/**
	 * An upright test image: blue, with a red block in the top-left quarter of the shorter side, so
	 * the block's corner shows which way the image was turned.
	 */
	public static BufferedImage uprightMarked(int width, int height) {
		BufferedImage image = solid(width, height, Color.BLUE);
		int block = Math.min(width, height) / 2;
		Graphics2D g = image.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, block, block);
		g.dispose();
		return image;
	}

	/**
	 * How a camera would store {@code upright} for an EXIF orientation of 1, 3, 6 or 8: turned the
	 * opposite way to the rotation a viewer applies. Uses Java 2D's own transforms, independent of the
	 * pipeline's pixel mapping.
	 */
	public static BufferedImage storedFor(BufferedImage upright, int orientation) {
		int w = upright.getWidth();
		int h = upright.getHeight();
		AffineTransform transform = new AffineTransform();
		BufferedImage stored;
		switch (orientation) {
			case 1 -> {
				return upright;
			}
			case 3 -> { // viewer turns 180°, so stored is turned 180°
				stored = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				transform.translate(w, h);
				transform.rotate(Math.PI);
			}
			case 6 -> { // viewer turns 90° clockwise, so stored is turned 90° counter-clockwise
				stored = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
				transform.translate(0, w);
				transform.rotate(-Math.PI / 2);
			}
			case 8 -> { // viewer turns 90° counter-clockwise, so stored is turned 90° clockwise
				stored = new BufferedImage(h, w, BufferedImage.TYPE_INT_RGB);
				transform.translate(h, 0);
				transform.rotate(Math.PI / 2);
			}
			default -> throw new IllegalArgumentException("orientation " + orientation);
		}
		Graphics2D g = stored.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(upright, transform, null);
		g.dispose();
		return stored;
	}

	// ---- Encoders ----

	public static byte[] png(BufferedImage image) {
		return write(image, "png");
	}

	/** A baseline JPEG at quality 0.95. */
	public static byte[] jpeg(BufferedImage image) {
		return jpeg(image, 0.95f, false, null);
	}

	/** A progressive JPEG at quality 0.9. */
	public static byte[] progressiveJpeg(BufferedImage image) {
		return jpeg(image, 0.9f, true, null);
	}

	/** A JPEG at the given quality, for timing runs. */
	public static byte[] jpeg(BufferedImage image, float quality) {
		return jpeg(image, quality, false, null);
	}

	/** A JPEG whose first marker segment after JFIF is the given APP1 payload. */
	public static byte[] jpegWithApp1(BufferedImage image, byte[] app1) {
		return jpeg(image, 0.95f, false, app1);
	}

	// ---- Not PNG or JPEG ----

	public static byte[] text() {
		return "This is a text file, not an image.\n".getBytes(StandardCharsets.US_ASCII);
	}

	public static byte[] gif() {
		return write(solid(8, 8, Color.RED), "gif");
	}

	public static byte[] bmp() {
		return write(solid(8, 8, Color.RED), "bmp");
	}

	/** A RIFF/WEBP header with a lossless (VP8L) chunk start; enough to be recognized as WebP. */
	public static byte[] webp() {
		ByteBuffer buffer = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(18);
		buffer.put("WEBPVP8L".getBytes(StandardCharsets.US_ASCII)).putInt(6);
		buffer.put(new byte[] { 0x2f, 0, 0, 0, 0, 0 });
		return buffer.array();
	}

	public static byte[] svg() {
		return """
				<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><script>alert(1)</script></svg>
				""".getBytes(StandardCharsets.UTF_8);
	}

	// ---- Hostile or damaged files ----

	/** The HTML appended to a valid PNG by {@link #pngWithAppendedHtml()}. */
	public static final byte[] APPENDED_HTML = "<html><script>alert('polyglot')</script></html>"
		.getBytes(StandardCharsets.US_ASCII);

	/** A valid 32 × 32 PNG followed by {@link #APPENDED_HTML}. */
	public static byte[] pngWithAppendedHtml() {
		return concat(png(noise(32, 32, 7)), APPENDED_HTML);
	}

	/** The first part of {@code bytes}, cut partway through the image data. */
	public static byte[] truncated(byte[] bytes) {
		return Arrays.copyOf(bytes, bytes.length * 6 / 10);
	}

	/** A 128 × 128 noise PNG cut partway through its IDAT data. */
	public static byte[] truncatedPng() {
		return truncated(png(noise(128, 128, 11)));
	}

	/** A 128 × 128 noise JPEG cut partway through its scan data. */
	public static byte[] truncatedJpeg() {
		return truncated(jpeg(noise(128, 128, 13)));
	}

	/**
	 * A PNG with only a signature, an IHDR declaring {@code width} × {@code height} RGBA pixels, and
	 * IEND: tens of bytes that would need gigabytes to decode.
	 */
	public static byte[] pngHeaderOnly(int width, int height) {
		ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height);
		ihdr.put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
		return concat(PNG_SIGNATURE, chunk("IHDR", ihdr.array()), chunk("IEND", new byte[0]));
	}

	/** {@link #pngHeaderOnly} at 50,000 × 50,000. */
	public static byte[] pngBomb() {
		return pngHeaderOnly(50_000, 50_000);
	}

	/** A 16 × 16 JPEG whose frame header is patched to declare 65,000 × 65,000 pixels. */
	public static byte[] jpegBomb() {
		byte[] jpeg = jpeg(solid(16, 16, Color.GRAY));
		Segment sof = segments(jpeg).stream().filter(Segment::isStartOfFrame).findFirst().orElseThrow();
		// SOF payload: precision (1), height (2), width (2), components (1), ...
		int height = sof.payloadOffset() + 1;
		ByteBuffer.wrap(jpeg).putShort(height, (short) 65_000).putShort(height + 2, (short) 65_000);
		return jpeg;
	}

	/** A palette PNG: entry 0 fully transparent, entry 1 opaque red; left half 0, right half 1. */
	public static byte[] palettePngWithTransparency() {
		byte[] reds = { (byte) 255, (byte) 255 };
		byte[] greens = { (byte) 255, 0 };
		byte[] blues = { (byte) 255, 0 };
		byte[] alphas = { 0, (byte) 255 };
		IndexColorModel model = new IndexColorModel(8, 2, reds, greens, blues, alphas);
		BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_BYTE_INDEXED, model);
		WritableRaster raster = image.getRaster();
		for (int y = 0; y < 32; y++) {
			for (int x = 32; x < 64; x++) {
				raster.setSample(x, y, 0, 1);
			}
		}
		return png(image);
	}

	/**
	 * A valid 16 × 16 PNG padded with a {@code tEXt} chunk before IEND to exactly {@code size}
	 * bytes. The padding comes after the image data, so decoders never read it.
	 */
	public static byte[] paddedPng(int size) {
		byte[] png = png(noise(16, 16, 17));
		int beforeIend = png.length - 12;
		int textLength = size - png.length - 12;
		byte[] text = new byte[textLength];
		Arrays.fill(text, (byte) 'x');
		byte[] keyword = "Comment\0".getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(keyword, 0, text, 0, keyword.length);
		return concat(Arrays.copyOf(png, beforeIend), chunk("tEXt", text),
				Arrays.copyOfRange(png, beforeIend, png.length));
	}

	/**
	 * A CMYK JPEG: a 4-band raster written by the JDK's JPEG writer, giving a frame header with four
	 * components, which the JDK's reader decodes as CMYK.
	 */
	public static byte[] cmykJpeg() {
		WritableRaster raster = Raster.createInterleavedRaster(java.awt.image.DataBuffer.TYPE_BYTE, 32, 32, 4, null);
		for (int y = 0; y < 32; y++) {
			for (int x = 0; x < 32; x++) {
				raster.setPixel(x, y, new int[] { 0, 255, 255, 0 });
			}
		}
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			writer.setOutput(out);
			writer.write(null, new IIOImage(raster, null, null), null);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	// ---- EXIF ----

	/** An EXIF APP1 payload whose IFD0 holds only Orientation (tag 0x0112) = {@code orientation}. */
	public static byte[] exifOrientation(int orientation, ByteOrder order) {
		ByteBuffer tiff = tiffHeader(order, 8 + 2 + 12 + 4);
		tiff.putShort((short) 1);
		putShortEntry(tiff, 0x0112, orientation);
		tiff.putInt(0);
		return exif(tiff);
	}

	/**
	 * An EXIF APP1 payload with Orientation 1 and a GPS IFD (tag 0x8825) holding GPSLatitudeRef
	 * "N", GPSLongitudeRef "E" and a GPS date stamp.
	 */
	public static byte[] exifWithGps() {
		int gpsOffset = 8 + 2 + 2 * 12 + 4;
		ByteBuffer tiff = tiffHeader(ByteOrder.LITTLE_ENDIAN, gpsOffset + 2 + 3 * 12 + 4 + 11);
		tiff.putShort((short) 2);
		putShortEntry(tiff, 0x0112, 1);
		tiff.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsOffset);
		tiff.putInt(0);
		int dateOffset = gpsOffset + 2 + 3 * 12 + 4;
		tiff.putShort((short) 3);
		tiff.putShort((short) 0x0001).putShort((short) 2).putInt(2).put(new byte[] { 'N', 0, 0, 0 });
		tiff.putShort((short) 0x0003).putShort((short) 2).putInt(2).put(new byte[] { 'E', 0, 0, 0 });
		tiff.putShort((short) 0x001D).putShort((short) 2).putInt(11).putInt(dateOffset);
		tiff.putInt(0);
		tiff.put("2026:10:11\0".getBytes(StandardCharsets.US_ASCII));
		return exif(tiff);
	}

	/** EXIF APP1 payloads that must all read as orientation 1. */
	public static List<byte[]> malformedExif() {
		List<byte[]> payloads = new ArrayList<>();
		payloads.add("Exif\0\0II".getBytes(StandardCharsets.ISO_8859_1)); // truncated TIFF header
		payloads.add("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>".getBytes(StandardCharsets.ISO_8859_1)); // XMP
		ByteBuffer outside = tiffHeader(ByteOrder.LITTLE_ENDIAN, 8);
		outside.putInt(4, 0x7FFFFF00); // IFD0 offset points far outside the segment
		payloads.add(exif(outside));
		ByteBuffer cut = tiffHeader(ByteOrder.BIG_ENDIAN, 8 + 2 + 6);
		cut.putShort((short) 5).put(new byte[] { 0x01, 0x12, 0, 3, 0, 0 }); // 5 entries claimed, half of one present
		payloads.add(exif(cut));
		ByteBuffer badMagic = tiffHeader(ByteOrder.LITTLE_ENDIAN, 8 + 2 + 12 + 4);
		badMagic.putShort(2, (short) 43);
		badMagic.putShort((short) 1);
		putShortEntry(badMagic, 0x0112, 6);
		badMagic.putInt(0);
		payloads.add(exif(badMagic));
		return payloads;
	}

	// ---- JPEG structure, for checks and patching ----

	/** A JPEG marker segment before the scan data: its marker byte and where its payload starts. */
	public record Segment(int marker, int payloadOffset, int payloadLength) {

		/** SOF0–SOF15, except DHT (C4), JPG (C8) and DAC (CC). */
		public boolean isStartOfFrame() {
			return this.marker >= 0xC0 && this.marker <= 0xCF && this.marker != 0xC4 && this.marker != 0xC8
					&& this.marker != 0xCC;
		}

	}

	/** The marker segments of a JPEG, from SOI up to and including SOS. */
	public static List<Segment> segments(byte[] jpeg) {
		List<Segment> segments = new ArrayList<>();
		int pos = 2;
		while (pos + 4 <= jpeg.length) {
			if ((jpeg[pos] & 0xFF) != 0xFF) {
				throw new IllegalArgumentException("not a marker at " + pos);
			}
			int marker = jpeg[pos + 1] & 0xFF;
			int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
			segments.add(new Segment(marker, pos + 4, length - 2));
			if (marker == 0xDA) {
				break;
			}
			pos += 2 + length;
		}
		return segments;
	}

	/** The payload of the first APP1 segment, or null. */
	public static byte[] app1(byte[] jpeg) {
		return segments(jpeg).stream()
			.filter((segment) -> segment.marker() == 0xE1)
			.findFirst()
			.map((segment) -> Arrays.copyOfRange(jpeg, segment.payloadOffset(),
					segment.payloadOffset() + segment.payloadLength()))
			.orElse(null);
	}

	/** The number of components in the JPEG's frame header. */
	public static int frameComponents(byte[] jpeg) {
		Segment sof = segments(jpeg).stream().filter(Segment::isStartOfFrame).findFirst().orElseThrow();
		return jpeg[sof.payloadOffset() + 5] & 0xFF;
	}

	/** The chunk types of a PNG, in order. */
	public static List<String> pngChunkTypes(byte[] png) {
		List<String> types = new ArrayList<>();
		int pos = 8;
		while (pos + 8 <= png.length) {
			int length = ByteBuffer.wrap(png, pos, 4).getInt();
			types.add(new String(png, pos + 4, 4, StandardCharsets.US_ASCII));
			pos += 12 + length;
		}
		return types;
	}

	public static boolean contains(byte[] haystack, byte[] needle) {
		outer: for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	// ---- Helpers ----

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static byte[] chunk(String type, byte[] data) {
		byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		return ByteBuffer.allocate(12 + data.length)
			.putInt(data.length)
			.put(typeBytes)
			.put(data)
			.putInt((int) crc.getValue())
			.array();
	}

	private static ByteBuffer tiffHeader(ByteOrder order, int size) {
		ByteBuffer tiff = ByteBuffer.allocate(size).order(order);
		tiff.put((order == ByteOrder.LITTLE_ENDIAN) ? new byte[] { 'I', 'I' } : new byte[] { 'M', 'M' });
		tiff.putShort((short) 42).putInt(8);
		return tiff;
	}

	/** A SHORT entry with count 1; the value sits in the first two bytes of the value field. */
	private static void putShortEntry(ByteBuffer tiff, int tag, int value) {
		tiff.putShort((short) tag).putShort((short) 3).putInt(1).putShort((short) value).putShort((short) 0);
	}

	private static byte[] exif(ByteBuffer tiff) {
		return concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), tiff.array());
	}

	private static byte[] jpeg(BufferedImage image, float quality, boolean progressive, byte[] app1) {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ImageWriteParam param = writer.getDefaultWriteParam();
		if (progressive) {
			param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
		}
		else {
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			IIOMetadata metadata = null;
			if (app1 != null) {
				metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), param);
				IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(JPEG_NATIVE_FORMAT);
				IIOMetadataNode markers = (IIOMetadataNode) root.getElementsByTagName("markerSequence").item(0);
				IIOMetadataNode unknown = new IIOMetadataNode("unknown");
				unknown.setAttribute("MarkerTag", "225");
				unknown.setUserObject(app1);
				markers.insertBefore(unknown, markers.getFirstChild());
				metadata.setFromTree(JPEG_NATIVE_FORMAT, root);
			}
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, metadata), param);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static byte[] write(BufferedImage image, String format) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			if (!ImageIO.write(image, format, bytes)) {
				throw new IllegalStateException("no writer for " + format);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			bytes.writeBytes(part);
		}
		return bytes.toByteArray();
	}

}
