package cafe.discosticker.api.login;

import java.time.Duration;
import java.time.Instant;

/**
 * A Discord account's age, read from its ID: a snowflake's top 42 bits are milliseconds since
 * the Discord epoch (the first second of 2015), so no extra Discord API call is needed.
 */
public final class AccountAge {

	static final long DISCORD_EPOCH_MILLIS = 1420070400000L;

	/** Accounts younger than this can't sign in. */
	static final Duration MINIMUM_AGE = Duration.ofDays(7);

	private AccountAge() {
	}

	/**
	 * Parses the {@code id} string from Discord's user object.
	 * @throws IllegalArgumentException if it isn't a positive 64-bit integer
	 */
	public static long parseId(String id) {
		long parsed;
		try {
			parsed = Long.parseLong(id);
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("Discord ID is not numeric", ex);
		}
		if (parsed <= 0) {
			throw new IllegalArgumentException("Discord ID is not positive");
		}
		return parsed;
	}

	public static Instant createdAt(long discordId) {
		return Instant.ofEpochMilli((discordId >> 22) + DISCORD_EPOCH_MILLIS);
	}

	/** True when the account is less than {@link #MINIMUM_AGE} old; exactly seven days is allowed. */
	public static boolean isTooNew(long discordId, Instant now) {
		return createdAt(discordId).plus(MINIMUM_AGE).isAfter(now);
	}

}
