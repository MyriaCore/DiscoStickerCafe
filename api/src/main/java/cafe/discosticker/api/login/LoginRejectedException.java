package cafe.discosticker.api.login;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * Discord confirmed who the user is, and a login rule refused them. Thrown from the user service,
 * before an authenticated session exists; the failure handler audits it.
 */
public class LoginRejectedException extends OAuth2AuthenticationException {

	private final long discordId;

	private final RejectionReason reason;

	public LoginRejectedException(long discordId, RejectionReason reason) {
		super(new OAuth2Error("login_rejected"), reason.code());
		this.discordId = discordId;
		this.reason = reason;
	}

	public long discordId() {
		return this.discordId;
	}

	public RejectionReason reason() {
		return this.reason;
	}

}
