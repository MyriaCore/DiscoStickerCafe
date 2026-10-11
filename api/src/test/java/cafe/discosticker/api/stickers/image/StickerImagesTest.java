package cafe.discosticker.api.stickers.image;

import static cafe.discosticker.api.stickers.image.ImageHeaderTest.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.time.Duration;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import cafe.discosticker.api.stickers.image.ImageRejectedException.Reason;
import cafe.discosticker.api.stickers.image.StickerImages.Size;

/** Shrinking, quality, transparency and orientation, with S = 160 (design D7). */
class StickerImagesTest {

	private static final int S = 160;

	private final StickerImages pipeline = new StickerImages(S, 8192, 32_000_000,
			new DecodeLimiter(2, Duration.ofSeconds(1)));

	@Test
	void fitShrinksTheLongerSideToSAndRoundsTheShorter() {
		assertThat(StickerImages.fit(4000, 3000, S)).isEqualTo(new Size(160, 120));
		assertThat(StickerImages.fit(161, 160, S)).isEqualTo(new Size(160, 159));
		assertThat(StickerImages.fit(160, 161, S)).isEqualTo(new Size(159, 160));
		assertThat(StickerImages.fit(8000, 1, S)).isEqualTo(new Size(160, 1));
		assertThat(StickerImages.fit(1, 8000, S)).isEqualTo(new Size(1, 160));
	}

	@Test
	void fitLeavesImagesAtOrBelowSUnchanged() {
		assertThat(StickerImages.fit(160, 160, S)).isEqualTo(new Size(160, 160));
		assertThat(StickerImages.fit(1, 1, S)).isEqualTo(new Size(1, 1));
		assertThat(StickerImages.fit(100, 50, S)).isEqualTo(new Size(100, 50));
	}

	@Test
	void shrinkingFineStripesGivesEvenGray() {
		BufferedImage shrunk = StickerImages.shrink(TestImages.stripes(4000, 3000), 160, 120);

		assertThat(shrunk.getWidth()).isEqualTo(160);
		assertThat(shrunk.getHeight()).isEqualTo(120);
		for (int y = 0; y < 120; y++) {
			for (int x = 0; x < 160; x++) {
				int argb = shrunk.getRGB(x, y);
				assertThat(argb >>> 24).isEqualTo(255);
				for (int shift : new int[] { 16, 8, 0 }) {
					assertThat((argb >> shift) & 0xFF).as("(%d,%d)", x, y).isBetween(127 - 16, 127 + 16);
				}
			}
		}
	}

	@Test
	void shrinkingKeepsTransparencyWithoutTintingEdges() {
		BufferedImage sticker = decode(this.pipeline.process(TestImages.png(TestImages.redSquareOnTransparentGreen(400))));

		assertThat(sticker.getWidth()).isEqualTo(160);
		assertThat(sticker.getHeight()).isEqualTo(160);
		assertThat(sticker.getRGB(0, 0) >>> 24).isZero();
		assertThat(sticker.getRGB(159, 159) >>> 24).isZero();
		assertThat(sticker.getRGB(80, 80)).isEqualTo(0xFFFF0000);
		for (int y = 0; y < 160; y++) {
			for (int x = 0; x < 160; x++) {
				int argb = sticker.getRGB(x, y);
				int red = (argb >> 16) & 0xFF;
				int green = (argb >> 8) & 0xFF;
				assertThat(green).as("green at (%d,%d) in %08x", x, y, argb).isLessThanOrEqualTo(red);
			}
		}
	}

	@Test
	void paletteTransparencyIsKept() {
		BufferedImage sticker = decode(this.pipeline.process(TestImages.palettePngWithTransparency()));

		assertThat(sticker.getWidth()).isEqualTo(64);
		assertThat(sticker.getHeight()).isEqualTo(32);
		assertThat(sticker.getRGB(10, 10) >>> 24).isZero();
		assertThat(sticker.getRGB(50, 10)).isEqualTo(0xFFFF0000);
	}

	@Test
	void orientTurnsStoredImagesUpright() {
		BufferedImage upright = TestImages.uprightMarked(20, 40);
		for (int orientation : new int[] { 3, 6, 8 }) {
			BufferedImage turned = StickerImages.orient(TestImages.storedFor(upright, orientation), orientation);

			assertThat(turned.getWidth()).as("width for %d", orientation).isEqualTo(20);
			assertThat(turned.getHeight()).as("height for %d", orientation).isEqualTo(40);
			assertThat(turned.getRGB(2, 2) & 0xFFFFFF).as("top left for %d", orientation).isEqualTo(0xFF0000);
			assertThat(turned.getRGB(17, 37) & 0xFFFFFF).as("bottom right for %d", orientation).isEqualTo(0x0000FF);
		}
	}

	@Test
	void orientationSixTurnsA40By20JpegInto20By40() {
		BufferedImage stored = TestImages.storedFor(TestImages.uprightMarked(20, 40), 6);
		byte[] jpeg = TestImages.jpegWithApp1(stored, TestImages.exifOrientation(6, ByteOrder.LITTLE_ENDIAN));

		Sticker sticker = this.pipeline.process(jpeg);

		assertThat(sticker.width()).isEqualTo(20);
		assertThat(sticker.height()).isEqualTo(40);
		BufferedImage png = decode(sticker);
		assertRed(png.getRGB(4, 4));
		assertBlue(png.getRGB(15, 32));
	}

	@Test
	void stickerDimensionsFollowFit() {
		assertSize(this.pipeline.process(TestImages.jpeg(TestImages.noise(4000, 3000, 1))), 160, 120);
		assertSize(this.pipeline.process(TestImages.png(TestImages.noise(161, 160, 2))), 160, 159);
		assertSize(this.pipeline.process(TestImages.png(TestImages.noise(160, 160, 3))), 160, 160);
		assertSize(this.pipeline.process(TestImages.png(TestImages.noise(1, 1, 4))), 1, 1);
		assertSize(this.pipeline.process(TestImages.jpeg(TestImages.noise(100, 50, 5))), 100, 50);
		assertSize(this.pipeline.process(TestImages.png(TestImages.noise(8000, 1, 6))), 160, 1);
	}

	@Test
	void busyWhenEveryPermitIsHeld() {
		DecodeLimiter limiter = new DecodeLimiter(1, Duration.ofMillis(50));
		StickerImages pipeline = new StickerImages(S, 8192, 32_000_000, limiter);
		byte[] png = TestImages.png(TestImages.noise(8, 8, 9));

		try (DecodeLimiter.Permit _ = limiter.acquire()) {
			assertRejected(() -> pipeline.process(png), Reason.BUSY);
		}
		assertSize(pipeline.process(png), 8, 8);
	}

	private static void assertSize(Sticker sticker, int width, int height) {
		assertThat(sticker.width()).isEqualTo(width);
		assertThat(sticker.height()).isEqualTo(height);
		BufferedImage png = decode(sticker);
		assertThat(png.getWidth()).isEqualTo(width);
		assertThat(png.getHeight()).isEqualTo(height);
	}

	private static void assertRed(int argb) {
		assertThat((argb >> 16) & 0xFF).isGreaterThan(180);
		assertThat(argb & 0xFF).isLessThan(80);
	}

	private static void assertBlue(int argb) {
		assertThat(argb & 0xFF).isGreaterThan(180);
		assertThat((argb >> 16) & 0xFF).isLessThan(80);
	}

	static BufferedImage decode(Sticker sticker) {
		try {
			return ImageIO.read(new ByteArrayInputStream(sticker.png()));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
