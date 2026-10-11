package cafe.discosticker.api.login;

import static cafe.discosticker.api.login.AccountAgeTest.idCreatedAt;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginPolicyTest {

	private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

	private final LoginPolicy policy = new LoginPolicy(Clock.fixed(NOW, ZoneOffset.UTC));

	private final long oldAccount = idCreatedAt(NOW.minus(Duration.ofDays(30)));

	private final long newAccount = idCreatedAt(NOW.minus(Duration.ofDays(1)));

	@Test
	void bannedIsRejected() {
		assertThat(policy.check(oldAccount, true)).contains(RejectionReason.BANNED);
	}

	@Test
	void bannedOutranksTooNew() {
		assertThat(policy.check(newAccount, true)).contains(RejectionReason.BANNED);
	}

	@Test
	void tooNewIsRejected() {
		assertThat(policy.check(newAccount, false)).contains(RejectionReason.TOO_NEW);
	}

	@Test
	void oldUnbannedAccountIsAccepted() {
		assertThat(policy.check(oldAccount, false)).isEmpty();
	}

	@Test
	void reasonCodesMatchTheAuditAndRedirectValues() {
		assertThat(RejectionReason.BANNED.code()).isEqualTo("banned");
		assertThat(RejectionReason.TOO_NEW.code()).isEqualTo("too_new");
	}

}
