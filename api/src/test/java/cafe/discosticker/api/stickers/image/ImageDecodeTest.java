package cafe.discosticker.api.stickers.image;

import static cafe.discosticker.api.stickers.image.ImageHeaderTest.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import cafe.discosticker.api.stickers.image.ImageRejectedException.Reason;

/** Damaged files are unreadable, never partly decoded; valid variants decode (design D6). */
class ImageDecodeTest {

	private final ImageIoDecoder decoder = new ImageIoDecoder();

	@Test
	void truncatedPngIsUnreadable() {
		assertRejected(() -> this.decoder.decode(ImageType.PNG, TestImages.truncatedPng()), Reason.UNREADABLE);
	}

	@Test
	void truncatedJpegIsUnreadableNotPartlyGray() {
		assertRejected(() -> this.decoder.decode(ImageType.JPEG, TestImages.truncatedJpeg()), Reason.UNREADABLE);
	}

	@Test
	void progressiveJpegDecodes() {
		BufferedImage image = this.decoder.decode(ImageType.JPEG, TestImages.progressiveJpeg(TestImages.noise(64, 48, 31)));

		assertThat(image.getWidth()).isEqualTo(64);
		assertThat(image.getHeight()).isEqualTo(48);
	}

	@Test
	void appendedDataIsDroppedByReencoding() {
		StickerImages pipeline = new StickerImages(160, 8192, 32_000_000, new DecodeLimiter(2, Duration.ofSeconds(1)));

		Sticker sticker = pipeline.process(TestImages.pngWithAppendedHtml());

		assertThat(sticker.width()).isEqualTo(32);
		assertThat(TestImages.contains(sticker.png(), TestImages.APPENDED_HTML)).isFalse();
	}

	@Test
	void textNamedAsPngIsUnsupported() {
		StickerImages pipeline = new StickerImages(160, 8192, 32_000_000, new DecodeLimiter(2, Duration.ofSeconds(1)));

		assertRejected(() -> pipeline.process(TestImages.text()), Reason.UNSUPPORTED_TYPE);
		assertRejected(() -> pipeline.process(TestImages.svg()), Reason.UNSUPPORTED_TYPE);
	}

}
