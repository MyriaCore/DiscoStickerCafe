package cafe.discosticker.api.stickers.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Stickers are bare PNGs: no metadata from the upload survives (design D7). */
class PngOutputTest {

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	/** Byte offset of IHDR's color type: signature, length, "IHDR", width, height, bit depth. */
	private static final int IHDR_COLOR_TYPE = 8 + 4 + 4 + 4 + 4 + 1;

	private final StickerImages pipeline = new StickerImages(160, 8192, 32_000_000,
			new DecodeLimiter(2, Duration.ofSeconds(1)));

	@Test
	void onlyImageChunks() {
		byte[] png = this.pipeline.process(TestImages.jpeg(TestImages.noise(64, 64, 21))).png();

		assertThat(Arrays.copyOf(png, 8)).isEqualTo(PNG_SIGNATURE);
		assertOnlyImageChunks(png);
	}

	@Test
	void locationDataIsDropped() {
		byte[] jpeg = TestImages.jpegWithApp1(TestImages.noise(64, 64, 22), TestImages.exifWithGps());
		byte[] png = this.pipeline.process(jpeg).png();

		assertOnlyImageChunks(png);
		assertThat(TestImages.contains(png, "2026:10:11".getBytes(StandardCharsets.US_ASCII))).isFalse();
		assertThat(TestImages.contains(png, "Exif".getBytes(StandardCharsets.US_ASCII))).isFalse();
	}

	@Test
	void opaqueSourcesGiveRgbAndTransparentOnesRgba() {
		byte[] opaque = this.pipeline.process(TestImages.png(TestImages.solid(16, 16, Color.RED))).png();
		byte[] transparent = this.pipeline.process(TestImages.png(TestImages.redSquareOnTransparentGreen(16))).png();

		assertThat(opaque[IHDR_COLOR_TYPE]).as("color type").isEqualTo((byte) 2);
		assertThat(transparent[IHDR_COLOR_TYPE]).as("color type").isEqualTo((byte) 6);
		assertOnlyImageChunks(transparent);
	}

	private static void assertOnlyImageChunks(byte[] png) {
		List<String> types = TestImages.pngChunkTypes(png);
		assertThat(types.getFirst()).isEqualTo("IHDR");
		assertThat(types.getLast()).isEqualTo("IEND");
		assertThat(types.subList(1, types.size() - 1)).isNotEmpty().containsOnly("IDAT");
	}

}
