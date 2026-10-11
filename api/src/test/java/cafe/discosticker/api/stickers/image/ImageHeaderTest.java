package cafe.discosticker.api.stickers.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.ByteOrder;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import cafe.discosticker.api.stickers.image.ImageRejectedException.Reason;

/** Dimensions and color are checked from the header, before any pixel is decoded (design D5). */
class ImageHeaderTest {

	private static final int MAX_SIDE = 8192;

	private static final long MAX_PIXELS = 32_000_000;

	@Test
	void pngBombIsRejectedWithoutDecoding() {
		CountingDecoder decoder = new CountingDecoder();

		assertRejected(() -> pipeline(decoder).process(TestImages.pngBomb()), Reason.DIMENSIONS_TOO_LARGE);
		assertThat(decoder.calls).isZero();
	}

	@Test
	void jpegBombIsRejectedWithoutDecoding() {
		CountingDecoder decoder = new CountingDecoder();

		assertRejected(() -> pipeline(decoder).process(TestImages.jpegBomb()), Reason.DIMENSIONS_TOO_LARGE);
		assertThat(decoder.calls).isZero();
	}

	@Test
	void capsAtTheBoundary() {
		assertRejected(() -> read(ImageType.PNG, TestImages.pngHeaderOnly(8193, 1)), Reason.DIMENSIONS_TOO_LARGE);
		assertRejected(() -> read(ImageType.PNG, TestImages.pngHeaderOnly(1, 8193)), Reason.DIMENSIONS_TOO_LARGE);
		assertRejected(() -> read(ImageType.PNG, TestImages.pngHeaderOnly(5657, 5657)), Reason.DIMENSIONS_TOO_LARGE);

		assertThat(read(ImageType.PNG, TestImages.pngHeaderOnly(8192, 1)))
			.isEqualTo(new ImageHeader(ImageType.PNG, 8192, 1, 1));
		assertThat(read(ImageType.PNG, TestImages.pngHeaderOnly(8000, 4000)))
			.isEqualTo(new ImageHeader(ImageType.PNG, 8000, 4000, 1));
	}

	@Test
	void cmykJpegIsRejectedWithoutDecoding() {
		CountingDecoder decoder = new CountingDecoder();

		assertRejected(() -> pipeline(decoder).process(TestImages.cmykJpeg()), Reason.UNSUPPORTED_COLOR);
		assertThat(decoder.calls).isZero();
	}

	@Test
	void jpegHeaderCarriesTheExifOrientation() {
		BufferedImage stored = TestImages.solid(40, 20, Color.BLUE);
		byte[] jpeg = TestImages.jpegWithApp1(stored, TestImages.exifOrientation(6, ByteOrder.LITTLE_ENDIAN));

		assertThat(read(ImageType.JPEG, jpeg)).isEqualTo(new ImageHeader(ImageType.JPEG, 40, 20, 6));
		assertThat(read(ImageType.JPEG, TestImages.jpeg(stored))).isEqualTo(new ImageHeader(ImageType.JPEG, 40, 20, 1));
	}

	@Test
	void unreadableHeaderIsUnreadable() {
		byte[] signatureOnly = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

		assertRejected(() -> read(ImageType.PNG, signatureOnly), Reason.UNREADABLE);
		assertRejected(() -> read(ImageType.JPEG, new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF }),
				Reason.UNREADABLE);
	}

	private static ImageHeader read(ImageType type, byte[] bytes) {
		return ImageHeader.read(type, bytes, MAX_SIDE, MAX_PIXELS);
	}

	private static StickerImages pipeline(Decoder decoder) {
		return new StickerImages(160, MAX_SIDE, MAX_PIXELS, new DecodeLimiter(2, Duration.ofSeconds(1)), decoder);
	}

	static void assertRejected(Runnable call, Reason reason) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(ImageRejectedException.class,
				(ex) -> assertThat(ex.reason()).isEqualTo(reason));
	}

	/** Decodes like the real pipeline and counts the calls, so a test can prove decode never ran. */
	static final class CountingDecoder implements Decoder {

		int calls;

		private final Decoder delegate = new ImageIoDecoder();

		@Override
		public BufferedImage decode(ImageType type, byte[] bytes) {
			this.calls++;
			return this.delegate.decode(type, bytes);
		}

	}

}
