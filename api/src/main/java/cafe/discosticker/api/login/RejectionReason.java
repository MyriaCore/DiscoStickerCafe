package cafe.discosticker.api.login;

/**
 * Why a login whose identity Discord confirmed was refused. {@link #code()} is the value written
 * to {@code login_audit.reason} and sent to the frontend as {@code /?login=<code>}.
 */
public enum RejectionReason {

	BANNED("banned"),

	TOO_NEW("too_new");

	private final String code;

	RejectionReason(String code) {
		this.code = code;
	}

	public String code() {
		return this.code;
	}

}
