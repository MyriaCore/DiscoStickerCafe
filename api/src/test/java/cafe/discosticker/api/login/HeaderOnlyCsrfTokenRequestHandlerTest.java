package cafe.discosticker.api.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Part;

/**
 * The token comes from the header only: resolving it never reads parameters or parts, so a
 * multipart body is never parsed before authorization (design D12).
 */
class HeaderOnlyCsrfTokenRequestHandlerTest {

	private static final CsrfToken TOKEN = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "raw-token");

	private final HeaderOnlyCsrfTokenRequestHandler handler = new HeaderOnlyCsrfTokenRequestHandler();

	@Test
	void tokenOnlyInTheBodyResolvesToNullWithoutReadingTheBody() {
		NoBodyReadsRequest request = new NoBodyReadsRequest();
		request.setContentType("multipart/form-data; boundary=x");
		request.addParameter("_csrf", "raw-token");

		assertThat(this.handler.resolveCsrfTokenValue(request, TOKEN)).isNull();
	}

	@Test
	void headerValueIsTheToken() {
		NoBodyReadsRequest request = new NoBodyReadsRequest();
		request.addHeader("X-XSRF-TOKEN", "raw-token");

		assertThat(this.handler.resolveCsrfTokenValue(request, TOKEN)).isEqualTo("raw-token");
	}

	@Test
	void handleLoadsTheTokenSoTheCookieIsSetOnEveryResponse() {
		AtomicInteger loads = new AtomicInteger();
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");

		this.handler.handle(request, new MockHttpServletResponse(), () -> {
			loads.incrementAndGet();
			return TOKEN;
		});

		assertThat(loads).hasPositiveValue();
		assertThat(request.getAttribute(CsrfToken.class.getName())).isInstanceOf(CsrfToken.class);
	}

	/** A POST whose body, parameters and parts must never be read. */
	static final class NoBodyReadsRequest extends MockHttpServletRequest {

		NoBodyReadsRequest() {
			super("POST", "/api/stickers");
		}

		@Override
		public String getParameter(String name) {
			throw new AssertionError("read parameter " + name);
		}

		@Override
		public Map<String, String[]> getParameterMap() {
			throw new AssertionError("read parameter map");
		}

		@Override
		public Enumeration<String> getParameterNames() {
			throw new AssertionError("read parameter names");
		}

		@Override
		public String[] getParameterValues(String name) {
			throw new AssertionError("read parameter values " + name);
		}

		@Override
		public Part getPart(String name) {
			throw new AssertionError("read part " + name);
		}

		@Override
		public Collection<Part> getParts() {
			throw new AssertionError("read parts");
		}

		@Override
		public ServletInputStream getInputStream() {
			throw new AssertionError("read body");
		}

		@Override
		public BufferedReader getReader() {
			throw new AssertionError("read body");
		}

	}

}
