package cafe.discosticker.api.stickers.image;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/** The in-house APP1 parser reads Orientation and treats anything malformed as upright (design D5). */
class ExifOrientationTest {

	@Test
	void readsOrientationInLittleEndian() {
		for (int orientation : new int[] { 1, 3, 6, 8 }) {
			assertThat(ExifOrientation.fromApp1(TestImages.exifOrientation(orientation, ByteOrder.LITTLE_ENDIAN)))
				.as("orientation %d", orientation)
				.isEqualTo(orientation);
		}
	}

	@Test
	void readsOrientationInBigEndian() {
		assertThat(ExifOrientation.fromApp1(TestImages.exifOrientation(6, ByteOrder.BIG_ENDIAN))).isEqualTo(6);
	}

	@Test
	void readsOrientationNextToOtherTags() {
		assertThat(ExifOrientation.fromApp1(TestImages.exifWithGps())).isEqualTo(1);
	}

	@Test
	void missingOrMalformedMeansUpright() {
		assertThat(ExifOrientation.fromApp1(null)).isEqualTo(1);
		assertThat(ExifOrientation.fromApp1(new byte[0])).isEqualTo(1);
		for (byte[] payload : TestImages.malformedExif()) {
			assertThat(ExifOrientation.fromApp1(payload)).as("%d-byte payload", payload.length).isEqualTo(1);
		}
	}

	@Test
	void outOfRangeValueMeansUpright() {
		assertThat(ExifOrientation.fromApp1(TestImages.exifOrientation(9, ByteOrder.LITTLE_ENDIAN))).isEqualTo(1);
		assertThat(ExifOrientation.fromApp1(TestImages.exifOrientation(0, ByteOrder.BIG_ENDIAN))).isEqualTo(1);
	}

}
