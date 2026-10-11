package cafe.discosticker.api.stickers;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Sticker public IDs: 128 bits from {@link SecureRandom}, as 22 base64url characters without
 * padding (design D11). The last character carries only 2 bits, so the canonical encoding ends in
 * one of {@code A}, {@code Q}, {@code g} or {@code w}; anything else is not an ID we issued.
 */
public final class PublicIds {

	public static final Pattern CANONICAL = Pattern.compile("[A-Za-z0-9_-]{21}[AQgw]");

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private PublicIds() {
	}

	static String next() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return ENCODER.encodeToString(bytes);
	}

	public static boolean isCanonical(String candidate) {
		return candidate != null && CANONICAL.matcher(candidate).matches();
	}

}
