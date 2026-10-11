package cafe.discosticker.api.stickers.image;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads the Orientation tag (0x0112) from IFD0 of a JPEG's EXIF APP1 segment, and nothing else.
 * Every offset is bounds-checked against the segment, so untrusted input can only ever produce
 * an orientation of 1 to 8.
 */
final class ExifOrientation {

	private static final int UPRIGHT = 1;

	/** "Exif\0\0" precedes the TIFF header. */
	private static final int TIFF_START = 6;

	private static final int ORIENTATION_TAG = 0x0112;

	private static final int TYPE_SHORT = 3;

	private static final int ENTRY_SIZE = 12;

	private ExifOrientation() {
	}

	/** The orientation (1–8) in an APP1 payload, or 1 when there is none or it is malformed. Never throws. */
	static int fromApp1(byte[] payload) {
		if (payload == null || payload.length < TIFF_START + 8 || payload[0] != 'E' || payload[1] != 'x'
				|| payload[2] != 'i' || payload[3] != 'f' || payload[4] != 0 || payload[5] != 0) {
			return UPRIGHT;
		}
		ByteOrder order;
		if (payload[TIFF_START] == 'I' && payload[TIFF_START + 1] == 'I') {
			order = ByteOrder.LITTLE_ENDIAN;
		}
		else if (payload[TIFF_START] == 'M' && payload[TIFF_START + 1] == 'M') {
			order = ByteOrder.BIG_ENDIAN;
		}
		else {
			return UPRIGHT;
		}
		ByteBuffer tiff = ByteBuffer.wrap(payload, TIFF_START, payload.length - TIFF_START).slice().order(order);
		long length = tiff.capacity();
		if (Short.toUnsignedInt(tiff.getShort(2)) != 42) {
			return UPRIGHT;
		}
		long ifd0 = Integer.toUnsignedLong(tiff.getInt(4));
		if (ifd0 < 8 || ifd0 + 2 > length) {
			return UPRIGHT;
		}
		int entries = Short.toUnsignedInt(tiff.getShort((int) ifd0));
		for (int i = 0; i < entries; i++) {
			long entry = ifd0 + 2 + (long) ENTRY_SIZE * i;
			if (entry + ENTRY_SIZE > length) {
				return UPRIGHT;
			}
			int at = (int) entry;
			if (Short.toUnsignedInt(tiff.getShort(at)) == ORIENTATION_TAG) {
				boolean oneShort = Short.toUnsignedInt(tiff.getShort(at + 2)) == TYPE_SHORT
						&& Integer.toUnsignedLong(tiff.getInt(at + 4)) == 1;
				// A SHORT value sits in the first two bytes of the value field, in either byte order.
				int value = Short.toUnsignedInt(tiff.getShort(at + 8));
				return (oneShort && value >= 1 && value <= 8) ? value : UPRIGHT;
			}
		}
		return UPRIGHT;
	}

}
