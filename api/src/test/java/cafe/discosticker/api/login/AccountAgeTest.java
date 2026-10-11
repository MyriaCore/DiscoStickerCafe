package cafe.discosticker.api.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class AccountAgeTest {

	private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

	@Test
	void decodesCreationTimeFromDiscordId() {
		// The example from Discord's snowflake documentation.
		assertThat(AccountAge.createdAt(175928847299117063L)).isEqualTo(Instant.parse("2016-04-30T11:18:25.796Z"));
	}

	@Test
	void justUnderSevenDaysIsTooNew() {
		long id = idCreatedAt(NOW.minus(Duration.ofDays(7)).plusMillis(1));

		assertThat(AccountAge.isTooNew(id, NOW)).isTrue();
	}

	@Test
	void exactlySevenDaysIsAllowed() {
		long id = idCreatedAt(NOW.minus(Duration.ofDays(7)));

		assertThat(AccountAge.isTooNew(id, NOW)).isFalse();
	}

	@Test
	void parsesNumericId() {
		assertThat(AccountAge.parseId("175928847299117063")).isEqualTo(175928847299117063L);
	}

	@Test
	void rejectsNonNumericId() {
		assertThatThrownBy(() -> AccountAge.parseId("not-a-snowflake")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AccountAge.parseId("-5")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AccountAge.parseId(null)).isInstanceOf(IllegalArgumentException.class);
	}

	static long idCreatedAt(Instant createdAt) {
		return (createdAt.toEpochMilli() - AccountAge.DISCORD_EPOCH_MILLIS) << 22;
	}

}
