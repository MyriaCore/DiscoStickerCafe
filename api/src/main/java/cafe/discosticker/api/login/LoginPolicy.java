package cafe.discosticker.api.login;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * The login rules, checked after Discord confirms who the user is and before an authenticated
 * session exists. A ban outranks account age.
 */
@Component
public class LoginPolicy {

	private final Clock clock;

	public LoginPolicy(Clock clock) {
		this.clock = clock;
	}

	/** Empty when the login may proceed. */
	public Optional<RejectionReason> check(long discordId, boolean banned) {
		if (banned) {
			return Optional.of(RejectionReason.BANNED);
		}
		if (AccountAge.isTooNew(discordId, this.clock.instant())) {
			return Optional.of(RejectionReason.TOO_NEW);
		}
		return Optional.empty();
	}

}
