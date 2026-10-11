package cafe.discosticker.api.stickers.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

import org.junit.jupiter.api.Test;

/** Type comes from leading bytes only, and only the JDK's own PNG and JPEG readers exist (design D4). */
class ImageTypeTest {

	@Test
	void pngAndJpegSignaturesAreDetected() {
		assertThat(ImageType.detect(TestImages.png(TestImages.solid(4, 4, Color.RED)))).contains(ImageType.PNG);
		assertThat(ImageType.detect(TestImages.jpeg(TestImages.solid(4, 4, Color.RED)))).contains(ImageType.JPEG);
		assertThat(ImageType.detect(TestImages.pngBomb())).contains(ImageType.PNG);
	}

	@Test
	void everythingElseIsUnsupported() {
		List<byte[]> others = List.of(TestImages.text(), TestImages.gif(), TestImages.bmp(), TestImages.webp(),
				TestImages.svg(), new byte[0], new byte[] { (byte) 0xFF, (byte) 0xD8 },
				new byte[] { (byte) 0x89, 'P', 'N', 'G' });
		for (byte[] bytes : others) {
			assertThat(ImageType.detect(bytes)).as("%d bytes", bytes.length).isEmpty();
		}
	}

	@Test
	void eachFormatHasExactlyOneReaderAndItIsTheJdks() {
		for (ImageType type : ImageType.values()) {
			List<ImageReader> readers = new ArrayList<>();
			ImageIO.getImageReadersByFormatName(type.formatName()).forEachRemaining(readers::add);
			assertThat(readers).as(type.formatName())
				.singleElement()
				.satisfies((reader) -> assertThat(reader.getClass().getName()).startsWith("com.sun.imageio.plugins."));
		}
	}

	@Test
	void creatingThePipelineTurnsOffImageIoDiskCache() {
		ImageIO.setUseCache(true);

		new StickerImages(160, 8192, 32_000_000, new DecodeLimiter(2, Duration.ofSeconds(1)));

		assertThat(ImageIO.getUseCache()).isFalse();
	}

}
