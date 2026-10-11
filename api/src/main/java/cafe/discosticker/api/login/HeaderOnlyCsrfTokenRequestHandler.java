package cafe.discosticker.api.login;

import java.util.function.Supplier;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code csrf.spa()}'s token handling, except that the token is read from the
 * {@code X-XSRF-TOKEN} header only. The default falls back to {@code request.getParameter("_csrf")},
 * which for {@code multipart/form-data} makes Tomcat parse the whole body to temp files inside the
 * CSRF filter, before the request is authorized. The frontend always sends the header, and the api
 * renders no forms.
 */
final class HeaderOnlyCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

	private final XorCsrfTokenRequestAttributeHandler attributes = new XorCsrfTokenRequestAttributeHandler();

	HeaderOnlyCsrfTokenRequestHandler() {
		// As spa() does: with no attribute name, handle() loads the token on every request, which
		// sets the XSRF-TOKEN cookie on every response the page might read it from.
		this.attributes.setCsrfRequestAttributeName(null);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
		this.attributes.handle(request, response, csrfToken);
	}

	/** The raw header value; the cookie holds the raw token, and the filter compares them in constant time. */
	@Override
	public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
		return request.getHeader(csrfToken.getHeaderName());
	}

}
