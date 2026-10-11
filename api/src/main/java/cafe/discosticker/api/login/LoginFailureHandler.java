package cafe.discosticker.api.login;

import java.io.IOException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Ends every failed login the same way: destroy the pre-login session (it only ever held the
 * OAuth state) and redirect to {@code /?login=<code>} with a fixed code.
 * <ul>
 * <li>A rule rejection (Discord confirmed the identity) is audited, with code {@code banned} or
 * {@code too_new}.</li>
 * <li>Anything earlier (a missing or mismatched state, a Discord error, denied consent, a failed
 * token or user call) is logged at WARN with only its error code, and is not audited.</li>
 * </ul>
 */
@Component
class LoginFailureHandler implements AuthenticationFailureHandler {

	private static final Logger logger = LoggerFactory.getLogger(LoginFailureHandler.class);

	/** Error codes are logged only when they look like one; Discord's `error` comes from the URL. */
	private static final Pattern ERROR_CODE = Pattern.compile("[a-z_]{1,64}");

	private final LoginAuditWriter audit;

	private final RedirectStrategy redirect = new DefaultRedirectStrategy();

	LoginFailureHandler(LoginAuditWriter audit) {
		this.audit = audit;
	}

	@Override
	public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		String code;
		if (exception instanceof LoginRejectedException rejected) {
			this.audit.rejected(rejected.discordId(), rejected.reason());
			code = rejected.reason().code();
		}
		else {
			logger.warn("Login failed before Discord confirmed an identity: {}", errorCode(exception));
			code = "error";
		}
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
		this.redirect.sendRedirect(request, response, redirectFor(code));
	}

	static String redirectFor(String code) {
		return "/?login=" + code;
	}

	private static String errorCode(AuthenticationException exception) {
		if (exception instanceof OAuth2AuthenticationException oauth2) {
			String code = oauth2.getError().getErrorCode();
			return (code != null && ERROR_CODE.matcher(code).matches()) ? code : "unrecognized_error_code";
		}
		return exception.getClass().getSimpleName();
	}

}
