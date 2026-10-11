package cafe.discosticker.api.login;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stands in for Discord's token endpoint and current-user endpoint on a random loopback port.
 * A test issues a code for the user it wants, drives the real login flow, and the api exchanges
 * that code here exactly as it would with Discord. The token endpoint only accepts the test
 * client's HTTP Basic credentials and {@code grant_type=authorization_code}.
 */
public final class FakeDiscord implements AutoCloseable {

	private static final SecureRandom RANDOM = new SecureRandom();

	/** A Discord user as the identify scope returns it. {@code globalName} may be null. */
	public record User(long id, String username, String globalName) {

		/** A user with a fresh random ID whose encoded creation time is {@code age} ago. */
		public static User createdAgo(Duration age) {
			long createdMillis = Instant.now().minus(age).toEpochMilli();
			long id = ((createdMillis - AccountAge.DISCORD_EPOCH_MILLIS) << 22) | RANDOM.nextInt(1 << 22);
			return new User(id, "user" + Long.toHexString(id), "Display " + Long.toHexString(id));
		}

		public User withGlobalName(String globalName) {
			return new User(this.id, this.username, globalName);
		}

	}

	/** One request the api made to the token endpoint. */
	public record TokenRequest(String authorization, Map<String, String> form) {
	}

	private final String expectedAuthorization;

	private final HttpServer server;

	private final Map<String, User> usersByCode = new ConcurrentHashMap<>();

	private final Map<String, User> usersByToken = new ConcurrentHashMap<>();

	private final List<TokenRequest> tokenRequests = new CopyOnWriteArrayList<>();

	private final List<String> issuedTokens = new CopyOnWriteArrayList<>();

	public FakeDiscord(String clientId, String clientSecret) throws IOException {
		// client_secret_basic: URL-encode both parts, then Base64 (RFC 6749 section 2.3.1).
		String credentials = URLEncoder.encode(clientId, StandardCharsets.UTF_8) + ":"
				+ URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
		this.expectedAuthorization = "Basic "
				+ Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.server.createContext("/api/oauth2/token", this::token);
		this.server.createContext("/api/v10/users/@me", this::currentUser);
		this.server.start();
	}

	public String tokenUri() {
		return baseUrl() + "/api/oauth2/token";
	}

	public String userInfoUri() {
		return baseUrl() + "/api/v10/users/@me";
	}

	/** An authorization code that the token endpoint will exchange once, for this user. */
	public String issueCode(User user) {
		String code = "code-" + randomHex();
		this.usersByCode.put(code, user);
		return code;
	}

	public List<TokenRequest> tokenRequests() {
		return List.copyOf(this.tokenRequests);
	}

	/** Every access and refresh token handed out so far. */
	public List<String> issuedTokens() {
		return List.copyOf(this.issuedTokens);
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	private void token(HttpExchange exchange) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			respond(exchange, 405, "{\"error\":\"invalid_request\"}");
			return;
		}
		String authorization = exchange.getRequestHeaders().getFirst("Authorization");
		Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		this.tokenRequests.add(new TokenRequest(authorization, form));
		if (!this.expectedAuthorization.equals(authorization)) {
			respond(exchange, 401, "{\"error\":\"invalid_client\"}");
			return;
		}
		User user = this.usersByCode.remove(String.valueOf(form.get("code")));
		if (!"authorization_code".equals(form.get("grant_type")) || user == null) {
			respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
			return;
		}
		String accessToken = "fake-access-" + randomHex();
		String refreshToken = "fake-refresh-" + randomHex();
		this.issuedTokens.add(accessToken);
		this.issuedTokens.add(refreshToken);
		this.usersByToken.put(accessToken, user);
		respond(exchange, 200, """
				{"access_token":"%s","token_type":"Bearer","expires_in":604800,"refresh_token":"%s","scope":"identify"}"""
			.formatted(accessToken, refreshToken));
	}

	private void currentUser(HttpExchange exchange) throws IOException {
		String authorization = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
		User user = authorization.startsWith("Bearer ") ? this.usersByToken.get(authorization.substring(7)) : null;
		if (user == null) {
			respond(exchange, 401, "{\"message\":\"401: Unauthorized\",\"code\":0}");
			return;
		}
		String globalName = (user.globalName() != null) ? "\"" + user.globalName() + "\"" : "null";
		respond(exchange, 200, """
				{"id":"%d","username":"%s","global_name":%s,"discriminator":"0","avatar":null,\
				"public_flags":0,"flags":0,"locale":"en-US","mfa_enabled":false}"""
			.formatted(user.id(), user.username(), globalName));
	}

	private String baseUrl() {
		InetSocketAddress address = this.server.getAddress();
		return "http://" + address.getHostString() + ":" + address.getPort();
	}

	private static Map<String, String> parseForm(String body) {
		Map<String, String> form = new LinkedHashMap<>();
		for (String pair : body.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			int eq = pair.indexOf('=');
			String name = URLDecoder.decode((eq < 0) ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			String value = (eq < 0) ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			form.put(name, value);
		}
		return form;
	}

	private static void respond(HttpExchange exchange, int status, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private static String randomHex() {
		byte[] bytes = new byte[12];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

}
