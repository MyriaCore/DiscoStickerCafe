package cafe.discosticker.api.stickers.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

import org.junit.jupiter.api.Test;

/**
 * The generated fixtures have the properties the pipeline tests rely on. Checked with ImageIO and
 * raw bytes only, never with the pipeline itself.
 */
class TestImagesTest {

	@Test
	void bombsDeclareHugeDimensionsInTheirHeaders() throws IOException {
		assertThat(headerSize("png", TestImages.pngBomb())).containsExactly(50_000, 50_000);
		assertThat(headerSize("jpeg", TestImages.jpegBomb())).containsExactly(65_000, 65_000);
		assertThat(TestImages.pngBomb().length).isLessThan(100);
		assertThat(TestImages.jpegBomb().length).isLessThan(1_000);
	}

	@Test
	void paddedPngIsExactlyTheLimitAndDecodes() throws IOException {
		byte[] png = TestImages.paddedPng(10_485_760);

		assertThat(png).hasSize(10_485_760);
		assertThat(ImageIO.read(new ByteArrayInputStream(png))).extracting(BufferedImage::getWidth).isEqualTo(16);
		assertThat(TestImages.pngChunkTypes(png)).containsSubsequence("IDAT", "tEXt", "IEND");
	}

	@Test
	void exifFixturesCarryTheirOrientationInTheIntendedByteOrder() {
		for (int orientation : new int[] { 1, 3, 6, 8 }) {
			byte[] app1 = TestImages.app1(TestImages.jpegWithApp1(TestImages.solid(8, 8, java.awt.Color.RED),
					TestImages.exifOrientation(orientation, ByteOrder.LITTLE_ENDIAN)));
			assertExifOrientation(app1, "II", orientation);
		}
		byte[] bigEndian = TestImages.app1(TestImages.jpegWithApp1(TestImages.solid(8, 8, java.awt.Color.RED),
				TestImages.exifOrientation(6, ByteOrder.BIG_ENDIAN)));
		assertExifOrientation(bigEndian, "MM", 6);
	}

	@Test
	void gpsJpegHasAGpsIfdPointer() {
		byte[] app1 = TestImages.app1(TestImages.jpegWithApp1(TestImages.solid(8, 8, java.awt.Color.RED),
				TestImages.exifWithGps()));

		assertThat(new String(app1, 0, 6, StandardCharsets.ISO_8859_1)).isEqualTo("Exif\0\0");
		// IFD0's second entry (little-endian): tag 0x8825, type LONG.
		ByteBuffer tiff = ByteBuffer.wrap(app1, 6, app1.length - 6).slice().order(ByteOrder.LITTLE_ENDIAN);
		assertThat(tiff.getShort(8)).isEqualTo((short) 2);
		assertThat(tiff.getShort(8 + 2 + 12) & 0xFFFF).isEqualTo(0x8825);
		assertThat(TestImages.contains(app1, "2026:10:11".getBytes(StandardCharsets.US_ASCII))).isTrue();
	}

	@Test
	void cmykJpegHasFourComponents() throws IOException {
		byte[] cmyk = TestImages.cmykJpeg();

		assertThat(TestImages.frameComponents(cmyk)).isEqualTo(4);
		// The JDK's reader treats it as CMYK: a 4-band image in a CMYK color space.
		ImageReader reader = ImageIO.getImageReadersByFormatName("jpeg").next();
		try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(cmyk))) {
			reader.setInput(in);
			BufferedImage image = reader.read(0);
			assertThat(image.getRaster().getNumBands()).isEqualTo(4);
			assertThat(image.getColorModel().getColorSpace().getType()).isEqualTo(ColorSpace.TYPE_CMYK);
		}
		finally {
			reader.dispose();
		}
	}

	@Test
	void truncatedFilesAreCutBeforeTheirEnd() {
		byte[] png = TestImages.truncatedPng();
		byte[] jpeg = TestImages.truncatedJpeg();

		assertThat(TestImages.pngChunkTypes(png)).doesNotContain("IEND");
		assertThat(Arrays.copyOfRange(jpeg, jpeg.length - 2, jpeg.length)).isNotEqualTo(new byte[] { (byte) 0xFF, (byte) 0xD9 });
	}

	@Test
	void progressiveJpegUsesAProgressiveFrame() {
		byte[] jpeg = TestImages.progressiveJpeg(TestImages.noise(64, 64, 3));

		assertThat(TestImages.segments(jpeg)).anyMatch((segment) -> segment.marker() == 0xC2);
	}

	@Test
	void transparentFixturesHideColorInTransparentPixels() {
		BufferedImage image = TestImages.redSquareOnTransparentGreen(400);

		assertThat(image.getRGB(0, 0)).isEqualTo(0x0000FF00);
		assertThat(image.getRGB(200, 200)).isEqualTo(0xFFFF0000);
		assertThat(TestImages.pngChunkTypes(TestImages.palettePngWithTransparency())).contains("PLTE", "tRNS");
	}

	@Test
	void storedOrientationsTurnTheUprightImageTheOppositeWay() {
		BufferedImage upright = TestImages.uprightMarked(20, 40);

		// Upright: red block at the top left. Viewer turns 6 clockwise, so stored has it bottom left.
		assertThat(TestImages.storedFor(upright, 6).getRGB(2, 17) & 0xFFFFFF).isEqualTo(0xFF0000);
		assertThat(TestImages.storedFor(upright, 6).getWidth()).isEqualTo(40);
		// 8: viewer turns counter-clockwise, so stored has it top right.
		assertThat(TestImages.storedFor(upright, 8).getRGB(37, 2) & 0xFFFFFF).isEqualTo(0xFF0000);
		// 3: turned 180°, bottom right.
		assertThat(TestImages.storedFor(upright, 3).getRGB(17, 37) & 0xFFFFFF).isEqualTo(0xFF0000);
	}

	private static void assertExifOrientation(byte[] app1, String byteOrder, int orientation) {
		assertThat(new String(app1, 0, 6, StandardCharsets.ISO_8859_1)).isEqualTo("Exif\0\0");
		assertThat(new String(app1, 6, 2, StandardCharsets.ISO_8859_1)).isEqualTo(byteOrder);
		ByteBuffer tiff = ByteBuffer.wrap(app1, 6, app1.length - 6)
			.slice()
			.order("II".equals(byteOrder) ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
		assertThat(tiff.getShort(8 + 2) & 0xFFFF).isEqualTo(0x0112);
		assertThat(tiff.getShort(8 + 2 + 8) & 0xFFFF).isEqualTo(orientation);
	}

	private static int[] headerSize(String format, byte[] bytes) throws IOException {
		ImageReader reader = ImageIO.getImageReadersByFormatName(format).next();
		try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
			reader.setInput(in);
			return new int[] { reader.getWidth(0), reader.getHeight(0) };
		}
		finally {
			reader.dispose();
		}
	}

}
