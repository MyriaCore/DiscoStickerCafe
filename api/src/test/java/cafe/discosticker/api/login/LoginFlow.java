package cafe.discosticker.api.login;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A browser stand-in for the login flow against the real Tomcat: redirects are never followed,
 * and cookies are kept in an explicit jar so tests can see every {@code Set-Cookie} and replay
 * every cookie received.
 */
public final class LoginFlow {

	/** The authorization redirect: the response and the decoded query of its Location. */
	public record Authorization(HttpResponse<String> response, Map<String, String> params) {
	}

	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	private final String baseUrl;

	private final FakeDiscord discord;

	private final Map<String, String> jar = new LinkedHashMap<>();

	private final List<String> received = new ArrayList<>();

	public LoginFlow(int port, FakeDiscord discord) {
		this.baseUrl = "http://localhost:" + port;
		this.discord = discord;
	}

	public HttpResponse<String> get(String path, String... headers) {
		return send("GET", path, headers);
	}

	public HttpResponse<String> post(String path, String... headers) {
		return send("POST", path, headers);
	}

	/** Starts sign-in, as the frontend's link does. */
	public Authorization startAuthorization(String... headers) {
		HttpResponse<String> response = get("/oauth2/authorization/discord", headers);
		String location = response.headers().firstValue("Location").orElse("");
		return new Authorization(response, queryParams(location));
	}

	/** Discord's redirect back to the api, with the given raw query string. */
	public HttpResponse<String> callback(String query) {
		return get("/login/oauth2/code/discord?" + query);
	}

	/** The whole flow: start, have Discord issue a code for this user, return to the callback. */
	public HttpResponse<String> signIn(FakeDiscord.User user) {
		Authorization authorization = startAuthorization();
		String code = this.discord.issueCode(user);
		return callback("code=" + encode(code) + "&state=" + encode(authorization.params().get("state")));
	}

	/** The cookie's current value in the jar, or null. */
	public String cookie(String name) {
		return this.jar.get(name);
	}

	/** Replaces the jar with every non-empty cookie value ever received, as name=value pairs. */
	public void replayEveryReceivedCookie() {
		this.jar.clear();
		for (String pair : this.received) {
			int eq = pair.indexOf('=');
			this.jar.put(pair.substring(0, eq), pair.substring(eq + 1));
		}
	}

	/** Every non-empty cookie ever received, as name=value pairs, in order. */
	public List<String> receivedCookies() {
		return List.copyOf(this.received);
	}

	/** The {@code Set-Cookie} headers in this response for the named cookie. */
	public static List<String> setCookies(HttpResponse<?> response, String name) {
		return response.headers()
			.allValues("Set-Cookie")
			.stream()
			.filter((header) -> header.startsWith(name + "="))
			.toList();
	}

	public static Map<String, String> queryParams(String uri) {
		Map<String, String> params = new LinkedHashMap<>();
		int question = uri.indexOf('?');
		if (question < 0) {
			return params;
		}
		for (String pair : uri.substring(question + 1).split("&")) {
			int eq = pair.indexOf('=');
			String name = URLDecoder.decode((eq < 0) ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			params.put(name, (eq < 0) ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return params;
	}

	public static String encode(String value) {
		return URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8);
	}

	private HttpResponse<String> send(String method, String path, String... headers) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.baseUrl + path))
			.method(method, HttpRequest.BodyPublishers.noBody());
		for (int i = 0; i < headers.length; i += 2) {
			request.header(headers[i], headers[i + 1]);
		}
		if (!this.jar.isEmpty()) {
			request.header("Cookie", this.jar.entrySet()
				.stream()
				.map((cookie) -> cookie.getKey() + "=" + cookie.getValue())
				.collect(Collectors.joining("; ")));
		}
		try {
			HttpResponse<String> response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			for (String header : response.headers().allValues("Set-Cookie")) {
				store(header);
			}
			return response;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private void store(String setCookie) {
		String pair = setCookie.split(";", 2)[0].trim();
		int eq = pair.indexOf('=');
		String name = pair.substring(0, eq);
		String value = pair.substring(eq + 1);
		boolean expired = value.isEmpty() || setCookie.matches("(?i).*;\\s*Max-Age=0(;.*)?");
		if (expired) {
			this.jar.remove(name);
		}
		else {
			this.jar.put(name, value);
			this.received.add(name + "=" + value);
		}
	}

}
