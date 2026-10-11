package cafe.discosticker.api.login;

import java.io.IOException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Runs once Spring Security has changed the session ID and stored the signed-in user, so an
 * "accepted" audit row always means a signed-in session exists. Records the login, raises the
 * session's idle timeout, and sends the browser to the home page (never to a request-supplied URL).
 */
@Component
class LoginSuccessHandler implements AuthenticationSuccessHandler {

	static final Duration SIGNED_IN_IDLE_TIMEOUT = Duration.ofHours(8);

	private static final Logger logger = LoggerFactory.getLogger(LoginSuccessHandler.class);

	private final UserAccounts accounts;

	private final LoginAuditWriter audit;

	private final TransactionTemplate transaction;

	private final RedirectStrategy redirect = new DefaultRedirectStrategy();

	LoginSuccessHandler(UserAccounts accounts, LoginAuditWriter audit, TransactionTemplate transaction) {
		this.accounts = accounts;
		this.audit = audit;
		this.transaction = transaction;
	}

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		long discordId = Long.parseLong(authentication.getName());
		try {
			this.transaction.executeWithoutResult((status) -> {
				this.accounts.recordLogin(discordId);
				this.audit.accepted(discordId);
			});
		}
		catch (RuntimeException ex) {
			// No audit row, no session: undo the sign-in rather than keep an unrecorded one.
			logger.error("Could not record an accepted login; signing the user out", ex);
			HttpSession session = request.getSession(false);
			if (session != null) {
				session.invalidate();
			}
			SecurityContextHolder.clearContext();
			this.redirect.sendRedirect(request, response, LoginFailureHandler.redirectFor("error"));
			return;
		}
		request.getSession().setMaxInactiveInterval((int) SIGNED_IN_IDLE_TIMEOUT.toSeconds());
		this.redirect.sendRedirect(request, response, "/");
	}

}
